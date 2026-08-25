package np.com.jagdamba.eced.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        val view = findViewById<PlayerView>(R.id.player_view)

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
            })
        }

        startTicker()
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
