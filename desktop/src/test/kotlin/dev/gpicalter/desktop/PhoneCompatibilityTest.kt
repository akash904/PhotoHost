package dev.gpicalter.desktop

import dev.gpicalter.core.dualHash
import dev.gpicalter.net.AssetDetailDto
import dev.gpicalter.net.BucketDto
import dev.gpicalter.net.EndpointsDto
import dev.gpicalter.net.HashCheckDto
import dev.gpicalter.net.HashCheckResultDto
import dev.gpicalter.net.HealthDto
import dev.gpicalter.net.PhoneClient
import dev.gpicalter.net.StatsDto
import dev.gpicalter.net.TimelineDto
import dev.gpicalter.net.TimelineItemDto
import dev.gpicalter.net.UploadFinishDto
import dev.gpicalter.net.UploadInitDto
import dev.gpicalter.net.UploadInitResultDto
import dev.gpicalter.net.UploadStatusDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayInputStream
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives a real desktop server the way the unmodified phone app does: over TLS with the pinned
 * certificate, through OkHttp, decoding into the phone's own wire types.
 *
 * What this proves is the server half of the milestone. What it cannot prove is the phone half --
 * that the app on a real phone, scanning the real QR, renders it. That needs a person and a phone.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PhoneCompatibilityTest {

    private val dataDir: File = Files.createTempDirectory("photohost-data").toFile()
    private val libraryDir: File = Files.createTempDirectory("photohost-lib").toFile()
    private lateinit var config: Config
    private lateinit var server: DesktopServer
    private lateinit var token: String
    private lateinit var fingerprint: String
    private lateinit var api: HttpClient
    private lateinit var base: String

    @BeforeAll
    fun startServer() = runBlocking {
        Fixtures.build(libraryDir)
        config = Config.load(dataDir)
        config.libraryRoot = libraryDir
        config.port = freePortPair()
        server = DesktopServer(config)
        assertTrue(server.start(), "server did not start: ${server.status.value.error}")

        token = config.token()
        fingerprint = assertNotNull(server.status.value.fingerprint, "no TLS identity")
        base = "https://127.0.0.1:${config.httpsPort}"
        api = PhoneClient.ktor(PhoneClient.okhttp(fingerprint, token))

        awaitIndexed()
    }

    @AfterAll
    fun stopServer() {
        runCatching { api.close() }
        server.stop()
        dataDir.deleteRecursively()
        libraryDir.deleteRecursively()
    }

    // ---------------------------------------------------------------- transport and auth

    @Test
    fun `health answers over pinned TLS without a token`() = runBlocking {
        val anon = PhoneClient.ktor(PhoneClient.okhttp(fingerprint, token = null))
        val r = anon.get("$base/health")
        assertEquals(200, r.status.value)
        val h = r.body<HealthDto>()
        assertTrue(h.ok)
        assertEquals("FOLDER", h.backend)
        assertTrue(h.mounted && h.writable)
        anon.close()
    }

    @Test
    fun `a client pinned to a different certificate refuses to connect`() = runBlocking {
        val wrong = PhoneClient.ktor(PhoneClient.okhttp("00".repeat(32), token))
        assertFailsWith<Exception> { wrong.get("$base/health") }
        wrong.close()
    }

    @Test
    fun `api routes demand the token`() = runBlocking {
        val anon = PhoneClient.ktor(PhoneClient.okhttp(fingerprint, token = null))
        assertEquals(401, anon.get("$base/api/v1/stats").status.value)
        assertEquals(401, anon.get("$base/api/v1/stats") { header("Authorization", "Bearer nope") }.status.value)
        assertEquals(200, api.get("$base/api/v1/stats").status.value)
        anon.close()
    }

    @Test
    fun `plain http port serves the same api`() = runBlocking {
        val plain = PhoneClient.ktor(PhoneClient.okhttp(fingerprint = null, token = token))
        val r = plain.get("http://127.0.0.1:${config.port}/api/v1/stats")
        assertEquals(200, r.status.value)
        plain.close()
    }

    @Test
    fun `pair link sets the browser cookie and the cookie authenticates`() = runBlocking {
        val browser = PhoneClient.ktor(
            PhoneClient.okhttp(fingerprint, token = null, followRedirects = false),
            followRedirects = false,
        )
        val r = browser.get("$base/pair") { parameter("c", token) }
        assertEquals(302, r.status.value)
        val cookie = assertNotNull(r.headers[HttpHeaders.SetCookie])
        assertTrue(cookie.startsWith("gpic=$token"), cookie)
        assertTrue("HttpOnly" in cookie && "SameSite=Lax" in cookie, cookie)

        val page = browser.get("$base/") { header(HttpHeaders.Cookie, "gpic=$token") }
        assertEquals(200, page.status.value)
        assertTrue(page.bodyAsText().contains("<html", ignoreCase = true))

        assertEquals(401, browser.get("$base/pair") { parameter("c", "wrong") }.status.value)
        browser.close()
    }

    @Test
    fun `endpoints decode into the phone's shape and carry the pinned fingerprint`() = runBlocking {
        val e = api.get("$base/api/v1/endpoints").body<EndpointsDto>()
        assertEquals(fingerprint, e.tlsFingerprint)
        assertTrue(e.endpoints.isNotEmpty(), "no endpoints advertised")
        assertTrue(e.endpoints.none { it.url.contains("172.") && it.label.contains("Hyper", ignoreCase = true) })
        assertTrue(e.endpoints.any { it.secure && it.url.endsWith(":${config.httpsPort}") })
    }

    @Test
    fun `pairing link parses the way the phone parses it`() {
        val link = assertNotNull(server.status.value.pairingLink)
        val uri = java.net.URI(link)
        assertEquals("https", uri.scheme)
        assertEquals(config.httpsPort, uri.port)
        assertEquals("/pair", uri.path)
        val query = uri.rawQuery.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals(token, query["c"])
        assertEquals(fingerprint, query["f"])
        assertEquals(64, query["f"]!!.length)
        val alternates = java.net.URLDecoder.decode(query["a"] ?: "", Charsets.UTF_8).split(',')
        assertTrue(alternates.all { it.startsWith("http://") || it.startsWith("https://") }, alternates.toString())
    }

    // ---------------------------------------------------------------- the timeline

    @Test
    fun `timeline lists every fixture, newest first`() = runBlocking {
        val items = fullTimeline()
        assertTrue(items.size >= Fixtures.MEDIA_COUNT)
        assertEquals(items.sortedWith(compareByDescending<TimelineItemDto> { it.capturedAt }.thenByDescending { it.id }), items)
    }

    @Test
    fun `keyset pages of two reproduce the full timeline exactly`() = runBlocking {
        val full = fullTimeline()
        val paged = ArrayList<TimelineItemDto>()
        var cursor: String? = null
        do {
            val page = api.get("$base/api/v1/timeline") {
                parameter("limit", 2)
                cursor?.let { parameter("cursor", it) }
            }.body<TimelineDto>()
            paged += page.items
            cursor = page.nextCursor
        } while (page.hasMore && cursor != null)
        assertEquals(full.map { it.id }, paged.map { it.id })
    }

    @Test
    fun `month buckets decode and add up`() = runBlocking {
        val buckets = api.get("$base/api/v1/timeline/buckets").body<List<BucketDto>>()
        assertEquals(fullTimeline().size, buckets.sumOf { it.count })
    }

    // ---------------------------------------------------------------- metadata

    @Test
    fun `exif orientation and offset date are read like the phone reads them`() = runBlocking {
        val a = detail(Fixtures.PORTRAIT)
        assertEquals(90, a.orientation)
        // Display dimensions: stored 1600x1200, shown 1200x1600.
        assertEquals(1200, a.width)
        assertEquals(1600, a.height)
        assertEquals(Fixtures.PORTRAIT_TAKEN_UTC, a.capturedAt)
        assertEquals(0, a.capturedAtSource, "expected EXIF_WITH_OFFSET")
        assertEquals(330, a.tzOffsetMinutes)
        assertNotNull(a.blurhash)
        assertEquals("image/jpeg", a.mime)
    }

    @Test
    fun `a date in the filename beats the mtime`() = runBlocking {
        val a = detail(Fixtures.LANDSCAPE)
        assertEquals(3, a.capturedAtSource, "expected FILENAME")
        val local = java.time.LocalDateTime.of(2024, 1, 15, 12, 34, 56)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(local, a.capturedAt)
        assertEquals(2000, a.width)
        assertEquals(1500, a.height)
    }

    @Test
    fun `with nothing else to go on the mtime is used and marked as such`() = runBlocking {
        val a = detail(Fixtures.SCREENSHOT)
        assertEquals(4, a.capturedAtSource, "expected MTIME")
        assertEquals(Fixtures.SCREENSHOT_MTIME, a.capturedAt)
        assertEquals("image/png", a.mime)
    }

    @Test
    fun `video metadata comes from the container`() = runBlocking {
        val v = detail(Fixtures.VIDEO)
        assertEquals(1, v.mediaType)
        assertEquals("video/mp4", v.mime)
        assertEquals(90, v.orientation)
        assertEquals(90, v.width, "display width should be the stored height")
        assertEquals(160, v.height)
        assertEquals(2000L, v.durationMs)
        assertEquals(Fixtures.VIDEO_CREATED_UTC, v.capturedAt)
        assertEquals(2, v.capturedAtSource, "container date is recorded as MEDIASTORE, as on the phone")
    }

    @Test
    fun `content hash is sha256 of the raw bytes, lowercase`() = runBlocking {
        val a = detail(Fixtures.PORTRAIT)
        val expected = File(libraryDir, Fixtures.PORTRAIT).inputStream().use { it.dualHash().contentHash }
        assertEquals(expected, a.contentHash)
        assertEquals(a.contentHash.lowercase(), a.contentHash)
        assertEquals(64, a.contentHash.length)
    }

    // ---------------------------------------------------------------- thumbnails

    @Test
    fun `grid thumbnail has a 256px short edge and the rotation baked in`() = runBlocking {
        val id = detail(Fixtures.PORTRAIT).id
        val r = api.get("$base/api/v1/assets/$id/thumb")
        assertEquals(200, r.status.value)
        assertEquals("image/jpeg", r.headers[HttpHeaders.ContentType])
        val img = ImageIO.read(ByteArrayInputStream(r.readRawBytes()))
        assertEquals(256, img.width)
        assertEquals(341, img.height)
        // Stored top-left red; after a clockwise quarter turn it must be top-right.
        assertTrue(isRed(img.getRGB(img.width - 10, 10)), "top-right should be red")
        assertFalse(isRed(img.getRGB(10, 10)), "top-left should not be red")
    }

    @Test
    fun `preview thumbnail has a 1440px long edge`() = runBlocking {
        val id = detail(Fixtures.LANDSCAPE).id
        val r = api.get("$base/api/v1/assets/$id/thumb") { parameter("size", "preview") }
        assertEquals(200, r.status.value)
        val img = ImageIO.read(ByteArrayInputStream(r.readRawBytes()))
        assertEquals(1440, img.width)
        assertEquals(1080, img.height)
    }

    @Test
    fun `thumbnails revalidate with 304`() = runBlocking {
        val id = detail(Fixtures.LANDSCAPE).id
        val first = api.get("$base/api/v1/assets/$id/thumb")
        val etag = assertNotNull(first.headers[HttpHeaders.ETag])
        val again = api.get("$base/api/v1/assets/$id/thumb") { header(HttpHeaders.IfNoneMatch, etag) }
        assertEquals(304, again.status.value)
    }

    @Test
    fun `formats without a decoder answer 202 and park their jobs as blocked`() = runBlocking {
        for (rel in listOf(Fixtures.HEIC, Fixtures.VIDEO)) {
            val id = detail(rel).id
            val r = api.get("$base/api/v1/assets/$id/thumb")
            assertEquals(202, r.status.value, rel)
        }
        val stats = api.get("$base/api/v1/stats").body<StatsDto>()
        assertEquals(4, stats.blockedJobs, "grid + preview for each of the two undecodable files")
        assertEquals(0, stats.failedJobs)
    }

    // ---------------------------------------------------------------- originals and Range

    @Test
    fun `original streams whole and in ranges`() = runBlocking {
        val id = detail(Fixtures.VIDEO).id
        val bytes = File(libraryDir, Fixtures.VIDEO).readBytes()

        val full = api.get("$base/api/v1/assets/$id/original")
        assertEquals(200, full.status.value)
        assertEquals("bytes", full.headers[HttpHeaders.AcceptRanges])
        assertContentEquals(bytes, full.readRawBytes())

        val mid = api.get("$base/api/v1/assets/$id/original") { header(HttpHeaders.Range, "bytes=100-199") }
        assertEquals(206, mid.status.value)
        assertEquals("bytes 100-199/${bytes.size}", mid.headers[HttpHeaders.ContentRange])
        assertContentEquals(bytes.copyOfRange(100, 200), mid.readRawBytes())

        val tail = api.get("$base/api/v1/assets/$id/original") { header(HttpHeaders.Range, "bytes=-50") }
        assertEquals(206, tail.status.value)
        assertContentEquals(bytes.copyOfRange(bytes.size - 50, bytes.size), tail.readRawBytes())

        val open = api.get("$base/api/v1/assets/$id/original") { header(HttpHeaders.Range, "bytes=${bytes.size - 10}-") }
        assertContentEquals(bytes.copyOfRange(bytes.size - 10, bytes.size), open.readRawBytes())

        val past = api.get("$base/api/v1/assets/$id/original") { header(HttpHeaders.Range, "bytes=${bytes.size + 5}-") }
        assertEquals(416, past.status.value)

        val head = api.head("$base/api/v1/assets/$id/original")
        assertEquals(200, head.status.value)
        assertEquals(bytes.size.toString(), head.headers[HttpHeaders.ContentLength])
    }

    @Test
    fun `raw store reads cannot leave the library folder`() = runBlocking {
        for (path in listOf("../config.properties", "..\\config.properties", "C:/Windows/win.ini", "2024/portrait.jpg:x")) {
            val r = api.get("$base/api/v1/fs/read") { parameter("path", path) }
            assertTrue(r.status.value in setOf(404, 503), "$path -> ${r.status.value}")
        }
        val ok = api.get("$base/api/v1/fs/read") { parameter("path", Fixtures.PORTRAIT) }
        assertEquals(200, ok.status.value)
    }

    // ---------------------------------------------------------------- the phone's backup protocol

    @Test
    fun `phone backup protocol uploads, resumes, verifies and dedupes`() = runBlocking {
        // A real JPEG, so its thumbnails render like any phone photo's. Noise, so it spans several
        // 1 MiB chunks and the resume paths are actually exercised.
        val rnd = java.util.Random(42)
        val noise = java.awt.image.BufferedImage(1800, 1400, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 1400) for (x in 0 until 1800) noise.setRGB(x, y, rnd.nextInt(0xFFFFFF))
        val bytes = java.io.ByteArrayOutputStream().also { ImageIO.write(noise, "jpg", it) }.toByteArray()
        assertTrue(bytes.size > (1 shl 20), "fixture too small to span chunks: ${bytes.size}")
        val hash = ByteArrayInputStream(bytes).dualHash().contentHash
        val name = "IMG_20250102_030405.jpg"

        val check = api.post("$base/api/v1/upload/check") {
            contentType(ContentType.Application.Json)
            setBody(HashCheckDto(listOf(hash)))
        }.body<HashCheckResultDto>()
        assertTrue(check.ids.isEmpty())

        val init = api.post("$base/api/v1/upload/init") {
            contentType(ContentType.Application.Json)
            setBody(UploadInitDto(name, bytes.size.toLong(), hash, capturedAt = 1_735_787_045_000L, sourceAlbum = "Camera"))
        }.body<UploadInitResultDto>()
        assertFalse(init.duplicate)
        val uploadId = assertNotNull(init.uploadId)

        // 1 MiB chunks, as BackupEngine sends them.
        val chunk = 1 shl 20
        val first = patchChunk(uploadId, 0, bytes.copyOfRange(0, chunk))
        assertEquals(chunk.toLong(), first.body<UploadStatusDto>().offset)

        // A chunk claiming the wrong offset is refused with the real one, never appended.
        val wrong = patchChunk(uploadId, 0, bytes.copyOfRange(0, chunk))
        assertEquals(409, wrong.status.value)
        assertEquals(chunk.toLong(), wrong.body<UploadStatusDto>().offset)

        // The resume probe the phone uses after losing a response.
        val probe = api.get("$base/api/v1/upload/$uploadId").body<UploadStatusDto>()
        assertEquals(chunk.toLong(), probe.offset)

        var offset = chunk
        while (offset < bytes.size) {
            val end = minOf(offset + chunk, bytes.size)
            assertEquals(200, patchChunk(uploadId, offset.toLong(), bytes.copyOfRange(offset, end)).status.value)
            offset = end
        }

        val finish = api.post("$base/api/v1/upload/$uploadId/finish")
        assertEquals(200, finish.status.value, finish.bodyAsText())
        val done = finish.body<UploadFinishDto>()
        assertFalse(done.duplicate)
        val relPath = assertNotNull(done.relPath)
        assertEquals("2025/01/IMG_20250102_030405-${hash.take(8)}.jpg", relPath)
        assertContentEquals(bytes, File(libraryDir, relPath).readBytes())

        val detail = api.get("$base/api/v1/assets/${done.assetId}").body<AssetDetailDto>()
        assertEquals(hash, detail.contentHash)

        // Re-offering the same bytes costs one round trip and zero bytes.
        val again = api.post("$base/api/v1/upload/init") {
            contentType(ContentType.Application.Json)
            setBody(UploadInitDto("renamed.jpg", bytes.size.toLong(), hash))
        }.body<UploadInitResultDto>()
        assertTrue(again.duplicate)
        assertEquals(done.assetId, again.assetId)
        assertNull(again.uploadId)

        val known = api.post("$base/api/v1/upload/check") {
            contentType(ContentType.Application.Json)
            setBody(HashCheckDto(listOf(hash)))
        }.body<HashCheckResultDto>()
        assertEquals(done.assetId, known.ids[hash])
    }

    @Test
    fun `an upload whose bytes do not match the declared hash is rejected`() = runBlocking {
        val bytes = ByteArray(1000) { it.toByte() }
        val init = api.post("$base/api/v1/upload/init") {
            contentType(ContentType.Application.Json)
            setBody(UploadInitDto("bad.jpg", bytes.size.toLong(), "ab".repeat(32)))
        }.body<UploadInitResultDto>()
        val id = assertNotNull(init.uploadId)
        assertEquals(200, patchChunk(id, 0, bytes).status.value)
        assertEquals(400, api.post("$base/api/v1/upload/$id/finish").status.value)
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun patchChunk(id: String, offset: Long, data: ByteArray): HttpResponse =
        api.patch("$base/api/v1/upload/$id") {
            parameter("offset", offset)
            setBody(data)
        }

    private suspend fun fullTimeline(): List<TimelineItemDto> =
        api.get("$base/api/v1/timeline") { parameter("limit", 500) }.body<TimelineDto>().items

    private suspend fun detail(relPath: String): AssetDetailDto {
        for (item in fullTimeline()) {
            val d = api.get("$base/api/v1/assets/${item.id}").body<AssetDetailDto>()
            if (d.relPath == relPath) return d
        }
        error("no asset for $relPath")
    }

    /** Waits until every fixture is indexed and every decodable one has both thumbnails. */
    private suspend fun awaitIndexed() {
        val deadline = System.currentTimeMillis() + 90_000
        var last: StatsDto? = null
        while (System.currentTimeMillis() < deadline) {
            val r = api.get("$base/api/v1/stats")
            if (r.status.value == 200) {
                val s = r.body<StatsDto>()
                last = s
                if (s.assets >= Fixtures.MEDIA_COUNT &&
                    s.gridThumbs >= Fixtures.IMAGE_COUNT &&
                    s.previewThumbs >= Fixtures.IMAGE_COUNT &&
                    s.pendingJobs == 0
                ) {
                    return
                }
            }
            delay(250)
        }
        error("library never finished indexing: $last")
    }

    private fun isRed(rgb: Int): Boolean {
        val r = (rgb shr 16) and 0xff
        val g = (rgb shr 8) and 0xff
        val b = rgb and 0xff
        return r > 180 && g < 80 && b < 80
    }

    /** A free port P whose TLS twin P+363 is also free. */
    private fun freePortPair(): Int {
        repeat(50) {
            val p = ServerSocket(0).use { it.localPort }
            if (p + Config.TLS_PORT_OFFSET < 65535 && runCatching { ServerSocket(p + Config.TLS_PORT_OFFSET).close() }.isSuccess) {
                return p
            }
        }
        error("no free port pair")
    }
}

/** Separate server lifetimes: what survives a restart. */
class RestartTest {

    @Test
    fun `token, certificate and index survive a restart without re-indexing`() = runBlocking {
        val dataDir = Files.createTempDirectory("photohost-data").toFile()
        val libraryDir = Files.createTempDirectory("photohost-lib").toFile()
        try {
            Fixtures.build(libraryDir)
            val port = ServerSocket(0).use { it.localPort }

            val first = Config.load(dataDir).apply { libraryRoot = libraryDir; this.port = port }
            val s1 = DesktopServer(first)
            assertTrue(s1.start())
            val fp1 = s1.status.value.fingerprint
            val token1 = first.token()
            awaitAssets(s1, Fixtures.MEDIA_COUNT)
            val fingerprints1 = s1.db.fingerprints().count()
            s1.stop()

            val second = Config.load(dataDir)
            assertEquals(libraryDir.absolutePath, second.libraryRoot.absolutePath)
            assertEquals(token1, second.token())
            val s2 = DesktopServer(second)
            assertTrue(s2.start())
            assertEquals(fp1, s2.status.value.fingerprint, "a new certificate would un-pair every client")
            // The start-up scan must skip every file on the fingerprint gate rather than re-hash it.
            val deadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < deadline && s2.status.value.scanNote == null) delay(100)
            val note = assertNotNull(s2.status.value.scanNote)
            assertTrue(note.contains("skip ${Fixtures.MEDIA_COUNT}"), note)
            assertEquals(Fixtures.MEDIA_COUNT, s2.db.assets().count())
            assertEquals(fingerprints1, s2.db.fingerprints().count())
            s2.stop()
        } finally {
            dataDir.deleteRecursively()
            libraryDir.deleteRecursively()
        }
    }

    @Test
    fun `a port already in use is reported, not swallowed`() = runBlocking {
        val dataDir = Files.createTempDirectory("photohost-data").toFile()
        val libraryDir = Files.createTempDirectory("photohost-lib").toFile()
        ServerSocket(0).use { taken ->
            try {
                val cfg = Config.load(dataDir).apply { libraryRoot = libraryDir; port = taken.localPort }
                val s = DesktopServer(cfg)
                assertFalse(s.start(), "start() claimed success on a taken port")
                val err = assertNotNull(s.status.value.error)
                assertTrue(err.contains("${taken.localPort}"), err)
            } finally {
                dataDir.deleteRecursively()
                libraryDir.deleteRecursively()
            }
        }
    }

    private suspend fun awaitAssets(s: DesktopServer, n: Int) {
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            if (s.db.assets().count() >= n) return
            delay(100)
        }
        error("only ${s.db.assets().count()} of $n assets indexed")
    }
}
