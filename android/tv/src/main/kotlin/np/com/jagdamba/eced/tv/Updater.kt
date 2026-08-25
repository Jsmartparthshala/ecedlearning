package np.com.jagdamba.eced.tv

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import np.com.jagdamba.eced.core.model.AppRelease
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Over-the-air updates, without an app store.
 *
 * These televisions are sideloaded and many of the cheap boxes have no Play
 * Store at all, so the update path is: read the one row in `app_release`, compare
 * version codes, fetch the APK, and hand it to the system package installer.
 *
 * Android 12+ always shows its own confirmation dialog before installing, and
 * there is no way around that short of device-owner provisioning, which reverse
 * provisioning deliberately avoids. So the teacher confirms twice - once in this
 * app, once to the system. That is the honest ceiling for a sideloaded product,
 * and pretending otherwise in the UI would be a lie a school discovers the first
 * time it updates.
 */
object Updater {

    /** What a check found. Rendered directly by the Settings screen. */
    sealed interface Result {
        /** Already on the newest build, or the catalogue has no release row. */
        data object UpToDate : Result

        /** A newer build exists and can be fetched. */
        data class Available(val release: AppRelease) : Result

        /**
         * A newer build exists but the release row carries no APK URL, so there
         * is nothing to download. Called out separately because it is an
         * operator mistake in the ops console, not a fault on the television.
         */
        data class NoDownload(val release: AppRelease) : Result

        /** The check itself failed - almost always no network. */
        data object Failed : Result
    }

    /** Version code of the build currently running. */
    private val installed: Int get() = BuildConfig.VERSION_CODE

    suspend fun check(): Result = withContext(Dispatchers.IO) {
        // latestRelease() already swallows its own errors and returns null, so a
        // null here means either "no row" or "the request failed". Both leave the
        // television on a working build, which is why they collapse to one
        // non-alarming outcome rather than an error the teacher cannot act on.
        val release = EcedApp.instance.catalog.latestRelease() ?: return@withContext Result.Failed

        when {
            release.versionCode <= installed -> Result.UpToDate
            release.apkUrl.isNullOrBlank()   -> Result.NoDownload(release)
            else                             -> Result.Available(release)
        }
    }

    /**
     * Downloads the APK and returns the file, or null if the fetch failed.
     *
     * Written to the external cache dir because that is what [FileProvider] can
     * hand to the installer, and because the system clears it under storage
     * pressure - a half-downloaded APK on a 8 GB box must not be permanent.
     *
     * @param onProgress fraction 0..1, or -1 when the server sends no
     *        Content-Length (common on GitHub Releases redirects).
     */
    suspend fun download(
        context: Context,
        release: AppRelease,
        onProgress: (Float) -> kotlin.Unit,
    ): File? = withContext(Dispatchers.IO) {
        val url = release.apkUrl ?: return@withContext null
        val target = File(context.externalCacheDir ?: context.cacheDir, "update-${release.versionCode}.apk")

        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                // Redirects are the norm here: GitHub Releases and most CDNs
                // answer with a 302 to a signed URL.
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
            }

            connection.inputStream.use { input ->
                val total = connection.contentLength.toLong()
                var read = 0L
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        onProgress(if (total > 0) read.toFloat() / total else -1f)
                    }
                }
            }
            connection.disconnect()
            target
        }.getOrElse {
            // A partial APK is worse than none: the installer would reject it with
            // a parse error that reads like the app is broken.
            target.delete()
            null
        }
    }

    /**
     * Hands the downloaded APK to the system installer.
     *
     * A `file://` URI throws FileUriExposedException on anything since Nougat, so
     * this must go through the FileProvider declared in the manifest.
     */
    fun install(context: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.updates", apk
        )
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
