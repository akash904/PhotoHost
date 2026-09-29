package io.github.akash904.photohost.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.akash904.photohost.core.Prefs

/**
 * Brings the server back after a reboot or an app update, but only if autostart was enabled --
 * an unattended appliance that needs someone to tap a button after every power cut is not an
 * appliance.
 *
 * `MY_PACKAGE_REPLACED` matters as much as `BOOT_COMPLETED` during development: installing a new
 * build stops the service, and without this the server would silently stay down.
 *
 * On Android 15+ a BOOT_COMPLETED receiver may not start `dataSync`, `camera`, `mediaPlayback`,
 * `microphone`, `mediaProjection` or `phoneCall` foreground services. `specialUse` is not on that
 * list, which is the second reason for that service type. The start is still wrapped, because a
 * refusal here must not crash the boot broadcast -- M1's watchdog is the backstop.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = Prefs(context)
        val shouldStart = when (action) {
            Intent.ACTION_BOOT_COMPLETED -> prefs.autostart
            // An app update is not a request to shut the server down.
            else -> prefs.wasRunning || prefs.autostart
        }
        if (!shouldStart) {
            Log.i("photohost", "$action: not configured to start, staying down")
            return
        }
        try {
            MediaServerService.start(context)
            Log.i("photohost", "boot: service start requested ($action)")
        } catch (t: Throwable) {
            // ForegroundServiceStartNotAllowedException on newer platforms, among others.
            Log.e("photohost", "boot: could not start service", t)
        }
    }
}
