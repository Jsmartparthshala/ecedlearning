package np.com.jagdamba.eced.tv

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import np.com.jagdamba.eced.core.model.AppRelease
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Background update delivery.
 *
 * [Updater] already knew how to find a release, fetch it and hand it to the
 * system installer - but only when somebody walked to the television, opened
 * Settings and pressed a button. Across forty sideloaded boxes in Nepali schools
 * that is not an update mechanism, it is a site visit.
 *
 * So the work splits in two, along the line Android itself draws:
 *
 *  - **Delivery is silent.** A daily check on Wi-Fi, and the APK is fetched and
 *    staged on disk before anyone is asked anything. This is the part that takes
 *    minutes on a school link, and the part nobody should have to sit and watch.
 *
 *  - **Installation is not, and cannot be.** Android 12 and up always shows its
 *    own confirmation before installing a package, and the only way past it is
 *    device-owner provisioning, which reverse provisioning deliberately avoids.
 *    A television that installed and restarted itself mid-lesson would be the
 *    wrong product even on a platform that allowed it.
 *
 * What the split buys is the thing that actually matters on a slow link: by the
 * time anyone says yes, the bytes are already there and the install is immediate,
 * instead of a teacher watching a percentage crawl in front of a class.
 *
 * Unmetered only. Some of these boxes run off a phone hotspot, and a 14 MB APK
 * pulled silently onto somebody's data would be this feature's first and last
 * impression.
 */
object UpdateDelivery {

    private const val TAG = "eced.update"
    private const val WORK_NAME = "eced-update-check"
    private const val PREFS = "updates"
    private const val KEY_CODE = "staged_version_code"
    private const val KEY_NAME = "staged_version_name"
    private const val KEY_PATH = "staged_path"
    private const val KEY_MANDATORY = "staged_mandatory"

    /** An APK already on disk and newer than what is running. */
    data class Staged(
        val versionCode: Int,
        val versionName: String,
        val mandatory: Boolean,
        val apk: File,
    )

    /**
     * Registers the daily check. Safe to call on every process start: KEEP leaves
     * an existing schedule alone rather than restarting it, so a television that
     * gets power-cycled twice a day does not keep resetting its own interval and
     * never reach the end of one.
     *
     * WorkManager persists the schedule across reboots itself, which is why there
     * is no BOOT_COMPLETED receiver here to get wrong.
     */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<Worker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    // An 8 GB box with a full partition should not be handed a
                    // download it cannot finish.
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
        )
    }

    /**
     * The staged build, if there is one still worth offering.
     *
     * Everything is re-checked rather than trusted. The file lives in the
     * external cache, which the system is free to clear under storage pressure;
     * and the record outlives the app being updated by some other route, in which
     * case the staged version is no longer newer and has to disappear quietly
     * rather than offer a downgrade.
     */
    fun staged(context: Context): Staged? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val code = prefs.getInt(KEY_CODE, 0)
        val path = prefs.getString(KEY_PATH, null)

        if (code <= BuildConfig.VERSION_CODE || path == null) {
            clear(context)
            return null
        }
        val apk = File(path)
        if (!apk.exists() || apk.length() == 0L) {
            clear(context)
            return null
        }
        return Staged(
            versionCode = code,
            versionName = prefs.getString(KEY_NAME, "").orEmpty(),
            mandatory = prefs.getBoolean(KEY_MANDATORY, false),
            apk = apk,
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun record(context: Context, release: AppRelease, apk: File) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CODE, release.versionCode)
            .putString(KEY_NAME, release.versionName)
            .putString(KEY_PATH, apk.absolutePath)
            .putBoolean(KEY_MANDATORY, release.mandatory)
            .apply()
    }

    /**
     * One check-and-stage pass.
     *
     * Nothing here touches the UI and nothing here installs. The only output is a
     * file on disk and four values in a preferences file; whether that ever
     * becomes an install is a question asked of a person, later, on a screen they
     * went to themselves.
     */
    class Worker(
        context: Context,
        params: WorkerParameters,
    ) : CoroutineWorker(context, params) {

        override suspend fun doWork(): Result {
            val context = applicationContext

            when (val result = Updater.check()) {
                is Updater.Result.UpToDate -> {
                    // Called for its side effect: if the running build has caught
                    // up with what was staged - somebody installed it, or a newer
                    // APK arrived by hand - this is where the stale record and
                    // its file stop being offered.
                    staged(context)
                    return Result.success()
                }

                // No network, or Supabase unreachable. Retry rather than fail:
                // WorkManager backs off on its own, and school links come and go.
                is Updater.Result.Failed -> return Result.retry()

                // A release row with no APK attached. That is an operator mistake
                // in the ops console, and no amount of retrying on a television
                // is going to fix it.
                is Updater.Result.NoDownload -> return Result.success()

                is Updater.Result.Available -> {
                    val release = result.release
                    if (staged(context)?.versionCode == release.versionCode) {
                        return Result.success()
                    }

                    // No progress callback. Nobody is watching this one, and a
                    // call per 64 KB block would be pure overhead.
                    val apk = Updater.download(context, release) { }
                        ?: return Result.retry()

                    record(context, release, apk)
                    Log.i(TAG, "staged " + release.versionName + " (" + apk.length() + " bytes)")
                    return Result.success()
                }
            }
        }
    }
}
