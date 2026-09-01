package np.com.jagdamba.eced.tv

import android.os.Bundle
import android.view.KeyEvent
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.os.SystemClock

/**
 * Single-activity host for the hero screen. Which fragment shows is decided
 * entirely by whether a session token is cached — there is no login screen
 * anywhere in this app by design (see "reverse provisioning" in the plan). The
 * school never types anything.
 *
 * The utility rail is [NavRail], shared with [PageActivity] so Videos, Profile,
 * Downloads and Settings behave as peers wherever a teacher happens to be.
 */
class MainActivity : FragmentActivity() {

    private lateinit var rail: NavRail

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // null: this screen *is* Videos.
        rail = NavRail(this, R.id.main_container, current = null)
        rail.attach()

        if (savedInstanceState == null) showInitialFragment()
    }

    override fun onStart() {
        super.onStart()
        checkStillActivated()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        rail.handleKey(event) || super.dispatchKeyEvent(event)

    /**
     * Ask the server whether this television is still activated, and return it to
     * the pairing screen if it is not.
     *
     * Without this, "Revoke" in the ops console is decorative. It marks the
     * session revoked and the television never finds out: the token sits in local
     * storage with a ten year expiry and nothing re-reads it, so a revoked set
     * keeps playing and keeps writing progress until somebody clears its data by
     * hand. The other half of the same problem was the unpair button, fixed in
     * 0005_release_device.sql.
     *
     * Only an explicit false logs the set out. A null means the server could not
     * be reached, and a school with a bad link must not lose its television over
     * it - see DeviceRepository.sessionLive.
     *
     * Throttled because onStart fires on every return from the player and from
     * Settings, and this question does not need answering that often. Boot is the
     * case that matters and boot is never throttled: the clock starts at zero.
     */
    private fun checkStillActivated() {
        if (!EcedApp.instance.devices.isPaired) return

        val now = SystemClock.elapsedRealtime()
        if (lastCheck != 0L && now - lastCheck < CHECK_INTERVAL_MS) return
        lastCheck = now

        lifecycleScope.launch {
            if (EcedApp.instance.devices.sessionLive() == false) {
                EcedApp.instance.devices.factoryReset()
                returnToPairing()
            }
        }
    }

    private fun showInitialFragment() {
        val paired = EcedApp.instance.devices.isPaired
        rail.setVisible(paired)
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_container, if (paired) BrowseFragment() else PairingFragment())
            .commit()
        // The catalogue is a network read, so the browse rows do not exist yet
        // and the rail would otherwise win the window's first focus pass.
        if (paired) rail.deferFocusToContent()
    }

    /**
     * Back to the pairing screen after the server said this set is revoked.
     *
     * commitAllowingStateLoss because this arrives from a network call that can
     * land after the activity has been stopped - a teacher switching to live TV
     * mid-check. Losing this transaction is harmless: the token is already gone
     * from local storage, so the next start shows the pairing screen anyway. A
     * crash there would not be harmless.
     */
    private fun returnToPairing() {
        if (isFinishing || isDestroyed) return
        rail.setVisible(false)
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_container, PairingFragment())
            .commitAllowingStateLoss()
    }

    /**
     * When the last check ran, on the monotonic clock. Zero means never, which is
     * why a cold start always checks.
     */
    private var lastCheck = 0L

    /** Called by PairingFragment once a token lands. */
    fun onPaired() {
        lastCheck = SystemClock.elapsedRealtime()
        rail.setVisible(true)
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_container, BrowseFragment())
            .commit()
        rail.deferFocusToContent()
    }

    private companion object {
        /**
         * Ten minutes. Long enough that navigating around the app costs nothing,
         * short enough that a television revoked from the office is off within a
         * lesson rather than within a term.
         */
        const val CHECK_INTERVAL_MS = 10 * 60 * 1000L
    }
}
