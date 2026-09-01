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
import android.content.Context
import np.com.jagdamba.eced.core.model.Lesson
import np.com.jagdamba.eced.core.model.Level
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

    /**
     * The grade whose subjects are currently on screen, by slug.
     *
     * Remembered across launches in plain SharedPreferences. A television bolted
     * to one classroom is that classroom's grade every day of the year, so making
     * a teacher re-pick it on every boot would be a new chore in exchange for
     * nothing. Not in the encrypted session store: this is a display preference,
     * not a credential, and it must survive an unpair.
     */
    private var selectedSlug: String? = null

    /**
     * Whether the screen has ever had real content on it.
     *
     * Drives the loading row, which must appear on a cold start and never again.
     * A reload keeps the previous rows on screen while it runs - that is
     * deliberate, see the comment in loadCatalog - so flashing "Loading" over a
     * screen that already has content would be a regression, not a courtesy.
     */
    private var hasLoadedOnce = false

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
                is SubjectPresenter.LevelTile   -> selectLevel(item.level)
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

        val repo = EcedApp.instance.catalog
        val progressRepo = EcedApp.instance.progress
        val deviceId = EcedApp.instance.devices.cachedDeviceId()

        loadJob = lifecycleScope.launch {
            // Cold start only. The catalogue is a network read that takes real
            // seconds on a rural link, and the placeholder row that exists to get
            // Leanback to build its rows fragment draws nothing - so until now the
            // very first thing a teacher saw after switching the television on was
            // an empty screen with no indication anything was happening.
            if (!hasLoadedOnce) {
                // One line of muted text, not a card. CardPresenter renders an
                // unknown item as a full tile with an empty artwork panel, so the
                // old loading row looked like a card that had failed - the
                // opposite of what it is there to say. The word appears once, in
                // the row rather than in a header above it.
                val a = ArrayObjectAdapter(MessagePresenter())
                a.add(getString(R.string.loading))
                rowsAdapter.setItems(
                    listOf(ListRow(HeaderItem(ID_LOADING, ""), a)),
                    null,
                )
            }

            // Collected first, attached at the end. Clearing the live adapter up
            // front would blank the screen for the length of the fetch, and a
            // cancelled load would leave it blank for good.
            val rows = mutableListOf<ListRow>()

            runCatching {
                // Header chip: resolve the school once per load, then refresh the
                // title bar in place rather than rebuilding it.
                EcedApp.instance.devices.refreshSchoolName()
                (titleView as? BrowseTitleView)?.refresh()

                // The ladder. Empty on a database that has not had
                // 0007_levels_and_classes.sql applied yet, and the whole grade
                // layer then falls back to the single-grade behaviour below -
                // an older APK and a newer database, or the reverse, must both
                // keep working, because the two are updated by different people
                // on different days.
                val levels = repo.levels()
                val current = levels.pickCurrent()
                selectedSlug = current?.slug

                // Subjects for the chosen grade. This is the row a teacher lands
                // on, so it is built and shown before the progress queries below.
                //
                // One request, counts included: subject_cards carries unit_count,
                // which is what the tile badge needs. The old code called
                // units(subject.id) once per subject to get that number and did
                // it again on every onResume, including every return from the
                // player.
                val subjectAdapter = ArrayObjectAdapter(SubjectPresenter())
                if (current != null) {
                    repo.subjectCards(current.id).forEach {
                        subjectAdapter.add(
                            SubjectPresenter.SubjectTile(it.toSubject(), it.unitCount)
                        )
                    }
                } else {
                    // Pre-0007 database: no levels, so every subject is the
                    // catalogue. No per-subject unit count is available without
                    // the view, and one query per subject is exactly what this
                    // change exists to remove, so the badge shows nothing.
                    repo.subjects().forEach {
                        subjectAdapter.add(SubjectPresenter.SubjectTile(it, 0))
                    }
                }
                rows.add(
                    ListRow(
                        HeaderItem(
                            ID_SUBJECTS,
                            current?.let { getString(R.string.row_subjects_of, it.nameEn) }
                                ?: getString(R.string.row_subjects),
                        ),
                        subjectAdapter,
                    )
                )

                // The grade row, directly under the subjects it controls, so the
                // relationship between the two is visible rather than something a
                // teacher has to be told. Picking a grade swaps the row above in
                // place - it does not open a screen, because that would put a
                // fourth press between a teacher and a video on every launch, for
                // a choice a classroom television makes once.
                //
                // Hidden entirely when there is only one grade, which is the
                // state every television in the field is in today.
                if (levels.size > 1) {
                    val levelAdapter = ArrayObjectAdapter(SubjectPresenter())
                    levels.forEach {
                        levelAdapter.add(
                            SubjectPresenter.LevelTile(it, selected = it.slug == current?.slug)
                        )
                    }
                    rows.add(
                        ListRow(HeaderItem(ID_LEVELS, getString(R.string.row_levels)), levelAdapter)
                    )
                }

                // Progress: every row below needs it, and one fetch beats
                // one-per-row on a rural connection.
                val saved = deviceId?.let { progressRepo.forDevice(it) }.orEmpty()
                val watchedLessons = repo.lessonsByIds(saved.keys.toList())
                val durations = watchedLessons.associate { it.id to (it.durationSec ?: 0) }
                val fractions = progressRepo.fractions(saved, durations)

                // Continue watching - anything started but not finished, newest first.
                //
                // Newest by when it was last watched. This said "newest first" and
                // then sorted on how far through each lesson was, so the row was
                // ordered by percentage: the lesson abandoned at 90% sat at the
                // front for good, and the one just watched went to the back for
                // being two minutes in. Pressing Back out of a lesson is the most
                // common thing anyone does here, and the lesson that was just left
                // is the one they are looking for.
                //
                // Fraction stays as the tie-break, for rows written before the
                // timestamp was maintained, which all carry the same first-opened
                // value.
                //
                // Compared as text, which holds because every row here comes back
                // from one database rendering one column at one offset, so the
                // strings sort the way the instants do.
                val continueRow = watchedLessons
                    .filter { (fractions[it.id] ?: 0f) in 0.01f..0.97f }
                    .sortedWith(
                        compareByDescending<Lesson> { saved[it.id]?.updatedAt.orEmpty() }
                            .thenByDescending { fractions[it.id] ?: 0f }
                    )
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
            hasLoadedOnce = true
        }
    }

    /**
     * Which grade to show: the one remembered from last time if it still exists
     * and still has something in it, otherwise the first grade that does.
     *
     * The fallback matters more than it looks. Grades are seeded empty and filled
     * in over months, so "first in the ladder" would land a television on ECED
     * whether or not ECED is what that school teaches, and "remembered" would pin
     * it to a grade whose content was later moved. Preferring a grade that has
     * content means a television never opens on an empty screen.
     */
    private fun List<Level>.pickCurrent(): Level? {
        if (isEmpty()) return null

        val devices = EcedApp.instance.devices
        val p = prefs()

        // A reassignment from the office outranks whatever was last chosen with
        // the remote. Moving a television from Nursery A to Grade 3 is an
        // administrative decision, and it would be useless if the set carried on
        // opening on the old grade because somebody had once pressed a tile. The
        // remembered pick is cleared on change rather than ignored, so a teacher
        // can still browse elsewhere afterwards and have that stick.
        val assignedClass = devices.cachedClassId()
        if (assignedClass != p.getString(KEY_CLASS, null)) {
            p.edit().putString(KEY_CLASS, assignedClass).remove(KEY_LEVEL).apply()
        }

        val remembered = p.getString(KEY_LEVEL, null)
        val assignedLevel = devices.cachedClassLevel()
        return firstOrNull { it.slug == remembered && it.hasContent }
            ?: firstOrNull { it.slug == assignedLevel && it.hasContent }
            ?: firstOrNull { it.hasContent }
            ?: first()
    }

    /**
     * Switch the visible grade and reload.
     *
     * A full reload rather than swapping one row: the continue-watching row is
     * scoped to the device rather than to a grade, so it is still correct either
     * way, but rebuilding everything keeps one code path for what the screen
     * contains instead of two that can drift apart. A grade switch is a rare,
     * deliberate act - one extra fetch on something a teacher does once a term.
     */
    private fun selectLevel(level: Level) {
        if (level.slug == selectedSlug) return
        if (!level.hasContent) return          // nothing to open; the tile says so
        prefs().edit().putString(KEY_LEVEL, level.slug).apply()
        selectedSlug = level.slug
        loadCatalog()
    }

    /**
     * Plain, unencrypted preferences on purpose. This holds one grade slug - a
     * display choice, not a credential - and it has to outlive an unpair, which
     * clears the encrypted session store.
     */
    private fun prefs() =
        requireContext().getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)

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
        const val ID_LEVELS    = 103L
        const val ID_OFFLINE   = 104L
        const val ID_LOADING   = 105L

        const val PREFS_UI  = "tv_ui"
        const val KEY_LEVEL = "selected_level_slug"

        /**
         * The class the television was assigned to when KEY_LEVEL was last
         * written. Only used to notice that the office has since moved it.
         */
        const val KEY_CLASS = "known_class_id"
    }
}
