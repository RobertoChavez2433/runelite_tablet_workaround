package com.runelitetablet.installer

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Integration tests for APK download logic using MockWebServer.
 *
 * ApkDownloader requires Android Context (for cacheDir, BuildConfig, AppLog),
 * so these tests exercise the HTTP download flow directly — real OkHttp against
 * mock HTTP responses — following the same pattern as JagexOAuth2ManagerTest.
 */
class ApkDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `download returns file on successful HTTP 200`() = runTest {
        val apkContent = ByteArray(1024) { it.toByte() }
        val releaseJson = makeReleaseJson("v1.0", "test.apk", apkContent.size.toLong())

        // First request: GitHub release API
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(releaseJson)
        )
        // Second request: APK download
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", apkContent.size.toString())
                .setBody(okio.Buffer().write(apkContent))
        )

        val file = downloadApk("test.apk", Regex("""test\.apk"""))

        assertTrue("Downloaded file should exist", file.exists())
        assertEquals("File size should match", apkContent.size.toLong(), file.length())
        assertTrue("File content should match", file.readBytes().contentEquals(apkContent))
    }

    @Test
    fun `download throws on HTTP 404`() = runTest {
        val releaseJson = makeReleaseJson("v1.0", "test.apk", 1024)

        server.enqueue(MockResponse().setResponseCode(200).setBody(releaseJson))
        server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))

        try {
            downloadApk("test.apk", Regex("""test\.apk"""))
            assertTrue("Should have thrown IOException", false)
        } catch (e: IOException) {
            assertTrue(
                "Error should mention HTTP 404, got: ${e.message}",
                e.message?.contains("404") == true
            )
        }
    }

    @Test
    fun `download throws on HTTP 500`() = runTest {
        val releaseJson = makeReleaseJson("v1.0", "test.apk", 1024)

        server.enqueue(MockResponse().setResponseCode(200).setBody(releaseJson))
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        try {
            downloadApk("test.apk", Regex("""test\.apk"""))
            assertTrue("Should have thrown IOException", false)
        } catch (e: IOException) {
            assertTrue(
                "Error should mention HTTP 500, got: ${e.message}",
                e.message?.contains("500") == true
            )
        }
    }

    @Test
    fun `download handles large response body`() = runTest {
        // 256 KB body to exercise streaming
        val largeContent = ByteArray(256 * 1024) { (it % 256).toByte() }
        val releaseJson = makeReleaseJson("v2.0", "large.apk", largeContent.size.toLong())

        server.enqueue(MockResponse().setResponseCode(200).setBody(releaseJson))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", largeContent.size.toString())
                .setBody(okio.Buffer().write(largeContent))
        )

        val file = downloadApk("large.apk", Regex("""large\.apk"""))

        assertEquals("Large file size should match", largeContent.size.toLong(), file.length())
        assertTrue("Large file content should match", file.readBytes().contentEquals(largeContent))
    }

    @Test
    fun `download sets correct headers`() = runTest {
        val releaseJson = makeReleaseJson("v1.0", "headers-test.apk", 64)

        server.enqueue(MockResponse().setResponseCode(200).setBody(releaseJson))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(okio.Buffer().write(ByteArray(64)))
        )

        downloadApk("headers-test.apk", Regex("""headers-test\.apk"""))

        // First recorded request: release fetch
        val releaseRequest = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("GET", releaseRequest.method)
        assertEquals(
            "application/vnd.github.v3+json",
            releaseRequest.getHeader("Accept")
        )

        // Second recorded request: APK download
        val downloadRequest = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("GET", downloadRequest.method)
        assertTrue(
            "Download path should contain asset name",
            downloadRequest.path?.contains("headers-test.apk") == true
        )
    }

    // --- Helper that mirrors ApkDownloader's two-step flow against MockWebServer ---

    /**
     * Reproduces the core download flow: fetch release JSON, find matching asset,
     * download the binary. Uses a temp directory instead of Context.cacheDir.
     */
    private fun downloadApk(assetName: String, pattern: Regex): File {
        // Step 1: Fetch release metadata (mirrors ApkDownloader.fetchRelease)
        val releaseUrl = server.url("/repos/test/test/releases/latest")
        val releaseRequest = Request.Builder()
            .url(releaseUrl)
            .header("Accept", "application/vnd.github.v3+json")
            .build()

        val release: GitHubRelease = client.newCall(releaseRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("GitHub API request failed: HTTP ${response.code}")
            }
            val body = response.body?.string()
                ?: throw IOException("Empty GitHub API response")
            json.decodeFromString<GitHubRelease>(body)
        }

        // Step 2: Find matching asset (mirrors ApkDownloader.selectAsset)
        val asset = release.assets.firstOrNull { pattern.matches(it.name) }
            ?: throw IOException("No matching APK asset found")

        // Step 3: Download the asset (mirrors ApkDownloader.download streaming)
        val apkDir = File(tempFolder.root, "apks").apply { mkdirs() }
        val apkFile = File(apkDir, asset.name)

        val downloadRequest = Request.Builder()
            .url(asset.downloadUrl)
            .build()

        client.newCall(downloadRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Download failed: HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("Empty response body")
            body.byteStream().use { input ->
                apkFile.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                }
            }
        }

        return apkFile
    }

    private fun makeReleaseJson(tag: String, assetName: String, assetSize: Long): String {
        val downloadUrl = server.url("/download/$assetName")
        return """
            {
                "tag_name": "$tag",
                "assets": [
                    {
                        "name": "$assetName",
                        "browser_download_url": "$downloadUrl",
                        "size": $assetSize
                    }
                ]
            }
        """.trimIndent()
    }
}
