package np.com.jagdamba.eced.tv

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.StatFs
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
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
 * Nothing here decodes a bitmap. Artwork is a flat subject colour and every
 * numeral is text, which matters on the 1 GB Mali-450 boxes this ships to.
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
        if (units.isNotEmpty()) load()
    }

    /**
     * Back out of the grid to the rail before back leaves the screen. One press
     * should undo one step, and dropping into the grid was a step.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK &&
            event.action == KeyEvent.ACTION_UP &&
            lessonGrid.hasFocus()
        ) {
            selectedTile?.requestFocus()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun load() {
        val subjectId = intent.getStringExtra(EXTRA_SUBJECT_ID) ?: return
        val focusUnitId = intent.getStringExtra(EXTRA_UNIT_ID)

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

            val first = units.getOrNull(startIndex) ?: return@launch
            showLessons(first, immediate = true)

            // Focus the unit that was opened, not the first one, so arriving from a
            // card on the home screen lands where the teacher pointed.
            unitRail.scrollToPosition(startIndex)
            unitRail.post {
                unitRail.findViewHolderForAdapterPosition(startIndex)?.itemView?.requestFocus()
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
            lessonGrid.scrollToPosition(0)
        }
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
            val kicker: TextView = v.findViewById(R.id.tu_kicker)
            val count: TextView  = v.findViewById(R.id.tu_count)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.tile_unit, parent, false)
        ).also { it.itemView.clipToOutline = true }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val u = items[position]
            holder.art.setBackgroundColor(style.colorStart)
            holder.no.text = u.sortOrder.toString()
            holder.kicker.text = getString(R.string.card_unit_kicker)
            val n = u.estDays ?: 0
            holder.count.text = if (n > 0) "$n videos" else ""

            holder.itemView.setOnFocusChangeListener { view, hasFocus ->
                lift(view, hasFocus)
                if (!hasFocus) return@setOnFocusChangeListener
                selectedTile?.takeIf { it !== view }?.isSelected = false
                view.isSelected = true
                selectedTile = view
                items.getOrNull(holder.bindingAdapterPosition)?.let { showLessons(it) }
            }

            // OK on a tile is a shortcut into the grid, not a new screen. The grid
            // already shows this unit, so opening anything would be a detour.
            holder.itemView.setOnClickListener {
                lessonGrid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
            }
        }
    }

    private inner class LessonGridAdapter(
        private val items: List<Lesson>,
    ) : RecyclerView.Adapter<LessonGridAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val art: FrameLayout   = v.findViewById(R.id.pl_art)
            val no: TextView       = v.findViewById(R.id.pl_no)
            val kicker: TextView   = v.findViewById(R.id.pl_kicker)
            val dur: TextView      = v.findViewById(R.id.pl_dur)
            val play: TextView     = v.findViewById(R.id.pl_play)
            val title: TextView    = v.findViewById(R.id.pl_title)
            val meta: TextView     = v.findViewById(R.id.pl_meta)
            val track: FrameLayout = v.findViewById(R.id.pl_track)
            val fill: View         = v.findViewById(R.id.pl_fill)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.poster_lesson, parent, false)
        ).also { it.itemView.clipToOutline = true }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val l = items[position]
            holder.art.setBackgroundColor(style.colorStart)
            holder.no.text = l.sortOrder.toString()
            holder.kicker.text = getString(R.string.card_lesson_kicker)
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

            holder.play.text = getString(R.string.play_glyph)
            holder.play.visibility = View.GONE
            holder.no.visibility = View.VISIBLE
            holder.itemView.alpha = if (l.isPlayable) 1f else DIMMED_ALPHA

            holder.itemView.setOnFocusChangeListener { view, hasFocus ->
                lift(view, hasFocus)
                // The play mark appears only under focus. On every poster it is
                // noise; on the focused one it says what OK will do.
                val showPlay = hasFocus && l.isPlayable
                holder.play.visibility = if (showPlay) View.VISIBLE else View.GONE
                holder.no.visibility = if (showPlay) View.INVISIBLE else View.VISIBLE
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

    private fun String.clean() = removePrefix("[PLACEHOLDER] ")

    companion object {
        const val EXTRA_UNIT_ID    = "unit_id"
        const val EXTRA_UNIT_TITLE = "unit_title"
        const val EXTRA_SUBJECT_ID = "subject_id"
        const val EXTRA_COLOR_1    = "color_1"
        const val EXTRA_COLOR_2    = "color_2"
        const val EXTRA_SUBJECT    = "subject"

        private const val GRID_COLUMNS = 5
        private const val FOCUS_SCALE = 1.06f

        /** Unreleased lessons. Most of the catalog is still unreleased, so this has
         *  to read as "not yet" without making the whole grid look broken. */
        private const val DIMMED_ALPHA = 0.72f
        private const val FOCUS_MS = 130L

        /** Long enough to skip units you arrow past, short enough to feel instant. */
        private const val RAIL_SETTLE_MS = 140L
    }
}
