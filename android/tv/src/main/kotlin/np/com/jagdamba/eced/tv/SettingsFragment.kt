package np.com.jagdamba.eced.tv

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import np.com.jagdamba.eced.core.model.AppRelease
import kotlinx.coroutines.launch

/**
 * Device settings, opened from the "This television" row on the home screen.
 *
 * This screen is where the reverse-provisioning story becomes visible: the
 * hardware identity, which school claimed it, and a session that does not expire
 * for ten years. On a product with no login screen, this is the only place a
 * teacher or an inspector can see what the device actually is.
 *
 */
class SettingsFragment : Fragment() {

    /** True while an update check or download is in flight. */
    private var checking = false

    /**
     * The release the confirmation panel is currently asking about. Held here
     * rather than captured in a lambda because the panel replaces this fragment's
     * container, and on a 1 GB box the process can be killed while the teacher is
     * still reading the question.
     */
    private var pendingRelease: AppRelease? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val devices = EcedApp.instance.devices

        // Registered before anything can post a result. The fragment manager
        // holds a result until this fragment is STARTED again, so an answer given
        // while the confirmation panel covered the screen still arrives.
        parentFragmentManager.setFragmentResultListener(KEY_UNPAIR, viewLifecycleOwner) { _, bundle ->
            if (bundle.getBoolean(ConfirmFragment.RESULT_CONFIRMED)) unpair()
        }
        parentFragmentManager.setFragmentResultListener(KEY_UPDATE, viewLifecycleOwner) { _, bundle ->
            val release = pendingRelease
            pendingRelease = null
            if (bundle.getBoolean(ConfirmFragment.RESULT_CONFIRMED) && release != null) {
                downloadAndInstall(release)
            }
        }

        view.bind(R.id.set_uuid, devices.hardwareUuid().uppercase())
        view.bind(R.id.set_code, devices.pairingCode())
        view.bind(R.id.set_school, devices.cachedSchoolName() ?: "—")
        view.bind(
            R.id.set_class,
            devices.cachedClassLabel()?.takeIf { it.isNotBlank() }
                ?: getString(R.string.set_class_none),
        )
        view.bind(R.id.set_version, "v" + BuildConfig.VERSION_NAME)
        view.bind(R.id.set_network, NetworkStatus.label(requireContext()))

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

        // Android has not allowed an app to change the Wi-Fi band, or switch Wi-Fi
        // on at all, since API 29. Handing over the system screen is the only
        // version of this control that actually does something.
        view.findViewById<TextView>(R.id.set_action_wifi).setOnClickListener {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
            // Some AOSP boxes ship no Wi-Fi settings activity at all; fall back to
            // the top-level settings rather than crashing on a missing component.
            if (intent.resolveActivity(requireContext().packageManager) != null) {
                startActivity(intent)
            } else {
                runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            }
        }

        view.findViewById<TextView>(R.id.set_action_unpair).setOnClickListener {
            confirmUnpair()
        }

        view.findViewById<TextView>(R.id.set_action_update).setOnClickListener {
            checkForUpdate()
        }

        lifecycleScope.launch {
            val release = EcedApp.instance.catalog.latestRelease()
            view.bind(
                R.id.set_latest,
                release?.let { "v${it.versionName}" } ?: "—"
            )
        }
    }

    /**
     * Manual update check, driven by the button rather than by a background poll.
     *
     * A school with a flaky link should be able to ask on demand instead of
     * waiting for a check it cannot see, and during an install visit the operator
     * needs a way to force one.
     */
    private fun checkForUpdate() {
        // A flag rather than `isEnabled = false`, because disabling the view that
        // currently holds focus makes Android drop focus to whatever is nearest -
        // the teacher presses OK and the highlight silently jumps to another card.
        // The guard still stops two checks racing to the same cache file, which
        // would leave a half-written APK for the installer to choke on.
        if (checking) return
        checking = true
        status(R.string.upd_checking)

        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = Updater.check()) {
                is Updater.Result.UpToDate -> status(R.string.upd_uptodate)
                is Updater.Result.Failed   -> status(R.string.upd_failed)

                is Updater.Result.NoDownload -> statusText(
                    getString(R.string.upd_no_download, result.release.versionName)
                )

                is Updater.Result.Available -> {
                    statusText(getString(R.string.upd_available_title))
                    offerUpdate(result.release)
                }
            }
            checking = false
        }
    }

    private fun offerUpdate(release: AppRelease) {
        val body = getString(
            // A mandatory release still asks. The alternative is a television
            // that reboots into an installer on its own during a lesson.
            if (release.mandatory) R.string.upd_available_body_forced
            else R.string.upd_available_body,
            release.versionName,
            BuildConfig.VERSION_NAME,
        )

        pendingRelease = release
        ConfirmFragment.show(
            fm = parentFragmentManager,
            containerId = R.id.page_root,
            requestKey = KEY_UPDATE,
            title = getString(R.string.upd_available_title),
            body = body,
            confirm = getString(R.string.upd_install),
            cancel = getString(R.string.upd_later),
            breadcrumb = getString(R.string.nav_settings),
        )
    }

    private fun downloadAndInstall(release: AppRelease) {
        val context = requireContext().applicationContext
        statusText(getString(R.string.upd_downloading_indeterminate))

        viewLifecycleOwner.lifecycleScope.launch {
            val apk = Updater.download(context, release) { fraction ->
                // The download runs on IO; touching a view from there crashes.
                val text = if (fraction < 0f) {
                    getString(R.string.upd_downloading_indeterminate)
                } else {
                    getString(R.string.upd_downloading, (fraction * 100).toInt())
                }
                view?.post { statusText(text) }
            }

            if (apk == null) {
                status(R.string.upd_download_failed)
                return@launch
            }

            status(R.string.upd_handoff)
            // From here the system installer owns the flow. Android 12+ always
            // shows its own confirmation and there is no way past it without
            // device-owner provisioning, which this product deliberately avoids.
            runCatching { Updater.install(context, apk) }
                .onFailure { status(R.string.upd_download_failed) }
        }
    }

    private fun status(res: Int) = statusText(getString(res))

    private fun statusText(text: String) {
        view?.findViewById<TextView>(R.id.set_update_status)?.apply {
            this.text = text
            visibility = View.VISIBLE
        }
    }

    /**
     * Unpairing is destructive and irreversible from the school's side - the
     * central office has to activate the television again - so it asks first.
     */
    private fun confirmUnpair() {
        ConfirmFragment.show(
            fm = parentFragmentManager,
            containerId = R.id.page_root,
            requestKey = KEY_UNPAIR,
            title = getString(R.string.unpair_title),
            body = getString(R.string.unpair_body),
            confirm = getString(R.string.unpair_confirm),
            cancel = getString(R.string.cancel),
            breadcrumb = getString(R.string.nav_settings),
        )
    }

    /**
     * Unpair for real.
     *
     * The old version cleared the local cache and restarted into the pairing
     * screen, which looked right for about two seconds - then the television
     * re-registered under the same hardware UUID, found the session nobody had
     * revoked, and paired itself straight back in. The button could not be made
     * to work by pressing it harder; it had never once logged a television out.
     *
     * The revoke now happens server side first, and the screen only changes if
     * it succeeded. Without a connection this reports that and stays paired,
     * which is the recoverable failure: a television that has forgotten its token
     * while the server still shows it claimed cannot be activated again, because
     * the code it displays is already spoken for.
     */
    private fun unpair() {
        status(R.string.unpair_working)

        viewLifecycleOwner.lifecycleScope.launch {
            if (!EcedApp.instance.devices.release()) {
                status(R.string.unpair_failed)
                return@launch
            }
            val intent = Intent(requireContext(), MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            requireActivity().finish()
        }
    }

    private fun View.bind(id: Int, value: String) {
        findViewById<TextView>(id)?.text = value
    }

    private companion object {
        const val KEY_UNPAIR = "confirm_unpair"
        const val KEY_UPDATE = "confirm_update"
    }
}
