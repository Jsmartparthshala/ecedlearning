package np.com.jagdamba.eced.tv

import android.os.Bundle
import androidx.fragment.app.FragmentActivity

/**
 * Single-activity host. Which fragment shows is decided entirely by whether a
 * session token is cached — there is no login screen anywhere in this app by design
 * (see "reverse provisioning" in the plan). The school never types anything.
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) showInitialFragment()
    }

    private fun showInitialFragment() {
        val paired = EcedApp.instance.devices.isPaired
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_container, if (paired) BrowseFragment() else PairingFragment())
            .commit()
    }

    /** Called by PairingFragment once a token lands. */
    fun onPaired() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_container, BrowseFragment())
            .commit()
    }
}
