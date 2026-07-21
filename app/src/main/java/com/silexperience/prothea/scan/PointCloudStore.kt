package com.silexperience.prothea.scan

/**
 * Nuage de points 3D thread-safe, capacite fixe avec remplacement aleatoire
 * (reservoir simplifie) pour garder une densite homogene sans exploser la RAM.
 */
class PointCloudStore(private val maxPoints: Int = 250_000) {

    private val data = FloatArray(maxPoints * 3)

    @Volatile var size = 0
        private set

    private var inserted = 0L

    @Synchronized
    fun add(x: Float, y: Float, z: Float) {
        if (size < maxPoints) {
            val i = size * 3
            data[i] = x; data[i + 1] = y; data[i + 2] = z
            size++
        } else {
            // Remplacement pseudo-aleatoire : decimation douce
            val slot = ((inserted * 2654435761L) ushr 32).toInt() % maxPoints
            val i = slot * 3
            data[i] = x; data[i + 1] = y; data[i + 2] = z
        }
        inserted++
    }

    @Synchronized
    fun snapshot(): Pair<FloatArray, Int> {
        val copy = FloatArray(size * 3)
        System.arraycopy(data, 0, copy, 0, size * 3)
        return copy to size
    }

    @Synchronized
    fun clear() {
        size = 0
        inserted = 0
    }
}
