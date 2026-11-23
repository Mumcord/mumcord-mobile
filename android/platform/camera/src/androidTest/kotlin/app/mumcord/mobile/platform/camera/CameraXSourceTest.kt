// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.platform.camera

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraXConfig
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The phone's camera runs only between start and stop (FR-5.75): stopping releases it, which is
 * what turns the system's camera indicator off. Uses the emulator's back camera.
 */
@RunWith(AndroidJUnit4::class)
class CameraXSourceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val manager = context.getSystemService(CameraManager::class.java)
    private val openCameras = Collections.synchronizedSet(mutableSetOf<String>())
    private val callback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            openCameras -= cameraId
        }

        override fun onCameraUnavailable(cameraId: String) {
            openCameras += cameraId
        }
    }
    private val frames = AtomicInteger()
    private val troubles = Collections.synchronizedList(mutableListOf<CameraTrouble>())
    private lateinit var source: CameraXSource

    @Before
    fun setUp() {
        assumeTrue("the device has a camera", manager.cameraIdList.isNotEmpty())
        limitToTheBackCamera()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.CAMERA)
        manager.registerAvailabilityCallback(callback, Handler(Looper.getMainLooper()))
        source = onMain {
            CameraXSource(context, { _, _, _, _, _, _, _, _, _ -> CameraFrames.PUSH_SENT.also { frames.incrementAndGet() } }) {
                troubles += it
            }
        }
    }

    @After
    fun tearDown() {
        if (::source.isInitialized) onMain { source.stop() }
        manager.unregisterAvailabilityCallback(callback)
    }

    @Test
    fun theCameraDeliversFramesOnlyBetweenStartAndStopAndIsReleasedAfterwards() {
        awaitTrue("no camera open before the start") { openCameras.isEmpty() }
        onMain { source.start(CameraFacing.Back) }
        awaitTrue("frames from the camera") { frames.get() >= MIN_FRAMES }
        awaitTrue("the camera is open") { openCameras.isNotEmpty() }
        assertTrue("no trouble: $troubles", troubles.isEmpty())

        onMain { source.stop() }
        awaitTrue("the camera is released") { openCameras.isEmpty() }
        Thread.sleep(SETTLE_MILLIS)
        val stopped = frames.get()
        Thread.sleep(SETTLE_MILLIS)
        assertTrue("no frames after the stop", frames.get() == stopped)

        onMain { source.start(CameraFacing.Front) }
        // The emulator may lack a front camera: the other one is used then.
        awaitTrue("frames again after a new start") { frames.get() > stopped + MIN_FRAMES }
    }

    /**
     * The test emulator has only a back camera while it reports itself as a device with two, so
     * CameraX would wait for a front camera forever. Only for this test process.
     */
    private fun limitToTheBackCamera() {
        if (configured) return
        ProcessCameraProvider.configureInstance(
            CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
                .setAvailableCamerasLimiter(CameraSelector.DEFAULT_BACK_CAMERA)
                .build(),
        )
        configured = true
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        val done = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            result = runCatching(block)
            done.countDown()
        }
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)
        return checkNotNull(result).getOrThrow()
    }

    private fun awaitTrue(what: String, condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (!condition()) {
            assertTrue("timed out waiting for $what", System.nanoTime() < until)
            Thread.sleep(POLL_MILLIS)
        }
    }

    private companion object {
        var configured = false
        const val MIN_FRAMES = 5
        const val WAIT_SECONDS = 20L
        const val POLL_MILLIS = 50L
        const val SETTLE_MILLIS = 500L
    }
}
