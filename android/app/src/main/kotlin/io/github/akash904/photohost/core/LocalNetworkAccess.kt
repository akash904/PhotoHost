package io.github.akash904.photohost.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Android's permission to talk to devices on the local network.
 *
 * From Android 17 (API 37), an app targeting it may not open connections to addresses on the
 * local network -- 192.168.x.x, 10.x.x.x and the like -- without this runtime permission. Nothing
 * says so: the connection simply never arrives, and the client sees a timeout. A Pixel 9 paired
 * with a library on the same Wi-Fi timed out on every request while Chrome on the same phone
 * loaded the library fine, because Chrome holds the permission and PhotoHost had never asked.
 * The Note phones it was developed on run Android 13, where the rule does not exist.
 *
 * Everything PhotoHost does is local-network traffic -- reaching a library, backing up to one,
 * serving one -- so it is asked for wherever those start, and its absence is named on the screen
 * that would otherwise blame the Wi-Fi.
 */
object LocalNetworkAccess {

    const val PERMISSION = Manifest.permission.ACCESS_LOCAL_NETWORK

    /** The first Android version that enforces it. */
    private const val ENFORCED_FROM_SDK = 37

    /** Whether this Android version enforces the permission at all. */
    fun applies(): Boolean = Build.VERSION.SDK_INT >= ENFORCED_FROM_SDK

    /** True when local-network access is available: granted, or not enforced here. */
    fun granted(context: Context): Boolean =
        !applies() || ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED
}
