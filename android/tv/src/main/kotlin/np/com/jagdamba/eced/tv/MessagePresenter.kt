package np.com.jagdamba.eced.tv

import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.Presenter

/**
 * A row that is one line of text rather than a row of cards.
 *
 * This exists because the loading state used to be a String handed to
 * CardPresenter, whose `else` branch puts the text in the title slot and leaves
 * the artwork panel filled with the default flat grey. On screen that is a full
 * 280dp card with a large empty slab above the word "Loading" - which reads as a
 * card that failed to load its picture, at the exact moment the app is trying to
 * say that nothing has gone wrong.
 *
 * Built in code rather than inflated: it is one TextView, and a layout file for
 * one TextView is a file to keep in sync for no gain.
 */
class MessagePresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val context = parent.context
        val text = TextView(context).apply {
            // Not focusable. There is nothing here to open, and a focusable
            // placeholder would take the gold ring and imply there is.
            isFocusable = false
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(ContextCompat.getColor(context, R.color.tv_muted))
            setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.text_body),
            )
            minHeight = resources.getDimensionPixelSize(R.dimen.card_height)
        }
        return ViewHolder(text)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        (viewHolder.view as TextView).text = item?.toString().orEmpty()
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        (viewHolder.view as TextView).text = null
    }
}
