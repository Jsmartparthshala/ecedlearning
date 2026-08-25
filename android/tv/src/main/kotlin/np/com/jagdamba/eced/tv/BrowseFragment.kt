package np.com.jagdamba.eced.tv

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.ListRowPresenter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import np.com.jagdamba.eced.core.model.Lesson
import np.com.jagdamba.eced.core.model.Subject

/**
 * Home screen: pick a subject, or carry on with something already started.
 *
 * The sidebar is gone. It held nine entries - two content rows, five subjects and
 * two utility pages - which put the entire product behind a list a teacher had to
 * read before anything appeared. Subjects are now big coloured tiles in the first
 * row, chosen with one press, and Downloads and Settings are two tiles at the
 * bottom rather than navigation.
 *
 * Leanback still does the vertical scrolling and focus work; only HEADERS_DISABLED
 * and the row contents changed, which is why this stayed a small edit rather than
 * a hand rolled screen.
 *
 * Note this is a SUBJECT-shaped taxonomy, which the 2082 curriculum's द्रष्टव्य note
 * says content must not be *delivered* as. The defensible reading is that this is a
 * browse taxonomy for teachers while lesson content stays integrated — but that is a
 * decision to make on the record, not to let a UI quietly imply.
 */
class BrowseFragment : BrowseSupportFragment() {

    /**
     * Rows are mutated in place rather than swapped. Leanback builds its rows
     * fragment once, from the adapter that exists when the browse fragment is
     * created, and with the sidebar disabled there is no header selection to
     * trigger a later rebuild - so this instance has to be attached up front and
     * kept.
     */
    private val rowsAdapter = ArrayObjectAdapter(rowPresenter())

    /** unit id -> owning subject, so the unit screen keeps the same styling. */
    private val subjectById = mutableMapOf<String, Subject>()

    /**
     * The in-flight catalogue load. Cancelled before starting another so a slow
     * first load cannot overwrite a newer one.
     */
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // No sidebar. The subject row is the navigation now.
        headersState = HEADERS_DISABLED
        isHeadersTransitionOnBackEnabled = false

        // Both of these have to happen before Leanback creates its views.
        //
        // Leanback decides whether to build a rows fragment from the adapter it
        // can see at creation time. With headers enabled, selecting a sidebar
        // entry would build one later; with headers disabled nothing ever does,
        // so an adapter attached afterwards renders a permanently blank screen.
        // One empty placeholder row is enough to get the fragment built, and an
        // empty row draws nothing, so it is never visible.
        rowsAdapter.add(ListRow(ArrayObjectAdapter(CardPresenter())))
        adapter = rowsAdapter
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        brandColor = ContextCompat.getColor(requireContext(), R.color.tv_bg)

        setOnItemViewClickedListener { _, item, _, _ ->
            when (item) {
                is SubjectPresenter.SubjectTile -> openSubject(item.subject)
                is Lesson -> openLesson(item)
            }
        }
    }

    /**
     * Reload on every resume: coming back from the player means progress changed,
     * so the bars and the continue row are stale.
     */
    override fun onResume() {
        super.onResume()
        loadCatalog()
    }

    /** Leanback only accepts a custom header that implements TitleViewAdapter.Provider. */
    override fun onInflateTitleView(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.browse_title, parent, false)

    /**
     * Row presenter tuned for the hardware this ships to.
     *
     * Leanback draws a z-shadow under every card and a dimming scrim over every
     * unfocused one. Both are per-card compositing work on a Mali-450 with 1 GB of
     * RAM, and neither survives the design rules here anyway - focus is a gold
     * stroke, not a shadow. Turning them off is the single biggest frame-time win
     * available on the browse screen.
     */
    private fun rowPresenter() = ListRowPresenter(FocusHighlight.ZOOM_FACTOR_SMALL, false)
        .apply {
            shadowEnabled = false
            // Leanback dims every row except the one holding focus. On a phone-sized
            // panel that is a subtle hint; on a television showing three rows it
            // renders two thirds of the screen at reduced alpha, which is why the
            // artwork on "Playable now" looked switched off while Subjects looked
            // lit. Focus here is already a 4dp gold stroke, legible from the back of
            // a classroom, so the dim adds nothing and costs the whole screen.
            selectEffectEnabled = false
        }

    private fun loadCatalog() {
        loadJob?.cancel()
        subjectById.clear()

        val repo = EcedApp.instance.catalog
        val progressRepo = EcedApp.instance.progress
        val deviceId = EcedApp.instance.devices.cachedDeviceId()

        loadJob = lifecycleScope.launch {
            // Collected first, attached at the end. Clearing the live adapter up
            // front would blank the screen for the length of the fetch, and a
            // cancelled load would leave it blank for good.
            val rows = mutableListOf<ListRow>()

            runCatching {
                // Header chip: resolve the school once per load, then refresh the
                // title bar in place rather than rebuilding it.
                EcedApp.instance.devices.refreshSchoolName()
                (titleView as? BrowseTitleView)?.refresh()

                // Subjects first. This is the row a teacher lands on, so it is
                // built and shown before the progress queries below run.
                val subjects = repo.subjects()
                val subjectAdapter = ArrayObjectAdapter(SubjectPresenter())
                subjects.forEach { subject ->
                    val units = repo.units(subject.id)
                    units.forEach { subjectById[it.id] = subject }
                    subjectAdapter.add(SubjectPresenter.SubjectTile(subject, units.size))
                }
                rows.add(
                    ListRow(HeaderItem(ID_SUBJECTS, getString(R.string.row_subjects)), subjectAdapter)
                )

                // Progress: every row below needs it, and one fetch beats
                // one-per-row on a rural connection.
                val saved = deviceId?.let { progressRepo.forDevice(it) }.orEmpty()
                val watchedLessons = repo.lessonsByIds(saved.keys.toList())
                val durations = watchedLessons.associate { it.id to (it.durationSec ?: 0) }
                val fractions = progressRepo.fractions(saved, durations)

                // Continue watching — anything started but not finished, newest first.
                val continueRow = watchedLessons
                    .filter { (fractions[it.id] ?: 0f) in 0.01f..0.97f }
                    .sortedByDescending { fractions[it.id] ?: 0f }
                    .take(10)

                if (continueRow.isNotEmpty()) {
                    val a = ArrayObjectAdapter(
                        CardPresenter(CardPresenter.SubjectStyle.default(requireContext()), fractions)
                    )
                    continueRow.forEach { a.add(it) }
                    rows.add(
                        ListRow(HeaderItem(ID_CONTINUE, getString(R.string.row_continue)), a)
                    )
                }

                // "Playable now" — 963 of 968 lessons have no video yet, so without
                // this row a demo can wander for a while before finding one.
                val playable = repo.playableSample()
                if (playable.isNotEmpty()) {
                    val a = ArrayObjectAdapter(
                        CardPresenter(CardPresenter.SubjectStyle.default(requireContext()), fractions)
                    )
                    playable.forEach { a.add(it) }
                    rows.add(
                        ListRow(HeaderItem(ID_PLAYABLE, getString(R.string.row_playable)), a)
                    )
                }

                // Profile, Downloads and Settings used to be a row down here.
                // They are the utility rail on the left now - repeating them as
                // cards would give a teacher two routes to the same screen and
                // one more row to scroll past to reach a lesson.
            }.onFailure {
                val a = ArrayObjectAdapter(CardPresenter())
                a.add(getString(R.string.offline))
                rows.add(ListRow(HeaderItem(ID_OFFLINE, getString(R.string.offline)), a))
            }

            // setItems diffs against what is already there, so an unchanged row
            // is not rebound and focus survives the reload on return from the
            // player. This is also where the placeholder row disappears.
            rowsAdapter.setItems(rows, null)
        }
    }

    /** Straight into the subject's units. No unit id, so the rail opens at the first. */
    private fun openSubject(subject: Subject) {
        startActivity(
            Intent(requireContext(), UnitActivity::class.java)
                .putExtra(UnitActivity.EXTRA_SUBJECT_ID, subject.id)
                .putExtra(UnitActivity.EXTRA_COLOR_1, subject.color1)
                .putExtra(UnitActivity.EXTRA_COLOR_2, subject.color2)
                .putExtra(UnitActivity.EXTRA_SUBJECT, subject.nameEn)
        )
    }

    private fun openLesson(lesson: Lesson) {
        if (!lesson.isPlayable) return
        startActivity(
            Intent(requireContext(), PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_LESSON_ID, lesson.id)
                .putExtra(PlayerActivity.EXTRA_VIDEO_URL, lesson.videoUrl)
                .putExtra(PlayerActivity.EXTRA_TITLE, lesson.titleEn)
        )
    }

    private companion object {
        const val ID_SUBJECTS  = 100L
        const val ID_CONTINUE  = 101L
        const val ID_PLAYABLE  = 102L
        const val ID_OFFLINE   = 104L
    }
}
