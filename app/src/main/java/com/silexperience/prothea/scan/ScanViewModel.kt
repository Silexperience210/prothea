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
        coverage.start()
        arCore.start()
        viewModelScope.launch {
            while (_sessionId.value == id) {
                _cloudPoints.value = arCore.cloud.size
                kotlinx.coroutines.delay(500)
            }
        }
    }

    fun onAzimuth(a: Float) {
        _lastAzimuthRaw.value = a
        _azimuthDeg.value = CoverageTracker.azimuthDeg(a)
        if (_calibrated.value) coverage.markSectorFor(a)
    }

    fun calibrate() {
        coverage.calibrate(_lastAzimuthRaw.value)
        _calibrated.value = true
    }

    fun onPhotoSaved(bytes: ByteArray) {
        val id = _sessionId.value ?: return
        sessions.savePhoto(id, bytes, _photoCount.value, _azimuthDeg.value)
        // Camera frontale : on joint la carte de profondeur ML (PNG gris)
        lastDepth?.let { sessions.saveDepthMap(id, _photoCount.value, it) }
        _photoCount.value += 1
    }

    /** Termine la session : sauvegarde le nuage PLY + meta chiffree. */
    fun finishSession(notes: String = "") {
        val id = _sessionId.value ?: return
        _busy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            if (arCore.cloud.size > 500) {
                val (pts, n) = arCore.cloud.snapshot()
                PlyExporter.write(pts, n, sessions.cloudFile(id))
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
            onDone(sessions.exportZip(id, dest))
        }
    }
}
