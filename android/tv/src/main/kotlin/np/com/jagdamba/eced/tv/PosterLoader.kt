package np.com.jagdamba.eced.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Poster frames for lesson cards.
 *
 * There is no Glide or Coil in this project and this is not the place to add
 * one: those pull in hundreds of methods and a lifecycle integration to solve
 * problems a card grid on a fixed-size TV does not have. What follows is the
 * part that actually matters on the target hardware.
 *
 * The memory rule from CardPresenter still holds — an Amlogic/Rockchip box with
 * 1 GB of RAM cannot afford a full-size decode per card. Three things keep this
 * cheap:
 *
 *  - the server hands out 320x180 JPEGs, roughly 8 KB each, so nothing large is
 *    ever transferred in the first place;
 *  - they decode as RGB_565, half the bytes of ARGB_8888, and a photographic
 *    frame has no alpha channel to lose. 320x180 lands at 115 KB;
 *  - the cache is bounded as a fraction of the heap, not a fixed count, so a
 *    512 MB box holds fewer than a 2 GB box instead of both hitting the same
 *    wall.
 *
 * Cards are recycled constantly while a row scrolls, so every load tags its
 * target ImageView with the URL it is fetching and drops the result if the tag
 * has changed by the time it returns. Without that check a fast scroll leaves
 * the wrong picture on the wrong card - the classic recycled-view bug.
 */
object PosterLoader {

    /** Two threads: enough to keep a scrolling row fed, few enough to stay polite. */
    private val io = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "poster-loader").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val main = Handler(Looper.getMainLooper())

    /** An eighth of the available heap. On a 1 GB box that is a few MB - dozens of cards. */
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8L).coerceIn(2L * 1024 * 1024, 24L * 1024 * 1024).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** URLs that failed once. Retrying every rebind would hammer a dead server. */
    private val failed = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Show [url] in [view], or leave the view empty if it cannot be had.
     *
     * Safe to call on every bind. A cache hit is applied synchronously so a
     * scroll back through an already-seen row does not flicker.
     *
     * @param onShown run once, on the main thread, at the moment a picture is
     *        actually on screen - and never if there is not going to be one. A
     *        card's badges, scrim and fallback numeral all have to know what is
     *        underneath them, and until this fires the answer is "the flat
     *        subject colour", not "a photograph that is on its way". Callers
     *        used to treat a URL as good as a picture, which left a card wearing
     *        a scrim over nothing for as long as the fetch took - a featureless
     *        grey rectangle, on exactly the slow connections these schools have.
     */
    fun load(view: ImageView, url: String?, onShown: (() -> Unit)? = null) {
        view.setImageBitmap(null)
        if (url.isNullOrBlank() || url in failed) {
            view.tag = null
            return
        }

        cache.get(url)?.let {
            view.tag = url
            view.setImageBitmap(it)
            onShown?.invoke()
            return
        }

        view.tag = url
        io.execute {
            val bmp = fetch(url)
            if (bmp == null) {
                failed.add(url)
                return@execute
            }
            cache.put(url, bmp)
            main.post {
                // The card may have been recycled onto a different lesson while
                // this was in flight. Only paint if it still wants this picture.
                if (view.tag == url) {
                    view.setImageBitmap(bmp)
                    onShown?.invoke()
                }
            }
        }
    }

    /** Called when a card is unbound, so an in-flight load lands nowhere. */
    fun cancel(view: ImageView) {
        view.tag = null
        view.setImageBitmap(null)
    }

    private fun fetch(url: String): Bitmap? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 6000
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) null
            else {
                // Read fully before decoding. BitmapFactory on a live socket
                // stream fails intermittently on slow links - it gets a short
                // read and returns null with no error. These are 8 KB.
                val buf = ByteArrayOutputStream(16 * 1024)
                BufferedInputStream(conn.inputStream).use { it.copyTo(buf) }
                val bytes = buf.toByteArray()
                BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                )
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        null
    }
}
