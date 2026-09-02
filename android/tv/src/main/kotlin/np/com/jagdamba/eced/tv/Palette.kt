package np.com.jagdamba.eced.tv

import android.graphics.Color
import androidx.core.graphics.ColorUtils

/**
 * Subject colours arrive from Postgres as raw hex, chosen when the palette was
 * dark and picked to glow against it: `#E8B64C`, `#2AA9D8`, and so on. On a cream
 * page those are five saturated blocks fighting the ground and each other, and
 * the navy type that has to sit on them drops to around 3:1.
 *
 * Re-graded here rather than in a migration, on purpose. The colours are the
 * school's identity for each subject and the hue is the part that carries it;
 * what does not travel between a dark page and a light one is the lightness and
 * the saturation. Fixing that client side keeps the hue authoritative in the
 * database, lets the APK and the catalogue ship on their own schedules, and
 * means a box still running last month's build renders last month's palette
 * correctly instead of a half-migrated one.
 *
 * The transform is deliberately blunt: keep the hue exactly, cap saturation, and
 * force one lightness for every subject. Forcing rather than scaling is what
 * makes the result a set - five tints at a common weight, which is what lets one
 * ink colour be legible on all of them - where scaling would preserve the
 * original spread and leave the yellow twice as light as the blue.
 */
object Palette {

    /** Every subject tint lands here, so one ink colour works on all of them. */
    private const val TINT_LIGHTNESS = 0.86f

    /** Above this a tint stops being a ground and starts being a signal. */
    private const val TINT_SATURATION_CEILING = 0.60f

    fun soften(color: Int): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[1] = minOf(hsl[1], TINT_SATURATION_CEILING)
        hsl[2] = TINT_LIGHTNESS
        return ColorUtils.HSLToColor(hsl)
    }

    /**
     * Parses a hex string from the catalogue and re-grades it. A colour the
     * database never filled in, or filled in wrongly, falls back rather than
     * throwing - a bad hex on one subject should cost that subject its tint,
     * not take the row down.
     */
    fun soften(hex: String?, fallback: Int): Int =
        runCatching { soften(Color.parseColor(hex ?: "")) }.getOrDefault(fallback)
}
