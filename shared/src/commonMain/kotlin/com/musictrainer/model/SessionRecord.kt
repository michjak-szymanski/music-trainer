package com.musictrainer.model

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.roundToInt

@Serializable
data class SessionRecord(
    val timestamp: Long,
    val correct: Int,
    val wrong: Int,
    val plannedNotes: Int,
    /** False if the session was restarted before all notes were played. */
    val completed: Boolean,
    val lowestMidi: Int,
    val highestMidi: Int,
    val notesPerClef: Int,
    val includeAccidentals: Boolean,
    val avgResponseMs: Long,
) {
    val answered: Int get() = correct + wrong
    val accuracyPercent: Int get() = accuracyPercent(correct, wrong)
    val score: Int get() = score(correct, wrong)
}

fun accuracyPercent(correct: Int, wrong: Int): Int {
    val total = correct + wrong
    return if (total == 0) 0 else (correct * 100.0 / total).roundToInt()
}

const val POINTS_CORRECT = 10
const val POINTS_WRONG = 5

/** Combined score: every hit earns points, every miss costs some. Never negative. */
fun score(correct: Int, wrong: Int): Int = max(0, correct * POINTS_CORRECT - wrong * POINTS_WRONG)
