package np.com.jagdamba.eced.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.leanback.widget.SearchOrbView
import androidx.leanback.widget.TitleViewAdapter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The browse screen's header: brand mark on the left, live device status on the
 * right.
 *
 * Leanback will only accept a custom title view if it implements
 * [TitleViewAdapter.Provider] — the fragment talks to the adapter, never to the
 * view directly, which is what lets it animate the title in and out on scroll.
 *
 * The chips are deliberately passive. They read state that already exists
 * (cached school name, connectivity, clock) rather than polling anything, so the
 * header costs nothing on a slow box.
 */
class BrowseTitleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : LinearLayout(context, attrs, defStyle), TitleViewAdapter.Provider {

    private val school: TextView by lazy { findViewById(R.id.chip_school) }
    private val net: TextView    by lazy { findViewById(R.id.chip_net) }
    private val clock: TextView  by lazy { findViewById(R.id.chip_clock) }

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    /** Ticks the clock and refreshes connectivity, once a minute. */
    private val minuteTicker = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refresh()
    }

    private val adapter = object : TitleViewAdapter() {
        override fun getSearchAffordanceView(): View? = null
        override fun setTitle(titleText: CharSequence?) = Unit   // brand is fixed
        override fun updateComponentsVisibility(flags: Int) {
            visibility = if (flags and FULL_VIEW_VISIBLE != 0) VISIBLE else GONE
        }
    }

    override fun getTitleViewAdapter(): TitleViewAdapter = adapter

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refresh()
        // ACTION_TIME_TICK only fires while registered at runtime; it cannot be
        // declared in the manifest. One broadcast a minute is far cheaper than a
        // handler loop and stays in step with the system clock.
        context.registerReceiver(minuteTicker, IntentFilter(Intent.ACTION_TIME_TICK))
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        runCatching { context.unregisterReceiver(minuteTicker) }
    }

    fun refresh() {
        clock.text = timeFormat.format(Date())
        // The real transport and band, not a generic "Wi-Fi". No app can change
        // the band, so this reports rather than offers - the actionable version
        // lives in Settings, which opens the system Wi-Fi screen.
        net.text = NetworkStatus.label(context)
        val name = EcedApp.instance.devices.cachedSchoolName()
        school.text = name ?: context.getString(R.string.chip_unclaimed)
        school.visibility = if (name == null) GONE else VISIBLE
    }

}
