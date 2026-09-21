package dev.gpicalter.net

import dev.gpicalter.core.Prefs
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.forms.formData
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The one way this app reads a library.
 *
 * It is used identically whether the library is on this phone or another one: when serving locally
 * the base URL is loopback. That keeps a single browsing code path, so the server phone exercises
 * exactly the same client the laptop does and local-only bugs cannot hide.
 */
class LibraryApi(private val prefs: Prefs) {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    val client: HttpClient = HttpClient(OkHttp) {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            // Generous: the server is a phone that may be busy thumbnailing a backlog.
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
    }

    /** Why the last request failed. Without this, every network problem looks identical. */
    @Volatile
    var lastError: String? = null
        private set

    fun baseUrl(): String = prefs.baseUrl()

    fun thumbUrl(assetId: Long, size: String = "grid"): String =
        "${baseUrl()}/api/v1/assets/$assetId/thumb?size=$size"

    fun originalUrl(assetId: Long): String = "${baseUrl()}/api/v1/assets/$assetId/original"

    /** Coil and ExoPlayer both need this, since thumbnails and originals are authenticated too. */
    fun authHeader(): String = "Bearer ${prefs.activeToken()}"

    suspend fun health(): HealthDto? = getOrNull("/health")

    suspend fun stats(): StatsDto? = getOrNull("/api/v1/stats")

    suspend fun timeline(cursor: String?, limit: Int = 200): TimelineDto? =
        getOrNull("/api/v1/timeline") {
            parameter("limit", limit)
            if (cursor != null) parameter("cursor", cursor)
        }

    suspend fun buckets(): List<BucketDto> = getOrNull<List<BucketDto>>("/api/v1/timeline/buckets") ?: emptyList()

    suspend fun asset(id: Long): AssetDetailDto? = getOrNull("/api/v1/assets/$id")

    suspend fun setFavorite(id: Long, favorite: Boolean): Boolean = try {
        client.patch("${baseUrl()}/api/v1/assets/$id") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(AssetPatchDto(favorite = favorite))
        }.status.isSuccess()
    } catch (t: Throwable) {
        lastError = "${t.javaClass.simpleName}: ${t.message}"
        false
    }

    suspend fun trashAsset(id: Long): Boolean = try {
        client.patch("${baseUrl()}/api/v1/assets/$id") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(AssetPatchDto(deleted = true))
        }.status.isSuccess()
    } catch (t: Throwable) {
        lastError = "${t.javaClass.simpleName}: ${t.message}"
        false
    }

    // ---------------------------------------------------------------- upload

    /**
     * Asks which of these hashes the library already holds.
     *
     * This is what makes continuous backup cheap: a client re-offering its whole camera roll pays
     * one small round trip per batch instead of re-uploading everything it cannot remember sending.
     */
    suspend fun knownHashes(hashes: List<String>): Map<String, Long> {
        if (hashes.isEmpty()) return emptyMap()
        return try {
            val r = client.post("${baseUrl()}/api/v1/upload/check") {
                auth()
                contentType(ContentType.Application.Json)
                setBody(HashCheckDto(hashes))
            }
            if (r.status.isSuccess()) r.body<HashCheckResultDto>().ids else emptyMap()
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            emptyMap()
        }
    }

    /**
     * Offers a capture date the client knows from MediaStore. The server applies it only if what it
     * holds is weaker, so this repairs historical mistakes without ever degrading a good date.
     */
    suspend fun offerCapturedAt(assetId: Long, capturedAt: Long): Boolean = try {
        client.patch("${baseUrl()}/api/v1/assets/$assetId") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(AssetPatchDto(capturedAt = capturedAt, capturedAtSource = 2))
        }.status.isSuccess()
    } catch (t: Throwable) {
        false
    }

    suspend fun uploadInit(
        name: String,
        size: Long,
        sha256: String,
        capturedAt: Long?,
        sourceAlbum: String? = null,
    ): UploadInitResultDto? =
        try {
            val r = client.post("${baseUrl()}/api/v1/upload/init") {
                auth()
                contentType(ContentType.Application.Json)
                setBody(UploadInitDto(name, size, sha256, capturedAt, sourceAlbum))
            }
            if (r.status.isSuccess()) r.body<UploadInitResultDto>() else null
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            null
        }

    /** Returns the server's offset after the chunk, or null on failure. */
    suspend fun uploadChunk(uploadId: String, offset: Long, bytes: ByteArray, length: Int): Long? = try {
        val r = client.patch("${baseUrl()}/api/v1/upload/$uploadId") {
            auth()
            parameter("offset", offset)
            setBody(if (length == bytes.size) bytes else bytes.copyOf(length))
        }
        when {
            r.status.isSuccess() -> r.body<UploadStatusDto>().offset
            // 409 carries the server's real offset, so a desynced client can resume rather than
            // restart the whole transfer.
            r.status.value == 409 -> r.body<UploadStatusDto>().offset
            else -> null
        }
    } catch (t: Throwable) {
        lastError = "${t.javaClass.simpleName}: ${t.message}"
        null
    }

    suspend fun uploadOffset(uploadId: String): Long? =
        getOrNull<UploadStatusDto>("/api/v1/upload/$uploadId")?.offset

    suspend fun uploadFinish(uploadId: String): UploadFinishDto? = try {
        val r = client.post("${baseUrl()}/api/v1/upload/$uploadId/finish") { auth() }
        if (r.status.isSuccess()) {
            r.body<UploadFinishDto>()
        } else {
            lastError = "finish: HTTP ${r.status.value}"
            null
        }
    } catch (t: Throwable) {
        lastError = "${t.javaClass.simpleName}: ${t.message}"
        null
    }

    /** Bulk op over a selection: trash, restore, favorite, unfavorite, purge. */
    suspend fun batch(ids: List<Long>, op: String): Int = try {
        val r = client.post("${baseUrl()}/api/v1/assets/batch") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(BatchDto(ids, op))
        }
        if (r.status.isSuccess()) r.body<BatchResultDto>().affected else 0
    } catch (t: Throwable) {
        lastError = "${t.javaClass.simpleName}: ${t.message}"
        0
    }

    suspend fun sources(): List<SourceDto> = getOrNull<List<SourceDto>>("/api/v1/sources") ?: emptyList()

    suspend fun trash(): List<TrashItemDto> = getOrNull<List<TrashItemDto>>("/api/v1/trash") ?: emptyList()

    suspend fun batchBySource(album: String, op: String): Int = try {
        val r = client.post("${baseUrl()}/api/v1/assets/batch") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(BatchDto(op = op, sourceAlbum = album))
        }
        if (r.status.isSuccess()) r.body<BatchResultDto>().affected else 0
    } catch (t: Throwable) {
        lastError = "${t.javaClass.simpleName}: ${t.message}"
        0
    }

    suspend fun emptyTrash(): Int = try {
        val r = client.post("${baseUrl()}/api/v1/trash/empty") { auth() }
        if (r.status.isSuccess()) r.body<BatchResultDto>().affected else 0
    } catch (t: Throwable) {
        lastError = "${t.javaClass.simpleName}: ${t.message}"
        0
    }

    suspend fun requestScan(): Boolean = try {
        client.post("${baseUrl()}/api/v1/scan") { auth() }.status.isSuccess()
    } catch (t: Throwable) {
        false
    }

    private suspend inline fun <reified T> getOrNull(
        path: String,
        crossinline configure: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ): T? = try {
        val response: HttpResponse = client.get("${baseUrl()}$path") {
            header(HttpHeaders.Authorization, authHeader())
            configure()
        }
        if (response.status.isSuccess()) {
            lastError = null
            response.body<T>()
        } else {
            lastError = "HTTP ${response.status.value} from $path"
            null
        }
    } catch (t: Throwable) {
        lastError = "${t.javaClass.simpleName}: ${t.message}"
        android.util.Log.w("gpic", "api $path failed", t)
        null
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth() {
        header(HttpHeaders.Authorization, authHeader())
    }
}

// ---------------------------------------------------------------- wire types
// Deliberately mirrors the server's DTOs. ignoreUnknownKeys means a newer server can add fields
// without breaking an older client -- which will matter once the two are updated separately.

@Serializable
data class TimelineItemDto(
    val id: Long,
    val mime: String = "",
    val mediaType: Int = 0,
    val width: Int? = null,
    val height: Int? = null,
    val orientation: Int = 0,
    val capturedAt: Long = 0,
    val tzOffsetMinutes: Int? = null,
    val durationMs: Long? = null,
    val favorite: Boolean = false,
    val blurhash: String? = null,
    val isVideo: Boolean = false,
) {
    /** Falls back to square so a missing dimension cannot break the row solver. */
    val aspectRatio: Float
        get() {
            val w = width ?: return 1f
            val h = height ?: return 1f
            if (w <= 0 || h <= 0) return 1f
            return (w.toFloat() / h).coerceIn(0.4f, 3.5f)
        }
}

@Serializable
data class TimelineDto(
    val items: List<TimelineItemDto> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)

@Serializable
data class BatchDto(
    val ids: List<Long> = emptyList(),
    val op: String = "",
    val sourceAlbum: String? = null,
)

@Serializable
data class SourceDto(val album: String = "", val count: Int = 0, val bytes: Long = 0)

@Serializable
data class TrashItemDto(
    val id: Long,
    val mime: String = "",
    val byteSize: Long = 0,
    val capturedAt: Long = 0,
    val deletedAt: Long = 0,
    val blurhash: String? = null,
    val sourceAlbum: String? = null,
)

@Serializable
data class BatchResultDto(val affected: Int = 0, val op: String = "")

@Serializable
data class HashCheckDto(val hashes: List<String> = emptyList())

@Serializable
data class HashCheckResultDto(
    val known: List<String> = emptyList(),
    val ids: Map<String, Long> = emptyMap(),
)

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
    val uploadId: String? = null,
    val offset: Long = 0,
    val duplicate: Boolean = false,
    val assetId: Long? = null,
)

@Serializable
data class UploadStatusDto(val uploadId: String = "", val offset: Long = 0)

@Serializable
data class UploadFinishDto(val assetId: Long, val duplicate: Boolean = false, val relPath: String? = null)

@Serializable
data class AssetPatchDto(
    val favorite: Boolean? = null,
    val deleted: Boolean? = null,
    val capturedAt: Long? = null,
    val capturedAtSource: Int? = null,
    val tzOffsetMinutes: Int? = null,
)

@Serializable
data class BucketDto(val bucket: String, val count: Int, val newestCapturedAt: Long)

@Serializable
data class AssetDetailDto(
    val id: Long,
    val contentHash: String = "",
    val mime: String = "",
    val mediaType: Int = 0,
    val byteSize: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
    val orientation: Int = 0,
    val durationMs: Long? = null,
    val capturedAt: Long = 0,
    val capturedAtSource: Int = 5,
    val tzOffsetMinutes: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    val blurhash: String? = null,
    val favorite: Boolean = false,
    val relPath: String? = null,
    val present: Boolean = true,
)

@Serializable
data class StatsDto(
    val assets: Int = 0,
    val trashed: Int = 0,
    val libraryBytes: Long = 0,
    val fingerprints: Int = 0,
    val missingFiles: Int = 0,
    val gridThumbs: Int = 0,
    val previewThumbs: Int = 0,
    val thumbCacheBytes: Long = 0,
    val pendingJobs: Int = 0,
    val blockedJobs: Int = 0,
    val failedJobs: Int = 0,
)

@Serializable
data class HealthDto(
    val ok: Boolean = false,
    val version: String = "",
    val uptimeS: Long = 0,
    val backend: String = "",
    val label: String = "",
    val mounted: Boolean = false,
    val writable: Boolean = false,
    val availableBytes: Long? = null,
    val totalBytes: Long? = null,
    val openFds: Int = 0,
)
