package np.com.jagdamba.eced.tv

/**
 * Strips the marker the seed data carries on lessons that have no real title yet.
 *
 * 963 of the 968 lessons are seeded as "[PLACEHOLDER] Orientation 1" and similar,
 * and the marker is for whoever is filling the catalogue in, not for a classroom.
 *
 * This lived as a private extension in CardPresenter and again in UnitActivity,
 * which is exactly why the player never had it: a third screen that wanted to show
 * a lesson title could not see either copy, and the first time PlayerActivity put
 * a title on screen it put "[PLACEHOLDER] Orientation 1" on screen. One definition
 * now, so the next screen to show a title gets it right without knowing this
 * paragraph exists.
 */
internal fun String.clean() = removePrefix("[PLACEHOLDER] ")
