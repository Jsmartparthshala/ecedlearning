package np.com.jagdamba.eced.tv

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.StatFs
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import np.com.jagdamba.eced.core.model.Lesson
import np.com.jagdamba.eced.core.model.Unit as CatalogUnit
import java.util.Locale

/**
 * Unit detail: a rail of unit tiles above a grid of lesson posters.
 *
 * This replaced two side by side lists. The lists were honest but wrong for the
 * medium. A teacher scanning from across a classroom recognises a coloured tile
 * with a number on it far faster than a line of text, and a grid fits ten lessons
 * in the space a list gave six.
 *
 * Two focus zones, which is the whole ergonomic argument. Left and right walk the
 * rail, down drops into the grid, up returns, back from the grid returns. The
 * rail is focus driven: arrowing onto a unit rewrites the header and swaps the
 * grid without pressing OK, so browsing the whole subject costs no clicks at all.
 *
 * Artwork is a flat subject colour with a poster frame over it where the lesson
 * has one. The frames are 320x180 and decode as RGB_565 - see [PosterLoader] for
 * why that budget holds on the 1 GB Mali-450 boxes this ships to. Every numeral
 * is still text rather than an image.
 */
class UnitActivity : FragmentActivity() {

    private lateinit var unitRail: RecyclerView
    private lateinit var lessonGrid: RecyclerView

    private val style by lazy {
        CardPresenter.SubjectStyle.of(
            intent.getStringExtra(EXTRA_COLOR_1),
            intent.getStringExtra(EXTRA_COLOR_2),
            intent.getStringExtra(EXTRA_SUBJECT),
        )
    }

    private var units: List<CatalogUnit> = emptyList()
    private var progress: Map<String, Float> = emptyMap()

    /**
     * The tile currently painted as selected. Held as a view rather than an index
     * because notifyItemChanged rebinds the row and steals focus mid press.
     */
    private var selectedTile: View? = null

    /**
     * In flight lesson load. Sweeping the rail fires one per tile; without this a
     * slow query for unit 3 can land after unit 7 and show the wrong grid.
     */
    private var lessonJob: Job? = null

    /**
     * Where the teacher actually is, as opposed to where they came in.
     *
     * onResume reloads this screen on every return from the player, to pick up
     * the progress bar the lesson just moved. That reload used to rebuild from
     * the intent, which names the unit the screen was *opened* with - so a
     * teacher who walked the rail to unit 5 and played a lesson was put back on
     * unit 1 by the act of watching it, every time, with no way to stop it
     * happening. On a remote that is four presses to undo, once per video.
     */
    private var currentUnitId: String? = null

    /** The lesson last focused in the grid, so a reload can put it back. */
    private var lastLessonId: String? = null

    /**
     * Set while returning from the player, so the reload restores the grid
     * instead of pulling focus up to the rail. Arriving fresh from the home
     * screen should still land on the rail - that is a different journey with a
     * different right answer.
     *
     * Simply *not* taking focus is not enough, which is what the first attempt at
     * this got wrong. The reload swaps both adapters, so the view that had focus
     * is destroyed; if nothing then claims focus, the framework awards it to the
     * first focusable thing in the window, which is rail tile one - and that
     * tile's focus listener rewrites the header and reloads the grid for unit
     * one. The teacher was put back on unit one by the framework instead of by
     * the intent, and the screen looked exactly as broken as before.
     */
    private var returningToGrid = false

    // setHasFixedSize on the two RecyclerViews below trips InvalidSetHasFixedSize,
    // and lint is wrong about both. The check asks whether the view is
    // wrap_content without asking which way it scrolls. The rail scrolls sideways
    // and is match_parent sideways; the grid scrolls downwards and is 0dp with
    // layout_weight, so its height comes from the parent and never from its
    // contents. Both are exactly the condition setHasFixedSize describes.
    //
    // The alternative to suppressing is to hardcode a dp height on the rail - a
    // real change to a television layout, made to quiet a warning about something
    // else. lintVitalRelease treats this as an error, so it is this or no release
    // build at all.
    @SuppressLint("InvalidSetHasFixedSize")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_unit)

        unitRail = findViewById(R.id.unit_rail)
        lessonGrid = findViewById(R.id.lesson_grid)

        val gap = resources.getDimensionPixelSize(R.dimen.grid_gap)

        unitRail.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        unitRail.addItemDecoration(GapDecoration(gap))

        lessonGrid.layoutManager = GridLayoutManager(this, GRID_COLUMNS)
        lessonGrid.addItemDecoration(GridGapDecoration(GRID_COLUMNS, gap))

        // Change animations cross fade the view being rebound, which on a remote
        // reads as the focus ring flickering. Nothing here animates by content.
        unitRail.itemAnimator = null
        lessonGrid.itemAnimator = null

        // Neither list changes size with its contents, so RecyclerView can skip a
        // full relayout every time an adapter is swapped.
        unitRail.setHasFixedSize(true)
        lessonGrid.setHasFixedSize(true)

        findViewById<TextView>(R.id.unit_crumb).text =
            intent.getStringExtra(EXTRA_SUBJECT) ?: getString(R.string.app_name)

        findViewById<TextView>(R.id.unit_storage_chip).text =
            getString(R.string.storage_free, freeSpace())

        load()
    }

    override fun onResume() {
        super.onResume()
        // Progress changes while the player is open; refresh on the way back.
        // `units` is empty only on the very first pass, when onCreate's load is
        // already running - so a non-empty list is what marks this as a return.
        if (units.isNotEmpty()) {
            returningToGrid = lastLessonId != null
            // Take the rail out of the running for focus until the grid has a
            // card to hand it to. Blocking it is what stops the framework
            // choosing tile one and undoing the whole journey; showLessons hands
            // it back the moment the card it wanted actually has focus.
            if (returningToGrid) {
                unitRail.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
            load()
        }
    }

    /**
     * Both ways out of the grid land on the unit the grid is showing.
     *
     * Back first: one press should undo one step, and dropping into the grid was
     * a step, so back returns to the rail rather than leaving the screen.
     *
     * Up is the same journey and used to end somewhere else entirely. Focus
     * search is geometric, so up from the fourth card went to whichever tile
     * happened to be overhead - and the rail is focus driven, so landing there
     * silently swapped the header and the whole grid to that unit. Walk to unit
     * two, drop in, press up: unit seven. The tile the grid belongs to is the
     * only correct answer, and it is already being tracked for back.
     *
     * Only from the top row. Anywhere else up means the row above, which is what
     * the grid itself already does.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val leavingGrid =
            (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) ||
            (event.keyCode == KeyEvent.KEYCODE_DPAD_UP &&
                event.action == KeyEvent.ACTION_DOWN && inTopRow())

        if (leavingGrid && lessonGrid.hasFocus()) {
            selectedTile?.requestFocus()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Is focus on the first row of the grid, with nothing above it but the rail? */
    private fun inTopRow(): Boolean {
        val focused = lessonGrid.findFocus() ?: return false
        val item = lessonGrid.findContainingItemView(focused) ?: return false
        return lessonGrid.getChildAdapterPosition(item) in 0 until GRID_COLUMNS
    }

    private fun load() {
        val subjectId = intent.getStringExtra(EXTRA_SUBJECT_ID) ?: return releaseRail()
        // The unit in hand wins over the one in the intent; the intent is only
        // the way in.
        val focusUnitId = currentUnitId ?: intent.getStringExtra(EXTRA_UNIT_ID)

        lifecycleScope.launch {
            val repo = EcedApp.instance.catalog
            units = repo.units(subjectId)

            val deviceId = EcedApp.instance.devices.cachedDeviceId()
            progress = deviceId?.let { id ->
                val saved = EcedApp.instance.progress.forDevice(id)
                val lessons = repo.lessonsByIds(saved.keys.toList())
                EcedApp.instance.progress.fractions(
                    saved, lessons.associate { it.id to (it.durationSec ?: 0) }
                )
            }.orEmpty()

            val startIndex = units.indexOfFirst { it.id == focusUnitId }.coerceAtLeast(0)
            unitRail.adapter = UnitRailAdapter(units)

            val first = units.getOrNull(startIndex) ?: return@launch releaseRail()

            // Read before showLessons, which clears it as soon as the grid has
            // taken focus - and can get there first when the lessons are cached.
            val comingBack = returningToGrid
            showLessons(first, immediate = true)

            // Focus the unit that was opened, not the first one, so arriving from a
            // card on the home screen lands where the teacher pointed.
            unitRail.scrollToPosition(startIndex)
            unitRail.post {
                val tile = unitRail.findViewHolderForAdapterPosition(startIndex)?.itemView
                    ?: return@post releaseRail()
                // Arriving: the rail is the right place to be standing. Coming
                // back: the tile is only marked, because focus belongs on the
                // card that was just watched and showLessons is putting it there.
                if (comingBack) select(tile) else tile.requestFocus()
            }
        }
    }

    /**
     * Point the header and the grid at [unit].
     *
     * @param immediate skip the settle delay. Sweeping the rail should not fire a
     *        query per tile, but the first load has nothing to debounce against.
     */
    private fun showLessons(unit: CatalogUnit, immediate: Boolean = false) {
        // Captured before the field moves: sweeping the rail lands on a new unit
        // and should start its grid at the top, but a reload of the unit already
        // on screen must not throw away where the teacher had scrolled to.
        val sameUnit = unit.id == currentUnitId
        currentUnitId = unit.id

        findViewById<TextView>(R.id.lesson_kicker).text =
            "${getString(R.string.card_unit_kicker)} ${unit.sortOrder}"
        findViewById<TextView>(R.id.lesson_title).text = unit.titleEn.clean()

        lessonJob?.cancel()
        lessonJob = lifecycleScope.launch {
            if (!immediate) delay(RAIL_SETTLE_MS)

            val lessons = EcedApp.instance.catalog.lessons(unit.id)
            val mins = lessons.sumOf { (it.durationSec ?: 0) } / 60
            findViewById<TextView>(R.id.lesson_meta).text =
                getString(R.string.unit_meta, lessons.size, mins)

            val done = lessons.count { (progress[it.id] ?: 0f) > 0.97f }
            val pct = if (lessons.isEmpty()) 0 else done * 100 / lessons.size
            findViewById<TextView>(R.id.unit_progress_chip).text =
                getString(R.string.unit_progress, pct)

            lessonGrid.adapter = LessonGridAdapter(lessons)

            val at = if (sameUnit) {
                lessons.indexOfFirst { it.id == lastLessonId }.coerceAtLeast(0)
            } else 0
            lessonGrid.scrollToPosition(at)

            if (sameUnit && returningToGrid) {
                returningToGrid = false
                // post() because the holder for `at` does not exist until the
                // scroll has been laid out.
                lessonGrid.post {
                    val card = lessonGrid.findViewHolderForAdapterPosition(at)?.itemView
                    card?.requestFocus()
                    // Always, and only now. Handing the rail back any earlier
                    // lets it win the focus it was blocked from; never handing it
                    // back at all would leave a unit with no lessons unusable,
                    // so the tile takes focus when there is no card to take it.
                    releaseRail()
                    if (card == null) selectedTile?.requestFocus()
                }
            } else if (!returningToGrid) {
                releaseRail()
            }
        }
    }

    /**
     * Paint a rail tile as the chosen one.
     *
     * Selection and focus are not the same thing here. Coming back from the
     * player the right tile has to look chosen - and has to be the tile back
     * returns to - while focus sits on the card down in the grid.
     */
    private fun select(tile: View) {
        selectedTile?.takeIf { it !== tile }?.isSelected = false
        tile.isSelected = true
        selectedTile = tile
    }

    /** Let the rail hold focus again once somewhere better has it. */
    private fun releaseRail() {
        unitRail.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
    }

    private fun play(lesson: Lesson) {
        if (!lesson.isPlayable) return
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_LESSON_ID, lesson.id)
                .putExtra(PlayerActivity.EXTRA_VIDEO_URL, lesson.videoUrl)
                .putExtra(PlayerActivity.EXTRA_TITLE, lesson.titleEn)
        )
    }

    /** Free space on the partition offline lessons would be written to. */
    private fun freeSpace(): String {
        val stat = StatFs(filesDir.absolutePath)
        val gb = stat.availableBytes / 1024.0 / 1024.0 / 1024.0
        return String.format(Locale.getDefault(), "%.1f GB", gb)
    }

    // ------------------------------------------------------------- adapters

    private inner class UnitRailAdapter(
        private val items: List<CatalogUnit>,
    ) : RecyclerView.Adapter<UnitRailAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val art: FrameLayout = v.findViewById(R.id.tu_art)
            val no: TextView     = v.findViewById(R.id.tu_no)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.tile_unit, parent, false)
        ).also { it.itemView.clipToOutline = true }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val u = items[position]
            holder.no.text = u.sortOrder.toString()
            paint(holder, holder.itemView.hasFocus())

            holder.itemView.setOnFocusChangeListener { view, hasFocus ->
                lift(view, hasFocus)
                paint(holder, hasFocus)
                if (!hasFocus) return@setOnFocusChangeListener
                select(view)
                items.getOrNull(holder.bindingAdapterPosition)?.let { showLessons(it) }
            }

            // OK on a tile is a shortcut into the grid, not a new screen. The grid
            // already shows this unit, so opening anything would be a detour.
            holder.itemView.setOnClickListener {
                lessonGrid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
            }
        }

        /**
         * The tile fills with the subject tint at rest and inverts to navy under
         * focus, matching every other focusable surface in the app.
         *
         * It has to be done here rather than in `tile_bg`, because the tile's
         * artwork is a child View drawn on top of that background - the state
         * list only ever gets to paint the 4dp of frame around it. Which is also
         * why a tile with focus used to show a gold ring around an unchanged
         * pastile square, on a cream page where the gold itself is 1.51:1: from
         * the back of a classroom, nothing on the rail looked selected at all.
         */
        private fun paint(holder: VH, focused: Boolean) {
            val c = holder.itemView.context
            holder.art.setBackgroundColor(
                if (focused) ContextCompat.getColor(c, R.color.surface_focus)
                else style.colorStart
            )
            holder.no.setTextColor(
                ContextCompat.getColor(
                    c, if (focused) R.color.on_focus else R.color.on_subject
                )
            )
        }
    }

    private inner class LessonGridAdapter(
        private val items: List<Lesson>,
    ) : RecyclerView.Adapter<LessonGridAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val art: FrameLayout   = v.findViewById(R.id.pl_art)
            val poster: ImageView  = v.findViewById(R.id.pl_poster)
            val scrim: View        = v.findViewById(R.id.pl_scrim)
            val no: TextView       = v.findViewById(R.id.pl_no)
            val dur: TextView      = v.findViewById(R.id.pl_dur)
            val play: ImageView    = v.findViewById(R.id.pl_play)
            val title: TextView    = v.findViewById(R.id.pl_title)
            val meta: TextView     = v.findViewById(R.id.pl_meta)
            val track: FrameLayout = v.findViewById(R.id.pl_track)
            val fill: View         = v.findViewById(R.id.pl_fill)

            /** Whether a picture is on this card *now*, not whether one was asked for. */
            var hasPoster = false
        }

        /**
         * Repaints everything on the artwork whose correct value depends on what
         * is underneath it.
         *
         * The grid mixes both kinds of card: a scrimmed video frame under one, a
         * flat subject tint under the next, and the same card switches from the
         * second to the first mid-scroll, when its poster arrives. One ink
         * colour cannot serve both - navy is 8:1 on the tint and 1.4:1 on the
         * scrim.
         *
         * The numeral goes with them. It is fallback artwork, not a label: it
         * exists so a card with no picture is not an empty rectangle. Once there
         * is a picture it would sit in the middle of it - in this catalogue,
         * squarely on the presenter's face - repeating what the title directly
         * underneath already says.
         */
        private fun VH.artwork(onPoster: Boolean) {
            hasPoster = onPoster
            val c = itemView.context
            val badge = ContextCompat.getColor(
                c, if (onPoster) R.color.badge_scrim_inverse else R.color.badge_scrim
            )
            val ink = if (onPoster) Color.WHITE else ContextCompat.getColor(c, R.color.on_subject)

            scrim.visibility = if (onPoster) View.VISIBLE else View.GONE
            no.visibility = when {
                onPoster -> View.GONE
                play.visibility == View.VISIBLE -> View.INVISIBLE
                else -> View.VISIBLE
            }
            no.setTextColor(ink)
            dur.setTextColor(ink)
            dur.setBackgroundColor(badge)
            track.setBackgroundColor(badge)
            fill.setBackgroundColor(
                if (onPoster) Color.WHITE else ContextCompat.getColor(c, R.color.brand_navy)
            )
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.poster_lesson, parent, false)
        ).also { it.itemView.clipToOutline = true }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val l = items[position]
            // The colour panel stays as the backdrop, so a poster that is still
            // loading or never arrives looks deliberate rather than broken.
            holder.art.setBackgroundColor(style.colorStart)
            holder.no.text = l.sortOrder.toString()
            holder.dur.text = "${(l.durationSec ?: 0) / 60} min"
            holder.title.text = l.titleEn.clean()

            val watched = ((progress[l.id] ?: 0f) * 100).toInt()
            // 963 of 968 lessons have no video yet. Saying so on the poster is worth
            // the space: landing on a dead card mid demo looks like a crash.
            holder.meta.text = when {
                !l.isPlayable -> getString(R.string.lesson_no_video)
                watched in 1..97 -> "$watched% watched"
                else -> getString(R.string.lesson_stream)
            }

            showProgress(holder, progress[l.id] ?: 0f)

            holder.play.visibility = View.GONE
            holder.itemView.alpha = if (l.isPlayable) 1f else DIMMED_ALPHA

            // Fallback dress first, every time. A recycled holder arrives wearing
            // the last lesson's state, and this one's picture - if it has one at
            // all - is a network read away.
            holder.artwork(false)
            if (l.posterUrl.isNullOrBlank()) PosterLoader.cancel(holder.poster)
            else PosterLoader.load(holder.poster, l.posterUrl) { holder.artwork(true) }

            holder.itemView.setOnFocusChangeListener { view, hasFocus ->
                lift(view, hasFocus)
                // Remembered on focus rather than on play, so backing out of a
                // lesson list also comes back to the right card.
                if (hasFocus) lastLessonId = l.id
                // The play mark appears only under focus. On every poster it is
                // noise; on the focused one it says what OK will do.
                val showPlay = hasFocus && l.isPlayable
                holder.play.visibility = if (showPlay) View.VISIBLE else View.GONE
                holder.no.visibility = when {
                    holder.hasPoster -> View.GONE
                    showPlay -> View.INVISIBLE
                    else -> View.VISIBLE
                }
            }
            holder.itemView.setOnClickListener { play(l) }
        }

        private fun showProgress(h: VH, fraction: Float) {
            if (fraction <= 0.01f) {
                h.track.visibility = View.GONE
                return
            }
            h.track.visibility = View.VISIBLE
            // post() because width is 0 until the poster has been laid out once.
            h.track.post {
                h.fill.layoutParams = h.fill.layoutParams.apply {
                    width = (h.track.width * fraction.coerceIn(0f, 1f)).toInt()
                }
                h.fill.requestLayout()
            }
        }
    }

    /**
     * Focus lift. A scale and a z bump, no elevation and no shadow. A shadow is a
     * blur, and blurs are the one thing a Mali-450 genuinely cannot afford.
     */
    private fun lift(view: View, focused: Boolean) {
        val scale = if (focused) FOCUS_SCALE else 1f
        view.animate().scaleX(scale).scaleY(scale).setDuration(FOCUS_MS).start()
        view.z = if (focused) 1f else 0f
    }

    // ------------------------------------------------------------ decorations

    /** Even gap between items in a single row. */
    private class GapDecoration(private val gap: Int) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State,
        ) {
            outRect.left = if (parent.getChildAdapterPosition(view) == 0) 0 else gap
        }
    }

    /**
     * Even gaps between grid columns without eating the overscan padding.
     *
     * Splitting the gap evenly across every item would leave the outer columns
     * narrower than the inner ones. Weighting each item's share by its column
     * keeps all five the same width and the outer edges flush with the padding.
     */
    private class GridGapDecoration(
        private val columns: Int,
        private val gap: Int,
    ) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State,
        ) {
            val pos = parent.getChildAdapterPosition(view)
            if (pos == RecyclerView.NO_POSITION) return
            val col = pos % columns
            outRect.left = gap * col / columns
            outRect.right = gap * (columns - 1 - col) / columns
            if (pos >= columns) outRect.top = gap
        }
    }


    companion object {
        const val EXTRA_UNIT_ID    = "unit_id"
        const val EXTRA_UNIT_TITLE = "unit_title"
        const val EXTRA_SUBJECT_ID = "subject_id"
        const val EXTRA_COLOR_1    = "color_1"
        const val EXTRA_COLOR_2    = "color_2"
        const val EXTRA_SUBJECT    = "subject"

        private const val GRID_COLUMNS = 4
        private const val FOCUS_SCALE = 1.06f

        /** Unreleased lessons. Most of the catalog is still unreleased, so this has
         *  to read as "not yet" without making the whole grid look broken. */
        private const val DIMMED_ALPHA = 0.72f
        private const val FOCUS_MS = 130L

        /** Long enough to skip units you arrow past, short enough to feel instant. */
        private const val RAIL_SETTLE_MS = 140L
    }
}
