package com.musictrainer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.musictrainer.notation.Score
import com.musictrainer.player.PieceLoop

/**
 * "Loops ▾" menu (enable any number of loops, jump to one, add / edit / delete) and the 🔁 button that
 * pauses all looping.
 */
@Composable
internal fun LoopControls(state: AppState, score: Score) {
    val p = state.player
    val loops = state.loopsOf(p.pieceId)
    val bars = score.measures.size
    val currentBar = (score.steps.getOrNull(p.cursorStep)?.measure ?: 0) + 1
    val inLoop = loops.firstOrNull { it.enabled && state.stepsOf(it)?.contains(p.cursorStep) == true }?.takeIf { state.loopsActive }
    var open by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<PieceLoop?>(null) }
    val compact = PaddingValues(horizontal = 10.dp, vertical = 0.dp)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            HoverHint("Loops: repeat bars. Turn on as many as you like; ▶ jumps to a loop.") {
                OutlinedButton(onClick = { open = true }, contentPadding = compact) {
                    val enabled = loops.count { it.enabled }
                    Text(
                        when {
                            inLoop != null -> "🔁 ${state.loopTitle(inLoop)}" + if (p.loopRepeats > 0) " ×${p.loopRepeats + 1}" else ""
                            loops.isEmpty() -> "Loops ▾"
                            else -> "Loops ($enabled/${loops.size}) ▾"
                        },
                    )
                }
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Column(Modifier.widthIn(min = 300.dp).padding(horizontal = 12.dp, vertical = 4.dp)) {
                    if (loops.isEmpty()) Text(
                        "No loops yet.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    loops.forEach { loop ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = loop.enabled, onCheckedChange = { state.updateLoop(loop.copy(enabled = it)) })
                            Column(Modifier.weight(1f)) {
                                Text(state.loopTitle(loop), fontWeight = if (loop == inLoop) FontWeight.Bold else FontWeight.Normal)
                                if (loop.name.isNotBlank()) Text(
                                    state.loopPlace(loop),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            HoverHint("Go to the loop's first note (and switch it on)") { TextButton(onClick = { state.jumpToLoop(loop); open = false }) { Text("Go to") } }
                            HoverHint("Hear it: plays the loop in Listen mode — switch back with Both / Right / Left") {
                                TextButton(onClick = { state.listenToLoop(loop); open = false }) { Text("🎧 Hear it") }
                            }
                            HoverHint("Edit: move its start, end or the whole loop") { TextButton(onClick = { state.editLoop(loop.id); open = false }) { Text("✎") } }
                            HoverHint("Rename") { TextButton(onClick = { renaming = loop; open = false }) { Text("Aa") } }
                            HoverHint("Delete") { TextButton(onClick = { state.deleteLoop(loop) }) { Text("✕", color = WrongRed) } }
                        }
                    }
                    if (loops.isEmpty()) Text("Use ＋ Loop next to this menu to make one.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        // A separate "+ Loop" button: pick a new loop's start and end on the sheet.
        HoverHint("New loop: click the note it starts on, then the note it ends on") {
            OutlinedButton(onClick = state::startLoopPick, contentPadding = compact, modifier = Modifier.padding(start = 6.dp)) { Text("＋ Loop") }
        }
        HoverHint(if (state.loopsActive) "Looping on — click to play straight through (loops are kept)" else "Looping paused — click to turn it back on") {
            TextButton(onClick = state::toggleLoopsActive, enabled = loops.any { it.enabled }, contentPadding = compact) {
                Text("🔁", fontSize = 18.sp, color = if (state.loopsActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                if (!state.loopsActive) Text(" off", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    renaming?.let { loop ->
        LoopNameDialog(loop.name, onDismiss = { renaming = null }) { name -> state.updateLoop(loop.copy(name = name.trim())); renaming = null }
    }
}

/** Just the (optional) name of a loop; its bars are chosen on the sheet. */
@Composable
private fun LoopNameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Loop name") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name (optional)") }) },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}


