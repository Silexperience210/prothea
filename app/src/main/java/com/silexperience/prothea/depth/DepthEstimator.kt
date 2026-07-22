package com.silexperience.prothea.depth

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Estimation de profondeur monoculaire on-device (MiDaS v2.1 small, TFLite).
 *
 * Utilisee pour la camera FRONTALE, ou ARCore depth est indisponible.
 * Sortie : profondeur inverse RELATIVE (0..1, 1 = proche) — pas d'echelle
 * metrique, mais suffisant pour l'apercu live et comme canal supplementaire
 * pour la reconstruction.
 *
 * Le modele (assets/depth_model.tflite, ~66 Mo) est telecharge par la CI ;
 * en son absence, l'estimateur se desactive proprement.
 */
class DepthEstimator(context: Context) {

    val available: Boolean
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null

    /** Derniere erreur d'inference (diagnostic visible dans l'UI). */
    @Volatile var lastError: String? = null
        private set

    /** Compteurs d'inferences (diagnostic). */
    @Volatile var inferenceOk = 0
        private set
    @Volatile var inferenceFailed = 0
        private set

    private val inputSize = 256

    init {
        val model = runCatching {
            context.assets.openFd(MODEL_ASSET).use { afd ->
                FileInputStream(afd.fileDescriptor).channel.map(
                    FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            }
        }.getOrNull()

        if (model == null) {
            available = false
            Log.w(TAG, "Modele de profondeur absent (assets/$MODEL_ASSET)")
        } else {
            val opts = Interpreter.Options().apply {
                numThreads = 4
                runCatching {
                    val d = GpuDelegate()
                    addDelegate(d)
                    gpuDelegate = d
                }
            }
            interpreter = runCatching { Interpreter(model, opts) }.getOrNull()
            available = interpreter != null
            Log.i(TAG, "DepthEstimator actif (gpu=${gpuDelegate != null})")
        }
    }

    data class Result(
        val depth: FloatArray, // taille w*h, 0..1 (1 = proche)
        val width: Int,
        val height: Int
    )

    /** Lance l'inference sur un bitmap (redimensionne en interne a 256x256). */
    fun estimate(src: Bitmap): Result? {
        val tflite = interpreter ?: return null
        return runCatching {
            val bmp = Bitmap.createScaledBitmap(src, inputSize, inputSize, true)
            val input = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3)
                .order(ByteOrder.nativeOrder())
            val px = IntArray(inputSize * inputSize)
            bmp.getPixels(px, 0, inputSize, 0, 0, inputSize, inputSize)
            for (p in px) {
                input.putFloat(((p shr 16) and 0xFF) / 255f)
                input.putFloat(((p shr 8) and 0xFF) / 255f)
                input.putFloat((p and 0xFF) / 255f)
            }
            input.rewind()

            val outShape = tflite.getOutputTensor(0).shape()
            val h = outShape.getOrElse(1) { inputSize }
            val w = outShape.getOrElse(2) { inputSize }
            // Sortie [1,H,W,1] ou [1,H,W] selon la conversion du modele
            val flat = FloatArray(h * w)
            if (outShape.size == 4) {
                val o4 = Array(1) { Array(h) { Array(w) { FloatArray(1) } } }
                tflite.run(input, o4)
                for (y in 0 until h) for (x in 0 until w) flat[y * w + x] = o4[0][y][x][0]
            } else {
                val o3 = Array(1) { Array(h) { FloatArray(w) } }
                tflite.run(input, o3)
                for (y in 0 until h) for (x in 0 until w) flat[y * w + x] = o3[0][y][x]
            }

            // Normalisation min-max -> 0..1 (MiDaS : valeur haute = proche)
            var min = Float.MAX_VALUE; var max = -Float.MAX_VALUE
            for (v in flat) {
                if (v < min) min = v
                if (v > max) max = v
            }
            val range = (max - min).takeIf { it > 1e-6f } ?: 1f
            for (i in flat.indices) flat[i] = (flat[i] - min) / range
            inferenceOk++
            Result(flat, w, h)
        }.onFailure {
            inferenceFailed++
            lastError = it.javaClass.simpleName + ": " + it.message
            Log.w(TAG, "inference: ${it.message}")
        }.getOrNull()
    }

    fun close() {
        runCatching { interpreter?.close() }
        runCatching { gpuDelegate?.close() }
        interpreter = null
        gpuDelegate = null
    }

    companion object {
        private const val TAG = "DepthEstimator"
        const val MODEL_ASSET = "depth_model.tflite"

        /** Palette type "viridis" simplifiee : proche = chaud, loin = froid. */
        fun colorize(r: Result): Bitmap {
            val bmp = Bitmap.createBitmap(r.width, r.height, Bitmap.Config.ARGB_8888)
            val px = IntArray(r.width * r.height)
            for (i in r.depth.indices) {
                val t = r.depth[i].coerceIn(0f, 1f)
                val red = (255 * (1.5f * t - 0.25f)).toInt().coerceIn(0, 255)
                val green = (255 * (1.2f - Math.abs(t - 0.55f) * 2f)).toInt().coerceIn(0, 255)
                val blue = (255 * (1.1f - 1.4f * t)).toInt().coerceIn(0, 255)
                px[i] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
            }
            bmp.setPixels(px, 0, r.width, 0, 0, r.width, r.height)
            return bmp
        }

        /** Version grise (pour sauvegarde PNG exploitable en reconstruction). */
        fun grayscale(r: Result): Bitmap {
            val bmp = Bitmap.createBitmap(r.width, r.height, Bitmap.Config.ARGB_8888)
            val px = IntArray(r.width * r.height)
            for (i in r.depth.indices) {
                val g = (r.depth[i].coerceIn(0f, 1f) * 255).toInt()
                px[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
            bmp.setPixels(px, 0, r.width, 0, 0, r.width, r.height)
            return bmp
        }
    }
}
