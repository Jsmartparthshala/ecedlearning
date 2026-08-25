package np.com.jagdamba.eced.tv

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.VerticalGridPresenter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import np.com.jagdamba.eced.core.model.Lesson

/**
 * Unit detail: the lessons inside one unit.
 *
 * A vertical grid rather than Leanback's DetailsFragment because units here run
 * 6-12 lessons and a grid shows them all at once. DetailsFragment is built around
 * one hero item plus related rows, which is the wrong shape for this.
 */
class UnitActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            val f = UnitGridFragment().apply {
                arguments = Bundle().apply {
                    putString(EXTRA_UNIT_ID, intent.getStringExtra(EXTRA_UNIT_ID))
                    putString(EXTRA_UNIT_TITLE, intent.getStringExtra(EXTRA_UNIT_TITLE))
                    putString(EXTRA_ACCENT, intent.getStringExtra(EXTRA_ACCENT))
                }
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_container, f)
                .commit()
        }
    }

    companion object {
        const val EXTRA_UNIT_ID    = "unit_id"
        const val EXTRA_UNIT_TITLE = "unit_title"
        const val EXTRA_ACCENT     = "accent"
    }
}

class UnitGridFragment : VerticalGridSupportFragment() {

    private val gridAdapter by lazy {
        ArrayObjectAdapter(
            CardPresenter(CardPresenter.parse(arguments?.getString(UnitActivity.EXTRA_ACCENT)))
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        title = arguments?.getString(UnitActivity.EXTRA_UNIT_TITLE)
            ?.removePrefix("[PLACEHOLDER] ")

        // setGridPresenter(), not the property — Leanback exposes an asymmetric
        // getter/setter pair that Kotlin surfaces as a read-only `val`.
        setGridPresenter(VerticalGridPresenter().apply { numberOfColumns = 4 })
        adapter = gridAdapter

        setOnItemViewClickedListener { _, item, _, _ ->
            if (item is Lesson && item.isPlayable) {
                startActivity(
                    Intent(requireContext(), PlayerActivity::class.java)
                        .putExtra(PlayerActivity.EXTRA_LESSON_ID, item.id)
                        .putExtra(PlayerActivity.EXTRA_VIDEO_URL, item.videoUrl)
                        .putExtra(PlayerActivity.EXTRA_TITLE, item.titleEn)
                )
            }
        }

        load()
    }

    private fun load() {
        val unitId = arguments?.getString(UnitActivity.EXTRA_UNIT_ID) ?: return
        lifecycleScope.launch {
            runCatching { EcedApp.instance.catalog.lessons(unitId) }
                .onSuccess { lessons -> lessons.forEach { gridAdapter.add(it) } }
        }
    }
}
