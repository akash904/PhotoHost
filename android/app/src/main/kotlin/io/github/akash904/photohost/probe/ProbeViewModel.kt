package io.github.akash904.photohost.probe

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.akash904.photohost.core.Prefs
import io.github.akash904.photohost.service.MediaServerService
import io.github.akash904.photohost.service.ServerState
import io.github.akash904.photohost.storage.InternalStore
import io.github.akash904.photohost.storage.LibraryStore
import io.github.akash904.photohost.storage.SafStore
import io.github.akash904.photohost.storage.StoreKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MAX_LINES = 4000

class ProbeViewModel(app: Application) : AndroidViewModel(app) {

    // Declared before init{}: property initialisers run in declaration order, and init{} logs.
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    // The same Prefs the service reads. They must agree on the backend, or the UI would probe one
    // store while the server served another.
    private val prefs = Prefs(app)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val _treeUri = MutableStateFlow(prefs.treeUri)
    val treeUri: StateFlow<Uri?> = _treeUri.asStateFlow()

    private val _backend = MutableStateFlow(
        runCatching { StoreKind.valueOf(prefs.backend) }.getOrDefault(StoreKind.INTERNAL),
    )
    val backend: StateFlow<StoreKind> = _backend.asStateFlow()

    private val _autostart = MutableStateFlow(prefs.autostart)
    val autostart: StateFlow<Boolean> = _autostart.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    val server: StateFlow<ServerState.Snapshot> = ServerState.state

    private var job: Job? = null

    init {
        log("Backend: ${_backend.value}")
        if (_backend.value == StoreKind.INTERNAL) {
            log("Library root: ${InternalStore(app).root.absolutePath}")
            log("Push files with:")
            log("  adb push <file> /sdcard/Android/data/io.github.akash904.photohost/files/library/")
            log("Note: app-private, so it is removed on uninstall. Fine for development.")
        } else {
            val saved = _treeUri.value
            if (saved == null) log("USB backend selected but no drive picked. Tap \"Pick drive\".")
            else log("Restored tree: $saved")
        }
    }

    // ------------------------------------------------------------------ server

    fun startServer() {
        MediaServerService.start(getApplication())
        log("")
        log("Starting server on port ${prefs.port} with backend ${_backend.value} ...")
        log("Pair a browser by opening  http://<phone-ip>:${prefs.port}/pair?c=${prefs.token()}")
        log("That sets an HttpOnly cookie so <img> and <video> carry no token in their URLs.")
    }

    fun stopServer() {
        MediaServerService.stop(getApplication())
        log("Stopping server ...")
    }

    fun setAutostart(enabled: Boolean) {
        prefs.autostart = enabled
        _autostart.value = enabled
        log("Autostart on boot: $enabled")
    }

    fun token(): String = prefs.token()

    // ------------------------------------------------------------------ backend

    fun setBackend(kind: StoreKind) {
        if (_running.value) {
            log("Cannot switch backend while a probe is running.")
            return
        }
        _backend.value = kind
        prefs.backend = kind.name
        log("")
        log("Backend -> $kind")
        if (kind == StoreKind.SAF && _treeUri.value == null) log("No drive picked yet. Tap \"Pick drive\".")
        if (server.value.running) log("Restart the server for this to take effect.")
    }

    /**
     * Persists the grant. Without takePersistableUriPermission the grant dies with the process,
     * so every reboot would need the drive re-picked by hand.
     */
    fun onTreeGranted(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(uri, flags)
        } catch (t: Throwable) {
            log("takePersistableUriPermission FAILED: ${t.javaClass.simpleName}: ${t.message}")
        }
        prefs.treeUri = uri
        _treeUri.value = uri
        setBackend(StoreKind.SAF)
        log("Picked $uri")
    }

    // ------------------------------------------------------------------ probes

    fun clear() {
        _lines.value = emptyList()
    }

    fun cancel() {
        job?.cancel()
        log("cancelled")
    }

    fun runAll() = launchProbe { runner ->
        runner.backendInfo()
        val survey = runner.survey()
        val big = survey.largest
        if (big != null) {
            runner.seek(big)
            runner.deterministicDocumentId(big)
        } else {
            log("")
            log("Skipping probes 2 and 4: no file found to test against.")
        }
        runner.capacity()
        survey.fattestDirRel?.let { runner.scanBenchmark(it) }
        runner.writeReadVerify(sizeMiB = 64)
        log("")
        log("=== Gate ===")
        if (_backend.value == StoreKind.INTERNAL) {
            log("Control run. Everything here should PASS; if it does not, the bug is in this code.")
            log("Only a run on the USB backend can clear M0.")
        } else {
            log("M0 passes only if probe 2 (seekability) and probe 4 (deterministic doc id) both")
            log("PASS and probe 3 sustains 15 MB/s or better. Also run the 256 MiB write and the")
            log("unplug drill before calling it done.")
        }
    }

    fun runWrite(sizeMiB: Int) = launchProbe { it.writeReadVerify(sizeMiB) }

    fun runUnplugDrill() = launchProbe { it.unplugDrill() }

    fun saveLog() = viewModelScope.launch {
        val out = File(getApplication<Application>().filesDir, "m0-log.txt")
        withContext(Dispatchers.IO) { out.writeText(_lines.value.joinToString("\n")) }
        log("")
        log("Saved: adb shell run-as io.github.akash904.photohost cat files/m0-log.txt")
    }

    private fun currentStore(): LibraryStore? {
        val app = getApplication<Application>()
        return when (_backend.value) {
            StoreKind.INTERNAL -> InternalStore(app)
            StoreKind.SAF -> {
                val uri = _treeUri.value
                if (uri == null) {
                    log("Pick the drive first, or switch the backend to Internal.")
                    null
                } else {
                    SafStore(app, uri)
                }
            }
        }
    }

    private fun launchProbe(block: suspend (ProbeRunner) -> Unit) {
        if (_running.value) {
            log("Already running; cancel first.")
            return
        }
        val store = currentStore() ?: return
        job = viewModelScope.launch {
            _running.value = true
            try {
                val runner = ProbeRunner(getApplication<Application>(), store, ::log)
                withContext(Dispatchers.IO) { block(runner) }
            } catch (t: Throwable) {
                log("ABORTED: ${t.javaClass.name}: ${t.message}")
            } finally {
                _running.value = false
            }
        }
    }

    private fun log(line: String) {
        // Mirrored to logcat so a run can be read with `adb logcat -s gpic` without tapping Save.
        if (line.isNotEmpty()) Log.i("gpic", line)
        val stamped = if (line.isEmpty()) "" else "${clock.format(Date())}  $line"
        val next = _lines.value + stamped
        _lines.value = if (next.size > MAX_LINES) next.takeLast(MAX_LINES) else next
    }
}
