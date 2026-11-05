package com.musictrainer

import com.musictrainer.model.Accidental
import com.musictrainer.model.Clef
import com.musictrainer.model.SessionRecord
import com.musictrainer.model.Settings
import com.musictrainer.model.SpelledNote
import com.musictrainer.model.notePool
import com.musictrainer.model.score
import com.musictrainer.session.Phase
import com.musictrainer.session.SessionController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TrainerTest {
    @Test
    fun spellingAndStaffPositions() {
        assertEquals("C4", SpelledNote.spell(60).name)
        assertEquals("C♯4", SpelledNote.spell(61).name)
        assertEquals("D♭4", SpelledNote.spell(61, preferFlat = true).name)
        assertEquals("B♭3", SpelledNote.spell(58, preferFlat = true).name)
        assertEquals(Accidental.FLAT, SpelledNote.spell(70, true).accidental)
        // Treble: E4 bottom line, F5 top line, middle C one ledger line below.
        assertEquals(0, SpelledNote.spell(64).staffPosition(Clef.TREBLE))
        assertEquals(8, SpelledNote.spell(77).staffPosition(Clef.TREBLE))
        assertEquals(-2, SpelledNote.spell(60).staffPosition(Clef.TREBLE))
        // Bass: G2 bottom line, A3 top line, middle C one ledger line above.
        assertEquals(0, SpelledNote.spell(43).staffPosition(Clef.BASS))
        assertEquals(8, SpelledNote.spell(57).staffPosition(Clef.BASS))
        assertEquals(10, SpelledNote.spell(60).staffPosition(Clef.BASS))
    }

    @Test
    fun poolsRespectRangeAndAccidentals() {
        val s = Settings(lowestMidi = 36, highestMidi = 96)
        assertTrue(notePool(Clef.TREBLE, s).all { it in 57..96 })
        assertTrue(notePool(Clef.BASS, s).all { it in 36..64 })
        assertTrue(notePool(Clef.TREBLE, s).none { SpelledNote.spell(it).accidental != Accidental.NONE })
        assertTrue(notePool(Clef.TREBLE, s.copy(includeAccidentals = true)).contains(61))
    }

    @Test
    fun sessionScoresRotatesClefAndLogs() = runTest {
        val records = mutableListOf<SessionRecord>()
        val session = SessionController(this, { testScheduler.currentTime }) { records += it }
        val settings = Settings(notesPerClef = 2, sessionLength = 6)
        session.start(settings)

        val clefs = mutableListOf<Clef>()
        repeat(6) { i ->
            clefs += session.clef
            assertEquals(Phase.WAITING, session.phase)
            val target = session.note!!.midi
            if (i % 3 == 0) {
                session.onNotePlayed(target + 1)
                assertEquals(Phase.WRONG, session.phase)
                session.onNotePlayed(target) // ignored during feedback
            } else {
                session.onNotePlayed(target)
                assertEquals(Phase.CORRECT, session.phase)
            }
            advanceTimeBy(settings.wrongPauseMs.toLong() + 1)
            runCurrent()
        }
        assertEquals(listOf(Clef.TREBLE, Clef.TREBLE, Clef.BASS, Clef.BASS, Clef.TREBLE, Clef.TREBLE), clefs)
        assertEquals(Phase.FINISHED, session.phase)
        assertEquals(4, session.correct)
        assertEquals(2, session.wrong)
        assertEquals(1, records.size)
        assertTrue(records[0].completed)
        assertEquals(score(4, 2), records[0].score)
        assertEquals(30, records[0].score)
        assertEquals(67, records[0].accuracyPercent)
    }

    @Test
    fun restartLogsUnfinishedSessionAndResetsScore() = runTest {
        val records = mutableListOf<SessionRecord>()
        val session = SessionController(this, { testScheduler.currentTime }) { records += it }
        session.start(Settings())
        session.onNotePlayed(session.note!!.midi)
        advanceTimeBy(1000); runCurrent()
        session.restart()
        assertEquals(0, session.correct)
        assertEquals(0, session.index)
        assertEquals(1, records.size)
        assertEquals(false, records[0].completed)
        session.restart() // nothing answered: not logged
        assertEquals(1, records.size)
    }
}
