package dev.gpicalter.core

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.security.SecureRandom

/**
 * The little shared settings the UI and the foreground service both need. The service starts in
 * its own process lifecycle -- possibly from BOOT_COMPLETED with no Activity ever created -- so it
 * cannot ask a ViewModel which backend to use. It reads it from here.
 */
class Prefs(context: Context) {

    private val p = context.applicationContext.getSharedPreferences("gpic", Context.MODE_PRIVATE)

    var backend: String
        get() = p.getString(KEY_BACKEND, "INTERNAL") ?: "INTERNAL"
        set(v) = p.edit().putString(KEY_BACKEND, v).apply()

    var treeUri: Uri?
        get() = p.getString(KEY_TREE, null)?.let(Uri::parse)
        set(v) = p.edit().putString(KEY_TREE, v?.toString()).apply()

    var port: Int
        get() = p.getInt(KEY_PORT, 8080)
        set(v) = p.edit().putInt(KEY_PORT, v).apply()

    /**
     * Whether to ask the router to accept inbound connections from the internet.
     *
     * Opt-in, and deliberately so: this is the difference between a library only reachable from your
     * own Wi-Fi and one reachable from anywhere. What guards it once open is the bearer token and the
     * pinned certificate -- but the choice to expose it belongs to the user, not to a default.
     */
    var remoteAccess: Boolean
        get() = p.getBoolean(KEY_REMOTE_ACCESS, false)
        set(v) = p.edit().putBoolean(KEY_REMOTE_ACCESS, v).apply()

    /** Whether the service should come back up after a reboot. Opt-in. */
    var autostart: Boolean
        get() = p.getBoolean(KEY_AUTOSTART, false)
        set(v) = p.edit().putBoolean(KEY_AUTOSTART, v).apply()

    /**
     * Whether the server was deliberately running when it last stopped.
     *
     * Installing a new build stops the service, and silently leaving the server down afterwards is
     * the wrong behaviour -- for an appliance and for development alike. Set on a successful start,
     * cleared only on an explicit stop, so a crash or an update still counts as "should be running".
     */
    var wasRunning: Boolean
        get() = p.getBoolean(KEY_WAS_RUNNING, false)
        set(v) = p.edit().putBoolean(KEY_WAS_RUNNING, v).apply()

    /**
     * Which library this app browses.
     *
     * Null means "this phone" and resolves to loopback, so the viewer speaks the same HTTP API it
     * would to a remote server. One browsing code path, and the server phone becomes its own
     * integration test.
     */
    var serverUrl: String?
        get() = p.getString(KEY_SERVER_URL, null)
        set(v) = p.edit().putString(KEY_SERVER_URL, v?.trimEnd('/')).apply()

    /**
     * Every address the paired server said it answers on, in preference order.
     *
     * Stored as an ordered list rather than a set because order is the whole point: try the LAN
     * address before the Tailscale one, so being at home does not route photos through a VPN hop
     * for no reason.
     */
    var serverCandidates: List<String>
        get() = p.getString(KEY_CANDIDATES, "").orEmpty()
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        set(v) = p.edit().putString(KEY_CANDIDATES, v.joinToString("\n")).apply()

    /**
     * SHA-256 of the server's TLS certificate, learned from the pairing QR.
     *
     * Present means "trust this one certificate and nothing else". Absent means plain HTTP on a
     * trusted LAN, where there is no certificate to pin.
     */
    var serverFingerprint: String?
        get() = p.getString(KEY_FINGERPRINT, null)
        set(v) = p.edit().putString(KEY_FINGERPRINT, v?.lowercase()).apply()

    /** Token for a remote server. The local server uses [token] instead. */
    var remoteToken: String?
        get() = p.getString(KEY_REMOTE_TOKEN, null)
        set(v) = p.edit().putString(KEY_REMOTE_TOKEN, v).apply()

    /** Whether this phone uploads its own camera roll to the configured library. */
    var backupEnabled: Boolean
        get() = p.getBoolean(KEY_BACKUP, false)
        set(v) = p.edit().putBoolean(KEY_BACKUP, v).apply()

    /** A camera roll is measured in gigabytes; cellular is off by default for a reason. */
    var backupWifiOnly: Boolean
        get() = p.getBoolean(KEY_BACKUP_WIFI, true)
        set(v) = p.edit().putBoolean(KEY_BACKUP_WIFI, v).apply()

    var backupWhileChargingOnly: Boolean
        get() = p.getBoolean(KEY_BACKUP_CHARGING, false)
        set(v) = p.edit().putBoolean(KEY_BACKUP_CHARGING, v).apply()

    /**
     * MediaStore bucket ids to back up.
     *
     * Null means the user has never chosen, which is deliberately distinct from an empty set:
     * "not configured yet" gets sensible defaults, whereas "explicitly nothing" must be obeyed.
     * Storing ids rather than names means renaming a folder does not silently change what syncs.
     */
    var backupBuckets: Set<String>?
        get() = if (p.contains(KEY_BUCKETS)) p.getStringSet(KEY_BUCKETS, emptySet())?.toSet() else null
        set(v) {
            val e = p.edit()
            if (v == null) e.remove(KEY_BUCKETS) else e.putStringSet(KEY_BUCKETS, v)
            e.apply()
        }

    /** Whether the first-launch introduction and permission request has been shown. */
    var onboarded: Boolean
        get() = p.getBoolean(KEY_ONBOARDED, false)
        set(v) = p.edit().putBoolean(KEY_ONBOARDED, v).apply()

    /**
     * MediaStore ids queued by an explicit "back up these" action.
     *
     * Held in preferences rather than passed through WorkManager's input Data, which is capped at
     * roughly 10 KB -- a few thousand selected photos would silently overflow it. Cleared by the
     * worker once the run completes.
     */
    var pendingManualIds: Set<String>
        get() = p.getStringSet(KEY_MANUAL_IDS, emptySet())?.toSet() ?: emptySet()
        set(v) = p.edit().putStringSet(KEY_MANUAL_IDS, v).apply()

    /**
     * Whether the one-time capture-date repair has run.
     *
     * Early uploads were indexed from the stored file alone, whose mtime is the moment it was
     * written, so their dates all collapsed onto the upload time. The repair offers MediaStore's
     * real DATE_TAKEN for items already on the server. It is one-shot because otherwise every run
     * would re-offer dates for an entire camera roll that is already correct.
     */
    var dateRepairDone: Boolean
        get() = p.getBoolean(KEY_DATE_REPAIR, false)
        set(v) = p.edit().putBoolean(KEY_DATE_REPAIR, v).apply()

    /** Base URL of whatever library this app is pointed at. */
    fun baseUrl(): String = serverUrl ?: "http://127.0.0.1:$port"

    /** The credential for [baseUrl]. */
    fun activeToken(): String = if (serverUrl == null) token() else remoteToken.orEmpty()

    val isLocalLibrary: Boolean get() = serverUrl == null

    /**
     * The access token, generated once and reused so pairing survives restarts.
     *
     * Stored in plaintext because the app has to *display* it for pairing, and a hash cannot be
     * displayed. That is a deliberate M2 simplification: at M6 this becomes short-lived pairing
     * codes that mint per-device tokens, where only the SHA-256 of each token is kept.
     */
    fun token(): String {
        p.getString(KEY_TOKEN, null)?.let { return it }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val fresh = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        p.edit().putString(KEY_TOKEN, fresh).apply()
        return fresh
    }

    fun regenerateToken(): String {
        p.edit().remove(KEY_TOKEN).apply()
        return token()
    }

    private companion object {
        const val KEY_BACKEND = "backend"
        const val KEY_TREE = "treeUri"
        const val KEY_PORT = "port"
        const val KEY_AUTOSTART = "autostart"
        const val KEY_WAS_RUNNING = "wasRunning"
        const val KEY_TOKEN = "token"
        const val KEY_SERVER_URL = "serverUrl"
        const val KEY_REMOTE_TOKEN = "remoteToken"
        const val KEY_CANDIDATES = "serverCandidates"
        const val KEY_FINGERPRINT = "serverFingerprint"
        const val KEY_BACKUP = "backupEnabled"
        const val KEY_BACKUP_WIFI = "backupWifiOnly"
        const val KEY_BACKUP_CHARGING = "backupChargingOnly"
        const val KEY_DATE_REPAIR = "dateRepairDone"
        const val KEY_BUCKETS = "backupBuckets"
        const val KEY_MANUAL_IDS = "pendingManualIds"
        const val KEY_ONBOARDED = "onboarded"
        const val KEY_REMOTE_ACCESS = "remoteAccess"
    }
}
