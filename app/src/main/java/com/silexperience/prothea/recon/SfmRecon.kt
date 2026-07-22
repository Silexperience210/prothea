package com.silexperience.prothea.recon

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.silexperience.prothea.depth.DepthEstimator
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.calib3d.Calib3d
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.SIFT
import org.opencv.imgproc.Imgproc
import org.opencv.android.Utils
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Photogrammetrie on-device (SfM + fusion profondeur monoculaire).
 *
 * 1. SIFT sur chaque photo + matching entre consecutives
 * 2. Matrice essentielle + recoverPose -> poses relatives chainees
 *    (baseline constante : l'anneau de 16 secteurs garantit des pas egaux)
 * 3. Triangulation de points 3D epars
 * 4. Alignement des cartes MiDaS sur les points epars (1/z = a + b*d, LS)
 * 5. Back-projection dense -> nuage unifie
 * 6. Redressement vertical (normale du plan des cameras) + echelle ~m
 */
object SfmRecon {

    private const val TAG = "SfmRecon"
    private var cvReady = false

    fun initOpenCv(): Boolean {
        if (!cvReady) cvReady = runCatching { OpenCVLoader.initLocal() }.getOrElse {
            runCatching { System.loadLibrary("opencv_java4") }.isSuccess
        }
        return cvReady
    }

    // Pose camera : X_cam = R * (X_world - C)
    class Cam(
        val r: Array<DoubleArray>, // 3x3
        val c: DoubleArray         // centre monde (3)
    )

    class SparsePoint(
        val x: Double, val y: Double, val z: Double,
        val obs: MutableList<Obs>
    )
    class Obs(val camIdx: Int, val u: Float, val v: Float)

    class SfmResult(
        val cams: List<Cam>,
        val sparse: List<SparsePoint>,
        val imgW: Int,
        val imgH: Int,
        val focal: Double
    )

    // ---------- Math utils ----------

    private fun matMul(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
        val out = Array(3) { DoubleArray(3) }
        for (i in 0..2) for (j in 0..2)
            for (k in 0..2) out[i][j] += a[i][k] * b[k][j]
        return out
    }

    private fun matT(a: Array<DoubleArray>) =
        Array(3) { i -> DoubleArray(3) { j -> a[j][i] } }

    private fun matVec(a: Array<DoubleArray>, v: DoubleArray) = DoubleArray(3) { i ->
        a[i][0] * v[0] + a[i][1] * v[1] + a[i][2] * v[2]
    }

    // ---------- Pipeline ----------

    fun run(
        photoFiles: List<File>,
        depthByPhoto: Map<String, DepthEstimator.Result>,
        prog: (String) -> Unit
    ): FloatArray? {
        if (!initOpenCv()) { Log.e(TAG, "OpenCV indisponible"); return null }
        if (photoFiles.size < 6) return null

        // ---- 1. Chargement + SIFT ----
        prog("SIFT : extraction des points caracteristiques…")
        class Img(val gray: Mat, val kps: Array<org.opencv.core.KeyPoint>, val desc: Mat, val w: Int, val h: Int)
        val sift = SIFT.create(2500)
        val imgs = ArrayList<Img>()
        var focal = 0.0
        for (f in photoFiles) {
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            val bmp = BitmapFactory.decodeFile(f.path, opts) ?: continue
            val w = bmp.width; val h = bmp.height
            focal = maxOf(w, h) * 1.2
            val mat = Mat()
            Utils.bitmapToMat(bmp, mat)
            val gray = Mat()
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
            val kps = MatOfKeyPoint()
            val desc = Mat()
            sift.detectAndCompute(gray, Mat(), kps, desc)
            imgs.add(Img(gray, kps.toArray(), desc, w, h))
        }
        if (imgs.size < 6) return null
        val w0 = imgs[0].w; val h0 = imgs[0].h
        val cx = w0 / 2.0; val cy = h0 / 2.0
        val K = Mat(3, 3, CvType.CV_64F).apply {
            put(0, 0, focal, 0.0, cx)
            put(1, 0, 0.0, focal, cy)
            put(2, 0, 0.0, 0.0, 1.0)
        }

        // ---- 2. Matching + recoverPose entre consecutives ----
        val n = imgs.size
        val cams = arrayOfNulls<Cam>(n)
        cams[0] = Cam(Array(3) { i -> DoubleArray(3) { j -> if (i == j) 1.0 else 0.0 } }, DoubleArray(3))
        val sparse = ArrayList<SparsePoint>()
        val matcher = BFMatcher.create(Core.NORM_L2, false)
        var chained = true

        for (i in 0 until n - 1) {
            prog("Photogrammetrie : appariement ${i + 1}/${n - 1}…")
            val a = imgs[i]; val b = imgs[i + 1]
            if (a.desc.rows() < 8 || b.desc.rows() < 8) { chained = false; break }
            val knn = ArrayList<MatOfDMatch>()
            matcher.knnMatch(a.desc, b.desc, knn, 2)
            val p1 = ArrayList<Point>(); val p2 = ArrayList<Point>()
            val pairIdx = ArrayList<IntArray>() // (kpA, kpB)
            for (m in knn) {
                val dm = m.toArray()
                if (dm.size >= 2 && dm[0].distance < 0.75f * dm[1].distance) {
                    p1.add(a.kps[dm[0].queryIdx].pt)
                    p2.add(b.kps[dm[0].trainIdx].pt)
                    pairIdx.add(intArrayOf(dm[0].queryIdx, dm[0].trainIdx))
                }
            }
            if (p1.size < 15) { chained = false; break }
            val mp1 = MatOfPoint2f(); mp1.fromList(p1)
            val mp2 = MatOfPoint2f(); mp2.fromList(p2)
            val mask = Mat()
            val E = Calib3d.findEssentialMat(
                mp1 as Mat, mp2 as Mat, focal,
                org.opencv.core.Point(cx, cy), Calib3d.RANSAC, 0.999, 1.5)
            if (E.total() == 0L) { chained = false; break }
            val R = Mat(); val t = Mat()
            val inl = Calib3d.recoverPose(E, mp1 as Mat, mp2 as Mat, K, R, t, mask)
            if (inl < 10) { chained = false; break }

            // Chainage : R_{i+1} = R_rel * R_i ; C_{i+1} = C_i - R_{i+1}^T * t (||t||=1, baseline const)
            val ri = cams[i]!!.r
            val rrel = Array(3) { r -> DoubleArray(3) { c -> R.get(r, c)[0] } }
            val tv = DoubleArray(3) { t.get(it, 0)[0] }
            val rNext = matMul(rrel, ri)
            val step = matVec(matT(rNext), tv)
            val ci = cams[i]!!.c
            val cNext = DoubleArray(3) { ci[it] - step[it] }
            cams[i + 1] = Cam(rNext, cNext)

            // Triangulation des inliers de cette paire (dans le repere monde)
            val inliers1 = ArrayList<Point>(); val inliers2 = ArrayList<Point>()
            val inlierIdx = ArrayList<IntArray>()
            for (k in 0 until p1.size) {
                if (k < mask.rows() && mask.get(k, 0)[0] > 0.5) {
                    inliers1.add(p1[k]); inliers2.add(p2[k]); inlierIdx.add(pairIdx[k])
                }
            }
            if (inliers1.size >= 8) {
                val P1 = projMat(K, cams[i]!!)
                val P2 = projMat(K, cams[i + 1]!!)
                val cnt = inliers1.size
                val q1 = Mat(cnt, 1, CvType.CV_32FC2)
                val q2 = Mat(cnt, 1, CvType.CV_32FC2)
                for (k in 0 until cnt) {
                    q1.put(k, 0, inliers1[k].x, inliers1[k].y)
                    q2.put(k, 0, inliers2[k].x, inliers2[k].y)
                }
                val pts4 = Mat()
                Calib3d.triangulatePoints(P1, P2, q1, q2, pts4)
                for (k in 0 until cnt) {
                    val w = pts4.get(3, k)[0]
                    if (abs(w) < 1e-9) continue
                    val X = pts4.get(0, k)[0] / w
                    val Y = pts4.get(1, k)[0] / w
                    val Z = pts4.get(2, k)[0] / w
                    // Doit etre devant les deux cameras
                    if (depthInCam(cams[i]!!, X, Y, Z) <= 0.05 ||
                        depthInCam(cams[i + 1]!!, X, Y, Z) <= 0.05) continue
                    sparse.add(SparsePoint(X, Y, Z, mutableListOf(
                        Obs(i, inliers1[k].x.toFloat(), inliers1[k].y.toFloat()),
                        Obs(i + 1, inliers2[k].x.toFloat(), inliers2[k].y.toFloat())
                    )))
                }
            }
        }
        if (!chained || cams.any { it == null } || sparse.size < 40) {
            Log.w(TAG, "SfM epars insuffisant (chain=$chained sparse=${sparse.size})")
            return null
        }
        @Suppress("UNCHECKED_CAST")
        val camList = cams.filterNotNull()
        val sfm = SfmResult(camList, sparse, w0, h0, focal)

        // ---- 3. Fusion dense : alignement MiDaS + back-projection ----
        prog("Fusion dense : alignement des profondeurs…")
        val points = denseFusion(photoFiles, depthByPhoto, sfm, prog) ?: return null

        // ---- 4. Redressement vertical + echelle ----
        prog("Redressement et mise a l'echelle…")
        return rectifyAndScale(points, camList, sparse)
    }

    private fun depthInCam(cam: Cam, x: Double, y: Double, z: Double): Double {
        val dx = x - cam.c[0]; val dy = y - cam.c[1]; val dz = z - cam.c[2]
        return cam.r[2][0] * dx + cam.r[2][1] * dy + cam.r[2][2] * dz
    }

    private fun projMat(K: Mat, cam: Cam): Mat {
        val t = matVec(cam.r, cam.c)
        val P = Mat(3, 4, CvType.CV_64F)
        for (r in 0..2) {
            for (c in 0..2) {
                var s = 0.0
                for (k in 0..2) s += K.get(r, k)[0] * cam.r[k][c]
                P.put(r, c, s)
            }
            var st = 0.0
            for (k in 0..2) st += K.get(r, k)[0] * (-t[k])
            P.put(r, 3, st)
        }
        return P
    }

    /** Ajuste 1/z = a + b*d par moindres carres sur les points epars de chaque camera,
     *  puis back-projette toute la carte MiDaS dans le repere monde. */
    private fun denseFusion(
        photoFiles: List<File>,
        depthByPhoto: Map<String, DepthEstimator.Result>,
        sfm: SfmResult,
        prog: (String) -> Unit
    ): FloatArray? {
        val out = ArrayList<Float>(300_000)
        var fused = 0
        for ((idx, file) in photoFiles.withIndex()) {
            if (idx >= sfm.cams.size) break
            val depth = depthByPhoto[file.name] ?: continue
            val cam = sfm.cams[idx]

            // Collecte (z, d) sur les points epars observes par cette camera
            val zs = ArrayList<Double>(); val ds = ArrayList<Double>()
            for (sp in sfm.sparse) {
                for (obs in sp.obs) {
                    if (obs.camIdx != idx) continue
                    val z = depthInCam(cam, sp.x, sp.y, sp.z)
                    if (z <= 0.05) continue
                    val du = (obs.u / sfm.imgW * depth.width).toInt().coerceIn(0, depth.width - 1)
                    val dv = (obs.v / sfm.imgH * depth.height).toInt().coerceIn(0, depth.height - 1)
                    zs.add(z); ds.add(depth.depth[dv * depth.width + du].toDouble())
                }
            }
            if (zs.size < 6) continue

            // Moindres carres 1/z = a + b*d
            var sd = 0.0; var sdd = 0.0; var siz = 0.0; var sdiz = 0.0
            val m = zs.size
            for (k in 0 until m) {
                val iz = 1.0 / zs[k]
                sd += ds[k]; sdd += ds[k] * ds[k]
                siz += iz; sdiz += ds[k] * iz
            }
            val det = m * sdd - sd * sd
            if (abs(det) < 1e-9) continue
            val b = (m * sdiz - sd * siz) / det
            val a = (siz - b * sd) / m
            if (b <= 0) continue // profondeur incoherente

            // Back-projection dense
            val Rt = matT(cam.r)
            val step = 2
            var y = 0
            while (y < depth.height) {
                var x = 0
                while (x < depth.width) {
                    val d = depth.depth[y * depth.width + x].toDouble()
                    val z = 1.0 / (a + b * d)
                    if (z in 0.05..3.5) {
                        val u = (x + 0.5) / depth.width * sfm.imgW
                        val v = (y + 0.5) / depth.height * sfm.imgH
                        val rx = (u - sfm.imgW / 2.0) / sfm.focal
                        val ry = (v - sfm.imgH / 2.0) / sfm.focal
                        val Xc = doubleArrayOf(rx * z, ry * z, z)
                        val Xw = matVec(Rt, Xc)
                        out.add((Xw[0] + cam.c[0]).toFloat())
                        out.add((Xw[1] + cam.c[1]).toFloat())
                        out.add((Xw[2] + cam.c[2]).toFloat())
                    }
                    x += step
                }
                y += step
            }
            fused++
            prog("Fusion dense : photo ${idx + 1}/${photoFiles.size}…")
        }
        if (fused < 4 || out.size < 30_000) return null
        return FloatArray(out.size) { out[it] }
    }

    /** Aligne l'axe vertical sur la normale du plan des cameras (anneau),
     *  et met a l'echelle pour que la distance camera-sujet mediane ~ 0,6 m. */
    private fun rectifyAndScale(
        pts: FloatArray, cams: List<Cam>, sparse: List<SparsePoint>
    ): FloatArray {
        // Centroide des cameras
        var gx = 0.0; var gy = 0.0; var gz = 0.0
        for (c in cams) { gx += c.c[0]; gy += c.c[1]; gz += c.c[2] }
        gx /= cams.size; gy /= cams.size; gz /= cams.size

        // Normale du plan de l'anneau : somme des produits vectoriels consecutifs
        var nx = 0.0; var ny = 0.0; var nz = 0.0
        for (i in cams.indices) {
            val a = cams[i].c; val b2 = cams[(i + 1) % cams.size].c
            val ax = a[0]-gx; val ay = a[1]-gy; val az = a[2]-gz
            val bx = b2[0]-gx; val by = b2[1]-gy; val bz = b2[2]-gz
            nx += ay*bz - az*by; ny += az*bx - ax*bz; nz += ax*by - ay*bx
        }
        var nl = sqrt(nx*nx + ny*ny + nz*nz)
        if (nl < 1e-9) { nx = 0.0; ny = 1.0; nz = 0.0; nl = 1.0 }
        nx /= nl; ny /= nl; nz /= nl

        // Rotation Rodrigues n -> (0,1,0)
        val up = doubleArrayOf(0.0, 1.0, 0.0)
        val vx = ny*up[2]-nz*up[1]; val vy = nz*up[0]-nx*up[2]; val vz = nx*up[1]-ny*up[0]
        val s = sqrt(vx*vx + vy*vy + vz*vz)
        val c = nx*up[0] + ny*up[1] + nz*up[2]
        val rot = Array(3) { i -> DoubleArray(3) { j -> if (i == j) 1.0 else 0.0 } }
        if (s > 1e-9) {
            val kx = vx/s; val ky = vy/s; val kz = vz/s
            val ca = c; val sa = s
            val K = arrayOf(
                doubleArrayOf(0.0, -kz, ky),
                doubleArrayOf(kz, 0.0, -kx),
                doubleArrayOf(-ky, kx, 0.0)
            )
            // R = I + sin*K + (1-cos)*K^2
            val K2 = matMul(K, K)
            for (i in 0..2) for (j in 0..2)
                rot[i][j] += sa * K[i][j] + (1 - ca) * K2[i][j]
        }

        // Echelle : distance mediane camera -> centroide epars ~ 0.6 m
        var sx = 0.0; var sy = 0.0; var sz = 0.0
        for (p in sparse) { sx += p.x; sy += p.y; sz += p.z }
        sx /= sparse.size; sy /= sparse.size; sz /= sparse.size
        val dists = cams.map {
            sqrt((it.c[0]-sx)*(it.c[0]-sx) + (it.c[1]-sy)*(it.c[1]-sy) + (it.c[2]-sz)*(it.c[2]-sz))
        }.sorted()
        val med = dists[dists.size / 2]
        val scale = if (med > 1e-6) 0.60 / med else 1.0

        val out = FloatArray(pts.size)
        var i = 0
        while (i < pts.size) {
            val x = (pts[i] - gx) * scale
            val y = (pts[i + 1] - gy) * scale
            val z = (pts[i + 2] - gz) * scale
            out[i] = (rot[0][0]*x + rot[0][1]*y + rot[0][2]*z).toFloat()
            out[i + 1] = (rot[1][0]*x + rot[1][1]*y + rot[1][2]*z).toFloat()
            out[i + 2] = (rot[2][0]*x + rot[2][1]*y + rot[2][2]*z).toFloat()
            i += 3
        }
        return out
    }
}
