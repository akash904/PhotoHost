package io.github.akash904.photohost.core

import kotlinx.serialization.Serializable

/**
 * A library this phone knows how to reach, other than its own.
 *
 * A person may run several libraries at once -- a phone for recent photos, a PC for the archive --
 * and they are separate libraries, not copies of one another. So this app keeps a list of them
 * rather than a single "the server", browses one at a time, and backs up to one it is told to,
 * which need not be the one on screen.
 *
 * Everything needed to reach a library travels with it: the address it was paired at, the others
 * it advertised, its access token and the certificate pin. Two libraries never share a pin or a
 * token, so switching between them is switching every one of those at once.
 */
@Serializable
data class LibraryProfile(
    /** Random and permanent. Never the address, which changes. */
    val id: String,
    /** Shown in the switcher; renamable. */
    val name: String,
    /** The address it was paired at. */
    val url: String,
    val token: String,
    /** SHA-256 of its TLS certificate, from the pairing code. Null for a plain-HTTP pairing. */
    val fingerprint: String? = null,
    /** Every address it advertised, tried in order when [url] does not answer. */
    val candidates: List<String> = emptyList(),
    val addedAt: Long = 0,
)

/**
 * A library as the client sees it: this phone's own, or a paired one, resolved to what a request
 * needs. [url] is null for this phone, which is reached over loopback.
 */
data class Library(
    val id: String,
    val name: String,
    val url: String?,
    val token: String,
    val fingerprint: String?,
    val candidates: List<String>,
) {
    val isLocal: Boolean get() = url == null

    fun baseUrl(localPort: Int): String = url ?: "http://127.0.0.1:$localPort"

    companion object {
        /** The id of this phone's own library. Never collides with a random UUID. */
        const val LOCAL_ID = "local"
    }
}
