package com.musictrainer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.sp
import com.musictrainer.AppState
import com.musictrainer.analysis.Doctor
import com.musictrainer.analysis.Professor
import com.musictrainer.analysis.barsText
import com.musictrainer.model.SpelledNote
import com.musictrainer.notation.Score
import com.musictrainer.player.HandsMode
import com.musictrainer.player.PieceMark

// ---------------------------------------------------------------- shared pieces

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun Muted(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** "🔁 Loop" (adds a loop for these 0-based bars) and "▶ Play here". */
@Composable
private fun BarActions(state: AppState, pieceId: String, bars: IntRange, label: String = "") {
    var added by remember(bars) { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { state.addLoopFor(pieceId, bars.first + 1, bars.last + 1, label); added = true }, enabled = !added, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text(if (added) "✓ Loop added" else "🔁 Loop ${if (bars.first == bars.last) "bar ${bars.first + 1}" else "bars ${bars.first + 1}–${bars.last + 1}"}")
        }
        TextButton(onClick = { state.playFromBar(bars.first + 1) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("▶ Play here") }
    }
}

// ---------------------------------------------------------------- notes and bookmarks

/** Notes tab of the piece page: every note and bookmark, with jump / edit / delete. */
@Composable
internal fun MarksPanel(state: AppState, pieceId: String, bars: Int, modifier: Modifier, score: Score? = null) {
    val marks = state.marksOf(pieceId)
    var editing by remember { mutableStateOf<PieceMark?>(null) }
    val list = rememberLazyListState()
    LazyColumn(modifier.dragToScroll(list), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Muted("To add one: on the play screen, 🔖 → Note… or Bookmark…, then click the note.") }
        if (marks.isEmpty()) item { Muted("No notes or bookmarks yet. Bookmarks mark places to jump to; notes are reminders shown on the sheet.") }
        items(marks, key = { it.id }) { m ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Text("${if (m.bookmark) "🔖" else "📝"} ${markPlace(score, m)}" + if (m.text.isNotBlank()) " — ${m.text}" else "")
                    Row {
                        TextButton(onClick = { state.playFromBar(m.bar); if (m.isFragment) state.jumpToMark(m) }) { Text("▶ Play from here") }
                        TextButton(onClick = { editing = m }) { Text("✎ Edit") }
                        TextButton(onClick = { state.deleteMark(pieceId, m) }) { Text("✕ Delete", color = WrongRed) }
                    }
                }
            }
        }
    }
    editing?.let { m ->
        if (m.isFragment) FragmentTextDialog(m.bookmark, markPlace(score, m), initial = m.text, onDismiss = { editing = null }) { text ->
            state.updateMark(pieceId, m.copy(text = text.trim())); editing = null
        } else MarkDialog(if (m.bookmark) "Edit bookmark" else "Edit note", m, bars, onDismiss = { editing = null }) { bar, text ->
            state.updateMark(pieceId, m.copy(bar = bar, text = text.trim())); editing = null
        }
    }
}

@Composable
internal fun MarkDialog(title: String, initial: PieceMark, bars: Int, onDismiss: () -> Unit, onSave: (Int, String) -> Unit) {
    var bar by remember { mutableStateOf(initial.bar.coerceIn(1, maxOf(1, bars))) }
    var text by remember { mutableStateOf(initial.text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberStepper("Bar", bar, "of $bars", step = 1) { bar = it.coerceIn(1, maxOf(1, bars)) }
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = initial.bookmark,
                    label = { Text(if (initial.bookmark) "Name (e.g. Second theme)" else "Note (e.g. keep the wrist loose)") },
                    modifier = Modifier.widthIn(min = 320.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(bar, text) }, enabled = initial.bookmark || text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "🔖" (bookmarks) or "📝" (notes) menu on the play screen: jump to one, or add one by clicking a note. */
@Composable
internal fun MarksMenu(state: AppState, score: Score, bookmarks: Boolean) {
    val p = state.player
    val marks = state.marksOf(p.pieceId).filter { it.bookmark == bookmarks }
    var open by remember { mutableStateOf(false) }
    val icon = if (bookmarks) "🔖" else "📝"
    Box {
        HoverHint(if (bookmarks) "Bookmarks: jump to one, or add one on a note" else "Notes: jump to one, or add one on a note") {
            OutlinedButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                Text(icon + if (marks.isNotEmpty()) " ${marks.size}" else "", fontSize = 15.sp)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.widthIn(min = 280.dp).padding(horizontal = 12.dp, vertical = 4.dp)) {
                if (marks.isEmpty()) Muted(if (bookmarks) "No bookmarks yet." else "No notes yet.")
                marks.forEach { m ->
                    TextButton(onClick = { state.jumpToMark(m); open = false }) {
                        Text("$icon " + markPlace(score, m) + (if (m.text.isNotBlank()) " — ${m.text.take(40)}" else "") + if (m.text.length > 40) "…" else "")
                    }
                }
                HorizontalDivider()
                TextButton(onClick = { state.startFragmentPick(bookmark = bookmarks); open = false }) {
                    Text(if (bookmarks) "＋ Bookmark… (then click the note)" else "＋ Note… (then click the note)")
                }
            }
        }
    }
}

@Suppress("unused")
private val keepHands = HandsMode.BOTH


/** "bar 3, beat 2 – bar 4, beat 1" for steps [from]..[to] of [score]. */
internal fun describeFragment(score: Score, from: Int, to: Int): String {
    fun at(step: Int): String {
        val st = score.steps.getOrNull(step) ?: return "?"
        val m = score.measures.getOrNull(st.measure) ?: return "bar ${st.measure + 1}"
        val beat = (st.time - m.start) / m.meter.beat + 1
        return "bar ${st.measure + 1}, beat $beat"
    }
    return if (from == to) at(from) else "${at(from)} – ${at(to)}"
}

/** Where a note / bookmark is: its fragment or its bar. */
internal fun markPlace(score: Score?, m: PieceMark): String =
    if (m.isFragment && score != null) describeFragment(score, m.fromStep, m.toStep).replaceFirstChar { it.uppercase() } else "Bar ${m.bar}"

/** Text for a new fragment note / bookmark. */
@Composable
internal fun FragmentTextDialog(bookmark: Boolean, place: String, initial: String = "", onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (bookmark) "New bookmark" else "New note") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(place.replaceFirstChar { it.uppercase() }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = bookmark, modifier = Modifier.widthIn(min = 320.dp),
                    label = { Text(if (bookmark) "Name (optional)" else "Note (e.g. use fingers 1-2-3 here)") },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = bookmark || text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
