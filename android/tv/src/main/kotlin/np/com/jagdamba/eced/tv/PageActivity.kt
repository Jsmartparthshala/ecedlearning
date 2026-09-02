package np.com.jagdamba.eced.tv

import android.os.Bundle
import android.view.KeyEvent
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.GuidedStepSupportFragment

/**
 * Host for Profile, Downloads and Settings.
 *
 * These were PageRow fragments inside the browse sidebar. With Leanback's sidebar
 * gone they are ordinary screens, which also deleted a whole class of layout bugs:
 * Leanback hosts a custom MainFragment inside `scale_frame`, which starts 238dp in
 * from the left but keeps the full screen width, so it overhangs the panel by the
 * same amount. Content rendered under the sidebar, and any `paddingEnd` landed off
 * the right edge of the television entirely.
 *
 * Carries the same [NavRail] as the hero screen, so none of these three is a dead
 * end reachable only by knowing the back button exists.
 */
class PageActivity : FragmentActivity() {

    private lateinit var rail: NavRail

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_page)

        val page = intent.getStringExtra(EXTRA_PAGE) ?: PAGE_DOWNLOADS

        rail = NavRail(this, R.id.page_container, current = page)
        rail.attach()

        findViewById<TextView>(R.id.page_title).text = getString(
            when (page) {
                PAGE_SETTINGS -> R.string.nav_settings
                PAGE_PROFILE  -> R.string.nav_profile
                PAGE_LEGAL    -> R.string.nav_legal
                else          -> R.string.nav_downloads
            }
        )

        // Only on a fresh start. On rotation or process restart the fragment
        // manager restores the fragment itself, and adding a second one would
        // stack two copies of the screen.
        if (savedInstanceState == null) {
            val fragment: Fragment = when (page) {
                PAGE_SETTINGS -> SettingsFragment()
                PAGE_PROFILE  -> ProfileFragment()
                PAGE_LEGAL    -> LegalFragment()
                else          -> DownloadsFragment()
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.page_container, fragment)
                .commit()
        }

        // Settings has focusable actions and should open on them; Profile and
        // Downloads are static, so focus falls back to the rail. Either way the
        // rail must not simply take it by default - see deferFocusToContent.
        rail.deferFocusToContent()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // A confirmation panel owns the whole screen and the whole remote for as
        // long as it is up. Without this guard the rail keeps answering LEFT
        // underneath it: the panel's actions are stacked vertically, so nothing
        // sits to their left, NavRail.handleKey reads that as "show me the menu"
        // and focus leaps out of the question onto a rail the teacher cannot see
        // over the panel. The remote then appears dead - which is the exact
        // symptom ConfirmFragment exists to remove.
        if (GuidedStepSupportFragment.getCurrentGuidedStepSupportFragment(supportFragmentManager) != null) {
            return super.dispatchKeyEvent(event)
        }

        // BACK inside an open legal document closes the document, not the screen.
        // The reader and the list are two states of one fragment rather than two
        // entries on a back stack, so without this the first BACK would leave the
        // screen from halfway down a privacy policy.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            val legal = supportFragmentManager.findFragmentById(R.id.page_container)
            if (legal is LegalFragment && legal.onBack()) return true
        }
        return rail.handleKey(event) || super.dispatchKeyEvent(event)
    }

    companion object {
        const val EXTRA_PAGE = "page"
        const val PAGE_DOWNLOADS = "downloads"
        const val PAGE_SETTINGS = "settings"
        const val PAGE_PROFILE = "profile"
        const val PAGE_LEGAL = "legal"
    }
}
