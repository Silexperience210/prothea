package com.silexperience.prothea.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.silexperience.prothea.export.StlLoader
import com.silexperience.prothea.scan.ScanViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

/**
 * Viewer STL embarque : rendu logiciel (peintre + ombrage plat),
 * rotation au doigt. 100 % local, aucune dependance 3D.
 */
@Composable
fun StlViewerScreen(vm: ScanViewModel, sessionId: String, onBack: () -> Unit) {
    val mesh by produceState<StlLoader.Mesh?>(initialValue = null, sessionId) {
        value = withContext(Dispatchers.IO) {
            StlLoader.load(vm.sessions.meshFile(sessionId))
        }
    }

    var rotX by remember { mutableStateOf(-0.5f) }
    var rotY by remember { mutableStateOf(0.6f) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                mesh?.let { "${it.triangles} triangles · fais glisser pour tourner" }
                    ?: "Chargement du maillage…",
                style = MaterialTheme.typography.bodyMedium
            )
            TextButton(onClick = onBack) { Text("Retour") }
        }

        val m = mesh
        if (m != null) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            rotY += drag.x * 0.008f
                            rotX += drag.y * 0.008f
                            rotX = rotX.coerceIn(-1.6f, 1.6f)
                        }
                    }
            ) {
                val cx = size.width / 2
                val cy = size.height / 2
                val scale = size.minDimension * 0.42f / m.maxDim
                val camDist = m.maxDim * 2.6f

                val sx = sin(rotX); val cxr = cos(rotX)
                val sy = sin(rotY); val cyr = cos(rotY)

                // Decimation d'affichage pour rester fluide pendant la rotation
                val stride = maxOf(1, m.triangles / 9000)
                val path = Path()

                // Trie les triangles par profondeur (peintre) sur un echantillon
                val triCount = (m.triangles + stride - 1) / stride
                val depths = FloatArray(triCount)
                val order = IntArray(triCount)

                var k = 0
                var t = 0
                while (t < m.triangles) {
                    var zSum = 0f
                    for (v in 2 until 9 step 3) {
                        val x = m.verts[t*9+v-2]; val z = m.verts[t*9+v]
                        // Rotation Y puis X (on n'a besoin que de la profondeur)
                        val yv = m.verts[t*9+v-1]
                        val z1 = -x * sy + z * cyr
                        val z2 = yv * sx + z1 * cxr
                        zSum += z2
                    }
                    depths[k] = zSum / 3f
                    order[k] = t
                    k++
                    t += stride
                }
                // Tri par profondeur decroissante (loin -> proche)
                val sortedIdx = (0 until triCount).sortedByDescending { depths[it] }

                for (k2 in sortedIdx) {
                    val ti = order[k2]
                    var zAvg = 0f
                    val pts = FloatArray(6)
                    var j = 0
                    for (v in 0 until 9 step 3) {
                        val x = m.verts[ti*9+v]; val y = m.verts[ti*9+v+1]; val z = m.verts[ti*9+v+2]
                        val x1 = x * cyr + z * sy
                        val z1 = -x * sy + z * cyr
                        val y2 = y * cxr - z1 * sx
                        val z2 = y * sx + z1 * cxr
                        zAvg += z2
                        // Perspective simple
                        val persp = camDist / (camDist - z2)
                        pts[j++] = cx + x1 * scale * persp
                        pts[j++] = cy - y2 * scale * persp
                    }
                    zAvg /= 3f

                    // Ombrage plat : normale . lumiere
                    val nx0 = m.normals[ti*3]; val ny0 = m.normals[ti*3+1]; val nz0 = m.normals[ti*3+2]
                    val nx1 = nx0 * cyr + nz0 * sy
                    val nz1 = -nx0 * sy + nz0 * cyr
                    val ny1 = ny0 * cxr - nz1 * sx
                    val nz2 = ny0 * sx + nz1 * cxr
                    val lum = 0.35f + 0.65f * (-(nz2)).coerceIn(0f, 1f)

                    path.reset()
                    path.moveTo(pts[0], pts[1])
                    path.lineTo(pts[2], pts[3])
                    path.lineTo(pts[4], pts[5])
                    path.close()
                    drawPath(
                        path,
                        Color(
                            red = 0.31f * lum + 0.12f,
                            green = 0.76f * lum + 0.10f,
                            blue = 0.97f * lum + 0.10f
                        )
                    )
                }
            }
        }
    }
}
