package np.com.jagdamba.eced.core.data

import android.util.Log

/**
 * Every network call in this layer is wrapped in runCatching so a dead connection
 * degrades gracefully instead of crashing a classroom TV mid-lesson. That is the
 * right behaviour — but silent failure is not debuggable, and "the grid is empty
 * and there's nothing in the logs" is the worst possible thing to hit on demo day.
 *
 * So: swallow the failure, but always say why.
 */
internal const val TAG = "EcedData"

internal inline fun <T> quietly(what: String, block: () -> T): T? =
    runCatching(block)
        .onFailure { Log.e(TAG, "$what failed: ${it::class.simpleName}: ${it.message}", it) }
        .getOrNull()
