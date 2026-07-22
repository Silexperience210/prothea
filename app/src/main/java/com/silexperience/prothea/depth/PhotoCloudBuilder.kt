package com.silexperience.prothea.depth

import com.silexperience.prothea.scan.PointCloudStore
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Construit un nuage de points a partir des PHOTOS + profondeur IA (MiDaS).
 *
 * Contournement pour les telephones ou ARCore depth ne retourne rien :
 * chaque photo est prise a un azimut connu (anneau de scan), a distance
 * approximative D du sujet. La carte de profondeur relative donne le rayon
 * de chaque point autour de l'axe du sujet.
 *
 * ECHELLE APPROXIMATIVE : le nuage est coherent en forme, l'echelle depend
 * de la distance reelle (calibrage ~60 cm). Ajustable au slicer.
 */
object PhotoCloudBuilder {

    /**
     * Ajoute les points d'une photo dans le nuage.
     * @param depth  carte de profondeur relative (1 = proche)
     * @param azimuthDeg azimut du telephone au moment de la photo (degres)
     * @param store  nuage de destination
     * @param distanceM distance sujet-telephone supposee (m)
     * @param fovVDeg champ vertical approximatif de la camera (degres)
     */
    fun append(
        depth: DepthEstimator.Result,
        azimuthDeg: Double,
        store: PointCloudStore,
        distanceM: Float = 0.60f,
        fovVDeg: Float = 52f,
        scale: Float = 1f
    ) {
        val w = depth.width
        val h = depth.height
        val baseAngle = Math.toRadians(azimuthDeg)
        val fovV = Math.toRadians(fovVDeg.toDouble())
        val heightSpan = 2f * distanceM * tan(fovV / 2).toFloat()
        val step = 3 // sous-echantillonnage (256/3 ~ 85x85 = ~7000 pts/photo max)

        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val d = depth.depth[y * w + x] // 0..1, 1 = proche
                val u = x.toFloat() / w - 0.5f
                val v = y.toFloat() / h
                // Garde uniquement la zone centrale de l'image (le sujet) :
                // seuil haut sur la proximite + marges laterales/verticales
                if (d > 0.50f && u in -0.35f..0.35f && v in 0.10f..0.90f) {
                    // Profondeur relative -> rayon autour de l'axe du sujet.
                    // Le sujet (proche, d~1) donne un petit rayon ; le fond est ecarte.
                    val r = (distanceM * (1.25f - 0.90f * d) * scale).coerceIn(0.04f, distanceM * 1.3f)
                    val angle = baseAngle + u * 0.7 // ~ +/-20 degres de champ lateral
                    val px = r * sin(angle).toFloat()
                    val pz = r * cos(angle).toFloat()
                    val py = (0.5f - v) * heightSpan
                    store.add(px, py, pz)
                }
                x += step
            }
            y += step
        }
    }

    /** Rayon median des pixels "sujet" d'une photo (memes filtres qu'append).
     *  Sert au recalage inter-photos : compense les variations de distance
     *  et de normalisation MiDaS d'une photo a l'autre. */
    fun medianSubjectRadius(depth: DepthEstimator.Result, distanceM: Float = 0.60f): Float {
        val w = depth.width; val h = depth.height
        val vals = ArrayList<Float>(4096)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val d = depth.depth[y * w + x]
                val u = x.toFloat() / w - 0.5f
                val v = y.toFloat() / h
                if (d > 0.50f && u in -0.35f..0.35f && v in 0.10f..0.90f) {
                    vals.add((distanceM * (1.25f - 0.90f * d)).coerceIn(0.04f, distanceM))
                }
                x += 3
            }
            y += 3
        }
        if (vals.isEmpty()) return distanceM * 0.4f
        vals.sort()
        return vals[vals.size / 2]
    }
}
