package np.com.jagdamba.eced.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.Presenter
import np.com.jagdamba.eced.core.model.Subject

/**
 * The home screen's subject tiles, and the two utility tiles beside them.
 *
 * These replaced the Leanback sidebar. A sidebar of nine entries put the five
 * subjects behind a list that a teacher had to read: the subjects are the whole
 * product, so they belong on the screen at full size, in their own colours,
 * chosen with one press.
 *
 * Same cost rules as [CardPresenter] - flat fills, text glyphs, stroke focus,
 * no bitmaps - because this is the first screen to draw on a 1 GB box.
 */
class SubjectPresenter : Presenter() {

    /** A subject plus the unit count shown on its badge. */
    data class SubjectTile(val subject: Subject, val unitCount: Int)

    /** Downloads and Settings. Not subjects, but the same tile shape. */
    data class UtilityTile(val id: Long, val label: String)

    class Holder(view: View) : Presenter.ViewHolder(view) {
        val art: FrameLayout = view.findViewById(R.id.cs_art)
        val name: TextView   = view.findViewById(R.id.cs_name)
        val count: TextView  = view.findViewById(R.id.cs_count)
    }

    override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder =
        Holder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.card_subject, parent, false)
        ).also { it.view.clipToOutline = true }

    override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
        val h = viewHolder as Holder
        val context = h.view.context

        when (item) {
            is SubjectTile -> {
                h.art.setBackgroundColor(
                    runCatching { android.graphics.Color.parseColor(item.subject.color1) }
                        .getOrDefault(ContextCompat.getColor(context, R.color.subject_neutral))
                )
                h.name.text = item.subject.nameEn
                h.count.text = context.getString(R.string.unit_count, item.unitCount)
                h.count.visibility = View.VISIBLE
            }

            is UtilityTile -> {
                // Neutral, so the coloured subjects stay the thing the eye lands on.
                h.art.setBackgroundColor(
                    ContextCompat.getColor(context, R.color.subject_neutral)
                )
                h.name.text = item.label
                h.count.text = ""
                h.count.visibility = View.GONE
            }

            else -> {
                h.name.text = item?.toString().orEmpty()
                h.count.text = ""
                h.count.visibility = View.GONE
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) {
        val h = viewHolder as Holder
        h.name.text = null
        h.count.text = null
    }
}
