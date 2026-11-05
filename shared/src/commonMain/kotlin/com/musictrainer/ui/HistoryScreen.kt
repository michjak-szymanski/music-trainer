package com.musictrainer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.musictrainer.AppState
import com.musictrainer.model.SessionRecord
import com.musictrainer.model.rangeLabel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(state: AppState) {
    val history = state.history
    var confirmClear by remember { mutableStateOf(false) }

    if (history.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No sessions yet. Finish (or restart) a practice session and it will appear here.")
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                FlowRow(
                    Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    val answered = history.sumOf { it.answered }
                    val correct = history.sumOf { it.correct }
                    Stat("Sessions", "${history.size}")
                    Stat("Best score", "${history.maxOf { it.score }}")
                    Stat("Notes played", "$answered")
                    Stat("Overall accuracy", if (answered == 0) "–" else "${correct * 100 / answered}%")
                    OutlinedButton(onClick = { confirmClear = true }) { Text("Clear history") }
                }
            }
        }
        item { TrendCharts(state) }
        items(history, key = { it.timestamp }) { rec ->
            Box(Modifier.animateItem()) { HistoryRow(rec, state.platform.formatDate(rec.timestamp)) }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("All ${history.size} saved sessions will be deleted.") },
            confirmButton = {
                TextButton(onClick = { state.clearHistory(); confirmClear = false }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

/** Score, accuracy and response time over sessions; side by side when wide, stacked when narrow. */
@Composable
private fun TrendCharts(state: AppState) {
    val sessions = state.history.filter { it.answered > 0 }.reversed() // oldest first
    fun points(value: (SessionRecord) -> Double) =
        sessions.map { TrendPoint(value(it), state.platform.formatDate(it.timestamp), hollow = !it.completed) }

    val charts: List<@Composable (Modifier) -> Unit> = listOf(
        { m -> TrendChart("Score per session", points { it.score.toDouble() }, { "${it.roundToInt()}" }, modifier = m) },
        {
            m -> TrendChart(
                "Accuracy", points { it.accuracyPercent.toDouble() }, { "${it.roundToInt()}%" },
                fixedRange = 0.0..100.0, modifier = m,
            )
        },
        {
            m -> TrendChart(
                "Average response time", points { it.avgResponseMs / 1000.0 }, { formatSeconds((it * 1000).toLong()) },
                higherIsBetter = false, modifier = m,
            )
        },
    )
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth > 900.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                charts.forEach { it(Modifier.weight(1f)) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                charts.forEach { it(Modifier.fillMaxWidth()) }
            }
        }
    }
    if (sessions.any { !it.completed }) {
        Text(
            "Hollow points are sessions that were restarted before the end.",
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryRow(rec: SessionRecord, date: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(date, fontWeight = FontWeight.Medium)
                Text("Score ${rec.score}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("${rec.accuracyPercent}%", fontWeight = FontWeight.Bold)
                Text("✓ ${rec.correct}", color = CorrectGreen, fontWeight = FontWeight.Bold)
                Text("✗ ${rec.wrong}", color = WrongRed, fontWeight = FontWeight.Bold)
                Text("avg ${formatSeconds(rec.avgResponseMs)}")
                if (!rec.completed) {
                    Text("stopped after ${rec.answered}/${rec.plannedNotes}", color = WrongRed)
                }
            }
            Text(
                "Range ${rangeLabel(rec.lowestMidi, rec.highestMidi)} (${rec.highestMidi - rec.lowestMidi + 1} keys) · " +
                    "clef switch every ${rec.notesPerClef} notes · ${rec.plannedNotes} notes · " +
                    if (rec.includeAccidentals) "with sharps/flats" else "naturals only",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
