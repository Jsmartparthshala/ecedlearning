package np.com.jagdamba.eced.tv

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.PageRow
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import np.com.jagdamba.eced.core.model.Lesson
import np.com.jagdamba.eced.core.model.Subject
import np.com.jagdamba.eced.core.model.Unit as CatalogUnit

/**
 * Home screen: one row per subject, cards are that subject's units.
 *
 * Note this is a SUBJECT-shaped taxonomy, which the 2082 curriculum's द्रष्टव्य note
 * says content must not be *delivered* as. The defensible reading is that this is a
 * browse taxonomy for teachers while lesson content stays integrated — but that is a
 * decision to make on the record, not to let a UI quietly imply.
 */
class BrowseFragment : BrowseSupportFragment() {

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())

    /** unit id -> owning subject, so the unit screen keeps the same styling. */
    private val unitStyle = mutableMapOf<String, Subject>()

    /**
     * The in-flight catalogue load. Must be cancelled before starting another:
     * clearing the adapter does not stop a coroutine that is midway through
     * adding rows, and the result is every row appearing twice.
     */
    private var loadJob: Job? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = Color.parseColor("#0B0E13")

        // PageRow entries need a factory that can build their fragment; without
        // this Leanback throws as soon as one is focused.
        mainFragmentRegistry.registerFragment(PageRow::class.java, PageFragmentFactory())

        adapter = rowsAdapter
        // Loading happens in onResume, which fires straight after this. Doing it
        // here as well would start two loads racing each other.

        setOnItemViewClickedListener { _, item, _, _ ->
            when (item) {
                is Lesson -> openLesson(item)
                is CatalogUnit -> openUnit(item)
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

    private fun loadCatalog() {
        loadJob?.cancel()
        rowsAdapter.clear()
        unitStyle.clear()

        val repo = EcedApp.instance.catalog
        val progressRepo = EcedApp.instance.progress
        val deviceId = EcedApp.instance.devices.cachedDeviceId()

        loadJob = lifecycleScope.launch {
            runCatching {
                // Progress first: every row below needs it, and one fetch beats
                // one-per-row on a rural connection.
                // Header chip: resolve the school once per load, then refresh the
                // title bar in place rather than rebuilding it.
                EcedApp.instance.devices.refreshSchoolName()
                (titleView as? BrowseTitleView)?.refresh()

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
                        CardPresenter(CardPresenter.SubjectStyle.DEFAULT, fractions)
                    )
                    continueRow.forEach { a.add(it) }
                    rowsAdapter.add(
                        ListRow(HeaderItem(0, getString(R.string.row_continue)), a)
                    )
                }

                // "Playable now" — 963 of 968 lessons have no video yet, so without
                // this row a demo can wander for a while before finding one.
                val playable = repo.playableSample()
                if (playable.isNotEmpty()) {
                    val a = ArrayObjectAdapter(
                        CardPresenter(CardPresenter.SubjectStyle.DEFAULT, fractions)
                    )
                    playable.forEach { a.add(it) }
                    rowsAdapter.add(ListRow(HeaderItem(1, getString(R.string.row_playable)), a))
                }

                val firstSubjectRow = rowsAdapter.size()

                repo.subjects().forEachIndexed { i, subject ->
                    val units = repo.units(subject.id)
                    val style = CardPresenter.SubjectStyle.of(
                        subject.color1, subject.color2, subject.nameEn
                    )
                    val a = ArrayObjectAdapter(CardPresenter(style, fractions))
                    units.forEach {
                        unitStyle[it.id] = subject
                        a.add(it)
                    }
                    rowsAdapter.add(
                        ListRow(HeaderItem((i + 2).toLong(), subject.nameEn), a)
                    )
                }
                // Utility sections last, so the catalogue stays the first thing
                // a teacher lands on.
                rowsAdapter.add(PageRow(HeaderItem(ID_DOWNLOADS, getString(R.string.nav_downloads))))
                rowsAdapter.add(PageRow(HeaderItem(ID_SETTINGS, getString(R.string.nav_settings))))
            }.onFailure {
                val a = ArrayObjectAdapter(CardPresenter())
                a.add(getString(R.string.offline))
                rowsAdapter.add(ListRow(HeaderItem(99, getString(R.string.offline)), a))
            }
        }
    }

    /** Builds the fragment behind a PageRow when its sidebar entry is selected. */
    private class PageFragmentFactory : BrowseSupportFragment.FragmentFactory<Fragment>() {
        override fun createFragment(row: Any?): Fragment {
            val header = (row as? PageRow)?.headerItem
            return when (header?.id) {
                ID_DOWNLOADS -> DownloadsFragment()
                ID_SETTINGS  -> SettingsFragment()
                else         -> DownloadsFragment()
            }
        }
    }

    private fun openUnit(unit: CatalogUnit) {
        startActivity(
            Intent(requireContext(), UnitActivity::class.java)
                .putExtra(UnitActivity.EXTRA_UNIT_ID, unit.id)
                .putExtra(UnitActivity.EXTRA_UNIT_TITLE, unit.titleEn)
                .putExtra(UnitActivity.EXTRA_COLOR_1, unitStyle[unit.id]?.color1)
                .putExtra(UnitActivity.EXTRA_COLOR_2, unitStyle[unit.id]?.color2)
                .putExtra(UnitActivity.EXTRA_SUBJECT, unitStyle[unit.id]?.nameEn)
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
        const val ID_DOWNLOADS = 900L
        const val ID_SETTINGS  = 901L
    }
}
