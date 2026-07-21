package com.silexperience.prothea.capture

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.silexperience.prothea.scan.ArCoreEngine
import com.silexperience.prothea.scan.ScanViewModel
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun CaptureScreen(vm: ScanViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCamera = granted }

    val photoCount by vm.photoCount.collectAsState()
    val azimuthDeg by vm.azimuthDeg.collectAsState()
    val calibrated by vm.calibrated.collectAsState()
    val cloudPoints by vm.cloudPoints.collectAsState()
    val busy by vm.busy.collectAsState()

    val imageCapture = remember { ImageCapture.Builder().build() }
    var capturing by remember { mutableStateOf(false) }

    // Relance ARCore une fois la permission camera accordee
    androidx.compose.runtime.LaunchedEffect(hasCamera) {
        if (hasCamera && vm.arCore.state != ArCoreEngine.State.READY) {
            vm.arCore.stop()
            vm.arCore.start()
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (!hasCamera) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("La camera est indispensable pour le scan 3D.")
                Button(
                    onClick = { permLauncher.launch(Manifest.permission.CAMERA) },
                    Modifier.padding(top = 16.dp)
                ) { Text("Autoriser la camera") }
                OutlinedButton(onClick = {
                    vm.cancelSession(); onDone()
                }, Modifier.padding(top = 8.dp)) { Text("Annuler") }
            }
            return@Box
        }

        // ---- Flux camera ----
        AndroidView(
            factory = { ctx ->
                val pv = PreviewView(ctx)
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    val provider = future.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(pv.surfaceProvider)
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, imageCapture
                    )
                }, ContextCompat.getMainExecutor(ctx))
                pv
            },
            modifier = Modifier.fillMaxSize()
        )

        // ---- Anneau de couverture ----
        Canvas(
            Modifier.align(Alignment.TopCenter).padding(top = 48.dp).size(160.dp)
        ) {
            val cx = size.width / 2
            val cy = size.height / 2
            val r = size.minDimension / 2 - 12f
            for (i in 0 until vm.coverage.sectors) {
                val a = i * 2 * PI / vm.coverage.sectors - PI / 2
                val covered = vm.coverage.covered[i]
                drawCircle(
                    color = if (covered) Color(0xFF66BB6A) else Color(0x66FFFFFF),
                    radius = if (covered) 9f else 7f,
                    center = Offset(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat())
                )
            }
            // Position courante du telephone
            val cur = Math.toRadians(azimuthDeg) - PI / 2
            drawCircle(
                color = Color(0xFF4FC3F7),
                radius = 12f,
                center = Offset(cx + r * cos(cur).toFloat(), cy + r * sin(cur).toFloat())
            )
        }

        // ---- Zone d'info / actions ----
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val arState = vm.arCore.state
            val arLabel = when (arState) {
                ArCoreEngine.State.READY ->
                    if (vm.arCore.depthSupported)
                        "ARCore actif · profondeur OK · $cloudPoints pts"
                    else "ARCore actif (sans capteur de profondeur)"
                ArCoreEngine.State.STARTING -> "ARCore : demarrage…"
                ArCoreEngine.State.UNSUPPORTED -> "ARCore indisponible — mode photos seules"
                ArCoreEngine.State.ERROR -> "ARCore en erreur — mode photos seules"
                ArCoreEngine.State.OFF -> ""
            }
            if (arLabel.isNotEmpty()) {
                Text(arLabel, color = Color.White,
                    style = MaterialTheme.typography.bodySmall)
            }

            Text(
                if (!calibrated)
                    "Cadrez le buste a 50-70 cm, puis visez"
                else
                    "Tournez lentement autour · ${vm.coverage.covered.count { it }}/${vm.coverage.sectors} secteurs · $photoCount photos",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium
            )

            CircularProgressIndicator(
                progress = { if (calibrated) vm.coverage.progress else 0f },
                modifier = Modifier.size(48.dp)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (!calibrated) {
                    Button(onClick = { vm.calibrate() }) { Text("Viser le buste") }
                } else {
                    Button(
                        onClick = {
                            if (capturing) return@Button
                            capturing = true
                            val tmp = File.createTempFile("cap", ".jpg", context.cacheDir)
                            val out = ImageCapture.OutputFileOptions.Builder(tmp).build()
                            imageCapture.takePicture(
                                out, ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(
                                        r: ImageCapture.OutputFileResults) {
                                        vm.onPhotoSaved(tmp.readBytes())
                                        tmp.delete()
                                        capturing = false
                                    }
                                    override fun onError(e: ImageCaptureException) {
                                        capturing = false
                                    }
                                }
                            )
                        },
                        enabled = !capturing && !busy
                    ) { Text(if (capturing) "…" else "Capturer") }

                    OutlinedButton(
                        onClick = { vm.finishSession(); onDone() },
                        enabled = !busy && photoCount >= 8
                    ) { Text(if (busy) "Finalisation…" else "Terminer") }
                }
            }
        }
    }
}
