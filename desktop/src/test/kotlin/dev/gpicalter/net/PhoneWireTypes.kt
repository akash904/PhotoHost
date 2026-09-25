package dev.gpicalter.net

import kotlinx.serialization.Serializable

// ---------------------------------------------------------------- the phone's wire types
//
// Copied verbatim from gpicAlter's net/LibraryApi.kt (commit d43de56), which is what the shipping
// phone app decodes server responses into. Tests decode through THESE, not through the server's own
// DTOs, because "the server can read its own output" proves nothing about the phone -- the two sets
// already differ in defaults and fields. Keep in sync with LibraryApi.kt, never with HttpServer.kt.

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
)

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
data class EndpointDto(val label: String = "", val url: String = "", val secure: Boolean = false)

@Serializable
data class EndpointsDto(
    val endpoints: List<EndpointDto> = emptyList(),
    val tlsFingerprint: String? = null,
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
