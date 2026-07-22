package com.silexperience.prothea.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.silexperience.prothea.scan.ScanViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SessionScreen(vm: ScanViewModel, sessionId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val info0 = remember(sessionId) {
        vm.sessions.listSessions().firstOrNull { it.id == sessionId }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let {
            vm.exportSession(sessionId, it) { ok ->
                Toast.makeText(
                    context,
                    if (ok) "Export ZIP termine" else "Echec de l'export",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    val stlLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("model/stl")
    ) { uri ->
        uri?.let {
            vm.exportStl(sessionId, it) { ok ->
                Toast.makeText(
                    context,
                    if (ok) "STL exporte" else "Echec de l'export STL",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    var info by remember { mutableStateOf(info0) }
    val busy by vm.busy.collectAsState()

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Session", style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold)

        val s = info
        if (s == null) {
            Text("Session introuvable.")
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)
                    Text(fmt.format(Date(s.dateMillis)))
                    Text("${s.photoCount} photos source")
                    if (s.hasCloud)
                        Text("Nuage 3D : ${s.pointCount} points (PLY, echelle reelle)")
                    else
                        Text("Pas de nuage 3D (telephone sans profondeur ARCore)")
                    if (s.hasMesh)
                        Text("Maillage STL genere (pret pour impression)",
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.SemiBold)
                }
            }

            if (s.hasCloud) {
                Button(
                    onClick = {
                        vm.generateStl(sessionId) { ok, msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            info = vm.sessions.listSessions().firstOrNull { it.id == sessionId }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (busy) "Reconstruction en cours…" else "Generer le STL (impression 3D)") }
            }

            if (s.hasMesh) {
                Button(
                    onClick = { stlLauncher.launch("$sessionId.stl") },
                    Modifier.fillMaxWidth()
                ) { Text("Exporter le STL") }
            }

            Button(
                onClick = { exportLauncher.launch("$sessionId.zip") },
                Modifier.fillMaxWidth()
            ) { Text("Exporter la session (ZIP)") }

            if (s.photoCount > 0) {
                OutlinedButton(
                    onClick = {
                        vm.sessions.deletePhotos(sessionId)
                        Toast.makeText(context,
                            "Photos sources supprimees", Toast.LENGTH_SHORT).show()
                    },
                    Modifier.fillMaxWidth()
                ) { Text("Supprimer les photos sources (vie privee)") }
            }

            OutlinedButton(
                onClick = {
                    vm.sessions.deleteSession(sessionId)
                    onBack()
                },
                Modifier.fillMaxWidth()
            ) { Text("Supprimer la session") }
        }

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = onBack) { Text("Retour") }
        }
    }
}
