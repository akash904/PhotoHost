package dev.gpicalter.server

import android.content.res.AssetManager
import dev.gpicalter.data.db.AppDatabase
import dev.gpicalter.data.entity.CaptureSource
import dev.gpicalter.data.entity.MediaType
import dev.gpicalter.data.entity.ThumbSize
import dev.gpicalter.data.entity.ThumbState
import dev.gpicalter.media.MetadataExtractor
import dev.gpicalter.media.ThumbnailCache
import dev.gpicalter.media.ThumbnailGenerator
import dev.gpicalter.storage.LibraryStore
import dev.gpicalter.storage.StoreEntry
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.application.Application
import io.ktor.server.application.serverConfig
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.cacheControl
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * How stale `last_access_at` is allowed to get.
 *
 * Eviction only needs to rank thumbnails against each other, and an hour's resolution does that
 * just as well as a millisecond's -- at a tiny fraction of the writes, on the path a scrolling grid
 * hits hundreds of times a second.
 */
private const val TOUCH_INTERVAL_MS = 60 * 60_000L

/**
 * The embedded HTTP server.
 *
 * CIO rather than Netty: Netty assumes a server JVM and hits missing-class failures on ART. CIO is
 * pure Kotlin coroutines and HTTP/1.x only, which is all a single-user LAN media server needs.
 */
class HttpServer(
    private val store: LibraryStore,
    private val db: AppDatabase,
    private val assets: AssetManager,
    private val thumbs: ThumbnailGenerator,
    private val thumbCache: ThumbnailCache,
    private val uploads: UploadService,
    private val auth: Auth,
    private val port: Int,
    private val httpsPort: Int,
    private val tls: CertStore.Identity?,
    private val openFds: () -> Int,
    private val onScanRequested: () -> Unit,
) {
    private var server: EmbeddedServer<*, *>? = null
    private val responder = RangeResponder(store)
    private val metadata = MetadataExtractor()
    private val startedAt = System.currentTimeMillis()

    fun start() {
        if (server != null) return
        server = embeddedServer(CIO, serverConfig { module { routes() } }) {
            // Plain HTTP only. Ktor's CIO engine throws UnsupportedOperationException on an SSL
            // connector, so TLS is terminated by TlsProxy in front of this and relayed here over
            // loopback. Only the TLS port should ever be opened to the internet.
            connector {
                host = "0.0.0.0"
                port = this@HttpServer.port
            }
        }.also { it.start(wait = false) }
    }

    private fun Application.routes() {
            install(ContentNegotiation) { json(Json { prettyPrint = true; explicitNulls = false }) }
            routing {
                get("/health") { call.respond(health()) }

                // The only place a token legitimately appears in a URL: one hand-off that converts
                // it into an HttpOnly cookie, so <img> and <video> never carry it.
                get("/pair") {
                    val peer = call.request.local.remoteHost
                    if (auth.isThrottled(peer)) {
                        call.respond(HttpStatusCode.TooManyRequests, "too many attempts")
                        return@get
                    }
                    val candidate = call.request.queryParameters["c"]
                    if (!auth.matches(candidate)) {
                        auth.recordFailure(peer)
                        call.respond(HttpStatusCode.Unauthorized, "bad pairing token")
                        return@get
                    }
                    auth.recordSuccess(peer)
                    // SameSite=Lax, deliberately not Strict. Strict withholds the cookie on any
                    // navigation the browser considers externally initiated -- including opening
                    // this very link from another app -- so the redirect right after pairing would
                    // arrive unauthenticated and pairing would appear to silently fail. Lax still
                    // blocks cross-site POSTs and subresource requests, which is the CSRF surface
                    // that actually matters here.
                    call.response.headers.append(
                        "Set-Cookie",
                        "${Auth.COOKIE}=$candidate; Path=/; HttpOnly; SameSite=Lax; Max-Age=31536000",
                    )
                    call.respondRedirect("/")
                }

                // The web UI, shipped inside the APK. No build step, no CDN: the page has to load
                // on a LAN with no internet, and a bundler would be a toolchain to maintain for
                // three files.
                get("/") { serveAsset(call, "web/index.html", ContentType.Text.Html) }
                get("/app.css") { serveAsset(call, "web/app.css", ContentType.Text.CSS) }
                get("/app.js") { serveAsset(call, "web/app.js", ContentType.Application.JavaScript) }

                /**
                 * Every address this server answers on.
                 *
                 * A phone is reachable at different addresses depending on where the client is:
                 * the LAN address at home, a Tailscale address away from it. The client pairs once
                 * via QR and then learns the full set from here, so it can pick whichever works
                 * instead of being pinned to the one that happened to be encoded in the code.
                 *
                 * The same filtered list the pairing code uses, not every address the server binds.
                 * Handing back the global IPv6 address here put it straight back into the client's
                 * candidate list after it had been left out of the code, which is most of the way
                 * to not having removed it: every failover sweep then spends a timeout probing an
                 * address whose prefix has very likely moved since it was learned.
                 */
                get("/api/v1/endpoints") {
                    if (call.denied()) return@get
                    val found = NetInterfaces.displayEndpoints()
                    val plain = found.map { EndpointDto(it.label, it.url(port), secure = false) }
                    val secure = if (tls == null) {
                        emptyList()
                    } else {
                        found.map {
                            EndpointDto("${it.label} over TLS", "https://${it.host}:$httpsPort", true)
                        }
                    }
                    call.respond(EndpointsDto(plain + secure, tls?.fingerprint))
                }

                get("/api/v1/stats") {
                    if (call.denied()) return@get
                    call.respond(stats())
                }

                post("/api/v1/scan") {
                    if (call.denied()) return@post
                    onScanRequested()
                    call.respond(HttpStatusCode.Accepted, mapOf("queued" to true))
                }

                get("/api/v1/timeline") {
                    if (call.denied()) return@get
                    val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 200).coerceIn(1, 500)
                    val cursor = Cursor.parse(call.request.queryParameters["cursor"])
                    val rows = db.assets().timeline(cursor?.capturedAt, cursor?.id ?: Long.MAX_VALUE, limit)
                    val next = rows.lastOrNull()?.let { Cursor(it.capturedAt, it.id).encode() }
                    call.respond(
                        TimelineDto(
                            items = rows.map { it.toDto() },
                            nextCursor = if (rows.size < limit) null else next,
                            hasMore = rows.size == limit,
                        ),
                    )
                }

                get("/api/v1/timeline/buckets") {
                    if (call.denied()) return@get
                    call.respond(db.assets().monthBuckets().map { BucketDto(it.bucket, it.count, it.newestCapturedAt) })
                }

                get("/api/v1/assets/{id}") {
                    if (call.denied()) return@get
                    val id = call.parameters["id"]?.toLongOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest, "bad id")
                    val asset = db.assets().byId(id)
                        ?: return@get call.respond(HttpStatusCode.NotFound, "no such asset")
                    val file = db.assetFiles().canonical(id)
                    call.respond(
                        AssetDetailDto(
                            id = asset.id,
                            contentHash = asset.contentHash,
                            mime = asset.mime,
                            mediaType = asset.mediaType,
                            byteSize = asset.byteSize,
                            width = asset.width,
                            height = asset.height,
                            orientation = asset.orientation,
                            durationMs = asset.durationMs,
                            capturedAt = asset.capturedAt,
                            capturedAtSource = asset.capturedAtSource,
                            tzOffsetMinutes = asset.tzOffsetMinutes,
                            latitude = asset.latitude,
                            longitude = asset.longitude,
                            cameraMake = asset.cameraMake,
                            cameraModel = asset.cameraModel,
                            blurhash = asset.blurhash,
                            favorite = asset.favorite,
                            relPath = file?.relPath,
                            present = file != null && file.missingSince == null,
                        ),
                    )
                }

                patch("/api/v1/assets/{id}") {
                    if (call.denied()) return@patch
                    val id = call.parameters["id"]?.toLongOrNull()
                        ?: return@patch call.respond(HttpStatusCode.BadRequest, "bad id")
                    val body = runCatching { call.receive<AssetPatchDto>() }.getOrNull()
                        ?: return@patch call.respond(HttpStatusCode.BadRequest, "bad body")
                    val now = System.currentTimeMillis()
                    body.favorite?.let { db.assets().setFavorite(id, it, now) }
                    // Accepted as an upgrade only. A client that knows a real capture date can
                    // repair one derived from a file's mtime, but can never overwrite a better one.
                    body.capturedAt?.let {
                        db.assets().upgradeCapturedAt(
                            id = id,
                            capturedAt = it,
                            source = body.capturedAtSource ?: CaptureSource.MEDIASTORE,
                            tzOffsetMinutes = body.tzOffsetMinutes,
                            now = now,
                        )
                    }
                    // Trash is a soft delete: the row and its hash survive so a restore is possible
                    // and a re-upload of the same bytes does not silently resurrect it as new.
                    body.deleted?.let {
                        if (it) db.assets().trash(listOf(id), now) else db.assets().restore(listOf(id), now)
                    }
                    val updated = db.assets().byId(id)
                        ?: return@patch call.respond(HttpStatusCode.NotFound, "no such asset")
                    // A typed DTO, not mapOf(...): a heterogeneous Map<String, Any> has no
                    // serializer, so the write would land and the response would then 500 --
                    // leaving the client convinced its change had failed.
                    call.respond(
                        AssetPatchResultDto(
                            id = updated.id,
                            favorite = updated.favorite,
                            deleted = updated.deletedAt != null,
                        ),
                    )
                }

                get("/api/v1/assets/{id}/thumb") { serveThumb(call) }
                get("/api/v1/assets/{id}/original") { serveOriginal(call, headOnly = false) }
                head("/api/v1/assets/{id}/original") { serveOriginal(call, headOnly = true) }

                /**
                 * Re-derives capture dates for assets that only ever had a weak one.
                 *
                 * Early uploads were dated from the stored file's mtime, which is the instant we
                 * wrote it. Re-running extraction now recovers the date from the filename, which
                 * survived the upload intact inside the stored name. Applied through the same
                 * upgrade-only rule, so a good date is never replaced by a worse one.
                 */
                post("/api/v1/repair/dates") {
                    if (call.denied()) return@post
                    var examined = 0
                    var fixed = 0
                    val weak = db.assets().withWeakDates(CaptureSource.MEDIASTORE, limit = 5000)
                    for (asset in weak) {
                        val file = db.assetFiles().canonical(asset.id) ?: continue
                        examined++
                        val meta = try {
                            store.openRead(file.relPath).use {
                                metadata.extract(it, asset.mime, file.modifiedAt, file.displayName)
                            }
                        } catch (t: Throwable) {
                            continue
                        }
                        if (meta.capturedAtSource >= asset.capturedAtSource) continue
                        val changed = db.assets().upgradeCapturedAt(
                            id = asset.id,
                            capturedAt = meta.capturedAt,
                            source = meta.capturedAtSource,
                            tzOffsetMinutes = meta.tzOffsetMinutes,
                            now = System.currentTimeMillis(),
                        )
                        if (changed > 0) fixed++
                    }
                    call.respond(RepairResultDto(examined = examined, fixed = fixed))
                }

                /**
                 * Bulk operations on a selection.
                 *
                 * `trash` is a soft delete: the row and its hash survive, so a restore is possible
                 * and a backup client re-offering the same bytes is still told "already have it"
                 * rather than silently resurrecting it.
                 *
                 * `purge` is the real thing -- rows, stored bytes and cached thumbnails all go, and
                 * the fingerprint is forgotten so a later rescan would treat the file as new.
                 */
                /** Where the library's contents came from, so a whole source can be reviewed. */
                get("/api/v1/sources") {
                    if (call.denied()) return@get
                    call.respond(
                        db.assets().sourceAlbums().map { SourceDto(it.album, it.count, it.bytes) },
                    )
                }

                post("/api/v1/assets/batch") {
                    if (call.denied()) return@post
                    val body = runCatching { call.receive<BatchDto>() }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "bad body")

                    // Either an explicit selection or a whole source album. Resolving the album to
                    // ids here means every op behaves identically whichever way it was addressed.
                    val ids = if (body.sourceAlbum != null) {
                        db.assets().idsFromSource(body.sourceAlbum)
                    } else {
                        body.ids
                    }
                    if (ids.isEmpty()) {
                        call.respond(BatchResultDto(0, body.op))
                        return@post
                    }
                    val now = System.currentTimeMillis()
                    var affected = 0
                    when (body.op) {
                        "trash" -> {
                            db.assets().trash(ids, now)
                            affected = ids.size
                        }
                        "restore" -> {
                            db.assets().restore(ids, now)
                            affected = ids.size
                        }
                        "favorite", "unfavorite" -> {
                            db.assets().setFavoriteAll(ids, body.op == "favorite", now)
                            affected = ids.size
                        }
                        "purge" -> affected = purge(ids)
                        else -> {
                            call.respond(HttpStatusCode.BadRequest, "unknown op ${body.op}")
                            return@post
                        }
                    }
                    call.respond(BatchResultDto(affected, body.op))
                }

                /** Empties the trash: everything soft-deleted is removed for real. */
                post("/api/v1/trash/empty") {
                    if (call.denied()) return@post
                    val ids = db.assets().trashed(limit = 10_000).map { it.id }
                    call.respond(BatchResultDto(purge(ids), "purge"))
                }

                get("/api/v1/trash") {
                    if (call.denied()) return@get
                    val rows = db.assets().trashed(limit = 500)
                    call.respond(
                        rows.map {
                            TrashItemDto(
                                id = it.id,
                                mime = it.mime,
                                byteSize = it.byteSize,
                                capturedAt = it.capturedAt,
                                deletedAt = it.deletedAt ?: 0,
                                blurhash = it.blurhash,
                                sourceAlbum = it.sourceAlbum,
                            )
                        },
                    )
                }

                // ---------------------------------------------------------------- upload

                // The call that makes continuous backup cheap: a client asks which of the hashes it
                // holds are already stored, and skips those entirely.
                post("/api/v1/upload/check") {
                    if (call.denied()) return@post
                    val body = runCatching { call.receive<HashCheckDto>() }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "bad body")
                    val ids = uploads.knownIds(body.hashes)
                    call.respond(HashCheckResultDto(known = ids.keys.toList(), ids = ids))
                }

                post("/api/v1/upload/init") {
                    if (call.denied()) return@post
                    val body = runCatching { call.receive<UploadInitDto>() }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "bad body")

                    // Known bytes: answered before a single byte is transferred.
                    val known = body.sha256?.let { db.assets().byHash(it.lowercase()) }
                    if (known != null) {
                        call.respond(UploadInitResultDto(null, 0, duplicate = true, assetId = known.id))
                        return@post
                    }
                    val session = uploads.begin(
                        body.name, body.size, body.sha256, body.capturedAt, body.sourceAlbum,
                    )
                    call.respond(UploadInitResultDto(session.id, 0, duplicate = false))
                }

                // Resume probe: the staging file's own length is the offset.
                get("/api/v1/upload/{id}") {
                    if (call.denied()) return@get
                    val id = call.parameters["id"].orEmpty()
                    val offset = uploads.offsetOf(id)
                    if (offset < 0) return@get call.respond(HttpStatusCode.NotFound, "no such upload")
                    call.respond(UploadStatusDto(id, offset))
                }

                patch("/api/v1/upload/{id}") {
                    if (call.denied()) return@patch
                    val id = call.parameters["id"].orEmpty()
                    val expected = call.request.queryParameters["offset"]?.toLongOrNull()
                    val channel = call.receiveChannel()
                    // Streams straight to disk rather than buffering the chunk in memory, so a
                    // client is free to send large chunks without risking the server's heap.
                    val result = uploads.append(id, expected) { out -> channel.copyTo(out) }
                    when (result) {
                        is UploadService.AppendResult.NoSuchSession ->
                            call.respond(HttpStatusCode.NotFound, "no such upload")
                        // A chunk written at the wrong position gives a file of the right length and
                        // the wrong content, so a mismatch is refused rather than appended blindly.
                        is UploadService.AppendResult.OffsetMismatch ->
                            call.respond(HttpStatusCode.Conflict, UploadStatusDto(id, result.actual))
                        is UploadService.AppendResult.Ok ->
                            call.respond(UploadStatusDto(id, result.offset))
                    }
                }

                post("/api/v1/upload/{id}/finish") {
                    if (call.denied()) return@post
                    when (val r = uploads.finish(call.parameters["id"].orEmpty())) {
                        is UploadService.FinishResult.Stored ->
                            call.respond(UploadFinishDto(r.assetId, duplicate = false, relPath = r.relPath))
                        is UploadService.FinishResult.Duplicate ->
                            call.respond(UploadFinishDto(r.assetId, duplicate = true))
                        is UploadService.FinishResult.NoSuchSession ->
                            call.respond(HttpStatusCode.NotFound, "no such upload")
                        is UploadService.FinishResult.SizeMismatch ->
                            call.respond(HttpStatusCode.BadRequest, "got ${r.actual} bytes, declared ${r.expected}")
                        is UploadService.FinishResult.HashMismatch ->
                            call.respond(HttpStatusCode.BadRequest, "hash ${r.actual}, declared ${r.expected}")
                        is UploadService.FinishResult.Failed ->
                            call.respond(HttpStatusCode.InternalServerError, r.reason)
                    }
                }

                delete("/api/v1/upload/{id}") {
                    if (call.denied()) return@delete
                    uploads.cancel(call.parameters["id"].orEmpty())
                    call.respond(HttpStatusCode.NoContent)
                }

                // Raw store browsing, kept from M2 for diagnostics.
                get("/api/v1/fs/list") {
                    if (call.denied()) return@get
                    val path = call.request.queryParameters["path"].orEmpty()
                    call.respond(store.list(path).map { EntryDto(it.relPath, it.name, it.isDirectory, it.size, it.lastModified, it.mime) })
                }
                get("/api/v1/fs/read") { serveFsFile(call, headOnly = false) }
                head("/api/v1/fs/read") { serveFsFile(call, headOnly = true) }
            }
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 1_000, timeoutMillis = 3_000)
        server = null
    }

    // ------------------------------------------------------------------ handlers

    /**
     * Thumbnails are immutable once generated -- their filename contains the content hash -- so they
     * get a one-year immutable cache header and an ETag. A client that has seen one never asks again.
     *
     * A thumbnail that has not been rendered yet returns **202**, not 404: the asset exists and the
     * image is coming. The client already drew the blurhash, so it just retries or waits for the
     * event rather than showing a broken image.
     */
    private suspend fun serveThumb(call: ApplicationCall) {
        if (call.denied()) return
        val id = call.parameters["id"]?.toLongOrNull()
            ?: return call.respond(HttpStatusCode.BadRequest, "bad id")
        val sizeClass = if (call.request.queryParameters["size"] == "preview") ThumbSize.PREVIEW else ThumbSize.GRID

        val asset = db.assets().byId(id) ?: return call.respond(HttpStatusCode.NotFound, "no such asset")
        val row = db.thumbnails().find(id, sizeClass)

        if (row?.state != ThumbState.READY || row.cacheRelPath == null) {
            call.respond(
                HttpStatusCode.Accepted,
                mapOf("state" to (row?.state ?: ThumbState.ABSENT).toString(), "retryAfterMs" to "2000"),
            )
            return
        }
        val file = thumbs.cacheFile(asset.contentHash, sizeClass)
        if (!file.isFile) {
            // Row says ready but the bytes are gone: the cache was cleared or evicted underneath us.
            db.thumbnails().delete(id, sizeClass)
            call.respond(HttpStatusCode.Accepted, mapOf("state" to "REGENERATING", "retryAfterMs" to "2000"))
            return
        }

        // Recorded here, above the 304, because a client revalidating a cached thumbnail is still
        // using it -- and a popular thumbnail is served from the client's cache almost every time,
        // so touching only on 200 would mark exactly the hottest entries as cold.
        //
        // The row is already in hand, so the throttle costs no extra read: one write per thumbnail
        // per interval, instead of one per request on a grid that fires hundreds at a time.
        val now = System.currentTimeMillis()
        if (row.lastAccessAt == null || now - row.lastAccessAt > TOUCH_INTERVAL_MS) {
            db.thumbnails().touch(id, sizeClass, now)
        }

        val etag = "\"${asset.contentHash.take(16)}-$sizeClass\""
        if (call.request.headers[HttpHeaders.IfNoneMatch] == etag) {
            call.respond(HttpStatusCode.NotModified)
            return
        }
        call.response.header(HttpHeaders.ETag, etag)
        call.response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = 31_536_000, visibility = CacheControl.Visibility.Private))
        call.respondBytes(file.readBytes(), ContentType.Image.JPEG)
    }

    private suspend fun serveOriginal(call: ApplicationCall, headOnly: Boolean) {
        if (call.denied()) return
        val id = call.parameters["id"]?.toLongOrNull()
            ?: return call.respond(HttpStatusCode.BadRequest, "bad id")
        val asset = db.assets().byId(id) ?: return call.respond(HttpStatusCode.NotFound, "no such asset")
        val file = db.assetFiles().canonical(id)
            ?: return call.respond(HttpStatusCode.ServiceUnavailable, "file not currently resolvable")

        val entry = StoreEntry(
            relPath = file.relPath,
            name = file.displayName,
            isDirectory = false,
            size = asset.byteSize,
            lastModified = file.modifiedAt,
            mime = asset.mime,
        )
        val download = call.request.queryParameters["download"] == "1"
        responder.serve(call, entry, headOnly = headOnly, asAttachment = download)
    }

    private suspend fun serveFsFile(call: ApplicationCall, headOnly: Boolean) {
        if (call.denied()) return
        val path = call.request.queryParameters["path"]
        if (path.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, "path required")
            return
        }
        val entry = try {
            store.stat(path)
        } catch (t: Throwable) {
            call.respond(HttpStatusCode.ServiceUnavailable, "store error: ${t.javaClass.simpleName}")
            return
        }
        if (entry == null || entry.isDirectory) {
            call.respond(HttpStatusCode.NotFound, "no such file")
            return
        }
        responder.serve(call, entry, headOnly = headOnly)
    }

    /**
     * Removes assets permanently: stored bytes first, then cached thumbnails, then the rows.
     *
     * Bytes go before rows deliberately. If this is interrupted halfway, an orphaned file on the
     * store is recoverable -- a rescan simply re-imports it -- whereas a row pointing at bytes that
     * no longer exist is a broken entry that shows up in the timeline and fails on every open.
     */
    private suspend fun purge(ids: List<Long>): Int {
        var removed = 0
        for (id in ids) {
            val asset = db.assets().byId(id) ?: continue
            db.assetFiles().forAsset(id).forEach { file ->
                runCatching { store.delete(file.relPath) }
            }
            runCatching {
                thumbs.delete(asset.contentHash, ThumbSize.GRID)
                thumbs.delete(asset.contentHash, ThumbSize.PREVIEW)
            }
            db.fingerprints().forgetByHash(asset.contentHash)
            db.assets().purge(listOf(id))
            removed++
        }
        return removed
    }

    private suspend fun ApplicationCall.denied(): Boolean {
        val peer = request.local.remoteHost
        // Checked before the token is even compared, so a throttled caller learns nothing about
        // whether its guess was close.
        if (auth.isThrottled(peer)) {
            respond(HttpStatusCode.TooManyRequests, "too many failed attempts; wait a few minutes")
            return true
        }
        if (auth.isPublicPath(this) || auth.isAuthorized(this)) {
            auth.recordSuccess(peer)
            return false
        }
        auth.recordFailure(peer)
        val wantsPage = request.headers[HttpHeaders.Accept]?.contains("text/html") == true
        if (wantsPage) {
            response.status(HttpStatusCode.Unauthorized)
            respondText(unpairedHtml(), ContentType.Text.Html)
        } else {
            respond(HttpStatusCode.Unauthorized, "pair first: open /pair?c=<token>")
        }
        return true
    }

    private fun unpairedHtml(): String = """
        <!doctype html><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Pair this device</title>
        <style>
          body{font:15px/1.5 system-ui;background:#101114;color:#e8eaed;margin:0;padding:40px 24px;max-width:560px}
          code{background:#191b20;padding:2px 6px;border-radius:4px;word-break:break-all}
          h2{margin:0 0 12px}
        </style>
        <h2>Pair this device</h2>
        <p>This browser has not been paired with the library yet.</p>
        <p>Open the pairing link once. The gpicAlter app on the server phone shows it, next to
           the Start/Stop button:</p>
        <p><code>http://&lt;phone-ip&gt;:8080/pair?c=&lt;token&gt;</code></p>
        <p>It sets a cookie that lasts a year, so you only do this once per browser.</p>
    """.trimIndent()

    // ------------------------------------------------------------------ payloads

    private fun health(): HealthDto {
        val cap = runCatching { store.capacity() }.getOrNull()
        return HealthDto(
            ok = true,
            version = "0.3-index",
            uptimeS = (System.currentTimeMillis() - startedAt) / 1000,
            backend = store.kind.name,
            label = store.label,
            mounted = runCatching { store.isMounted }.getOrDefault(false),
            writable = runCatching { store.isWritable }.getOrDefault(false),
            availableBytes = cap?.availableBytes,
            totalBytes = cap?.totalBytes,
            openFds = openFds(),
        )
    }

    private suspend fun stats(): StatsDto {
        // Rows can over-report: two assets with identical bytes share one file but count twice.
        // The walk is the disk's own answer, and it is what eviction is judged against.
        val thumbCacheDisk = thumbCache.diskBytes()
        return StatsDto(
        assets = db.assets().count(),
        trashed = db.assets().trashCount(),
        libraryBytes = db.assets().totalBytes(),
        fingerprints = db.fingerprints().count(),
        missingFiles = db.assetFiles().missingCount(),
        gridThumbs = db.thumbnails().readyCount(ThumbSize.GRID),
        previewThumbs = db.thumbnails().readyCount(ThumbSize.PREVIEW),
        thumbCacheBytes = db.thumbnails().cachedBytes(),
        thumbCacheDiskBytes = thumbCacheDisk,
        thumbCacheLimitBytes = thumbCache.budget(thumbCacheDisk).limitBytes,
        pendingJobs = db.jobs().countByState(0),
        blockedJobs = db.jobs().countByState(5),
        failedJobs = db.jobs().countByState(3),
            pendingByType = db.jobs().pendingByType().associate { it.type to it.count },
        )
    }

    private suspend fun serveAsset(call: ApplicationCall, path: String, type: ContentType) {
        if (call.denied()) return
        val bytes = try {
            assets.open(path).use { it.readBytes() }
        } catch (t: Throwable) {
            call.respond(HttpStatusCode.NotFound, "missing asset $path")
            return
        }
        // No long cache: these ship with the APK and change on every build.
        call.response.cacheControl(CacheControl.NoCache(null))
        call.respondBytes(bytes, type)
    }
}

/**
 * Keyset cursor. Encodes the last row's (captured_at, id) so the next page is an index seek rather
 * than an OFFSET walk -- the reason deep scrolling stays as fast as the first page.
 */
data class Cursor(val capturedAt: Long, val id: Long) {
    fun encode(): String = "${capturedAt}_$id"

    companion object {
        fun parse(raw: String?): Cursor? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split('_')
            if (parts.size != 2) return null
            val ts = parts[0].toLongOrNull() ?: return null
            val id = parts[1].toLongOrNull() ?: return null
            return Cursor(ts, id)
        }
    }
}

private fun dev.gpicalter.data.dao.TimelineRow.toDto() = TimelineItemDto(
    id = id,
    mime = mime,
    mediaType = mediaType,
    width = width,
    height = height,
    orientation = orientation,
    capturedAt = capturedAt,
    tzOffsetMinutes = tzOffsetMinutes,
    durationMs = durationMs,
    favorite = favorite,
    blurhash = blurhash,
    isVideo = mediaType == MediaType.VIDEO,
)

/** Deliberately terse field names: 200 of these have to fit comfortably in one response. */
@Serializable
data class TimelineItemDto(
    val id: Long,
    val mime: String,
    val mediaType: Int,
    val width: Int?,
    val height: Int?,
    val orientation: Int,
    val capturedAt: Long,
    val tzOffsetMinutes: Int?,
    val durationMs: Long?,
    val favorite: Boolean,
    val blurhash: String?,
    val isVideo: Boolean,
)

@Serializable
data class TimelineDto(val items: List<TimelineItemDto>, val nextCursor: String?, val hasMore: Boolean)

@Serializable
data class BatchDto(
    val ids: List<Long> = emptyList(),
    val op: String = "",
    /** Targets every asset from this source album instead of an id list. */
    val sourceAlbum: String? = null,
)

@Serializable
data class SourceDto(val album: String, val count: Int, val bytes: Long)

@Serializable
data class BatchResultDto(val affected: Int, val op: String)

@Serializable
data class TrashItemDto(
    val id: Long,
    val mime: String,
    val byteSize: Long,
    val capturedAt: Long,
    val deletedAt: Long,
    val blurhash: String?,
    val sourceAlbum: String? = null,
)

@Serializable
data class EndpointDto(val label: String, val url: String, val secure: Boolean = false)

@Serializable
data class EndpointsDto(val endpoints: List<EndpointDto>, val tlsFingerprint: String? = null)

@Serializable
data class RepairResultDto(val examined: Int, val fixed: Int)

@Serializable
data class HashCheckDto(val hashes: List<String> = emptyList())

@Serializable
data class HashCheckResultDto(val known: List<String>, val ids: Map<String, Long> = emptyMap())

@Serializable
data class UploadInitDto(
    val name: String,
    val size: Long,
    val sha256: String? = null,
    val capturedAt: Long? = null,
    val sourceAlbum: String? = null,
)

@Serializable
data class UploadInitResultDto(
    val uploadId: String?,
    val offset: Long,
    val duplicate: Boolean,
    val assetId: Long? = null,
)

@Serializable
data class UploadStatusDto(val uploadId: String, val offset: Long)

@Serializable
data class UploadFinishDto(val assetId: Long, val duplicate: Boolean, val relPath: String? = null)

@Serializable
data class AssetPatchDto(
    val favorite: Boolean? = null,
    val deleted: Boolean? = null,
    val capturedAt: Long? = null,
    val capturedAtSource: Int? = null,
    val tzOffsetMinutes: Int? = null,
)

@Serializable
data class AssetPatchResultDto(val id: Long, val favorite: Boolean, val deleted: Boolean)

@Serializable
data class BucketDto(val bucket: String, val count: Int, val newestCapturedAt: Long)

@Serializable
data class AssetDetailDto(
    val id: Long,
    val contentHash: String,
    val mime: String,
    val mediaType: Int,
    val byteSize: Long,
    val width: Int?,
    val height: Int?,
    val orientation: Int,
    val durationMs: Long?,
    val capturedAt: Long,
    val capturedAtSource: Int,
    val tzOffsetMinutes: Int?,
    val latitude: Double?,
    val longitude: Double?,
    val cameraMake: String?,
    val cameraModel: String?,
    val blurhash: String?,
    val favorite: Boolean,
    val relPath: String?,
    val present: Boolean,
)

@Serializable
data class StatsDto(
    val assets: Int,
    val trashed: Int,
    val libraryBytes: Long,
    val fingerprints: Int,
    val missingFiles: Int,
    val gridThumbs: Int,
    val previewThumbs: Int,
    val thumbCacheBytes: Long,
    /** What the cache directory actually occupies, as opposed to what the rows add up to. */
    val thumbCacheDiskBytes: Long,
    val thumbCacheLimitBytes: Long,
    val pendingJobs: Int,
    val blockedJobs: Int,
    val failedJobs: Int,
    val pendingByType: Map<String, Int>,
)

@Serializable
data class HealthDto(
    val ok: Boolean,
    val version: String,
    val uptimeS: Long,
    val backend: String,
    val label: String,
    val mounted: Boolean,
    val writable: Boolean,
    val availableBytes: Long?,
    val totalBytes: Long?,
    val openFds: Int,
)

@Serializable
data class EntryDto(
    val relPath: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val mime: String,
)
