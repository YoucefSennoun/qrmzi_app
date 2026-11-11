package tv.qrmzi.client.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper

/**
 * Small connectivity helper.
 *
 * The player uses it for one thing only: when a page fails because the box is
 * offline, show the right message and retry exactly once as soon as a network
 * comes back. Everything else keeps working the way it did.
 */
class NetworkMonitor(context: Context) {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    fun isOnline(): Boolean = try {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (t: Throwable) {
        true
    }

    /**
     * Calls [onAvailable] on the main thread when a usable network appears.
     * Returns an idempotent cancel function; registration failures are ignored
     * (the caller already has a working error screen).
     */
    fun watchAvailable(onAvailable: () -> Unit): () -> Unit {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return {}
        var fired = false
        var cancelled = false
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (fired || cancelled) return
                fired = true
                main.post { if (!cancelled) onAvailable() }
            }
        }
        val registered = try {
            cm.registerDefaultNetworkCallback(callback)
            true
        } catch (t: Throwable) {
            false
        }
        return {
            cancelled = true
            if (registered) {
                runCatching { cm.unregisterNetworkCallback(callback) }
            }
        }
    }
}
