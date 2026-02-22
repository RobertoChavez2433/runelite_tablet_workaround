package com.runelitetablet.installer

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val assets: List<ReleaseAsset>
)

@Serializable
data class ReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long
)

enum class GitHubRepo(
    val owner: String,
    val repo: String,
    val releaseTag: String?,
    val assetPattern: Regex
) {
    TERMUX(
        "termux", "termux-app", null,
        Regex("""termux-app_v.+\+apt-android-7-github-debug_arm64-v8a\.apk""")
    ),
    TERMUX_X11(
        "termux", "termux-x11", "nightly",
        Regex("""app-arm64-v8a-debug\.apk""")
    )
}

class ApkDownloader(
    private val context: Context,
    private val httpClient: OkHttpClient
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun download(
        repo: GitHubRepo,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val release = fetchRelease(repo)
        val asset = selectAsset(release.assets, repo.assetPattern)
            ?: throw IOException("No matching APK asset found for ${repo.name}")

        val apkDir = File(context.cacheDir, "apks").apply { mkdirs() }
        val apkFile = File(apkDir, asset.name)

        if (apkFile.exists() && apkFile.length() == asset.size) {
            return@withContext apkFile
        }

        val request = Request.Builder()
            .url(asset.downloadUrl)
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("Download failed: HTTP ${response.code}")
        }

        val body = response.body ?: throw IOException("Empty response body")
        val totalBytes = body.contentLength()

        body.byteStream().use { input ->
            apkFile.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Long = 0
                var read: Int

                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    bytesRead += read
                    onProgress(bytesRead, totalBytes)
                }
            }
        }

        apkFile
    }

    private suspend fun fetchRelease(repo: GitHubRepo): GitHubRelease = withContext(Dispatchers.IO) {
        val url = if (repo.releaseTag != null) {
            "https://api.github.com/repos/${repo.owner}/${repo.repo}/releases/tags/${repo.releaseTag}"
        } else {
            "https://api.github.com/repos/${repo.owner}/${repo.repo}/releases/latest"
        }

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github.v3+json")
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("GitHub API request failed: HTTP ${response.code}")
        }

        val responseBody = response.body?.string()
            ?: throw IOException("Empty GitHub API response")

        json.decodeFromString<GitHubRelease>(responseBody)
    }

    private fun selectAsset(assets: List<ReleaseAsset>, pattern: Regex): ReleaseAsset? {
        return assets.firstOrNull { pattern.matches(it.name) }
            ?: assets.firstOrNull { it.name.contains("universal") && it.name.endsWith(".apk") }
    }
}
