package np.com.jagdamba.eced.tv

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import np.com.jagdamba.eced.core.model.Lesson
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

    /** unit id -> owning subject's colour, so the unit screen keeps the same accent. */
    private val unitAccent = mutableMapOf<String, String?>()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        title = getString(R.string.browse_title)
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = Color.parseColor("#0B0E13")
        searchAffordanceColor = Color.parseColor("#E8B64C")

        adapter = rowsAdapter
        loadCatalog()

        setOnItemViewClickedListener { _, item, _, _ ->
            when (item) {
                is Lesson -> openLesson(item)
                is CatalogUnit -> openUnit(item)
            }
        }
    }

    private fun loadCatalog() {
        val repo = EcedApp.instance.catalog

        lifecycleScope.launch {
            runCatching {
                // "Playable now" first — 963 of 968 lessons have no video yet, so
                // without this row a demo can wander for a while before finding one.
                val playable = repo.playableSample()
                if (playable.isNotEmpty()) {
                    val a = ArrayObjectAdapter(CardPresenter())
                    playable.forEach { a.add(it) }
                    rowsAdapter.add(ListRow(HeaderItem(0, getString(R.string.row_playable)), a))
                }

                repo.subjects().forEachIndexed { i, subject ->
                    val units = repo.units(subject.id)
                    val a = ArrayObjectAdapter(CardPresenter(CardPresenter.parse(subject.color1)))
                    units.forEach {
                        unitAccent[it.id] = subject.color1
                        a.add(it)
                    }
                    rowsAdapter.add(
                        ListRow(HeaderItem((i + 1).toLong(), subject.nameEn), a)
                    )
                }
            }.onFailure {
                val a = ArrayObjectAdapter(CardPresenter())
                a.add(getString(R.string.offline))
                rowsAdapter.add(ListRow(HeaderItem(99, "…"), a))
            }
        }
    }

    private fun openUnit(unit: CatalogUnit) {
        startActivity(
            Intent(requireContext(), UnitActivity::class.java)
                .putExtra(UnitActivity.EXTRA_UNIT_ID, unit.id)
                .putExtra(UnitActivity.EXTRA_UNIT_TITLE, unit.titleEn)
                .putExtra(UnitActivity.EXTRA_ACCENT, unitAccent[unit.id])
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
}
