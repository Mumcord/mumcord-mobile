// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.identities

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import app.mumcord.mobile.R
import app.mumcord.mobile.TestSettings
import app.mumcord.mobile.platform.scanner.QrCameraPreview
import app.mumcord.mobile.ui.camera.CameraSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * What only the phone's system can do for the identity screens: the file picker, the share
 * sheet, the camera question and the camera picture.
 */
class IdentitySystem(
    /** Opens the system's file picker for an identity file. */
    val pickFile: () -> Unit,
    /** Hands a backup to the system's share sheet. */
    val share: (ExportedIdentity) -> Unit,
    val askCamera: () -> Unit,
    val openCameraSettings: () -> Unit,
    /** The camera picture of the scanner; it reports the QR codes it reads by itself. */
    val viewfinder: @Composable (Modifier) -> Unit,
)

/** An identity file is a few kilobytes; anything far larger is not one and is not read. */
const val MAX_IDENTITY_FILE_BYTES = 1024 * 1024

private const val EXPORT_DIRECTORY = "identity-export"
private const val P12_MIME = "application/x-pkcs12"

/** The real file picker, share sheet and camera. Picked files and read QR codes go to [controller]. */
@Composable
fun rememberIdentitySystem(controller: IdentitiesController, camera: CameraSystem): IdentitySystem {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                controller.importFile(withContext(Dispatchers.IO) { readIdentityFile(context, uri) })
            }
        }
    }
    // Once per start of the app, not again after a rotation while the share sheet is open: a
    // backup of an earlier export does not stay on the phone.
    var swept by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!swept) {
            swept = true
            withContext(Dispatchers.IO) { File(context.cacheDir, EXPORT_DIRECTORY).deleteRecursively() }
        }
    }
    return remember(picker, camera) {
        IdentitySystem(
            // Phones name the type of a .p12 file in different ways, so every file can be picked;
            // what is not an identity is refused when it is read.
            pickFile = { picker.launch(arrayOf("*/*")) },
            share = { exported ->
                scope.launch {
                    val uri = withContext(Dispatchers.IO) { writeExport(context, exported) }
                    if (uri != null) shareExport(context, uri)
                }
            },
            askCamera = camera.ask,
            openCameraSettings = camera.openSettings,
            viewfinder = { modifier ->
                val texts = TestSettings.qrTexts
                if (texts == null) {
                    QrCameraPreview(controller::onQrText, controller::cameraFailed, modifier)
                } else {
                    LaunchedEffect(texts) { texts.collect(controller::onQrText) }
                }
            },
        )
    }
}

/** The bytes of the picked file; null when it cannot be read or is too large for an identity. */
private fun readIdentityFile(context: Context, uri: Uri): ByteArray? =
    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            // One byte more than allowed is enough to know that the file is too large.
            val buffer = ByteArray(MAX_IDENTITY_FILE_BYTES + 1)
            var size = 0
            while (size < buffer.size) {
                val read = input.read(buffer, size, buffer.size - size)
                if (read < 0) break
                size += read
            }
            buffer.copyOf(size).takeIf { size in 1..MAX_IDENTITY_FILE_BYTES }
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

/** The backup as a file other apps can read through a one-time grant; it replaces the last one. */
private fun writeExport(context: Context, exported: ExportedIdentity): Uri? =
    try {
        val directory = File(context.cacheDir, EXPORT_DIRECTORY)
        directory.deleteRecursively()
        if (!directory.mkdirs()) throw IOException("no export directory")
        val file = File(directory, exported.fileName)
        file.writeBytes(exported.bytes)
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    } catch (_: IOException) {
        null
    }

private fun shareExport(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = P12_MIME
        putExtra(Intent.EXTRA_STREAM, uri)
        // The share sheet shows the file by this, and the grant travels with it.
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, context.getString(R.string.identity_export_share))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(chooser)
    } catch (_: ActivityNotFoundException) {
        // A phone without anything to share with; the system's chooser exists on every phone.
    }
}
