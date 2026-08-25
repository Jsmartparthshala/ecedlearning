package np.com.jagdamba.eced.tv

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.leanback.app.BrowseSupportFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Device settings, shown as a page inside the browse sidebar.
 *
 * This screen is where the reverse-provisioning story becomes visible: the
 * hardware identity, which school claimed it, and a session that does not expire
 * for ten years. On a product with no login screen, this is the only place a
 * teacher or an inspector can see what the device actually is.
 *
 * Implements [BrowseSupportFragment.MainFragmentAdapterProvider] because Leanback
 * will not host a fragment inside a PageRow otherwise.
 */
class SettingsFragment : Fragment(), BrowseSupportFragment.MainFragmentAdapterProvider {

    private val mainFragmentAdapter = BrowseSupportFragment.MainFragmentAdapter(this)

    override fun getMainFragmentAdapter() = mainFragmentAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val devices = EcedApp.instance.devices

        view.bind(R.id.set_uuid, devices.hardwareUuid().uppercase())
        view.bind(R.id.set_code, devices.pairingCode())
        view.bind(R.id.set_school, devices.cachedSchoolName() ?: "—")
        view.bind(R.id.set_version, "v" + BuildConfig.VERSION_NAME)

        view.bind(
            R.id.set_state,
            getString(if (devices.isPaired) R.string.set_authenticated else R.string.set_waiting)
        )
        view.bind(R.id.set_expires, devices.cachedExpiry()?.take(10) ?: "—")

        // Offline caching is not built yet. Saying so plainly beats showing a
        // confident "0 MB" that implies a feature which does not exist.
        view.bind(R.id.set_storage, getString(R.string.set_storage_none))
        view.bind(R.id.set_codec, getString(R.string.set_codec_value))
        view.bind(R.id.set_channel, getString(R.string.set_channel_value))

        lifecycleScope.launch {
            val release = EcedApp.instance.catalog.latestRelease()
            view.bind(
                R.id.set_latest,
                release?.let { "v${it.versionName}" } ?: "—"
            )
        }
    }

    private fun View.bind(id: Int, value: String) {
        findViewById<TextView>(id)?.text = value
    }
}
