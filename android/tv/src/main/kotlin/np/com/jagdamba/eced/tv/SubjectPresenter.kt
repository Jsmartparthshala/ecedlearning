package np.com.jagdamba.eced.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.Presenter
import np.com.jagdamba.eced.core.model.Level
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

    /**
     * One grade in the ladder row. [selected] is the grade currently showing in
     * the Subjects row above, marked so a teacher can see where they are without
     * having to move focus back down to find out.
     */
    data class LevelTile(val level: Level, val selected: Boolean)

    class Holder(view: View) : Presenter.ViewHolder(view) {
        val art: FrameLayout = view.findViewById(R.id.cs_art)
        val name: TextView   = view.findViewById(R.id.cs_name)
        val nameNp: TextView = view.findViewById(R.id.cs_name_np)
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
                h.setSecondary(item.subject.nameNp)
                h.count.text = context.getString(R.string.unit_count, item.unitCount)
                h.count.visibility = View.VISIBLE
            }

            is LevelTile -> {
                // Colour by stage rather than per grade: thirteen distinct hues
                // would be noise, and the four stages are the grouping a teacher
                // actually thinks in. A grade with nothing in it yet is drawn
                // muted and says so, instead of opening an empty screen.
                val stageColor = when (item.level.stage) {
                    "eced"      -> R.color.stage_eced
                    "basic"     -> R.color.stage_basic
                    "secondary" -> R.color.stage_secondary
                    else        -> R.color.stage_higher
                }
                h.art.setBackgroundColor(
                    ContextCompat.getColor(
                        context,
                        if (item.level.hasContent) stageColor else R.color.subject_neutral_dark
                    )
                )
                h.name.text = item.level.nameEn
                h.setSecondary(item.level.nameNp)
                h.count.text = when {
                    !item.level.hasContent -> context.getString(R.string.level_empty)
                    item.selected          -> context.getString(R.string.level_showing)
                    else -> context.getString(R.string.level_subject_count, item.level.subjectCount)
                }
                h.count.visibility = View.VISIBLE
            }

            is UtilityTile -> {
                // Neutral, so the coloured subjects stay the thing the eye lands on.
                h.art.setBackgroundColor(
                    ContextCompat.getColor(context, R.color.subject_neutral)
                )
                h.name.text = item.label
                h.setSecondary(null)
                h.count.text = ""
                h.count.visibility = View.GONE
            }

            else -> {
                h.name.text = item?.toString().orEmpty()
                h.setSecondary(null)
                h.count.text = ""
                h.count.visibility = View.GONE
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) {
        val h = viewHolder as Holder
        h.name.text = null
        h.nameNp.text = null
        h.count.text = null
    }

    /**
     * The Nepali line, hidden rather than left empty when a row has none.
     *
     * GONE and not INVISIBLE: the column is bottom-anchored, so an invisible line
     * would still hold its height and lift the English name off the baseline the
     * tiles beside it use. A half-translated catalogue would then show as a row
     * of tiles whose titles do not line up.
     */
    private fun Holder.setSecondary(text: String?) {
        if (text.isNullOrBlank()) {
            nameNp.text = null
            nameNp.visibility = View.GONE
        } else {
            nameNp.text = text
            nameNp.visibility = View.VISIBLE
        }
    }
}
