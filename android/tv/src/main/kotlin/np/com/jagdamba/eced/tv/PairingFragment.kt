package np.com.jagdamba.eced.tv

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The demo's opening beat: TV boots, shows a code, and waits. Someone in the central
 * office clicks one button and this screen changes by itself.
 *
 * DELIBERATE CHOICE: this polls every 2s rather than using Supabase Realtime.
 * Realtime is the "right" answer and the plan calls for it, but the websocket API
 * surface differs between supabase-kt versions and this code has never been
 * compiled. Polling cannot fail to work, is visually identical at demo speed, and
 * costs ~30 requests/minute against a 5 GB/month budget. Swap to Realtime after the
 * demo, not before it.
 */
class PairingFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_pairing, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val devices  = EcedApp.instance.devices
        val codeView = view.findViewById<TextView>(R.id.pairing_code)
        val status   = view.findViewById<TextView>(R.id.pairing_status)

        codeView.text = devices.pairingCode()

        viewLifecycleOwner.lifecycleScope.launch {
            val row = devices.register(appVersion = BuildConfig.VERSION_NAME)
            if (row?.id == null) {
                status.text = getString(R.string.offline)
                return@launch
            }

            status.text = getString(R.string.pairing_hint)

            while (isActive) {
                val session = devices.fetchSession(row.id!!)
                if (session != null) {
                    devices.cacheSession(session.token, session.expiresAt, row.id!!)
                    (activity as? MainActivity)?.onPaired()
                    return@launch
                }
                delay(2_000)
            }
        }
    }
}
