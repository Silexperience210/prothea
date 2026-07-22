package com.silexperience.prothea.scan

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.exceptions.UnavailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Moteur ARCore optionnel.
 *
 * - Si l'appareil supporte ARCore + profondeur (ToF / depth-from-motion) :
 *   fournit l'echelle metrique et accumule un nuage de points 3D reel.
 * - Sinon : le moteur se desactive silencieusement, le scan photo reste possible.
 *
 * ARCore exige un contexte GL pour Session.update() : on cree un mini
 * contexte EGL (pbuffer) sur un thread dedie.
 */
class ArCoreEngine(private val context: Context) {

    enum class State { OFF, STARTING, READY, UNSUPPORTED, ERROR }

    @Volatile var state = State.OFF
        private set

    @Volatile var depthSupported = false
        private set

    // Diagnostics (visibles dans l'UI pour comprendre un nuage a 0 pts)
    @Volatile var depthFramesOk = 0
        private set
    @Volatile var depthFramesFailed = 0
        private set
    @Volatile var lastDepthError: String? = null
        private set
    @Volatile var tracking = false
        private set

    private var session: Session? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var textureId = -1

    private val pointCloud = PointCloudStore(maxPoints = 250_000)
    val cloud: PointCloudStore get() = pointCloud

    /** Pose courante (tx,ty,tz,qx,qy,qz,qw) ou null. Mise a jour ~10 Hz. */
    @Volatile var lastPose: FloatArray? = null
        private set

    fun start() {
        if (state != State.OFF) return
        state = State.STARTING
        thread = HandlerThread("arcore").also { t ->
            t.start()
            handler = Handler(t.looper)
            handler?.post { initGlAndSession() }
        }
    }

    fun stop() {
        handler?.post {
            runCatching { session?.close() }
            session = null
            destroyGl()
            thread?.quitSafely()
        }
        state = State.OFF
    }

    private fun initGlAndSession() {
        try {
            createGl()
            val s = Session(context)
            val config = Config(s)
            depthSupported = s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
            config.depthMode =
                if (depthSupported) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            s.configure(config)
            s.setCameraTextureName(textureId)
            s.resume()
            session = s
            state = State.READY
            tick()
        } catch (e: UnavailableException) {
            Log.w(TAG, "ARCore indisponible : ${e.message}")
            state = State.UNSUPPORTED
            destroyGl()
        } catch (t: Throwable) {
            Log.e(TAG, "Erreur init ARCore", t)
            state = State.ERROR
            destroyGl()
        }
    }

    private fun tick() {
        val s = session ?: return
        try {
            val frame = s.update()
            val cam = frame.camera
            if (cam.trackingState == com.google.ar.core.TrackingState.TRACKING) {
                tracking = true
                val p = cam.pose
                lastPose = floatArrayOf(p.tx(), p.ty(), p.tz(), p.qx(), p.qy(), p.qz(), p.qw())
                if (depthSupported) {
                    try {
                        accumulateDepth(frame, cam)
                        depthFramesOk++
                    } catch (t: Throwable) {
                        depthFramesFailed++
                        lastDepthError = t.javaClass.simpleName + ": " + t.message
                    }
                }
            } else {
                tracking = false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "update() : ${t.message}")
        }
        handler?.postDelayed({ tick() }, 100)
    }

    /** Sous-echantillonne la depth map et projette les points en coordonnees monde. */
    private fun accumulateDepth(frame: Frame, cam: com.google.ar.core.Camera) {
        frame.acquireDepthImage16Bits().use { depth ->
            val w = depth.width
            val h = depth.height
            val buf: ByteBuffer = depth.planes[0].buffer.order(ByteOrder.nativeOrder())
            val intr = cam.textureIntrinsics
            val fx = intr.focalLength[0]; val fy = intr.focalLength[1]
            val cx = intr.principalPoint[0]; val cy = intr.principalPoint[1]
            val iw = intr.imageDimensions[0].toFloat()
            val ih = intr.imageDimensions[1].toFloat()
            val step = 6 // sous-echantillonnage
            val pose = cam.pose
            val camPoint = FloatArray(3)
            var y = 0
            while (y < h) {
                var x = 0
                while (x < w) {
                    val idx = (y * depth.planes[0].rowStride + x * 2)
                    val mm = buf.getShort(idx).toInt() and 0xFFFF
                    if (mm in 200..4000) { // 20 cm .. 4 m : zone utile d'un scan buste
                        val d = mm / 1000f
                        // pixel depth-map -> pixel image camera
                        val u = x.toFloat() / w * iw
                        val v = y.toFloat() / h * ih
                        camPoint[0] = (u - cx) / fx * d
                        camPoint[1] = (v - cy) / fy * d
                        camPoint[2] = -d // ARCore : la camera regarde vers -Z
                        val world = pose.transformPoint(camPoint)
                        pointCloud.add(world[0], world[1], world[2])
                    }
                    x += step
                }
                y += step
            }
        }
    }

    // ---------- GL minimal pour ARCore ----------

    private fun createGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0)
        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(
            eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        val surfAttribs = intArrayOf(EGL14.EGL_WIDTH, 8, EGL14.EGL_HEIGHT, 8, EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], surfAttribs, 0)
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
    }

    private fun destroyGl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    companion object { private const val TAG = "ArCoreEngine" }
}
