package com.musictrainer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musictrainer.AppState
import com.musictrainer.model.Clef
import com.musictrainer.model.POINTS_CORRECT
import com.musictrainer.model.POINTS_WRONG
import com.musictrainer.model.SessionRecord
import com.musictrainer.model.accuracyPercent
import com.musictrainer.model.fullNoteName
import com.musictrainer.model.isBlackKey
import com.musictrainer.model.score
import com.musictrainer.session.Phase

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PracticeScreen(state: AppState) {
    val s = state.session
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            val shown = if (s.phase == Phase.FINISHED) s.settings.sessionLength else s.index + 1
            Text("Note $shown / ${s.settings.sessionLength}", style = MaterialTheme.typography.titleMedium)
            Text(
                "${s.clef.label} clef · ${s.clef.other.label.lowercase()} in ${s.notesUntilClefChange}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text("✓ ${s.correct}", color = CorrectGreen, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text("✗ ${s.wrong}", color = WrongRed, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text("Score ${score(s.correct, s.wrong)}", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { s.restart(state.settings) }) { Text("Restart session") }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (s.phase == Phase.FINISHED) {
                s.lastRecord?.let { ResultsCard(it) { s.restart(state.settings) } }
            } else {
                StaffView(s, Modifier.fillMaxSize())
            }
        }

        val target = s.note
        val message = when {
            s.phase == Phase.CORRECT -> "Correct!"
            s.phase == Phase.WRONG && target != null ->
                "Wrong: you played ${s.playedNote?.midi?.let(::fullNoteName)}, the note was ${fullNoteName(target.midi)}"
            s.phase == Phase.WAITING -> "Play the note shown on the ${if (s.clef == Clef.TREBLE) "treble" else "bass"} staff"
            else -> ""
        }
        Text(
            message,
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            textAlign = TextAlign.Center,
            fontSize = 18.sp,
            fontWeight = if (s.phase == Phase.WAITING) FontWeight.Normal else FontWeight.Bold,
            color = when (s.phase) {
                Phase.CORRECT -> CorrectGreen
                Phase.WRONG -> WrongRed
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        val highlights = buildMap<Int, Color> {
            state.pressedKey?.let { put(it, Color(0xFF8AB4F8)) }
            if (target != null) when (s.phase) {
                Phase.CORRECT -> put(target.midi, CorrectGreen)
                Phase.WRONG -> {
                    s.playedNote?.let { put(it.midi, WrongRed) }
                    put(target.midi, CorrectGreen)
                }
                else -> Unit
            }
        }
        VirtualKeyboard(s.settings.lowestMidi, s.settings.highestMidi, highlights, state::onVirtualKey)
    }
}

/**
 * On-screen keyboard. When the keys would be too narrow to tap (e.g. a phone in
 * portrait), the range is split into two stacked rows: upper half on top, lower half below.
 */
@Composable
private fun VirtualKeyboard(low: Int, high: Int, highlights: Map<Int, Color>, onKey: (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val whiteKeys = (low..high).count { !isBlackKey(it) }
        val split = maxWidth / whiteKeys < 26.dp
        if (!split) {
            Piano(low, high, highlights, onKey, Modifier.fillMaxWidth().height(110.dp))
        } else {
            // Split on the C closest to the middle of the range.
            val mid = (low + high) / 2
            val splitC = (((mid + 6) / 12) * 12).coerceIn(low + 1, high)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Piano(splitC, high, highlights, onKey, Modifier.fillMaxWidth().height(100.dp))
                Piano(low, splitC - 1, highlights, onKey, Modifier.fillMaxWidth().height(100.dp))
            }
        }
    }
}

@Composable
private fun ResultsCard(record: SessionRecord, onRestart: () -> Unit) {
    Card(Modifier.widthIn(max = 460.dp).padding(16.dp)) {
        Column(
            Modifier.padding(28.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Session complete", style = MaterialTheme.typography.headlineSmall)
            Text("${record.score}", fontSize = 64.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text("points", style = MaterialTheme.typography.labelLarge)
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Text("✓ ${record.correct} correct", color = CorrectGreen, fontWeight = FontWeight.Bold)
                Text("✗ ${record.wrong} wrong", color = WrongRed, fontWeight = FontWeight.Bold)
            }
            Text("Accuracy ${accuracyPercent(record.correct, record.wrong)}%", style = MaterialTheme.typography.titleLarge)
            Text("Average response ${formatSeconds(record.avgResponseMs)}")
            Text(
                "Score = $POINTS_CORRECT × correct − $POINTS_WRONG × wrong",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRestart) { Text("Start new session") }
        }
    }
}

fun formatSeconds(ms: Long): String {
    val tenths = (ms + 50) / 100
    return "${tenths / 10}.${tenths % 10} s"
}
