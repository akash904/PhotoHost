package io.github.akash904.photohost.desktop

import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties

/**
 * The desktop's equivalent of the phone's Prefs: port, access token and library folder, kept in
 * `config.properties` in the data directory.
 *
 * The data directory (database, thumbnails, TLS identity, upload staging, log) is
 * `%LOCALAPPDATA%\PhotoHost` -- local, per user and not roamed, which is where Windows expects an
 * app's machine-specific state. The library folder is separate and holds only originals.
 */
class Config private constructor(
    val dataDir: File,
    private val file: File,
    private val props: Properties,
) {
    var port: Int
        get() = props.getProperty(KEY_PORT)?.toIntOrNull() ?: DEFAULT_PORT
        set(v) = set(KEY_PORT, v.toString())

    /** TLS listens here, as on the phone. Fixed relative to [port] so a pairing code stays valid. */
    val httpsPort: Int get() = port + TLS_PORT_OFFSET

    var libraryRoot: File
        get() = props.getProperty(KEY_LIBRARY)?.let(::File) ?: defaultLibrary()
        set(v) = set(KEY_LIBRARY, v.absolutePath)

    /**
     * Path to ffmpeg.exe for video thumbnails, or "none" to switch them off. Unset means: look next
     * to the app, then on the PATH.
     */
    var ffmpeg: String?
        get() = props.getProperty(KEY_FFMPEG)?.takeIf { it.isNotBlank() }
        set(v) = if (v == null) { props.remove(KEY_FFMPEG); save() } else set(KEY_FFMPEG, v)

    /**
     * The access token, generated once and reused so pairing survives restarts. Generated exactly
     * as the phone does it -- 32 random bytes, URL-safe base64 without padding -- so a token from
     * either server looks the same to every client.
     */
    fun token(): String {
        props.getProperty(KEY_TOKEN)?.takeIf { it.isNotBlank() }?.let { return it }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val fresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        set(KEY_TOKEN, fresh)
        return fresh
    }

    private fun set(key: String, value: String) {
        props.setProperty(key, value)
        save()
    }

    private fun save() {
        file.parentFile?.mkdirs()
        // Written to a sibling and renamed, so a crash mid-write cannot lose the token and thereby
        // un-pair every client.
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { props.store(it, "PhotoHost settings") }
        java.nio.file.Files.move(
            tmp.toPath(), file.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
    }

    companion object {
        const val DEFAULT_PORT = 8080
        const val TLS_PORT_OFFSET = 363
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token"
        private const val KEY_LIBRARY = "library"
        private const val KEY_FFMPEG = "ffmpeg"

        fun defaultDataDir(): File {
            val local = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
            return if (local != null) File(local, "PhotoHost") else File(System.getProperty("user.home"), ".photohost")
        }

        /**
         * Directly under the user's profile, deliberately NOT in Pictures: OneDrive's folder backup
         * redirects Pictures on many Windows installs, and a photo library there would be uploaded
         * to the cloud file by file -- exactly what self-hosting is meant to avoid.
         */
        fun defaultLibrary(): File = File(System.getProperty("user.home"), "PhotoHost Library")

        fun load(dataDir: File): Config {
            val file = File(dataDir, "config.properties")
            val props = Properties()
            if (file.isFile) file.inputStream().use { props.load(it) }
            return Config(dataDir, file, props)
        }
    }
}
