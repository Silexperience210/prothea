package com.silexperience.prothea.export

import java.io.BufferedOutputStream
import java.io.File
import java.util.Locale

/** Export PLY ASCII (metres, repere monde ARCore). */
object PlyExporter {

    fun write(points: FloatArray, count: Int, dest: File): Boolean = runCatching {
        BufferedOutputStream(dest.outputStream()).bufferedWriter().use { w ->
            w.write("ply\n")
            w.write("format ascii 1.0\n")
            w.write("comment Prothea scan - unite : metre\n")
            w.write("element vertex $count\n")
            w.write("property float x\nproperty float y\nproperty float z\n")
            w.write("end_header\n")
            val sb = StringBuilder()
            for (i in 0 until count) {
                sb.setLength(0)
                sb.append(String.format(Locale.US, "%.5f %.5f %.5f",
                    points[i * 3], points[i * 3 + 1], points[i * 3 + 2]))
                w.write(sb.toString())
                w.write("\n")
            }
        }
    }.isSuccess
}
