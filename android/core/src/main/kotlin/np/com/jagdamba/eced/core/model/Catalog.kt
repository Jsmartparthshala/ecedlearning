package np.com.jagdamba.eced.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Catalog models. These mirror the Supabase tables 1:1 on purpose — the catalog is
 * data, never compiled in, so adding a subject or restructuring units is a database
 * change and this file does not move.
 */

@Serializable
data class Subject(
    val id: String,
    val slug: String,
    @SerialName("name_en") val nameEn: String,
    @SerialName("name_np") val nameNp: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("color_1") val color1: String? = null,
    @SerialName("color_2") val color2: String? = null,
    val icon: String? = null,
)

@Serializable
data class Unit(
    val id: String,
    @SerialName("subject_id") val subjectId: String,
    @SerialName("title_en") val titleEn: String,
    @SerialName("title_np") val titleNp: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("theme_tag") val themeTag: String? = null,
    /** Curriculum day-count. Units are variable length by design. */
    @SerialName("est_days") val estDays: Int? = null,
    val icon: String? = null,
)

@Serializable
data class Lesson(
    val id: String,
    @SerialName("unit_id") val unitId: String,
    @SerialName("title_en") val titleEn: String,
    @SerialName("title_np") val titleNp: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("duration_sec") val durationSec: Int? = null,
    @SerialName("video_url") val videoUrl: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    /** 'h264' for emulator, 'h265' for real hardware. */
    val codec: String = "h264",
    @SerialName("size_bytes") val sizeBytes: Long? = null,
) {
    val isPlayable: Boolean get() = !videoUrl.isNullOrBlank()
}

@Serializable
data class Progress(
    val id: String? = null,
    @SerialName("lesson_id") val lessonId: String,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("profile_id") val profileId: String? = null,
    @SerialName("position_sec") val positionSec: Int = 0,
    val completed: Boolean = false,
)

@Serializable
data class DeviceRow(
    val id: String? = null,
    @SerialName("hardware_uuid") val hardwareUuid: String,
    @SerialName("school_id") val schoolId: String? = null,
    @SerialName("claimed_at") val claimedAt: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
)

@Serializable
data class SessionRow(
    val id: String,
    @SerialName("device_id") val deviceId: String,
    val token: String,
    @SerialName("expires_at") val expiresAt: String,
    val revoked: Boolean = false,
)

@Serializable
data class AppRelease(
    @SerialName("version_name") val versionName: String,
    @SerialName("version_code") val versionCode: Int,
    @SerialName("apk_url") val apkUrl: String? = null,
    val mandatory: Boolean = false,
)

/** Shape of the joined `devices -> schools(name)` select used for the header chip. */
@Serializable
data class SchoolName(val name: String? = null)

@Serializable
data class DeviceWithSchool(
    @SerialName("school_id") val schoolId: String? = null,
    val schools: SchoolName? = null,
)
