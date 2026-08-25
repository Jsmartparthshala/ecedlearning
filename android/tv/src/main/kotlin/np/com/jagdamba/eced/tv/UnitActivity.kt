package np.com.jagdamba.eced.tv

import android.content.Intent
import android.os.Bundle
import android.os.StatFs
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import np.com.jagdamba.eced.core.model.Lesson
import np.com.jagdamba.eced.core.model.Unit as CatalogUnit
import java.util.Locale

/**
 * Unit detail: units on the left, the selected unit's lessons on the right.
 *
 * Two panes rather than Leanback's DetailsFragment. DetailsFragment is built
 * around a single hero item with related rows; this screen is a picker over 24
 * units of 6-12 lessons each. Side-by-side lists are the right shape and give a
 * shorter D-pad path — left/right changes pane, up/down moves within one.
 *
 * Focusing a unit on the left loads its lessons on the right without leaving the
 * screen, which is how the prototype behaves and how a teacher expects to browse.
 */
class UnitActivity : FragmentActivity() {

    private lateinit var unitList: RecyclerView
    private lateinit var lessonList: RecyclerView

    private val style by lazy {
        CardPresenter.SubjectStyle.of(
            intent.getStringExtra(EXTRA_COLOR_1),
            intent.getStringExtra(EXTRA_COLOR_2),
            intent.getStringExtra(EXTRA_SUBJECT),
        )
    }

    private var units: List<CatalogUnit> = emptyList()
    private var progress: Map<String, Float> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_unit)

        unitList = findViewById(R.id.unit_list)
        lessonList = findViewById(R.id.lesson_list)
        unitList.layoutManager = LinearLayoutManager(this)
        lessonList.layoutManager = LinearLayoutManager(this)

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
            unitList.adapter = UnitAdapter(units, startIndex) { showLessons(it) }
            units.getOrNull(startIndex)?.let { showLessons(it) }
        }
    }

    private fun showLessons(unit: CatalogUnit) {
        findViewById<TextView>(R.id.lesson_kicker).text =
            "${style.label.uppercase(Locale.getDefault())} · ${getString(R.string.card_unit_kicker)} ${unit.sortOrder}"
        findViewById<TextView>(R.id.lesson_title).text =
            unit.titleEn.removePrefix("[PLACEHOLDER] ")

        lifecycleScope.launch {
            val lessons = EcedApp.instance.catalog.lessons(unit.id)
            val mins = lessons.sumOf { (it.durationSec ?: 0) } / 60
            findViewById<TextView>(R.id.lesson_meta).text =
                "${lessons.size} videos · $mins min"

            val done = lessons.count { (progress[it.id] ?: 0f) > 0.97f }
            val pct = if (lessons.isEmpty()) 0 else done * 100 / lessons.size
            findViewById<TextView>(R.id.unit_progress_chip).text =
                getString(R.string.unit_progress, pct)

            lessonList.adapter = LessonAdapter(lessons, progress) { play(it) }
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
        val path = filesDir
        val stat = StatFs(path.absolutePath)
        val gb = stat.availableBytes / 1024.0 / 1024.0 / 1024.0
        return String.format(Locale.getDefault(), "%.1f GB", gb)
    }

    // ------------------------------------------------------------- adapters

    private inner class UnitAdapter(
        private val items: List<CatalogUnit>,
        private var selected: Int,
        private val onSelect: (CatalogUnit) -> Unit,
    ) : RecyclerView.Adapter<UnitAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val badge: TextView = v.findViewById(R.id.ru_badge)
            val title: TextView = v.findViewById(R.id.ru_title)
            val meta: TextView  = v.findViewById(R.id.ru_meta)
            val pct: TextView   = v.findViewById(R.id.ru_pct)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.row_unit, parent, false)
        )

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val u = items[position]
            holder.badge.text = u.sortOrder.toString()
            holder.badge.setBackgroundColor(style.colorStart)
            holder.title.text = u.titleEn.removePrefix("[PLACEHOLDER] ")
            holder.meta.text = "${u.estDays ?: 0} videos"
            holder.pct.text = ""
            holder.itemView.isSelected = position == selected

            // Focus, not click, drives the right pane: on a remote you arrow down
            // the list and expect the detail to follow without pressing OK.
            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    val old = selected
                    selected = holder.bindingAdapterPosition
                    notifyItemChanged(old)
                    notifyItemChanged(selected)
                    onSelect(items[selected])
                }
            }
            holder.itemView.setOnClickListener { onSelect(items[position]) }
        }
    }

    private inner class LessonAdapter(
        private val items: List<Lesson>,
        private val progress: Map<String, Float>,
        private val onPlay: (Lesson) -> Unit,
    ) : RecyclerView.Adapter<LessonAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val no: TextView     = v.findViewById(R.id.rl_no)
            val title: TextView  = v.findViewById(R.id.rl_title)
            val meta: TextView   = v.findViewById(R.id.rl_meta)
            val action: TextView = v.findViewById(R.id.rl_action)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.row_lesson, parent, false)
        )

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val l = items[position]
            holder.no.text = l.sortOrder.toString()
            holder.title.text = l.titleEn.removePrefix("[PLACEHOLDER] ")

            val mins = (l.durationSec ?: 0) / 60
            val watched = ((progress[l.id] ?: 0f) * 100).toInt()
            holder.meta.text = buildString {
                append("$mins min · ${l.codec.uppercase(Locale.getDefault())}")
                if (l.isPlayable) {
                    append(" · ").append(holder.meta.context.getString(R.string.lesson_stream))
                } else {
                    append(" · ").append(holder.meta.context.getString(R.string.lesson_no_video))
                }
                if (watched in 1..97) append("  ·  $watched% watched")
            }

            holder.action.text = if (l.isPlayable) "▶" else ""
            holder.itemView.alpha = if (l.isPlayable) 1f else 0.55f
            holder.itemView.setOnClickListener { onPlay(l) }
        }
    }

    companion object {
        const val EXTRA_UNIT_ID    = "unit_id"
        const val EXTRA_UNIT_TITLE = "unit_title"
        const val EXTRA_SUBJECT_ID = "subject_id"
        const val EXTRA_COLOR_1    = "color_1"
        const val EXTRA_COLOR_2    = "color_2"
        const val EXTRA_SUBJECT    = "subject"
    }
}
