package np.com.jagdamba.eced.tv

import android.content.Context
import android.graphics.Color
import androidx.core.content.ContextCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.leanback.widget.Presenter
import np.com.jagdamba.eced.core.model.Lesson
import np.com.jagdamba.eced.core.model.Unit as CatalogUnit

/**
 * Card presenter, built for the hardware this actually ships to: Amlogic /
 * Allwinner / Rockchip boxes with a Mali-450-class GPU and often 1 GB of RAM.
 *
 * Deliberately NOT using Leanback's ImageCardView: it renders nothing useful
 * without a bitmap, and a bitmap per card is the single biggest memory cost on a
 * 1 GB device — one 1920x1080 ARGB_8888 decode is 8.3 MB.
 *
 * What this does instead is cheap on purpose:
 *  - artwork is a flat solid colour, not an image and not a gradient. No decode,
 *    no allocation, no shader, no thumbnail pipeline.
 *  - the large glyph is text, so it costs a font lookup rather than memory
 *  - focus is a stroke, not elevation or a glow
 *  - the artwork panel and info strip do not overlap, so overdraw stays at 1x
 *
 * @param subject colours and identity for the owning subject, so a row is
 *        recognisable at a glance from ten feet away.
 * @param progress lesson id -> 0..1 watched, read at bind time.
 */
class CardPresenter(
    private val subject: SubjectStyle = SubjectStyle.DEFAULT,
    private val progress: Map<String, Float> = emptyMap(),
) : Presenter() {

    /**
     * Everything the card needs to know about a subject, resolved once per row.
     * `colorEnd` is kept for the darker surfaces elsewhere (unit header, hero),
     * not for blending — cards are a single flat fill.
     */
    data class SubjectStyle(
        val colorStart: Int,
        val colorEnd: Int,
        val label: String,
    ) {
        companion object {
            /**
             * Fallback for rows that span subjects (Continue watching, Playable
             * now). Resolved from resources rather than a literal so it follows
             * the light/dark palette.
             */
            fun default(context: Context) = SubjectStyle(
                ContextCompat.getColor(context, R.color.subject_neutral),
                ContextCompat.getColor(context, R.color.subject_neutral_dark),
                "",
            )

            /**
             * Context-free fallback, for the paths that resolve a style before
             * they have one. These were `#3A4453` and `#1E2530` - two dark
             * slates, which was right when the page behind them was darker
             * still. On cream they are the two heaviest things on the screen,
             * and the navy type they carry drops to 1.4:1. Same neutrals as
             * `subject_neutral` and `subject_neutral_dark`, kept in step by
             * hand because a companion object has no Context to resolve them.
             */
            val DEFAULT = SubjectStyle(
                Color.parseColor("#E8E2D2"),
                Color.parseColor("#F3EFE3"),
                "",
            )

            /**
             * Both colours go through [Palette.soften], so a card and a subject
             * tile render the same subject as the same colour. They did not
             * before: the tile re-graded and the card did not, which put a pale
             * "ECED English" tile in the top row and a fully saturated one in
             * Continue watching directly underneath it.
             */
            fun of(color1: String?, color2: String?, label: String?) = SubjectStyle(
                colorStart = Palette.soften(color1, DEFAULT.colorStart),
                colorEnd   = Palette.soften(color2, DEFAULT.colorEnd),
                label      = label.orEmpty(),
            )
        }
    }

    class Holder(view: View) : Presenter.ViewHolder(view) {
        val art: FrameLayout   = view.findViewById(R.id.card_art)
        val poster: ImageView  = view.findViewById(R.id.card_poster)
        val scrim: View        = view.findViewById(R.id.card_poster_scrim)
        val glyph: TextView    = view.findViewById(R.id.card_glyph)
        val kicker: TextView   = view.findViewById(R.id.card_kicker)
        val count: TextView    = view.findViewById(R.id.card_count)
        val title: TextView    = view.findViewById(R.id.card_title)
        val meta: TextView     = view.findViewById(R.id.card_meta)
        val track: FrameLayout = view.findViewById(R.id.card_progress_track)
        val fill: View         = view.findViewById(R.id.card_progress_fill)
    }

    override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder =
        Holder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.card_lesson, parent, false)
        )

    override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
        val h = viewHolder as Holder
        // Flat fill, not a gradient: one solid colour is the cheapest thing a GPU
        // can draw, and it keeps the subject identity unambiguous at ten feet.
        // It also stays as the backdrop behind any poster, so a card that is
        // still loading — or whose poster never arrives — looks deliberate
        // rather than broken.
        h.art.setBackgroundColor(subject.colorStart)
        showPoster(h, (item as? Lesson)?.posterUrl)

        when (item) {
            is CatalogUnit -> {
                h.glyph.text  = item.sortOrder.toString()
                h.kicker.text = h.kicker.context.getString(R.string.card_unit_kicker)
                val n = item.estDays ?: 0
                h.count.text  = if (n > 0) "$n videos" else ""
                h.title.text  = item.titleEn.clean()
                h.meta.text   = subject.label
                showProgress(h, 0f)
            }

            is Lesson -> {
                h.glyph.text  = item.sortOrder.toString()
                h.kicker.text = h.kicker.context.getString(R.string.card_lesson_kicker)
                h.count.text  = "${(item.durationSec ?: 0) / 60} min"
                h.title.text  = item.titleEn.clean()
                // 963 of 968 lessons have no video yet. Saying so on the card is
                // worth the space: landing on a dead card mid-demo looks like a crash.
                h.meta.text = when {
                    !item.isPlayable -> h.meta.context.getString(R.string.no_video_short)
                    // Rows that span subjects carry no label, which left the line
                    // blank and the card looking like it had failed to load half
                    // its content. Say how it plays instead.
                    subject.label.isBlank() -> h.meta.context.getString(R.string.lesson_stream)
                    else -> subject.label
                }
                showProgress(h, progress[item.id] ?: 0f)
            }

            else -> {
                h.glyph.text = ""
                h.kicker.text = ""
                h.count.text = ""
                h.title.text = item?.toString().orEmpty()
                h.meta.text = ""
                showProgress(h, 0f)
            }
        }

        // Focus repaints what a state list cannot reach: the scrubber, and the
        // badges sitting on the artwork. Set both here and immediately, because
        // a recycled holder arrives carrying the last card's state.
        h.view.setOnFocusChangeListener { _, hasFocus -> h.paint(hasFocus) }
        h.paint(h.view.hasFocus())
    }

    /**
     * The card inverts on focus - white plate to navy - which `card_bg` and the
     * `text_on_surface` state lists handle for the title and the strip. Three
     * things they cannot handle: the scrubber, which is a plain View with a
     * colour rather than a state list; and the two badges over the artwork,
     * whose legibility depends on whether a real poster loaded underneath them,
     * which is a data question and not a view state.
     *
     * A ColorStateList on `android:background` would cover the first, but only
     * from API 29. This ships to minSdk 23.
     */
    private fun Holder.paint(focused: Boolean) {
        val c = view.context
        val onPoster = scrim.visibility == View.VISIBLE

        // On the white strip the scrubber has to be navy - gold is 1.66:1 there
        // and reads as an empty track. Once the card is navy, gold is the only
        // one of the two that shows.
        fill.setBackgroundColor(
            ContextCompat.getColor(c, if (focused) R.color.focus_gold else R.color.brand_navy)
        )
        track.setBackgroundColor(
            ContextCompat.getColor(
                c, if (focused) R.color.badge_scrim_inverse else R.color.hairline_strong
            )
        )

        // Over a scrimmed video frame nothing dark survives; over the flat
        // subject tint navy is 8:1 and white would be 1.3:1.
        val ink = if (onPoster) Color.WHITE else ContextCompat.getColor(c, R.color.on_subject)
        val badge = ContextCompat.getColor(
            c, if (onPoster) R.color.badge_scrim_inverse else R.color.badge_scrim
        )
        glyph.setTextColor(ink)
        kicker.setTextColor(ink)
        count.setTextColor(ink)
        kicker.setBackgroundColor(badge)
        count.setBackgroundColor(badge)
    }

    /**
     * Units have no poster of their own and unplayable lessons have nothing to
     * show a frame of, so both keep the flat colour panel. Only a lesson with a
     * real video gets a picture, which makes the picture itself a signal that
     * the card will play.
     */
    private fun showPoster(h: Holder, url: String?) {
        if (url.isNullOrBlank()) {
            PosterLoader.cancel(h.poster)
            h.scrim.visibility = View.GONE
            return
        }
        h.scrim.visibility = View.VISIBLE
        PosterLoader.load(h.poster, url)
    }

    private fun showProgress(h: Holder, fraction: Float) {
        if (fraction <= 0.01f) {
            h.track.visibility = View.GONE
            return
        }
        h.track.visibility = View.VISIBLE
        // post() because width is 0 until the card has been laid out once.
        h.track.post {
            h.fill.layoutParams = h.fill.layoutParams.apply {
                width = (h.track.width * fraction.coerceIn(0f, 1f)).toInt()
            }
            h.fill.requestLayout()
        }
    }

    override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) {
        val h = viewHolder as Holder
        h.title.text = null
        h.meta.text = null
        h.glyph.text = null
        h.track.visibility = View.GONE
        // Releases the card's reference to the bitmap and makes any in-flight
        // fetch land nowhere. The cache still holds it, so rebinding is instant.
        PosterLoader.cancel(h.poster)
        h.scrim.visibility = View.GONE
    }

}
