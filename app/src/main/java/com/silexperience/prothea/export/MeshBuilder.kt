package com.silexperience.prothea.export

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Reconstruction de surface on-device : nuage de points -> STL binaire (mm).
 *
 * Methode : reconstruction cylindrique autour de l'axe vertical du sujet
 * (ARCore : Y = haut). Le buste scanne a 360 degres se prete bien a une
 * carte radiale R(theta, y), triangulee + fermeture haut/bas.
 *
 * Pipeline :
 * 1. Nettoyage : suppression du decor (points trop loin du centre dense)
 * 2. Carte radiale mediane R(theta, y) + interpolation des trous
 * 3. Lissage
 * 4. Triangulation + capuchons -> STL binaire en millimetres
 */
object MeshBuilder {

    class MeshStats(val inputPoints: Int, val keptPoints: Int,
                    val triangles: Int, val heightMm: Int)

    fun loadPly(file: File): FloatArray? = runCatching {
        val pts = ArrayList<Float>(65536)
        var inBody = false
        file.bufferedReader().useLines { lines ->
            for (l in lines) {
                if (!inBody) {
                    if (l == "end_header") inBody = true
                } else {
                    val p = l.trim().split(" ")
                    if (p.size >= 3) {
                        pts.add(p[0].toFloat()); pts.add(p[1].toFloat()); pts.add(p[2].toFloat())
                    }
                }
            }
        }
        if (pts.size < 300) null else FloatArray(pts.size) { pts[it] }
    }.getOrNull()

    /** Construit le maillage et ecrit le STL. Retourne les stats ou null. */
    fun build(plyFile: File, stlOut: File): MeshStats? {
        val pts = loadPly(plyFile) ?: return null
        val n = pts.size / 3

        // ---- 1. Centre et nettoyage du decor ----
        val xs = FloatArray(n); val ys = FloatArray(n); val zs = FloatArray(n)
        for (i in 0 until n) { xs[i] = pts[i*3]; ys[i] = pts[i*3+1]; zs[i] = pts[i*3+2] }
        val cx = median(xs); val cz = median(zs)
        val radii = FloatArray(n) { i ->
            val dx = xs[i]-cx; val dz = zs[i]-cz
            sqrt(dx*dx + dz*dz)
        }
        val rMed = median(radii.copyOf())
        // Garde les points plausibles du sujet (vire murs, sol lointain, mobilier)
        val keep = BooleanArray(n) { i -> radii[i] in 0.02f..(rMed * 1.6f) }
        var kept = 0
        for (b in keep) if (b) kept++
        if (kept < 300) return null

        // ---- 2. Carte radiale R(theta, y) ----
        var yMin = Float.MAX_VALUE; var yMax = -Float.MAX_VALUE
        for (i in 0 until n) if (keep[i]) { yMin = min(yMin, ys[i]); yMax = max(yMax, ys[i]) }
        val height = yMax - yMin
        if (height < 0.15f) return null

        val nTheta = 96
        val nY = (height / 0.004f).toInt().coerceIn(32, 160) // ~4 mm vertical
        val bins = HashMap<Int, ArrayList<Float>>(8192)

        for (i in 0 until n) {
            if (!keep[i]) continue
            val theta = atan2((xs[i]-cx).toDouble(), (zs[i]-cz).toDouble()).toFloat()
            var ti = (((theta + Math.PI) / (2*Math.PI)) * nTheta).toInt() % nTheta
            if (ti < 0) ti += nTheta
            val yi = (((ys[i]-yMin) / height) * (nY-1)).toInt().coerceIn(0, nY-1)
            val key = yi * nTheta + ti
            bins.getOrPut(key) { ArrayList(8) }.add(radii[i])
        }

        // Agregation par PERCENTILE 25 : la surface du sujet est le point le
        // plus proche sur chaque rayon — robuste au fond et au bruit MiDaS
        val grid = Array(nY) { FloatArray(nTheta) { -1f } }
        for ((key, values) in bins) {
            values.sort()
            val p25 = values[(values.size * 0.25f).toInt().coerceIn(0, values.size - 1)]
            grid[key / nTheta][key % nTheta] = p25.coerceIn(rMed * 0.3f, rMed * 1.7f)
        }

        // Interpolation des trous : lignes theta d'abord, puis recopie verticale
        for (y in 0 until nY) fillRowCircular(grid[y])
        fillRowsVertical(grid)

        // Filtre MEDIAN circulaire (tue les pics "herisson"), puis lissage
        medianFilter(grid, nTheta)
        repeat(3) { smooth(grid, nTheta) }

        // ---- 3. Triangulation + STL binaire (mm) ----
        val tris = ArrayList<FloatArray>(nTheta * nY * 2)
        fun vertex(ti: Int, yi: Int): FloatArray {
            val t = ti % nTheta
            val theta = (t.toDouble() / nTheta) * 2 * Math.PI - Math.PI
            val y = yMin + height * yi / (nY - 1)
            val r = max(grid[yi][t], 0.01f)
            return floatArrayOf(
                (cx + r * sin(theta)).toFloat(),
                y,
                (cz + r * cos(theta)).toFloat()
            )
        }
        for (y in 0 until nY - 1) for (t in 0 until nTheta) {
            val a = vertex(t, y); val b = vertex(t+1, y)
            val c = vertex(t+1, y+1); val d = vertex(t, y+1)
            tris.add(floatArrayOf(*a, *b, *c))
            tris.add(floatArrayOf(*a, *c, *d))
        }
        // Capuchons haut/bas
        val bottom = floatArrayOf(cx, yMin, cz)
        val top = floatArrayOf(cx, yMax, cz)
        for (t in 0 until nTheta) {
            val a0 = vertex(t, 0); val b0 = vertex(t+1, 0)
            tris.add(floatArrayOf(*bottom, *b0, *a0))
            val a1 = vertex(t, nY-1); val b1 = vertex(t+1, nY-1)
            tris.add(floatArrayOf(*top, *a1, *b1))
        }

        writeBinaryStl(stlOut, tris)
        return MeshStats(n, kept, tris.size, (height*1000).toInt())
    }

    // ---------- helpers ----------

    private fun median(a: FloatArray): Float {
        val s = a.copyOf().also { it.sort() }
        return s[s.size / 2]
    }

    private fun fillRowCircular(row: FloatArray) {
        val n = row.size
        // Trouve une case remplie pour amorcer
        var start = -1
        for (i in 0 until n) if (row[i] >= 0) { start = i; break }
        if (start == -1) return
        var lastVal = row[start]
        var lastIdx = 0
        // Parcours circulaire depuis start : interpole lineairement les vides
        for (k in 1..n) {
            val i = (start + k) % n
            if (row[i] >= 0) {
                val gap = k - lastIdx
                if (gap > 1) {
                    for (g in 1 until gap) {
                        val f = g.toFloat() / gap
                        row[(start + lastIdx + g) % n] = lastVal * (1-f) + row[i] * f
                    }
                }
                lastVal = row[i]; lastIdx = k
            }
        }
    }

    private fun fillRowsVertical(grid: Array<FloatArray>) {
        // Recopie la ligne remplie la plus proche vers les lignes vides
        val nY = grid.size
        fun rowEmpty(y: Int): Boolean {
            for (v in grid[y]) if (v >= 0) return false
            return true
        }
        var up = 0
        while (up < nY && rowEmpty(up)) up++
        if (up == nY) return
        for (y in 0 until up) grid[y] = grid[up].copyOf()
        var last = up
        for (y in up+1 until nY) {
            if (rowEmpty(y)) grid[y] = grid[last].copyOf() else last = y
        }
    }

    /** Mediane 3x3 (theta circulaire) : elimine les pics isoles. */
    private fun medianFilter(grid: Array<FloatArray>, nTheta: Int) {
        val nY = grid.size
        val src = grid.map { it.copyOf() }
        val window = FloatArray(9)
        for (y in 1 until nY-1) for (t in 0 until nTheta) {
            var k = 0
            for (dy in -1..1) for (dt in -1..1) {
                window[k++] = src[y+dy][(t+dt+nTheta)%nTheta]
            }
            window.sort(0, 9)
            grid[y][t] = window[4]
        }
    }

    private fun smooth(grid: Array<FloatArray>, nTheta: Int) {
        val nY = grid.size
        val src = grid.map { it.copyOf() }
        for (y in 1 until nY-1) for (t in 0 until nTheta) {
            grid[y][t] = (src[y][t] * 4 +
                src[y][(t+1)%nTheta] + src[y][(t-1+nTheta)%nTheta] +
                src[y-1][t] + src[y+1][t]) / 8f
        }
    }

    private fun writeBinaryStl(out: File, tris: List<FloatArray>) {
        DataOutputStream(BufferedOutputStream(out.outputStream())).use { d ->
            val header = ByteArray(80)
            val title = "Prothea mesh (mm)".toByteArray()
            System.arraycopy(title, 0, header, 0, title.size)
            d.write(header)
            d.writeInt(Integer.reverseBytes(tris.size))
            for (t in tris) {
                val (ax, ay, az) = Triple(t[0], t[1], t[2])
                val ux = t[3]-ax; val uy = t[4]-ay; val uz = t[5]-az
                val vx = t[6]-ax; val vy = t[7]-ay; val vz = t[8]-az
                var nx = uy*vz - uz*vy; var ny = uz*vx - ux*vz; var nz = ux*vy - uy*vx
                val nl = sqrt(nx*nx + ny*ny + nz*nz)
                if (nl > 0) { nx/=nl; ny/=nl; nz/=nl }
                fun f(v: Float) { d.writeInt(java.lang.Float.floatToIntBits(v * 1000f).reverseBytes()) }
                f(nx); f(ny); f(nz) // normale (sans unite)
                for (i in 0 until 9) f(t[i]) // sommets en mm
                d.writeShort(0)
            }
        }
    }

    private fun Int.reverseBytes(): Int = Integer.reverseBytes(this)
}
