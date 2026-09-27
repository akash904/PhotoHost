package io.github.akash904.photohost.server

import android.system.Os
import android.system.OsConstants
import io.github.akash904.photohost.storage.LibraryStore
import io.github.akash904.photohost.storage.StoreEntry
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.FileInputStream

private const val BUFFER = 256 * 1024

/** What the client asked for. */
sealed interface RangeSpec {
    data object Full : RangeSpec
    data class Partial(val start: Long, val endInclusive: Long) : RangeSpec
    data object Unsatisfiable : RangeSpec
}

/**
 * Parses a single byte range. Deliberately hand-rolled rather than using Ktor's `PartialContent`
 * plugin: that plugin is built around `File`-backed content, and everything here arrives as a
 * [android.os.ParcelFileDescriptor] with no path behind it.
 *
 * Multi-range requests are answered with the whole body, which is legal and which no browser needs
 * for video playback.
 */
fun parseRangeHeader(raw: String?, size: Long): RangeSpec {
    if (raw.isNullOrBlank()) return RangeSpec.Full
    val spec = raw.trim()
    if (!spec.startsWith("bytes=", ignoreCase = true)) return RangeSpec.Full
    val body = spec.substring("bytes=".length).trim()
    if (body.contains(',')) return RangeSpec.Full
    val dash = body.indexOf('-')
    if (dash < 0) return RangeSpec.Unsatisfiable
    if (size <= 0L) return RangeSpec.Unsatisfiable

    val firstPart = body.substring(0, dash).trim()
    val lastPart = body.substring(dash + 1).trim()
    return try {
        when {
            // "bytes=-500": the final 500 bytes.
            firstPart.isEmpty() -> {
                val suffix = lastPart.toLong()
                if (suffix <= 0L) RangeSpec.Unsatisfiable
                else RangeSpec.Partial((size - suffix).coerceAtLeast(0L), size - 1)
            }
            // "bytes=500-": from 500 to the end.
            lastPart.isEmpty() -> {
                val start = firstPart.toLong()
                if (start < 0 || start >= size) RangeSpec.Unsatisfiable
                else RangeSpec.Partial(start, size - 1)
            }
            // "bytes=500-999", with the end clamped to the last byte.
            else -> {
                val start = firstPart.toLong()
                val end = lastPart.toLong().coerceAtMost(size - 1)
                if (start < 0 || start > end || start >= size) RangeSpec.Unsatisfiable
                else RangeSpec.Partial(start, end)
            }
        }
    } catch (e: NumberFormatException) {
        RangeSpec.Unsatisfiable
    }
}

/**
 * Streams bytes out of a [LibraryStore] with HTTP Range support, which is what makes video
 * scrubbing work.
 *
 * Two things here are load-bearing:
 *
 *  - The descriptor is opened **inside** the response writer and closed by `use`, so it is
 *    released even when the client aborts mid-stream. A browser seeking a video aborts constantly;
 *    one descriptor leaked per abort exhausts the process limit within an afternoon, and the
 *    symptom is "the server worked fine for a day and then stopped responding".
 *  - Concurrent full-size streams are capped. Twenty simultaneous 4K streams plus a thumbnail
 *    burst would otherwise exhaust descriptors or heap.
 */
class RangeResponder(private val store: LibraryStore) {

    private val streams = Semaphore(4)

    suspend fun serve(
        call: ApplicationCall,
        entry: StoreEntry,
        headOnly: Boolean = false,
        asAttachment: Boolean = false,
    ) {
        // Cheap but stable validator. From M3 this becomes the content hash, which is strictly
        // better: identical bytes then share an ETag no matter where the file sits.
        val etag = "\"${entry.size}-${entry.lastModified}\""
        val contentType = runCatching { ContentType.parse(entry.mime) }
            .getOrDefault(ContentType.Application.OctetStream)

        call.response.header(HttpHeaders.AcceptRanges, "bytes")
        call.response.header(HttpHeaders.ETag, etag)
        if (asAttachment) {
            call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"${entry.name}\"")
        }

        // If-Range: only honour the Range when the file has not changed underneath the client.
        val ifRange = call.request.headers[HttpHeaders.IfRange]
        val rangeHeader = if (ifRange != null && ifRange != etag) null
        else call.request.headers[HttpHeaders.Range]

        when (val spec = parseRangeHeader(rangeHeader, entry.size)) {
            RangeSpec.Unsatisfiable -> {
                call.response.header(HttpHeaders.ContentRange, "bytes */${entry.size}")
                call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)
            }

            RangeSpec.Full -> stream(
                call, entry, 0, entry.size - 1, HttpStatusCode.OK, contentType, headOnly,
            )

            is RangeSpec.Partial -> {
                call.response.header(
                    HttpHeaders.ContentRange,
                    "bytes ${spec.start}-${spec.endInclusive}/${entry.size}",
                )
                stream(
                    call, entry, spec.start, spec.endInclusive,
                    HttpStatusCode.PartialContent, contentType, headOnly,
                )
            }
        }
    }

    private suspend fun stream(
        call: ApplicationCall,
        entry: StoreEntry,
        start: Long,
        endInclusive: Long,
        status: HttpStatusCode,
        type: ContentType,
        headOnly: Boolean,
    ) {
        val length = if (entry.size <= 0L) 0L else endInclusive - start + 1

        if (headOnly) {
            // Captured into locals: inside the object literal, `status` would resolve to the
            // property being declared rather than the parameter, and recurse forever.
            val outStatus = status
            val outType = type
            val outLength = length
            call.respond(object : OutgoingContent.NoContent() {
                override val status: HttpStatusCode get() = outStatus
                override val contentType: ContentType get() = outType
                override val contentLength: Long get() = outLength
            })
            return
        }

        streams.withPermit {
            call.respondBytesWriter(contentType = type, status = status, contentLength = length) {
                if (length <= 0L) return@respondBytesWriter
                // Opened here so `use` closes it when the writer finishes OR throws on abort.
                store.openRead(entry.relPath).use { pfd ->
                    Os.lseek(pfd.fileDescriptor, start, OsConstants.SEEK_SET)
                    val input = FileInputStream(pfd.fileDescriptor)
                    val buf = ByteArray(BUFFER)
                    var remaining = length
                    while (remaining > 0L) {
                        val want = minOf(remaining, buf.size.toLong()).toInt()
                        val n = input.read(buf, 0, want)
                        if (n < 0) break
                        writeFully(buf, 0, n)
                        remaining -= n
                    }
                    flush()
                }
            }
        }
    }
}
