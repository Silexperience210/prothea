package com.silexperience.prothea.scan

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.silexperience.prothea.depth.DepthEstimator
import com.silexperience.prothea.export.PlyExporter
import com.silexperience.prothea.storage.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    val sessions = SessionManager(app)
    val arCore = ArCoreEngine(app)
    val coverage = CoverageTracker(app).also { it.listener = ::onAzimuth }

    /** Estimation de profondeur ML pour la camera frontale (lazy : charge le modele une fois). */
    val depthEstimator by lazy { DepthEstimator(app) }

    /** Derniere carte de profondeur estimee (camera frontale). */
    @Volatile var lastDepth: DepthEstimator.Result? = null

    /** Nuage construit depuis les photos + profondeur IA (fallback sans ARCore depth). */
    val photoCloud = PointCloudStore(150_000)

    private val _sessionId = MutableStateFlow<String?>(null)
    val sessionId = _sessionId.asStateFlow()

    private val _photoCount = MutableStateFlow(0)
    val photoCount = _photoCount.asStateFlow()

    private val _azimuthDeg = MutableStateFlow(0.0)
    val azimuthDeg = _azimuthDeg.asStateFlow()

    private val _calibrated = MutableStateFlow(false)
    val calibrated = _calibrated.asStateFlow()

    private val _cloudPoints = MutableStateFlow(0)
    val cloudPoints = _cloudPoints.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    private val _lastAzimuthRaw = MutableStateFlow(0f)

    fun startNewSession() {
        val id = sessions.createSession()
        _sessionId.value = id
        _photoCount.value = 0
        _calibrated.value = false
        arCore.cloud.clear()
        photoCloud.clear()
        coverage.start()
        arCore.start()
        viewModelScope.launch {
            while (_sessionId.value == id) {
                _cloudPoints.value = arCore.cloud.size + photoCloud.size
                kotlinx.coroutines.delay(500)
            }
        }
    }

    /** Capture automatique quand on entre dans un secteur non couvert (mode scan). */
    private val _autoCapture = MutableStateFlow(true)
    val autoCapture = _autoCapture.asStateFlow()

    fun toggleAutoCapture() { _autoCapture.value = !_autoCapture.value }

    /** Secteur actuellement vise (null avant calibrage). */
    fun sectorNow(): Int? = coverage.sectorFor(_lastAzimuthRaw.value)

    fun onAzimuth(a: Float) {
        _lastAzimuthRaw.value = a
        _azimuthDeg.value = CoverageTracker.azimuthDeg(a)
    }

    fun calibrate() {
        coverage.calibrate(_lastAzimuthRaw.value)
        _calibrated.value = true
    }

    fun onPhotoSaved(bytes: ByteArray) {
        val id = _sessionId.value ?: return
        val index = _photoCount.value
        val azimuth = _azimuthDeg.value
        sessions.savePhoto(id, bytes, index, azimuth)
        // Le secteur n'est valide QUE si une photo y a ete prise
        coverage.markSectorFor(_lastAzimuthRaw.value)
        _photoCount.value += 1
        // Profondeur IA sur CHAQUE photo (avant ET arriere) : alimente le
        // nuage-photo, independant d'ARCore. En arriere-plan pour ne pas
        // bloquer l'UI pendant l'inference.
        if (depthEstimator.available) {
            viewModelScope.launch(Dispatchers.Default) {
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return@launch
                val res = depthEstimator.estimate(bmp) ?: return@launch
                lastDepth = res
                sessions.saveDepthMap(id, index, res)
                com.silexperience.prothea.depth.PhotoCloudBuilder.append(
                    res, azimuth, photoCloud)
            }
        }
    }

    /** Termine la session : sauvegarde le nuage PLY + meta chiffree. */
    fun finishSession(notes: String = "") {
        val id = _sessionId.value ?: return
        _busy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            // Priorite au nuage ARCore (echelle metrique) ; sinon nuage-photo IA
            // (echelle approximative mais fonctionne sur tous les telephones)
            val best = if (arCore.cloud.size > 500) arCore.cloud
                       else if (photoCloud.size > 1000) photoCloud
                       else null
            best?.let {
                val (pts, n) = it.snapshot()
                PlyExporter.write(pts, n, sessions.cloudFile(id))
                sessions.writeCloudSource(id,
                    if (it === arCore.cloud) "arcore" else "photos")
            }
            sessions.writeMeta(
                id, notes,
                coverage.covered.count { it }, coverage.sectors,
                arCore.depthSupported
            )
            arCore.stop()
            coverage.stop()
            _sessionId.value = null
            _busy.value = false
        }
    }

    fun cancelSession() {
        _sessionId.value?.let { sessions.deleteSession(it) }
        _sessionId.value = null
        arCore.stop()
        coverage.stop()
    }

    fun exportSession(id: String, dest: Uri, onDone: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = sessions.exportZip(id, dest)
            kotlinx.coroutines.withContext(Dispatchers.Main) { onDone(ok) }
        }
    }

    /** Genere le STL. Si pas de nuage, le reconstruit d'abord depuis les
     *  photos de la session (profondeur IA + azimut dans le nom de fichier). */
    fun generateStl(id: String, onProgress: (String) -> Unit = {},
                    onDone: (Boolean, String) -> Unit) {
        _busy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            // Les callbacks UI (Toast, etat Compose) doivent revenir sur le thread principal
            suspend fun prog(msg: String) =
                kotlinx.coroutines.withContext(Dispatchers.Main) { onProgress(msg) }
            suspend fun done(ok: Boolean, msg: String) {
                _busy.value = false
                kotlinx.coroutines.withContext(Dispatchers.Main) { onDone(ok, msg) }
            }

            val cloudFile = sessions.cloudFile(id)
            // Ne reutilise un nuage existant que s'il vient d'ARCore (fiable).
            // Un nuage issu des photos est TOUJOURS reconstruit (derniers filtres).
            val cloudOk = cloudFile.exists() &&
                sessions.cloudSource(id) == "arcore" &&
                (com.silexperience.prothea.export.MeshBuilder.loadPly(cloudFile)?.size ?: 0) >= 900

            if (!cloudOk) {
                // Reconstruction retroactive du nuage depuis les photos
                if (!depthEstimator.available) {
                    done(false, "Modele IA absent — impossible de reconstruire le nuage")
                    return@launch
                }
                val photos = sessions.photosDir(id).listFiles()
                    ?.filter { it.extension.equals("jpg", true) }?.sorted()
                if (photos.isNullOrEmpty()) {
                    done(false, "Pas de nuage 3D ni photos dans cette session")
                    return@launch
                }
                val store = PointCloudStore(200_000)
                val azRe = Regex("az(\\d+)")
                // Passe 1 : inference + rayon median par photo (recalage inter-photos)
                data class Shot(val az: Double, val res: com.silexperience.prothea.depth.DepthEstimator.Result, val medR: Float)
                val shots = ArrayList<Shot>()
                for (f in photos) {
                    val az = azRe.find(f.name)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
                    prog("Analyse photo ${shots.size + 1}/${photos.size}…")
                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
                    val bmp = android.graphics.BitmapFactory.decodeFile(f.path, opts) ?: continue
                    val res = depthEstimator.estimate(bmp) ?: continue
                    val medR = com.silexperience.prothea.depth.PhotoCloudBuilder.medianSubjectRadius(res)
                    shots.add(Shot(az.toDouble(), res, medR))
                }
                // Passe 2 : chaque photo est rescalee sur le rayon median global
                val meds = shots.map { it.medR }.sorted()
                val globalMed = meds.getOrElse(meds.size / 2) { 0.25f }
                for (s in shots) {
                    val scale = if (s.medR > 0.01f) (globalMed / s.medR).coerceIn(0.6f, 1.6f) else 1f
                    com.silexperience.prothea.depth.PhotoCloudBuilder.append(
                        s.res, s.az, store, scale = scale)
                }
                val processed = shots.size
                if (store.size < 1000) {
                    done(false, "Echec reconstruction : ${store.size} pts " +
                        "(IA ok=${depthEstimator.inferenceOk} err=${depthEstimator.inferenceFailed} " +
                        "${depthEstimator.lastError ?: ""})".take(150))
                    return@launch
                }
                prog("Ecriture du nuage (${store.size} pts)…")
                val (pts, n) = store.snapshot()
                PlyExporter.write(pts, n, cloudFile)
                sessions.writeCloudSource(id, "photos")
            }

            prog("Reconstruction de la surface…")
            val stats = com.silexperience.prothea.export.MeshBuilder.build(
                cloudFile, sessions.meshFile(id))
            if (stats != null)
                done(true, "${stats.keptPoints}/${stats.inputPoints} pts · " +
                        "${stats.triangles} triangles · hauteur ${stats.heightMm} mm")
            else
                done(false, "Nuage insuffisant pour reconstruire une surface")
        }
    }

    fun exportStl(id: String, dest: Uri, onDone: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = sessions.exportFile(sessions.meshFile(id), dest)
            kotlinx.coroutines.withContext(Dispatchers.Main) { onDone(ok) }
        }
    }
}
