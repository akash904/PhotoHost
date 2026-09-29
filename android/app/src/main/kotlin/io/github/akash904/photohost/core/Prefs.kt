package io.github.akash904.photohost.core

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.serialization.json.Json
import java.security.SecureRandom

/**
 * What this phone has been told it is.
 *
 * [UNSET] is a real answer and not a placeholder for [HOST]. "No server address configured" is true
 * both of a phone that holds the library and of one that has never been opened, and treating those
 * as the same thing means a brand new phone gets arranged around a server it has not agreed to run.
 */
enum class DeviceRole { UNSET, HOST, VIEWER }

/**
 * The little shared settings the UI and the foreground service both need. The service starts in
 * its own process lifecycle -- possibly from BOOT_COMPLETED with no Activity ever created -- so it
 * cannot ask a ViewModel which backend to use. It reads it from here.
 */
class Prefs(context: Context) {

    private val p = context.applicationContext.getSharedPreferences("photohost", Context.MODE_PRIVATE)

    var backend: String
        get() = p.getString(KEY_BACKEND, "INTERNAL") ?: "INTERNAL"
        set(v) = p.edit().putString(KEY_BACKEND, v).apply()

    var treeUri: Uri?
        get() = p.getString(KEY_TREE, null)?.let(Uri::parse)
        set(v) = p.edit().putString(KEY_TREE, v?.toString()).apply()

    /**
     * Where a library move is headed, while one is unfinished: "INTERNAL", or "SAF" plus a tree URI.
     *
     * Persisted rather than held in memory because a move is done file by file, and until it ends
     * the library is split between two places. If the process dies halfway, this is what says so:
     * the server refuses to start and the Storage card offers to resume, instead of serving a
     * library with half its files unreachable.
     */
    val moveTarget: Pair<String, Uri?>?
        get() = p.getString(KEY_MOVE_BACKEND, null)?.let { it to p.getString(KEY_MOVE_TREE, null)?.let(Uri::parse) }

    fun beginMove(backend: String, tree: Uri?) {
        p.edit().putString(KEY_MOVE_BACKEND, backend).putString(KEY_MOVE_TREE, tree?.toString()).commit()
    }

    /** Drops a move that never started moving anything. */
    fun cancelMove() {
        p.edit().remove(KEY_MOVE_BACKEND).remove(KEY_MOVE_TREE).commit()
    }

    /** Switches to the move's destination and clears it, in one write, so neither can happen alone. */
    fun finishMove() {
        val (backend, tree) = moveTarget ?: return
        p.edit()
            .putString(KEY_BACKEND, backend)
            .apply { if (tree != null) putString(KEY_TREE, tree.toString()) }
            .remove(KEY_MOVE_BACKEND)
            .remove(KEY_MOVE_TREE)
            .commit()
    }

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

    // ---------------------------------------------------------------- libraries

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The libraries this phone has been paired with, not counting its own.
     *
     * Written with commit() rather than apply(): the backup worker and the foreground service read
     * these from other threads, possibly the moment a pairing finishes, and a list still sitting in
     * apply()'s write-behind queue is a list they cannot see.
     */
    fun remoteLibraries(): List<LibraryProfile> =
        p.getString(KEY_LIBRARIES, null)
            ?.let { raw -> runCatching { json.decodeFromString<List<LibraryProfile>>(raw) }.getOrNull() }
            .orEmpty()

    private fun saveRemote(list: List<LibraryProfile>) {
        p.edit().putString(KEY_LIBRARIES, json.encodeToString(list)).commit()
    }

    /** Whether this phone keeps a library of its own, which then appears as "This phone". */
    fun hostsLibrary(): Boolean = role == DeviceRole.HOST

    fun localLibrary(): Library = Library(Library.LOCAL_ID, "This phone", null, token(), null, emptyList())

    /** Every library the switcher offers: this phone's first when it has one, then paired ones. */
    fun libraries(): List<Library> = buildList {
        if (hostsLibrary()) add(localLibrary())
        remoteLibraries().forEach { add(it.toLibrary()) }
    }

    /** The library on screen. */
    var activeLibraryId: String
        get() = p.getString(KEY_ACTIVE_LIBRARY, Library.LOCAL_ID) ?: Library.LOCAL_ID
        set(v) {
            p.edit().putString(KEY_ACTIVE_LIBRARY, v).commit()
        }

    /**
     * Where this phone's camera roll is backed up to. Deliberately separate from [activeLibraryId]:
     * looking at the archive on the PC must not quietly redirect the phone's backup there, and
     * backing up to the PC must not stop you browsing the phone's own library.
     */
    var backupLibraryId: String?
        get() = p.getString(KEY_BACKUP_LIBRARY, null)
        set(v) {
            p.edit().putString(KEY_BACKUP_LIBRARY, v).commit()
        }

    fun activeLibrary(): Library =
        resolve(activeLibraryId)
            ?: remoteLibraries().firstOrNull()?.toLibrary()
            ?: localLibrary()

    /** Null when no backup target is set, or the one set has been removed. */
    fun backupLibrary(): Library? = backupLibraryId?.let(::resolve)

    fun library(id: String): Library? = resolve(id)

    private fun resolve(id: String): Library? =
        if (id == Library.LOCAL_ID) localLibrary() else remoteLibraries().firstOrNull { it.id == id }?.toLibrary()

    private fun LibraryProfile.toLibrary() = Library(id, name, url, token, fingerprint, candidates)

    /**
     * Records a pairing and returns the library it belongs to.
     *
     * Scanning the same library again -- recognised by its certificate, or by its address when it
     * has none -- updates that entry instead of adding a second one, so re-pairing after a token or
     * address change keeps the library's name and its place as the backup target.
     */
    fun addOrUpdateLibrary(url: String, token: String, fingerprint: String?, candidates: List<String>): LibraryProfile {
        val clean = url.trimEnd('/')
        val pin = fingerprint?.lowercase()
        val list = remoteLibraries().toMutableList()
        val i = list.indexOfFirst { (pin != null && it.fingerprint == pin) || it.url == clean }
        val profile = if (i >= 0) {
            list[i].copy(url = clean, token = token, fingerprint = pin, candidates = candidates).also { list[i] = it }
        } else {
            LibraryProfile(
                id = java.util.UUID.randomUUID().toString(),
                name = defaultName(clean),
                url = clean,
                token = token,
                fingerprint = pin,
                candidates = candidates,
                addedAt = System.currentTimeMillis(),
            ).also { list += it }
        }
        saveRemote(list)
        return profile
    }

    fun renameLibrary(id: String, name: String) {
        if (name.isBlank()) return
        saveRemote(remoteLibraries().map { if (it.id == id) it.copy(name = name.trim()) else it })
    }

    /**
     * Forgets a paired library. Nothing on the server is touched: this phone just stops knowing the
     * way there. If it was on screen, the next one takes its place; if it was the backup target,
     * backup stops rather than silently switching to some other library.
     */
    fun removeLibrary(id: String) {
        if (id == Library.LOCAL_ID) return
        saveRemote(remoteLibraries().filterNot { it.id == id })
        if (activeLibraryId == id) activeLibraryId = libraries().firstOrNull()?.id ?: Library.LOCAL_ID
        if (backupLibraryId == id) backupLibraryId = null
    }

    /** What the server says its other addresses are, for the library that said it. */
    fun updateCandidates(id: String, urls: List<String>) {
        if (id == Library.LOCAL_ID) return
        saveRemote(remoteLibraries().map { if (it.id == id) it.copy(candidates = urls) else it })
    }

    /** "Library at 192.168.1.20" until the server says what it is, or the user names it. */
    fun defaultName(url: String): String =
        "Library at " + runCatching { java.net.URI(url).host }.getOrNull().orEmpty().trim('[', ']').ifEmpty { url }

    /**
     * Moves the single library an older install knew about into the list, once.
     *
     * Before there was a list there was one set of keys: an address, its candidates, a pin and a
     * token. Those become the first paired library, on screen and as the backup target -- exactly
     * where the app was pointing -- so upgrading changes nothing anyone can see. The old keys are
     * left in place and unused, so installing the previous build again still finds its settings.
     */
    fun migrateLibrariesIfNeeded() {
        if (p.contains(KEY_LIBRARIES)) return
        val legacyUrl = p.getString(KEY_SERVER_URL, null)?.trimEnd('/')
        if (legacyUrl == null) {
            saveRemote(emptyList())
            activeLibraryId = Library.LOCAL_ID
            // Backup used to go to whatever was browsed: with no server set, this phone's own.
            backupLibraryId = Library.LOCAL_ID
            return
        }
        val profile = LibraryProfile(
            id = java.util.UUID.randomUUID().toString(),
            name = defaultName(legacyUrl),
            url = legacyUrl,
            token = p.getString(KEY_REMOTE_TOKEN, null).orEmpty(),
            fingerprint = p.getString(KEY_FINGERPRINT, null)?.lowercase(),
            candidates = p.getString(KEY_CANDIDATES, "").orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() },
            addedAt = System.currentTimeMillis(),
        )
        saveRemote(listOf(profile))
        activeLibraryId = profile.id
        backupLibraryId = profile.id
    }

    // Views of the active library, kept so the many readers written before there was a list still
    // read what they meant: "the library on screen".

    /** The active library's address; null means this phone, over loopback. */
    val serverUrl: String? get() = activeLibrary().url

    /** Every address the active library advertised, in preference order (LAN before Tailscale). */
    val serverCandidates: List<String> get() = activeLibrary().candidates

    /** The active library's certificate pin. Present means "trust this certificate and nothing else". */
    val serverFingerprint: String? get() = activeLibrary().fingerprint

    /** Whether this phone uploads its own camera roll to the configured library. */
    var backupEnabled: Boolean
        get() = p.getBoolean(KEY_BACKUP, false)
        set(v) = p.edit().putBoolean(KEY_BACKUP, v).apply()

    /**
     * The library that this phone's "already sent" records were confirmed against.
     *
     * Those records (source fingerprints) are keyed on the photo, not on a library, so on their
     * own they cannot tell "sent to the PC" from "sent to the Note 10". Backup trusts them only
     * while this matches its current target. Null, as on every phone before this existed, means
     * "not confirmed against anything": the next run checks everything with the target once.
     */
    var backupRecordsLibraryId: String?
        get() = p.getString(KEY_BACKUP_RECORDS_LIBRARY, null)
        set(v) = p.edit().putString(KEY_BACKUP_RECORDS_LIBRARY, v).apply()

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

    /**
     * Whether this phone holds the library, browses someone else's, or has not said yet.
     *
     * Kept as its own preference rather than inferred from [serverUrl], because the absence of a
     * server address means two different things and only one of them is "this phone is the
     * library".
     *
     * Installs made before this existed have no stored value, so one is worked out from what they
     * already did: a phone with a server address configured was browsing, and a phone that has been
     * through onboarding without one was hosting. Only a phone that has neither is genuinely
     * undecided, which is exactly a fresh install. The inference is not written back, so the first
     * explicit choice replaces it permanently.
     */
    var role: DeviceRole
        get() = p.getString(KEY_ROLE, null)
            ?.let { runCatching { DeviceRole.valueOf(it) }.getOrDefault(DeviceRole.UNSET) }
            ?: DeviceRole.UNSET
        set(v) = p.edit().putString(KEY_ROLE, v.name).apply()

    /**
     * Works out a role for installs made before [role] existed, once, and writes it down.
     *
     * Deliberately not a fallback inside the getter. The inputs it reads keep changing while the
     * app runs, and one of them changes almost immediately: the first-run dialog sets [onboarded]
     * before anybody has chosen anything, so a getter that re-derives would turn a brand new phone
     * into a host the moment that dialog was dismissed. Evaluating once, at container construction
     * and therefore before any of that, is what makes the answer mean what it says.
     *
     * A phone with a server address configured was browsing. A phone that had already been through
     * onboarding at this point, without one, was hosting. A phone with neither has genuinely not
     * decided, which is exactly a fresh install.
     */
    fun migrateRoleIfUnrecorded() {
        if (p.contains(KEY_ROLE)) return
        role = when {
            serverUrl != null -> DeviceRole.VIEWER
            onboarded -> DeviceRole.HOST
            else -> DeviceRole.UNSET
        }
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

    /** Base URL of the library on screen. */
    fun baseUrl(): String = activeLibrary().baseUrl(port)

    /** The credential for [baseUrl]. */
    fun activeToken(): String = activeLibrary().token

    val isLocalLibrary: Boolean get() = activeLibrary().isLocal

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
        const val KEY_MOVE_BACKEND = "moveTargetBackend"
        const val KEY_MOVE_TREE = "moveTargetTree"
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
        const val KEY_ROLE = "deviceRole"
        const val KEY_REMOTE_ACCESS = "remoteAccess"
        const val KEY_LIBRARIES = "libraries"
        const val KEY_ACTIVE_LIBRARY = "activeLibrary"
        const val KEY_BACKUP_LIBRARY = "backupLibrary"
        const val KEY_BACKUP_RECORDS_LIBRARY = "backupRecordsLibrary"
    }
}
