package com.runelitetablet.installer

import android.content.Context
import com.runelitetablet.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

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
        AppLog.http("download: repo=${repo.name} starting release fetch")
        val memBefore = AppLog.memorySnapshot()
        val diskBefore = AppLog.diskSnapshot(context)
        AppLog.perf("download start: $memBefore | $diskBefore")

        val release = fetchRelease(repo)
        val asset = selectAsset(release.assets, repo.assetPattern)
            ?: throw IOException("No matching APK asset found for ${repo.name}")

        AppLog.http(
            "parsed release: tag=${release.tagName} assetCount=${release.assets.size} " +
                "matchedAsset=${asset.name} matchedSize=${asset.size} pattern=${repo.assetPattern}"
        )

        val apkDir = File(context.cacheDir, "apks").apply { mkdirs() }
        val apkFile = File(apkDir, asset.name)

        if (apkFile.exists() && apkFile.length() == asset.size) {
            AppLog.http("download: cache hit — skipping download file=${apkFile.name} size=${asset.size}")
            return@withContext apkFile
        }
        AppLog.http("download: cache miss — downloading url=${asset.downloadUrl} expectedSize=${asset.size}")

        val request = Request.Builder()
            .url(asset.downloadUrl)
            .build()

        AppLog.http("download: starting HTTP GET url=${asset.downloadUrl}")

        val call = httpClient.newCall(request)
        val downloadStartMs = System.currentTimeMillis()
        try {
            val response = call.execute()
            response.use { resp ->
                val latencyMs = System.currentTimeMillis() - downloadStartMs
                AppLog.http(
                    "response: status=${resp.code} contentType=${resp.header("Content-Type")} " +
                        "contentLength=${resp.header("Content-Length")} latencyMs=$latencyMs"
                )
                if (!resp.isSuccessful) {
                    val bodyPreview = resp.body?.string()?.take(500) ?: "<empty>"
                    AppLog.http("response non-2xx body preview: $bodyPreview")
                    throw IOException("Download failed: HTTP ${resp.code}")
                }

                val body = resp.body ?: throw IOException("Empty response body")
                val totalBytes = body.contentLength()

                body.byteStream().use { input ->
                    apkFile.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        var totalRead: Long = 0
                        var read: Int
                        var lastProgressUpdate = 0L
                        var lastProgressPercent = -1
                        var lastThroughputCheck = downloadStartMs
                        var bytesAtLastCheck = 0L

                        while (input.read(buffer).also { read = it } != -1) {
                            if (!coroutineContext.isActive) {
                                call.cancel()
                                apkFile.delete()
                                throw CancellationException("Download cancelled")
                            }
                            output.write(buffer, 0, read)
                            totalRead += read
                            val now = System.currentTimeMillis()
                            if (now - lastProgressUpdate >= 100) {
                                onProgress(totalRead, totalBytes)
                                lastProgressUpdate = now
                            }

                            // Log progress every 5% or every 2 seconds
                            val percent = if (totalBytes > 0) (totalRead * 100 / totalBytes).toInt() else -1
                            val elapsed = now - lastThroughputCheck
                            if ((percent >= 0 && percent / 5 > lastProgressPercent / 5) || elapsed >= 2000) {
                                val bytesSinceLast = totalRead - bytesAtLastCheck
                                val throughputKbps = if (elapsed > 0) bytesSinceLast * 1000 / elapsed / 1024 else 0
                                AppLog.http(
                                    "progress: ${percent}% totalRead=$totalRead totalBytes=$totalBytes " +
                                        "throughput=${throughputKbps}KB/s"
                                )
                                lastProgressPercent = percent
                                lastThroughputCheck = now
                                bytesAtLastCheck = totalRead
                            }
                        }

                        // Report final progress (100%)
                        onProgress(totalRead, totalBytes)

                        val totalDurationMs = System.currentTimeMillis() - downloadStartMs
                        val avgThroughputKbps = if (totalDurationMs > 0) totalRead * 1000 / totalDurationMs / 1024 else 0
                        AppLog.http(
                            "complete: totalBytes=$totalRead durationMs=$totalDurationMs " +
                                "avgThroughput=${avgThroughputKbps}KB/s"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw e
        } finally {
            if (!coroutineContext.isActive) {
                call.cancel()
            }
        }

        val memAfter = AppLog.memorySnapshot()
        val diskAfter = AppLog.diskSnapshot(context)
        AppLog.perf("download complete: before=[$memBefore | $diskBefore] after=[$memAfter | $diskAfter]")

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

        AppLog.http("fetchRelease: url=$url headers=[Accept: application/vnd.github.v3+json]")

        val call = httpClient.newCall(request)
        val requestStartMs = System.currentTimeMillis()
        try {
            val response = call.execute()
            response.use { resp ->
                val latencyMs = System.currentTimeMillis() - requestStartMs
                AppLog.http(
                    "fetchRelease response: status=${resp.code} contentType=${resp.header("Content-Type")} " +
                        "contentLength=${resp.header("Content-Length")} latencyMs=$latencyMs"
                )
                if (!resp.isSuccessful) {
                    val bodyPreview = resp.body?.string()?.take(500) ?: "<empty>"
                    AppLog.http("fetchRelease non-2xx body preview: $bodyPreview")
                    throw IOException("GitHub API request failed: HTTP ${resp.code}")
                }

                val responseBody = resp.body?.string()
                    ?: throw IOException("Empty GitHub API response")

                json.decodeFromString<GitHubRelease>(responseBody)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw e
        } finally {
            if (!coroutineContext.isActive) {
                call.cancel()
            }
        }
    }

    private fun selectAsset(assets: List<ReleaseAsset>, pattern: Regex): ReleaseAsset? {
        return assets.firstOrNull { pattern.matches(it.name) }
            ?: assets.firstOrNull { it.name.contains("universal") && it.name.endsWith(".apk") }
    }
}
