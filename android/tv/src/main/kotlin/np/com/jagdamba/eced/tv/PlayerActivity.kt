package np.com.jagdamba.eced.tv

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Lesson playback.
 *
 * PlayerView gives us D-pad transport controls for free on TV: OK toggles play/pause,
 * left/right seek, down opens the control bar. Do not hand-roll these.
 */
class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var ticker: Job? = null

    private val lessonId by lazy { intent.getStringExtra(EXTRA_LESSON_ID).orEmpty() }
    private val videoUrl by lazy { intent.getStringExtra(EXTRA_VIDEO_URL).orEmpty() }
    private val title    by lazy { intent.getStringExtra(EXTRA_TITLE).orEmpty().clean() }

    private lateinit var view: PlayerView
    private lateinit var errorPanel: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        view = findViewById(R.id.player_view)
        errorPanel = findViewById(R.id.player_error)

        player = ExoPlayer.Builder(this).build().also { p ->
            view.player = p
            p.setMediaItem(MediaItem.fromUri(videoUrl))
            p.prepare()

            lifecycleScope.launch {
                // Resume where the class left off. This is the single most-noticed
                // feature in a classroom — lessons get interrupted constantly.
                val resumeSec = resumePosition()
                if (resumeSec > 5) p.seekTo(resumeSec * 1000L)
                p.playWhenReady = true
            }

            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        saveProgress(completed = true)
                        finish()
                    }
                }

                override fun onPlayerError(error: PlaybackException) = showError(error)

                override fun onPlayerErrorChanged(error: PlaybackException?) {
                    // Cleared by a successful retry. Take the panel down with it,
                    // rather than leaving the message over a video that is playing.
                    if (error == null) hideError()
                }
            })
        }

        startTicker()
    }

    /**
     * Says what happened, in a sentence a teacher can act on.
     *
     * A failed load otherwise leaves a black screen wearing the stock transport
     * bar, which reads as a video that is about to start rather than one that
     * will never arrive - and the only way out is a back button nobody mentioned.
     *
     * The distinction the message draws is the only one that is useful in a
     * classroom: the television could not fetch the video, or it fetched it and
     * could not play it. Everything media3 reports in the 2xxx range is the
     * former, which is also the one a teacher can do something about.
     */
    private fun showError(error: PlaybackException) {
        val network = error.errorCode in
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE

        errorPanel.findViewById<TextView>(R.id.pe_kicker).text =
            title.ifBlank { getString(R.string.player_error_kicker) }
        errorPanel.findViewById<TextView>(R.id.pe_title).setText(
            if (network) R.string.player_error_title_network else R.string.player_error_title_other
        )
        errorPanel.findViewById<TextView>(R.id.pe_body).setText(
            if (network) R.string.player_error_body_network else R.string.player_error_body_other
        )

        // One thing on screen saying what happened. The controller underneath is
        // still focusable and still shows a running clock over a video that does
        // not exist, so it goes away while the message is up.
        view.useController = false
        view.hideController()
        errorPanel.visibility = View.VISIBLE
    }

    private fun hideError() {
        errorPanel.visibility = View.GONE
        view.useController = true
    }

    /**
     * OK retries while the message is up.
     *
     * Retrying is worth a key of its own here because the common failure in a
     * school is a connection that comes back: the lesson that would not load a
     * moment ago loads now, and making the teacher walk back out to the grid and
     * in again to find that out is the wrong answer. Back still leaves.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
        if (ok && errorPanel.visibility == View.VISIBLE) {
            player?.let { p ->
                hideError()
                p.prepare()
                p.playWhenReady = true
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private suspend fun resumePosition(): Int {
        val deviceId = EcedApp.instance.devices.cachedDeviceId() ?: return 0
        return EcedApp.instance.progress.resumePosition(lessonId, deviceId)
    }

    /** Debounced write-back. Every 10s, not every frame — this is a 5 GB/month budget. */
    private fun startTicker() {
        ticker = lifecycleScope.launch {
            while (isActive) {
                delay(10_000)
                saveProgress(completed = false)
            }
        }
    }

    private fun saveProgress(completed: Boolean) {
        val p = player ?: return
        val deviceId = EcedApp.instance.devices.cachedDeviceId() ?: return
        val pos = (p.currentPosition / 1000).toInt()
        if (pos <= 0 && !completed) return

        lifecycleScope.launch {
            EcedApp.instance.progress.save(
                lessonId    = lessonId,
                positionSec = pos,
                completed   = completed,
                deviceId    = deviceId,
            )
        }
    }

    override fun onPause() {
        super.onPause()
        saveProgress(completed = false)
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        ticker?.cancel()
        player?.release()
        player = null
    }

    companion object {
        const val EXTRA_LESSON_ID = "lesson_id"
        const val EXTRA_VIDEO_URL = "video_url"
        const val EXTRA_TITLE     = "title"
    }
}
