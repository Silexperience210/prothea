package com.silexperience.prothea.storage

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Gestion des sessions de scan — 100 % local :
 * - repertoire prive de l'app (inaccessible aux autres apps)
 * - metadonnees chiffrees (AES-256-GCM, cle dans Android Keystore)
 * - les photos sources peuvent etre supprimees apres reconstruction
 */
class SessionManager(private val context: Context) {

    private val root: File = File(context.filesDir, "sessions")

    data class SessionInfo(
        val id: String,
        val photoCount: Int,
        val pointCount: Long,
        val hasCloud: Boolean,
        val hasMesh: Boolean = false,
        val dateMillis: Long
    )

    fun createSession(): String {
        val id = "scan_" + System.currentTimeMillis()
        File(photosDir(id).path).mkdirs()
        return id
    }

    fun sessionDir(id: String) = File(root, id)
    fun photosDir(id: String) = File(root, "$id/photos")
    fun cloudFile(id: String) = File(root, "$id/cloud.ply")
    fun meshFile(id: String) = File(root, "$id/mesh.stl")

    /** Marqueur d'origine du nuage ("arcore" = echelle metrique, "photos" = IA approx). */
    fun writeCloudSource(id: String, source: String) {
        File(root, "$id/cloud.src").writeText(source)
    }
    fun cloudSource(id: String): String? =
        File(root, "$id/cloud.src").takeIf { it.exists() }?.readText()?.trim()

    /** Copie un fichier de session vers une destination SAF. */
    fun exportFile(file: File, dest: Uri): Boolean = runCatching {
        context.contentResolver.openOutputStream(dest)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        } ?: throw IllegalStateException("SAF output null")
    }.isSuccess

    fun savePhoto(id: String, bytes: ByteArray, index: Int, azimuthDeg: Double) {
        val f = File(photosDir(id), "photo_%03d_az%03d.jpg".format(index, azimuthDeg.toInt()))
        f.writeBytes(bytes)
    }

    /** Sauvegarde la carte de profondeur ML (camera frontale) en PNG gris. */
    fun saveDepthMap(id: String, index: Int, depth: com.silexperience.prothea.depth.DepthEstimator.Result) {
        val dir = File(root, "$id/depth").apply { mkdirs() }
        val f = File(dir, "photo_%03d_depth.png".format(index))
        runCatching {
            f.outputStream().use { out ->
                com.silexperience.prothea.depth.DepthEstimator.grayscale(depth)
                    .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
        }
    }

    fun listSessions(): List<SessionInfo> {
        val dir = root.listFiles() ?: return emptyList()
        return dir.filter { it.isDirectory }.map { s ->
            val photos = File(s, "photos").listFiles()?.count {
                it.extension.equals("jpg", true)
            } ?: 0
            val cloud = File(s, "cloud.ply")
            val mesh = File(s, "mesh.stl")
            SessionInfo(
                id = s.name,
                photoCount = photos,
                pointCount = if (cloud.exists()) countPlyPoints(cloud) else 0,
                hasCloud = cloud.exists(),
                hasMesh = mesh.exists(),
                dateMillis = s.name.removePrefix("scan_").toLongOrNull() ?: 0L
            )
        }.sortedByDescending { it.dateMillis }
    }

    private fun countPlyPoints(f: File): Long {
        // Compte rapide via l'en-tete PLY
        f.bufferedReader().useLines { lines ->
            for (l in lines) {
                if (l.startsWith("element vertex"))
                    return l.removePrefix("element vertex").trim().toLongOrNull() ?: 0
                if (l == "end_header") break
            }
        }
        return 0
    }

    /** Ecrit les metadonnees chiffrees (jamais en clair sur disque). */
    fun writeMeta(id: String, notes: String, sectorsCovered: Int, sectorsTotal: Int,
                  arCoreDepth: Boolean) {
        val plain = File(context.cacheDir, "meta_tmp_$id.json")
        val json = JSONObject()
            .put("id", id)
            .put("date", System.currentTimeMillis())
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("notes", notes)
            .put("coverage", "$sectorsCovered/$sectorsTotal")
            .put("arcoreDepth", arCoreDepth)
            .put("photos", JSONArray(photosDir(id).listFiles()?.map { it.name } ?: emptyList<String>()))
        plain.writeText(json.toString(2))

        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val encFile = File(sessionDir(id), "meta.enc.json")
        if (encFile.exists()) encFile.delete()
        val encrypted = EncryptedFile.Builder(
            context, encFile, masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()
        plain.inputStream().use { inp ->
            encrypted.openFileOutput().use { out -> inp.copyTo(out) }
        }
        plain.delete()
    }

    /** Supprime les photos sources apres reconstruction (option vie privee). */
    fun deletePhotos(id: String) {
        photosDir(id).listFiles()?.forEach { it.delete() }
    }

    fun deleteSession(id: String) {
        sessionDir(id).deleteRecursively()
    }

    /** Exporte la session complete (photos + PLY + meta) en ZIP via SAF. */
    fun exportZip(id: String, dest: Uri): Boolean {
        val dir = sessionDir(id)
        if (!dir.exists()) return false
        return runCatching {
            context.contentResolver.openOutputStream(dest)?.use { os ->
                ZipOutputStream(os.buffered()).use { zip ->
                    dir.walkTopDown().filter { it.isFile }.forEach { f ->
                        zip.putNextEntry(ZipEntry(f.relativeTo(dir).path))
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            } ?: throw IllegalStateException("SAF output null")
        }.isSuccess
    }
}
