package np.com.jagdamba.eced.tv

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.leanback.widget.Presenter
import np.com.jagdamba.eced.core.model.Lesson
import np.com.jagdamba.eced.core.model.Unit as CatalogUnit

/**
 * Flat card presenter, built for the hardware this actually ships to: Amlogic /
 * Allwinner / Rockchip boxes with a Mali-450-class GPU and often 1 GB of RAM.
 *
 * Deliberately NOT using Leanback's ImageCardView:
 *  - it renders nothing useful without a bitmap, and we have no artwork yet
 *  - a bitmap per card is the single biggest memory cost on a 1 GB device
 *    (one 1920x1080 ARGB_8888 decode is 8.3 MB)
 *
 * A flat coloured card is cheaper to draw, needs no thumbnail pipeline, and for
 * children's content it reads better than a frozen video frame. Overdraw stays at
 * 1x: one opaque background, one accent bar, two text views. Focus is a stroke
 * rather than a glow or elevation, because strokes are effectively free here and
 * blurs are not.
 *
 * @param accentColor the owning subject's colour, so a row is identifiable at a
 *        glance from ten feet away.
 */
class CardPresenter(private val accentColor: Int = DEFAULT_ACCENT) : Presenter() {

    class Holder(view: View) : Presenter.ViewHolder(view) {
        val accent: View   = view.findViewById(R.id.card_accent)
        val title: TextView = view.findViewById(R.id.card_title)
        val meta: TextView  = view.findViewById(R.id.card_meta)
    }

    override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.card_lesson, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
        val h = viewHolder as Holder
        h.accent.setBackgroundColor(accentColor)

        when (item) {
            is CatalogUnit -> {
                h.title.text = item.titleEn.clean()
                val n = item.estDays ?: 0
                h.meta.text = if (n > 0) "$n lessons" else ""
            }

            is Lesson -> {
                h.title.text = item.titleEn.clean()
                // 963 of 968 lessons have no video yet. Saying so on the card is
                // worth two lines: landing on a dead card mid-demo looks like a crash.
                h.meta.text = if (item.isPlayable) {
                    "${(item.durationSec ?: 0) / 60} min"
                } else {
                    h.meta.context.getString(R.string.no_video_short)
                }
            }

            else -> {
                h.title.text = item?.toString().orEmpty()
                h.meta.text = ""
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) {
        val h = viewHolder as Holder
        h.title.text = null
        h.meta.text = null
    }

    private fun String.clean() = removePrefix("[PLACEHOLDER] ")

    companion object {
        val DEFAULT_ACCENT: Int = Color.parseColor("#E8B64C")

        /** Subject colours come from the database, so a new subject needs no code. */
        fun parse(hex: String?): Int = runCatching {
            Color.parseColor(hex ?: "")
        }.getOrDefault(DEFAULT_ACCENT)
    }
}
