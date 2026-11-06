package com.musictrainer.player

import kotlinx.serialization.Serializable

/** Sortable columns of the piece library. */
@Serializable
enum class LibrarySort(val label: String) {
    TITLE("Title"), TYPE("Type"), DURATION("Duration"), NOTES("Notes"), LAST_PLAYED("Last played"), BEST("Best"),
}

/** Note player settings (kept separately from the Note feeder's). */
@Serializable
data class PlayerSettings(
    /** Folders scanned for MIDI files. */
    val folders: List<String> = emptyList(),
    /** Stored as "playStyle": older versions saved "style" with Follow tempo as the default. */
    val playStyle: PlayStyle = PlayStyle.WAIT,
    /** Wait for me: also check rhythm and how long notes are held. */
    val checkTiming: Boolean = true,
    /** Seconds counted down (N … 0) before playing with Right, Left or Both hands; 0 = off. */
    val countdownSeconds: Int = 5,
    /** One bar of count-in before tempo playback. */
    val countIn: Boolean = true,
    /** Light up the keys of the next chord on the on-screen keyboard. */
    /** Off by default (stored under a new name so the old "on" default does not carry over). */
    val hintNextKeys: Boolean = false,
    /** Where notes the app plays go: [APP_PIANO] or a MIDI output device id. */
    val soundOutput: String = APP_PIANO,
    /** Opacity of the cursor stripe, percent. */
    val stripeOpacity: Int = 10,
    /** Notation look in the Note player. */
    val notationStyle: com.musictrainer.model.NotationStyle = com.musictrainer.model.NotationStyle(),
    /** Sheet zoom in percent, shared by all pieces. */
    val zoomPercent: Int = 100,
    /** Library sort column and direction (remembered). */
    val librarySort: LibrarySort = LibrarySort.TITLE,
    val librarySortDescending: Boolean = false,
) {
    companion object {
        const val MAX_COUNTDOWN = 30
        const val APP_PIANO = "app"
        const val MIN_ZOOM = 50
        const val MAX_ZOOM = 250
    }
}
