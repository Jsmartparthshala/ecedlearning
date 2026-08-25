package np.com.jagdamba.eced.tv

import android.os.Bundle
import android.view.KeyEvent
import androidx.fragment.app.FragmentActivity

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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        rail.handleKey(event) || super.dispatchKeyEvent(event)

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

    /** Called by PairingFragment once a token lands. */
    fun onPaired() {
        rail.setVisible(true)
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_container, BrowseFragment())
            .commit()
        rail.deferFocusToContent()
    }
}
