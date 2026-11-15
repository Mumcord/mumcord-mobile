// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.platform.video

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.util.AttributeSet
import android.util.Log
import android.view.TextureView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shows the pictures of one stream, letterboxed on black. A TextureView rather than a
 * SurfaceView, so the picture can be clipped to rounded corners and moved like any other view
 * (the tile and the floating mini window).
 *
 * Each view draws on a render thread of its own ([THREAD_NAME]) with an OpenGL ES 3.0 context:
 * the engine's I420 planes go into three single-channel textures (the only copy) and a shader
 * turns them into RGB. A redraw is only scheduled when the engine has a new picture, the view
 * changes size or a stream is shown; between pictures the thread sleeps. Pictures are never
 * queued: while a redraw is pending, further news of pictures is dropped and the redraw takes
 * the newest one.
 */
class VideoView @JvmOverloads constructor(context: Context, attributes: AttributeSet? = null) :
    TextureView(context, attributes),
    TextureView.SurfaceTextureListener {
    private var renderer: Renderer? = null
    private var shown: Shown? = null

    /** Called on the render thread with the size of every new picture drawn (tests, timing). */
    @Volatile
    var onPicture: ((width: Int, height: Int) -> Unit)? = null

    init {
        surfaceTextureListener = this
        isOpaque = false
    }

    /** Shows [videoId] from [frames], told about new pictures by [router]. */
    fun show(frames: VideoFrames, router: VideoRouter, videoId: Long) {
        val next = Shown(frames, router, videoId)
        if (next == shown) return
        stopShowing()
        shown = next
        renderer?.start(next)
    }

    /** Stops showing a stream and frees the render thread; called when the view leaves the screen. */
    fun release() {
        stopShowing()
        renderer?.quit()
        renderer = null
    }

    private fun stopShowing() {
        val old = shown ?: return
        renderer?.stopShowing(old)
        shown = null
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        renderer = Renderer(surface, width, height).also { renderer -> shown?.let(renderer::start) }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        renderer?.resize(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        shown?.let { renderer?.stopShowing(it) }
        renderer?.quit()
        renderer = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private data class Shown(val frames: VideoFrames, val router: VideoRouter, val videoId: Long)

    private inner class Renderer(surface: SurfaceTexture, width: Int, height: Int) {
        private val thread = HandlerThread(THREAD_NAME).apply { start() }
        private val handler = Handler(thread.looper)
        private val pending = AtomicBoolean(false)
        private val drawTask = Runnable { draw() }
        private val onReady = Runnable { if (pending.compareAndSet(false, true)) handler.post(drawTask) }

        // Only touched on the render thread.
        private var gl: Gl? = null
        private var current: Shown? = null
        private var surfaceWidth = width
        private var surfaceHeight = height
        private var picture = 0L

        init {
            handler.post { gl = Gl.create(surface) }
        }

        fun start(shown: Shown) {
            handler.post {
                current = shown
                picture = 0L
                gl?.clear(surfaceWidth, surfaceHeight)
            }
            shown.router.listen(shown.videoId, onReady)
            // The engine tells about the next picture only once the waiting one was taken.
            onReady.run()
        }

        fun stopShowing(shown: Shown) {
            shown.router.stop(shown.videoId, onReady)
            handler.post { if (current == shown) current = null }
        }

        fun resize(width: Int, height: Int) {
            handler.post {
                surfaceWidth = width
                surfaceHeight = height
                redraw(newPicture = false)
            }
        }

        fun quit() {
            val done = CountDownLatch(1)
            handler.post {
                gl?.destroy()
                gl = null
                done.countDown()
            }
            // The surface may only go once the thread no longer draws into it.
            if (!done.await(QUIT_WAIT_MS, TimeUnit.MILLISECONDS)) Log.w(LOG_TAG, "video: the render thread did not stop in time")
            thread.quitSafely()
        }

        private fun draw() {
            pending.set(false)
            val shown = current ?: return
            val gl = gl ?: return
            val size = shown.frames.upload(shown.videoId, gl.textures, gl.sizes)
            if (size == 0L) return
            picture = size
            redraw(newPicture = true)
        }

        private fun redraw(newPicture: Boolean) {
            val gl = gl ?: return
            if (picture == 0L || current == null) return
            val width = packedWidth(picture)
            val height = packedHeight(picture)
            gl.draw(fitViewport(surfaceWidth, surfaceHeight, width, height), surfaceWidth, surfaceHeight)
            if (newPicture) onPicture?.invoke(width, height)
        }
    }

    /** The render thread's OpenGL ES state: EGL, the shader and the three plane textures. */
    private class Gl(
        private val display: EGLDisplay,
        private val context: EGLContext,
        private val surface: EGLSurface,
        private val program: Int,
        private val vertices: ByteBuffer,
        val textures: IntArray,
    ) {
        val sizes = IntArray(6)

        fun clear(width: Int, height: Int) {
            GLES30.glViewport(0, 0, width, height)
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            EGL14.eglSwapBuffers(display, surface)
        }

        fun draw(viewport: IntArray, width: Int, height: Int) {
            GLES30.glViewport(0, 0, width, height)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
            GLES30.glUseProgram(program)
            for (plane in 0 until 3) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + plane)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[plane])
            }
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, vertices)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            EGL14.eglSwapBuffers(display, surface)
        }

        fun destroy() {
            GLES30.glDeleteTextures(3, textures, 0)
            GLES30.glDeleteProgram(program)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
        }

        companion object {
            fun create(texture: SurfaceTexture): Gl? {
                val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                val version = IntArray(2)
                if (!EGL14.eglInitialize(display, version, 0, version, 1)) return failed("initialising EGL")
                val configs = arrayOfNulls<EGLConfig>(1)
                val count = IntArray(1)
                val attributes = intArrayOf(
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_NONE,
                )
                if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) {
                    return failed("choosing an EGL config")
                }
                val config = configs[0]
                val context = EGL14.eglCreateContext(
                    display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
                )
                if (context == EGL14.EGL_NO_CONTEXT) return failed("creating the GL context")
                val surface = EGL14.eglCreateWindowSurface(display, config, texture, intArrayOf(EGL14.EGL_NONE), 0)
                if (surface == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(display, surface, surface, context)) {
                    EGL14.eglDestroyContext(display, context)
                    return failed("creating the window surface")
                }
                val program = program() ?: return null
                val textures = IntArray(3)
                GLES30.glGenTextures(3, textures, 0)
                for (name in textures) {
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, name)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                }
                // Planes have odd widths; rows are not padded to four bytes.
                GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
                GLES30.glUseProgram(program)
                listOf("y_plane", "u_plane", "v_plane").forEachIndexed { unit, name ->
                    GLES30.glUniform1i(GLES30.glGetUniformLocation(program, name), unit)
                }
                val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
                val vertices = ByteBuffer.allocateDirect(quad.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
                vertices.asFloatBuffer().put(quad)
                return Gl(display, context, surface, program, vertices, textures)
            }

            private fun program(): Int? {
                val vertex = shader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER) ?: return null
                val fragment = shader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER) ?: return null
                val program = GLES30.glCreateProgram()
                GLES30.glAttachShader(program, vertex)
                GLES30.glAttachShader(program, fragment)
                GLES30.glBindAttribLocation(program, 0, "position")
                GLES30.glLinkProgram(program)
                GLES30.glDeleteShader(vertex)
                GLES30.glDeleteShader(fragment)
                val linked = IntArray(1)
                GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linked, 0)
                if (linked[0] == 0) return failed("linking the shader: ${GLES30.glGetProgramInfoLog(program)}")
                return program
            }

            private fun shader(type: Int, source: String): Int? {
                val shader = GLES30.glCreateShader(type)
                GLES30.glShaderSource(shader, source)
                GLES30.glCompileShader(shader)
                val compiled = IntArray(1)
                GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
                if (compiled[0] == 0) return failed("compiling a shader: ${GLES30.glGetShaderInfoLog(shader)}")
                return shader
            }

            private fun <T> failed(what: String): T? {
                Log.e(LOG_TAG, "video: $what failed (EGL error ${EGL14.eglGetError()})")
                return null
            }
        }
    }

    companion object {
        const val THREAD_NAME = "mumcord-video-render"
        private const val LOG_TAG = "Mumcord"
        private const val QUIT_WAIT_MS = 1_000L

        // The quad covers the viewport; the picture's first row is at the top.
        private const val VERTEX_SHADER = """#version 300 es
in vec2 position;
out vec2 uv;
void main() {
    uv = vec2((position.x + 1.0) * 0.5, (1.0 - position.y) * 0.5);
    gl_Position = vec4(position, 0.0, 1.0);
}
"""

        // BT.601, limited range (16–235 luma, 16–240 chroma), as the engine's pictures are.
        private const val FRAGMENT_SHADER = """#version 300 es
precision mediump float;
in vec2 uv;
uniform sampler2D y_plane;
uniform sampler2D u_plane;
uniform sampler2D v_plane;
out vec4 color;
void main() {
    float y = (texture(y_plane, uv).r - 0.0625) * 1.1644;
    float u = texture(u_plane, uv).r - 0.5;
    float v = texture(v_plane, uv).r - 0.5;
    color = vec4(y + 1.5960 * v, y - 0.3918 * u - 0.8130 * v, y + 2.0172 * u, 1.0);
}
"""
    }
}
