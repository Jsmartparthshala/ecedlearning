package np.com.jagdamba.eced.tv

import android.app.Activity
import android.content.Intent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView

/**
 * How long to wait for the content to produce something focusable before giving
 * up and handing focus to the rail. Long enough for a fragment to inflate, short
 * enough that a static page is never unresponsive for a noticeable moment.
 */
private const val FOCUS_SETTLE_MS = 700L

/**
 * The utility rail down the left: Videos, Profile, Downloads, Settings.
 *
 * Shared by every screen that shows it, because the alternative turned out to be
 * a trap - Profile opened as its own screen with no rail, so the only way out was
 * the system back button and nothing on screen said so. The four destinations are
 * peers now: from Profile you arrow left and go straight to Settings.
 *
 * Collapsed the rail is a strip of glyphs; focusing it expands it over the content
 * to show labels. It overlays rather than pushes because pushing would re-lay out
 * the whole browse screen on every entry and exit, which is real work on the
 * Mali-450 boxes this ships to.
 *
 * @param current the page this screen is showing, or null when it is the hero
 *        (Videos) screen. Used to mark the rail and to make re-selecting the
 *        current entry a no-op rather than a reload.
 */
class NavRail(
    private val activity: Activity,
    private val contentId: Int,
    private val current: String?,
) {

    private val root: ViewGroup = activity.findViewById(R.id.sidebar)

    private data class Entry(val viewId: Int, val icon: Int, val label: Int, val page: String?)

    private val entries = listOf(
        Entry(R.id.nav_videos, R.drawable.ic_nav_videos, R.string.nav_videos, null),
        Entry(R.id.nav_profile, R.drawable.ic_nav_profile, R.string.nav_profile, PageActivity.PAGE_PROFILE),
        Entry(R.id.nav_downloads, R.drawable.ic_nav_downloads, R.string.nav_downloads, PageActivity.PAGE_DOWNLOADS),
        Entry(R.id.nav_settings, R.drawable.ic_nav_settings, R.string.nav_settings, PageActivity.PAGE_SETTINGS),
    )

    /** The entry matching this screen, which stays marked while the rail is closed. */
    private val currentEntry get() = entries.first { it.page == current }

    private var released = false
    private var layoutWatcher: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var settleRunnable: Runnable? = null

    fun attach() {
        entries.forEach { entry ->
            val row = activity.findViewById<View>(entry.viewId)
            row.findViewById<ImageView>(R.id.si_glyph).setImageResource(entry.icon)
            row.findViewById<TextView>(R.id.si_label).text = activity.getString(entry.label)
            row.isSelected = entry.page == current

            row.setOnClickListener { go(entry) }
            // Expansion belongs to the rail, not to one row, so every row reports
            // focus and the rail decides from whether any of them still has it.
            row.setOnFocusChangeListener { _, _ -> row.post { sync() } }
        }
        sync()
    }

    /**
     * Gives the first focus to the content rather than to the rail.
     *
     * The window's initial focus pass runs the moment the activity is laid out,
     * which is long before the catalogue arrives - that is a network read. At
     * that instant the rail's four buttons are the only focusable views in the
     * entire window, so the rail won the pass, expanded because it had focus,
     * and the first thing a teacher saw was the menu sitting over the subject
     * they opened the television to reach. Every later symptom followed from
     * that one: arrowing left appeared to do nothing (the rail already had
     * focus), and OK on a freshly launched screen fired Videos.
     *
     * Blocking the rail's descendants takes it out of that pass without making
     * it unreachable. Focus moves to the content as soon as the content has
     * anything to give it, and the rail is restored either way - a screen where
     * nothing at all can be focused is a dead television, and Profile and
     * Downloads are static text that will never report a focusable child.
     */
    fun deferFocusToContent() {
        val content = activity.findViewById<ViewGroup>(contentId)
        root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

        val watcher = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (content.hasFocusable()) settle(content)
            }
        }
        layoutWatcher = watcher
        content.viewTreeObserver.addOnGlobalLayoutListener(watcher)

        val backstop = Runnable { settle(content) }
        settleRunnable = backstop
        content.postDelayed(backstop, FOCUS_SETTLE_MS)
    }

    private fun settle(content: ViewGroup) {
        if (released) return
        release(content)
        // Content first, rail only as a fallback.
        if (!content.requestFocus()) focusCurrent()
    }

    /** Restores the rail to the normal focus order. Idempotent. */
    private fun release(content: ViewGroup?) {
        if (released) return
        released = true
        val target = content ?: activity.findViewById(contentId)
        layoutWatcher?.let { target?.viewTreeObserver?.removeOnGlobalLayoutListener(it) }
        settleRunnable?.let { target?.removeCallbacks(it) }
        layoutWatcher = null
        settleRunnable = null
        root.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
    }

    /** Puts focus on the entry this screen is already showing. */
    fun focusCurrent() {
        release(null)
        activity.findViewById<View>(currentEntry.viewId).requestFocus()
    }

    private fun go(entry: Entry) {
        if (entry.page == current) {
            // Already here. Hand focus back to the content, which collapses the
            // rail on the way out.
            activity.findViewById<View>(contentId).requestFocus()
            return
        }

        if (entry.page == null) {
            // Videos is the hero screen, which is always below this one rather
            // than beside it. Finishing returns to it instead of stacking a
            // second copy on top of the first.
            activity.finish()
            return
        }

        activity.startActivity(
            Intent(activity, PageActivity::class.java)
                .putExtra(PageActivity.EXTRA_PAGE, entry.page)
        )
        // Moving sideways between rail destinations must not deepen the back
        // stack, or backing out of Settings walks through every page visited.
        if (current != null) activity.finish()
    }

    /** Collapsed to glyphs unless the rail holds focus, in which case labels show. */
    private fun sync() {
        val expanded = root.hasFocus()
        val width = activity.resources.getDimensionPixelSize(
            if (expanded) R.dimen.sidebar_expanded_width else R.dimen.sidebar_collapsed_width
        )
        if (root.layoutParams.width != width) {
            root.layoutParams = root.layoutParams.apply { this.width = width }
            root.requestLayout()
        }
        entries.forEach { entry ->
            activity.findViewById<View>(entry.viewId)
                .findViewById<TextView>(R.id.si_label).visibility =
                if (expanded) View.VISIBLE else View.GONE
        }
    }

    /**
     * Left from the leftmost thing on screen opens the rail.
     *
     * The rail overlays the content rather than sitting beside it, so ordinary
     * focus search does not connect the two: from a card at the left edge,
     * `focusSearch(FOCUS_LEFT)` returns null rather than finding the rail. That
     * null is the signal - the teacher has run out of content to the left, which
     * is exactly when they mean the rail.
     *
     * @return true when the rail took the key.
     */
    fun handleKey(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_DPAD_LEFT ||
            event.action != KeyEvent.ACTION_DOWN ||
            root.visibility != View.VISIBLE ||
            root.hasFocus()
        ) return false

        // A null focus is not a reason to swallow the key. It means the content
        // has not produced anything focusable yet, or this page is static text -
        // and left still means "show me the menu" in both cases.
        val focused = activity.currentFocus
        if (focused != null && focused.focusSearch(View.FOCUS_LEFT) != null) return false

        // Opens on the entry already showing. A rail that opens pointing at some
        // other destination invites a reflexive OK and lands the teacher
        // somewhere they never asked for.
        focusCurrent()
        return true
    }

    /** Hidden on the pairing screen, which is one instruction and nothing else. */
    fun setVisible(visible: Boolean) {
        root.visibility = if (visible) View.VISIBLE else View.GONE
    }
}
