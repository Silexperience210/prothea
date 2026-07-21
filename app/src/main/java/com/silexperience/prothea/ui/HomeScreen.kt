package com.silexperience.prothea.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.silexperience.prothea.scan.ScanViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    vm: ScanViewModel,
    onNewScan: () -> Unit,
    onOpenSession: (String) -> Unit
) {
    var sessions by remember { mutableStateOf(vm.sessions.listSessions()) }
    val current by vm.sessionId.collectAsState()
    if (current == null) sessions = vm.sessions.listSessions()

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Text("Prothea", style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold)
        Text(
            "Scan 3D du buste pour protheses externes sur mesure.",
            style = MaterialTheme.typography.bodyLarge
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Confidentialite absolue", fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.secondary)
                Text(
                    "Cette application n'a AUCUN acces reseau. Vos scans ne quittent " +
                        "jamais ce telephone : photos et nuages 3D restent dans le stockage " +
                        "prive, metadonnees chiffrees. Vous seule decidez de l'export.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Button(onClick = onNewScan, Modifier.fillMaxWidth().height(56.dp)) {
            Text("Nouveau scan")
        }

        Text("Sessions", style = MaterialTheme.typography.titleMedium)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sessions) { s ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)
                            Text(fmt.format(Date(s.dateMillis)), fontWeight = FontWeight.Medium)
                            Text(
                                "${s.photoCount} photos" +
                                    if (s.hasCloud) " · nuage ${s.pointCount} pts" else "",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        TextButton(onClick = { onOpenSession(s.id) }) { Text("Ouvrir") }
                    }
                }
            }
        }
    }
}
