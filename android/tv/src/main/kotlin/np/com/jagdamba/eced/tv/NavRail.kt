package np.com.jagdamba.eced.tv

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView

/**
 * How long to wait for the content to produce something focusable before giving
 * up and handing focus to the rail. Long enough for a fragment to inflate, short
 * enough that a static page is never unresponsive for a noticeable moment.
 */
private const val FOCUS_SETTLE_MS = 700L

/**
 * How long the rail takes to widen or narrow.
 *
 * Short enough to stay ahead of a second key press, long enough to read as the
 * panel sliding out from under the glyphs rather than the screen cutting to a
 * different layout. Only the rail's own width is animated, so the content beside
 * it measures from cache and the Mali-450 boxes have four rows to re-lay out.
 */
private const val RAIL_SLIDE_MS = 180L

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

    /**
     * Whether the rail holds focus because a teacher asked for it.
     *
     * Focus that lands on the rail without being asked for is a bug every time,
     * and it is the framework that does it: arriving at Profile or Downloads
     * from another rail destination, the outgoing screen finishes a beat after
     * the incoming one is laid out, and the focus pass that runs when the old
     * window goes away lands on a rail row. The page then sits there with the
     * menu open across it, pointing at whichever row geometry chose.
     *
     * Tracking the difference is what makes that recoverable: focus the rail
     * asked for is kept, focus it did not ask for is handed straight back.
     */
    private var opened = false

    private var widthAnimator: ValueAnimator? = null

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
            //
            // A row that gains focus nobody asked for gives it back rather than
            // expanding. Handing it to the content is the whole correction: the
            // rail no longer holds focus, so it collapses on the next sync of
            // its own accord.
            row.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && !opened && handBackToContent()) return@setOnFocusChangeListener
                row.post { sync() }
            }
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
        opened = true
        activity.findViewById<View>(currentEntry.viewId).requestFocus()
    }

    /**
     * Gives focus to the page, if the page will take it.
     *
     * False when it will not - a screen still waiting on the catalogue has
     * nothing focusable on it, and a television where no key does anything is
     * worse than one showing a menu it was not asked for. In that case the rail
     * keeps focus, which is the same fallback [settle] makes.
     */
    private fun handBackToContent(): Boolean =
        activity.findViewById<View>(contentId)?.requestFocus() == true

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
        // Closed is closed. Whatever opened it, the next thing to open it has to
        // ask again.
        if (!expanded) opened = false

        // Labels switch at the ends rather than fading: the panel slides out from
        // behind the glyph column and the labels are simply there once it has,
        // which is one movement instead of two competing ones.
        entries.forEach { entry ->
            activity.findViewById<View>(entry.viewId)
                .findViewById<TextView>(R.id.si_label).visibility =
                if (expanded) View.VISIBLE else View.GONE
        }

        val target = activity.resources.getDimensionPixelSize(
            if (expanded) R.dimen.sidebar_expanded_width else R.dimen.sidebar_collapsed_width
        )
        val from = root.layoutParams.width
        if (from == target) return

        // Interruptible on purpose - arrowing in and straight back out again
        // reverses from wherever the panel currently is rather than jumping to
        // the end of a movement the teacher has already changed their mind about.
        widthAnimator?.cancel()
        if (from <= 0) {
            setRailWidth(target)
            return
        }
        widthAnimator = ValueAnimator.ofInt(from, target).apply {
            duration = RAIL_SLIDE_MS
            interpolator = DecelerateInterpolator(2.5f)
            addUpdateListener { setRailWidth(it.animatedValue as Int) }
            start()
        }
    }

    private fun setRailWidth(px: Int) {
        root.layoutParams = root.layoutParams.apply { this.width = px }
        root.requestLayout()
    }

    /**
     * Left from the leftmost thing on screen opens the rail.
     *
     * The signal is that focus search finds no more content to the left. From a
     * card at the left edge of a row it returns null outright; from a plain
     * focusable container it returns a rail row, which is the same answer said
     * differently. Either way the teacher has run out of content, which is
     * exactly when they mean the rail.
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
        //
        // Finding the rail itself does not count as content either. Profile and
        // Downloads are prose, so their container is focusable in order to hold
        // focus at all, and from a plain container the focus search happily walks
        // left into the rail - the rail overlays the content but is still its
        // sibling. That answer is not null, so this used to hand the key back to
        // ordinary focus search, which picks a row by geometry: pressing left on
        // Downloads opened the menu pointing at Profile. Then one reflexive OK
        // went somewhere nobody asked for, or landed on the row already showing
        // and merely shut the menu again, which reads as the menu ignoring you.
        val focused = activity.currentFocus
        val next = focused?.focusSearch(View.FOCUS_LEFT)
        if (next != null && !inRail(next)) return false

        // Opens on the entry already showing. A rail that opens pointing at some
        // other destination invites a reflexive OK and lands the teacher
        // somewhere they never asked for.
        focusCurrent()
        return true
    }

    /** Whether a view is one of the rail's own, rather than page content. */
    private fun inRail(view: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v === root) return true
            v = v.parent as? View
        }
        return false
    }

    /** Hidden on the pairing screen, which is one instruction and nothing else. */
    fun setVisible(visible: Boolean) {
        root.visibility = if (visible) View.VISIBLE else View.GONE
    }
}
