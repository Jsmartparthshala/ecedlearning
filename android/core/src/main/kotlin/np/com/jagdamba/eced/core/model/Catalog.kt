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
    /** Null only on a database that predates 0007_levels_and_classes.sql. */
    @SerialName("level_id") val levelId: String? = null,
)

/**
 * One rung of the CDC ladder: ECED, Basic 1-8, Secondary 9-10, 11-12.
 *
 * Read from the `level_cards` view rather than `levels`, so the counts arrive
 * with the row. The television uses [playableCount] to mark a grade that has no
 * video content yet, which is the difference between a screen that says "coming
 * soon" and one that just looks broken.
 */
@Serializable
data class Level(
    val id: String,
    val slug: String,
    @SerialName("name_en") val nameEn: String,
    @SerialName("name_np") val nameNp: String? = null,
    /** eced | basic | secondary | higher. Groups the tiles and picks their colour. */
    val stage: String = "basic",
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("subject_count") val subjectCount: Int = 0,
    @SerialName("playable_count") val playableCount: Int = 0,
) {
    val hasContent: Boolean get() = subjectCount > 0
}

/**
 * A subject with its counts already totalled, from the `subject_cards` view.
 *
 * This exists to delete a query-per-subject. The home screen used to call
 * units(subject.id) once per subject just to put "6 units" on a tile, on every
 * single resume - six round trips for five ECED subjects, and close to a hundred
 * across the full ladder. The counts belong in the same row as the subject.
 */
@Serializable
data class SubjectCard(
    val id: String,
    val slug: String,
    @SerialName("level_id") val levelId: String? = null,
    @SerialName("name_en") val nameEn: String,
    @SerialName("name_np") val nameNp: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("color_1") val color1: String? = null,
    @SerialName("color_2") val color2: String? = null,
    val icon: String? = null,
    @SerialName("unit_count") val unitCount: Int = 0,
    @SerialName("lesson_count") val lessonCount: Int = 0,
    @SerialName("playable_count") val playableCount: Int = 0,
) {
    /** The plain subject, for the screens that only need identity and colour. */
    fun toSubject(): Subject =
        Subject(id, slug, nameEn, nameNp, sortOrder, color1, color2, icon)
}

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

/**
 * A teacher, as the television needs them: a name and the class they take.
 * Nothing else is stored - see 0004_teachers.sql for why there is no phone
 * number on this table.
 */
@Serializable
data class TeacherName(
    val name: String? = null,
    val role: String? = null,
)

/**
 * The class a television belongs to, as the device needs it: the school's label
 * for the room, and the grade that class sits in. The grade is what the home
 * screen actually uses - it is the default the television opens on.
 */
@Serializable
data class ClassRow(
    val label: String? = null,
    @SerialName("level_id") val levelId: String? = null,
    val levels: LevelSlug? = null,
)

/** Just enough of a level to name it and match it against the ladder. */
@Serializable
data class LevelSlug(
    val slug: String? = null,
    @SerialName("name_en") val nameEn: String? = null,
)

@Serializable
data class DeviceWithSchool(
    @SerialName("school_id") val schoolId: String? = null,
    val schools: SchoolName? = null,
    @SerialName("teacher_id") val teacherId: String? = null,
    val teachers: TeacherName? = null,
    @SerialName("class_id") val classId: String? = null,
    val classes: ClassRow? = null,
)
