package com.musictrainer.player

import kotlinx.serialization.Serializable

/** Sortable columns of the piece library. */
@Serializable
enum class LibrarySort(val label: String) {
    TITLE("Title"), TYPE("Type"), DURATION("Duration"), NOTES("Notes"), LAST_PLAYED("Last played"), BEST("Best"),
}

/** Note player settings (kept separately from the Note finder's). */
/**
 * A repeating part of a piece: notes (play steps) [fromStep]..[toStep] when set, else whole bars
 * [startBar]..[endBar] (1-based, inclusive; also kept up to date for note loops).
 */
@Serializable
data class PieceLoop(
    val id: Long, val startBar: Int, val endBar: Int, val enabled: Boolean = true, val name: String = "",
    val fromStep: Int = -1, val toStep: Int = -1,
) {
    val byNotes: Boolean get() = fromStep >= 0 && toStep >= fromStep
    val label: String get() = name.ifBlank { if (startBar == endBar) "Bar $startBar" else "Bars $startBar–$endBar" }
}

/**
 * A note or a bookmark in a piece: on a fragment from play step [fromStep] to [toStep] (any notes), or — for
 * marks made before fragments existed — on a whole bar ([bar], 1-based; also the fragment's first bar).
 */
@Serializable
data class PieceMark(val id: Long, val bar: Int, val text: String, val bookmark: Boolean, val fromStep: Int = -1, val toStep: Int = -1) {
    val isFragment: Boolean get() = fromStep >= 0
}

/** Picking a fragment on the sheet: first note chosen ([from]) or not yet. */
data class FragmentPick(val bookmark: Boolean, val from: Int? = null)

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
    /** Tempo for files that don't say their tempo (quarter notes per minute). */
    val defaultBpm: Int = 120,
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
