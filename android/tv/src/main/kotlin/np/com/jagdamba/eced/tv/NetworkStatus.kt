package np.com.jagdamba.eced.tv

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build

/**
 * What the television is actually connected through, including the Wi-Fi band.
 *
 * There is deliberately no "switch to 5 GHz" here, because no app can do that.
 * Android has not let an app choose a band, or even turn Wi-Fi on, since API 29 -
 * that is a system setting, and the honest control is a link to the system screen
 * (see the Settings action) rather than a switch that quietly does nothing.
 *
 * Reading the band is free: [NetworkCapabilities.getTransportInfo] carries the
 * WifiInfo from API 29 without needing location permission, and the deprecated
 * WifiManager path covers the older boxes.
 */
object NetworkStatus {

    /** 802.11 channel frequencies, in MHz, by band. */
    private val BAND_24 = 2400..2500
    private val BAND_5 = 4900..5899
    private val BAND_6 = 5900..7125

    fun label(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return context.getString(R.string.net_offline)
        val network = cm.activeNetwork ?: return context.getString(R.string.net_offline)
        val caps = cm.getNetworkCapabilities(network)
            ?: return context.getString(R.string.net_offline)

        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return context.getString(R.string.net_offline)
        }

        return when {
            // Checked first: many of the cheap boxes this ships to are wired, and
            // reporting "Wi-Fi" on an Ethernet install would be a lie a teacher
            // could catch.
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                context.getString(R.string.net_ethernet)

            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                context.getString(bandLabel(frequencyMhz(context, caps)))

            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                context.getString(R.string.net_cellular)

            else -> context.getString(R.string.net_wifi)
        }
    }

    private fun bandLabel(mhz: Int) = when (mhz) {
        in BAND_24 -> R.string.net_wifi_24
        in BAND_5 -> R.string.net_wifi_5
        in BAND_6 -> R.string.net_wifi_6
        // Unknown rather than guessed. A wrong band on screen is worse than none.
        else -> R.string.net_wifi
    }

    @Suppress("DEPRECATION")
    private fun frequencyMhz(context: Context, caps: NetworkCapabilities): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (caps.transportInfo as? WifiInfo)?.let { return it.frequency }
        }
        // Pre-Q, and the fallback when transportInfo is withheld. Deprecated, but
        // it is the only route on the older boxes and still works there.
        val wm = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return -1
        return runCatching { wm.connectionInfo?.frequency ?: -1 }.getOrDefault(-1)
    }
}
