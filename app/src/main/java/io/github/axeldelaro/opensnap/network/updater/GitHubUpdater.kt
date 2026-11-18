package io.github.axeldelaro.opensnap.network.updater

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.github.axeldelaro.opensnap.BuildConfig
import io.github.axeldelaro.opensnap.crypto.CryptoEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Integrated GitHub Releases Auto-Updater (`https://api.github.com/repos/axeldelaro/Opensnap/releases/latest`).
 * Checks for newer versions of `OpenSnap-latest.apk`, streams the signed APK with live byte progress,
 * and launches Android's Package Installer via `FileProvider`.
 */
object GitHubUpdater {

    @Serializable
    data class GitHubReleaseDto(
        val tag_name: String = "",
        val name: String = "",
        val body: String = "",
        val html_url: String = "",
        val assets: List<GitHubAssetDto> = emptyList()
    )

    @Serializable
    data class GitHubAssetDto(
        val name: String = "",
        val browser_download_url: String = "",
        val size: Long = 0L
    )

    data class UpdateCheckResult(
        val currentVersion: String,
        val latestVersion: String,
        val isUpdateAvailable: Boolean,
        val releaseNotes: String,
        val apkDownloadUrl: String?,
        val apkSizeBytes: Long
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun checkForUpdates(): Result<UpdateCheckResult> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(BuildConfig.RELEASES_API_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "OpenSnap-Android/${BuildConfig.VERSION_NAME}")
                .build()

            httpClient.newCall(request).execute().use { resp ->
                if (resp.code == 404) {
                    return@runCatching UpdateCheckResult(
                        currentVersion = BuildConfig.VERSION_NAME,
                        latestVersion = BuildConfig.VERSION_NAME,
                        isUpdateAvailable = false,
                        releaseNotes = "Aucune release publiée pour le moment sur GitHub.",
                        apkDownloadUrl = null,
                        apkSizeBytes = 0L
                    )
                }
                if (!resp.isSuccessful) {
                    error("GitHub API HTTP ${resp.code}")
                }
                val rawBody = resp.body?.string() ?: error("Réponse GitHub vide")
                val release = CryptoEngine.canonicalJson.decodeFromString<GitHubReleaseDto>(rawBody)
                val cleanLatest = release.tag_name.removePrefix("v").trim()
                val cleanCurrent = BuildConfig.VERSION_NAME.removePrefix("v").trim()

                val apkAsset = release.assets.firstOrNull {
                    it.name.equals("OpenSnap-latest.apk", ignoreCase = true)
                } ?: release.assets.firstOrNull {
                    it.name.endsWith(".apk", ignoreCase = true)
                }

                UpdateCheckResult(
                    currentVersion = cleanCurrent,
                    latestVersion = cleanLatest.ifBlank { cleanCurrent },
                    isUpdateAvailable = apkAsset != null && isNewerSemver(cleanLatest, cleanCurrent),
                    releaseNotes = release.body,
                    apkDownloadUrl = apkAsset?.browser_download_url,
                    apkSizeBytes = apkAsset?.size ?: 0L
                )
            }
        }
    }

    suspend fun downloadApkToCache(
        context: Context,
        downloadUrl: String,
        onProgressPercent: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val outFile = File(updatesDir, "OpenSnap-latest.apk")

            val req = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "OpenSnap-Android/${BuildConfig.VERSION_NAME}")
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Échec du téléchargement HTTP ${resp.code}")
                val body = resp.body ?: error("Flux APK vide")
                val totalLength = body.contentLength()

                body.byteStream().use { input ->
                    FileOutputStream(outFile).use { output ->
                        val buffer = ByteArray(16_384)
                        var downloaded = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (totalLength > 0L) {
                                val pct = ((downloaded * 100L) / totalLength).toInt().coerceIn(0, 100)
                                onProgressPercent(pct)
                            }
                        }
                        output.fd.sync()
                    }
                }
            }
            outFile
        }
    }

    fun promptInstallApk(context: Context, apkFile: File) {
        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(installIntent)
    }

    private fun isNewerSemver(candidate: String, current: String): Boolean {
        val candParts = candidate.split('.').map { it.toIntOrNull() ?: 0 }
        val currParts = current.split('.').map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(candParts.size, currParts.size)
        for (i in 0 until maxLen) {
            val a = candParts.getOrElse(i) { 0 }
            val b = currParts.getOrElse(i) { 0 }
            if (a > b) return true
            if (a < b) return false
        }
        return false
    }
}
