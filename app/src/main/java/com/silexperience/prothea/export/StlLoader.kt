package com.silexperience.prothea.export

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Chargeur de STL binaire (format ecrit par MeshBuilder). */
object StlLoader {

    class Mesh(
        val triangles: Int,
        /** Sommets : 9 floats par triangle (x,y,z x3), en mm, centres sur l'origine. */
        val verts: FloatArray,
        /** Normales : 3 floats par triangle. */
        val normals: FloatArray,
        val maxDim: Float
    )

    fun load(file: File): Mesh? = runCatching {
        val bytes = file.readBytes()
        if (bytes.size < 84) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(80)
        val count = buf.int
        if (count <= 0 || 84 + count * 50L > bytes.size) return null

        val verts = FloatArray(count * 9)
        val normals = FloatArray(count * 3)
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE

        for (t in 0 until count) {
            normals[t*3] = buf.float; normals[t*3+1] = buf.float; normals[t*3+2] = buf.float
            for (v in 0 until 9) {
                val f = buf.float
                verts[t*9+v] = f
                when (v % 3) {
                    0 -> { if (f < minX) minX = f; if (f > maxX) maxX = f }
                    1 -> { if (f < minY) minY = f; if (f > maxY) maxY = f }
                    else -> { if (f < minZ) minZ = f; if (f > maxZ) maxZ = f }
                }
            }
            buf.short // attribut
        }

        // Centrage sur l'origine
        val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2; val cz = (minZ + maxZ) / 2
        for (i in verts.indices) {
            when (i % 3) {
                0 -> verts[i] -= cx
                1 -> verts[i] -= cy
                else -> verts[i] -= cz
            }
        }
        val maxDim = maxOf(maxX - minX, maxY - minY, maxZ - minZ).takeIf { it > 0 } ?: 1f
        Mesh(count, verts, normals, maxDim)
    }.getOrNull()
}
