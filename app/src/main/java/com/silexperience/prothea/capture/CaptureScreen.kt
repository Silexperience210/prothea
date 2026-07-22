package com.silexperience.prothea.capture

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
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
import androidx.compose.ui.graphics.asImageBitmap
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
    val autoCapture by vm.autoCapture.collectAsState()

    val imageCapture = remember { ImageCapture.Builder().build() }
    var capturing by remember { mutableStateOf(false) }

    // Fonction de capture (manuelle ou auto)
    fun performCapture() {
        if (capturing) return
        capturing = true
        val tmp = File.createTempFile("cap", ".jpg", context.cacheDir)
        val out = ImageCapture.OutputFileOptions.Builder(tmp).build()
        imageCapture.takePicture(
            out, ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    vm.onPhotoSaved(tmp.readBytes())
                    tmp.delete()
                    capturing = false
                }
                override fun onError(e: ImageCaptureException) {
                    capturing = false
                }
            }
        )
    }

    // Camera arriere par defaut ; la frontale permet l'auto-scan en solo
    var useFrontCamera by remember { mutableStateOf(false) }
    val cameraSelector = if (useFrontCamera)
        CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

    // ---- Mode scan : capture automatique a l'entree d'un secteur non couvert ----
    androidx.compose.runtime.LaunchedEffect(calibrated, autoCapture) {
        while (calibrated && autoCapture) {
            kotlinx.coroutines.delay(300)
            if (capturing || busy) continue
            val sector = vm.sectorNow()
            if (sector != null && !vm.coverage.isCovered(sector)) {
                performCapture()
                kotlinx.coroutines.delay(900) // cooldown entre deux secteurs
            }
        }
    }

    // ARCore utilise sa propre camera (arriere) : on le coupe en mode
    // camera avant pour ne pas accumuler un nuage incoherent avec l'aperçu
    androidx.compose.runtime.LaunchedEffect(hasCamera, useFrontCamera) {
        if (!hasCamera) return@LaunchedEffect
        if (useFrontCamera) {
            vm.arCore.stop()
        } else if (vm.arCore.state != ArCoreEngine.State.READY) {
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

        // ---- Flux camera (rebind automatique au changement de camera) ----
        val previewView = remember { PreviewView(context) }
        var depthOverlay by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
        val analysisExecutor = remember { java.util.concurrent.Executors.newSingleThreadExecutor() }
        var lastInferenceAt by remember { mutableStateOf(0L) }

        val imageAnalysis = remember {
            ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
        }

        androidx.compose.runtime.LaunchedEffect(cameraSelector, hasCamera) {
            if (!hasCamera) return@LaunchedEffect
            val provider = ProcessCameraProvider.getInstance(context).get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            provider.unbindAll()
            if (useFrontCamera && vm.depthEstimator.available) {
                // Camera frontale : estimation de profondeur ML ~3 fois/s
                imageAnalysis.setAnalyzer(analysisExecutor) { proxy ->
                    val now = System.currentTimeMillis()
                    if (now - lastInferenceAt > 300) {
                        lastInferenceAt = now
                        runCatching {
                            var bmp = proxy.toBitmap()
                            val rot = proxy.imageInfo.rotationDegrees
                            if (rot != 0) {
                                val m = android.graphics.Matrix().apply { postRotate(rot.toFloat()) }
                                bmp = android.graphics.Bitmap.createBitmap(
                                    bmp, 0, 0, bmp.width, bmp.height, m, true)
                            }
                            // Miroir : la frontale affiche une image inversee
                            val mirror = android.graphics.Matrix().apply { preScale(-1f, 1f) }
                            bmp = android.graphics.Bitmap.createBitmap(
                                bmp, 0, 0, bmp.width, bmp.height, mirror, true)
                            vm.depthEstimator.estimate(bmp)?.let { result ->
                                vm.lastDepth = result
                                depthOverlay = com.silexperience.prothea.depth.DepthEstimator.colorize(result)
                            }
                        }
                    }
                    proxy.close()
                }
                provider.bindToLifecycle(
                    lifecycleOwner, cameraSelector, preview, imageCapture, imageAnalysis)
            } else {
                imageAnalysis.clearAnalyzer()
                vm.lastDepth = null
                depthOverlay = null
                provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageCapture)
            }
        }
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // ---- Apercu profondeur ML (camera frontale) ----
        depthOverlay?.let { bmp ->
            androidx.compose.foundation.Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Apercu profondeur",
                alpha = 0.85f,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 220.dp)
                    .size(140.dp)
            )
        }

        // ---- Bouton mode auto / manuel (haut gauche) ----
        if (calibrated) {
            OutlinedButton(
                onClick = { vm.toggleAutoCapture() },
                modifier = Modifier.align(Alignment.TopStart).padding(top = 48.dp, start = 16.dp)
            ) {
                Text(if (autoCapture) "Mode manuel" else "Mode auto")
            }
        }

        // ---- Bouton switch camera (haut droite) ----
        OutlinedButton(
            onClick = { useFrontCamera = !useFrontCamera },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 48.dp, end = 16.dp)
        ) {
            Text(if (useFrontCamera) "Camera arriere" else "Camera avant")
        }
        if (useFrontCamera) {
            Text(
                "Mode auto-scan : tenez le telephone a bout de bras et tournez lentement sur vous-meme. La profondeur est estimee par IA (relative, sans echelle metrique).",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.TopCenter)
                    .padding(top = 220.dp, start = 32.dp, end = 32.dp)
            )
        }

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
            val arLabel = if (useFrontCamera) {
                if (vm.depthEstimator.available)
                    "Profondeur ML active (relative) — apercu en bas a droite, carte PNG jointe a chaque photo"
                else
                    "Modele de profondeur ML absent — photos seules"
            } else when (arState) {
                ArCoreEngine.State.READY ->
                    if (cloudPoints > 0)
                        "Points 3D accumules : $cloudPoints (ARCore + IA photos)"
                    else if (vm.arCore.depthSupported) {
                        val d = vm.arCore
                        "ARCore · 0 pts — tracking=${d.tracking} depthOk=${d.depthFramesOk} echecs=${d.depthFramesFailed} ${d.lastDepthError ?: ""}".take(120)
                    } else "Points 3D via IA sur les photos"
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
                else if (autoCapture)
                    "SCAN AUTO : tournez lentement — chaque nouveau secteur declenche une photo · ${vm.coverage.covered.count { it }}/${vm.coverage.sectors} · $photoCount photos"
                else
                    "Tournez lentement + Capturer dans chaque secteur · ${vm.coverage.covered.count { it }}/${vm.coverage.sectors} secteurs · $photoCount photos",
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
                        onClick = { performCapture() },
                        enabled = !capturing && !busy
                    ) { Text(if (capturing) "…" else "Capturer") }

                    OutlinedButton(
                        onClick = { vm.finishSession(); onDone() },
                        enabled = !busy && photoCount >= 3
                    ) { Text(if (busy) "Finalisation…" else "Terminer") }
                }
            }
        }
    }
}
