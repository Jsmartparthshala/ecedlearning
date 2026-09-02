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

        /** The tile's colour at rest, so leaving focus can restore it. */
        var restFill: Int = 0
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
                h.restFill = tint(item.subject.color1)
                    .takeIf { it != 0 }
                    ?: ContextCompat.getColor(context, R.color.subject_neutral)
                h.name.text = item.subject.nameEn
                h.setSecondary(item.subject.nameNp)
                // A zero here means the count is unknown, not that the subject is
                // empty: on a database without 0007 there is no subject_cards view
                // to read unit_count from, and every tile would otherwise claim
                // "0 units" for a subject that has twenty-four. Say nothing rather
                // than something false.
                if (item.unitCount > 0) {
                    h.count.text = context.getString(R.string.unit_count, item.unitCount)
                    h.count.visibility = View.VISIBLE
                } else {
                    h.count.text = ""
                    h.count.visibility = View.GONE
                }
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
                h.restFill = ContextCompat.getColor(
                    context,
                    if (item.level.hasContent) stageColor else R.color.subject_neutral_dark
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
                // Neutral, so the tinted subjects stay the thing the eye lands on.
                h.restFill = ContextCompat.getColor(context, R.color.subject_neutral)
                h.name.text = item.label
                h.setSecondary(null)
                h.count.text = ""
                h.count.visibility = View.GONE
            }

            else -> {
                h.restFill = ContextCompat.getColor(context, R.color.subject_neutral)
                h.name.text = item?.toString().orEmpty()
                h.setSecondary(null)
                h.count.text = ""
                h.count.visibility = View.GONE
            }
        }

        // Bound tiles can already hold focus - Leanback recycles these - so the
        // state is applied now and not only when it next changes.
        h.view.setOnFocusChangeListener { _, hasFocus -> h.applyFocus(hasFocus) }
        h.applyFocus(h.view.hasFocus())
    }

    override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) {
        val h = viewHolder as Holder
        h.name.text = null
        h.nameNp.text = null
        h.count.text = null
    }

    /**
     * A catalogue colour, re-graded to something that belongs on cream.
     *
     * `subjects.color_1` holds fully saturated hex - #2AA9D8, #E1701A - chosen
     * when the app was dark and a tile was a block of colour. On a cream ground
     * those read as five competing signs rather than as one screen, which is the
     * whole complaint this rework exists to answer.
     *
     * Doing it here rather than in a migration is deliberate. The colours stay
     * the catalogue's own, an operator adding a subject cannot land a tile that
     * breaks the palette, and the two halves of the product - an APK and a
     * database updated by different people on different days - do not have to
     * agree on a release for the screen to look right.
     *
     * Lightness is forced rather than scaled so every hue arrives at the same
     * weight; saturation is capped rather than forced so blue still reads as
     * blue. Navy text measures 8.9-10:1 across the resulting set.
     */
    /** Re-graded for the cream ground. See [Palette]. */
    private fun tint(hex: String?): Int = Palette.soften(hex, 0)

    /**
     * Focus, as an inversion rather than a ring.
     *
     * The gold ring did this on the dark palette. Gold on cream is 1.51:1, so on
     * this palette the ring is not subtle, it is absent - see colors.xml. A
     * focused tile therefore fills brand navy and turns its text cream, which
     * measures 7.61:1 against the ground and reads from the back of a classroom
     * without a glow, a shadow or a scale animation that a Mali-450 would have to
     * pay for.
     *
     * Applied on bind as well as on change, because Leanback recycles these view
     * holders and a tile can be bound while it already holds focus.
     */
    private fun Holder.applyFocus(focused: Boolean) {
        val context = view.context
        art.setBackgroundColor(
            if (focused) ContextCompat.getColor(context, R.color.brand_navy) else restFill
        )
        val ink = ContextCompat.getColor(
            context, if (focused) R.color.on_focus else R.color.on_subject
        )
        name.setTextColor(ink)
        nameNp.setTextColor(ink)
        count.setTextColor(ink)
        count.setBackgroundColor(
            ContextCompat.getColor(
                context, if (focused) R.color.badge_scrim_inverse else R.color.badge_scrim
            )
        )
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
