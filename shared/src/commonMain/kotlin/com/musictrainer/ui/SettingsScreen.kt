package com.musictrainer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.musictrainer.AppState
import com.musictrainer.DetectStep
import com.musictrainer.model.KeyboardSize
import com.musictrainer.model.MIDI_MAX
import com.musictrainer.model.MIDI_MIN
import com.musictrainer.model.Settings
import com.musictrainer.model.fullNoteName
import com.musictrainer.model.rangeLabel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(state: AppState) {
    val s = state.settings
    fun set(new: Settings) = state.updateSettings(new)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Changing practice settings starts a new session.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Section("Note range — ${s.rangeKeys} keys (${rangeLabel(s.lowestMidi, s.highestMidi)})") {
            NoteStepper("Lowest note", s.lowestMidi) {
                set(s.copy(lowestMidi = it.coerceIn(MIDI_MIN, s.highestMidi - Settings.MIN_RANGE)))
            }
            NoteStepper("Highest note", s.highestMidi) {
                set(s.copy(highestMidi = it.coerceIn(s.lowestMidi + Settings.MIN_RANGE, MIDI_MAX)))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val presets = listOf(
                    "61 keys C2–C7" to (36 to 96),
                    "49 keys C2–C6" to (36 to 84),
                    "88 keys A0–C8" to (21 to 108),
                    "Around middle C (C3–C5)" to (48 to 72),
                    "Treble focus (C4–C6)" to (60 to 84),
                    "Bass focus (C2–C4)" to (36 to 60),
                    "My keyboard" to (s.keyboardLowest to s.keyboardHighest),
                )
                presets.forEach { (label, range) ->
                    FilterChip(
                        selected = s.lowestMidi == range.first && s.highestMidi == range.second,
                        onClick = { set(s.copy(lowestMidi = range.first, highestMidi = range.second)) },
                        label = { Text(label) },
                    )
                }
            }
            Text(
                "Treble clef asks notes from A3 upward, bass clef up to E4.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Session") {
            NumberStepper("Switch clef (treble ↔ bass) every", s.notesPerClef, "notes", step = 1) {
                set(s.copy(notesPerClef = it))
            }
            NumberStepper("Notes per session", s.sessionLength, "notes", step = 5) {
                set(s.copy(sessionLength = it))
            }
            NumberStepper("Show correct answer after a mistake for", s.wrongPauseMs / 100, "× 0.1 s", step = 5) {
                set(s.copy(wrongPauseMs = it * 100))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = s.includeAccidentals, onCheckedChange = { set(s.copy(includeAccidentals = it)) })
                Text("Include sharps and flats (black keys)")
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = s.virtualKeySound, onCheckedChange = { set(s.copy(virtualKeySound = it)) })
                Text("Piano sound when tapping on-screen keys")
            }
        }

        Section("MIDI keyboard — ${s.keyboardKeys} keys (${rangeLabel(s.keyboardLowest, s.keyboardHighest)})") {
            Text(
                "MIDI devices don't report their size, so pick it here or detect it. " +
                    "If you use octave-shift buttons, detect again afterwards.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyboardSize.entries.forEach { k ->
                    FilterChip(
                        selected = s.keyboardLowest == k.lowest && s.keyboardHighest == k.highest,
                        onClick = { set(s.copy(keyboardLowest = k.lowest, keyboardHighest = k.highest)) },
                        label = { Text("${k.keys} keys") },
                    )
                }
            }
            if (state.detectStep == DetectStep.OFF) {
                Button(onClick = state::startDetection) { Text("Detect: press lowest and highest key") }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (state.detectStep == DetectStep.LOWEST) "Press the LOWEST key…" else "Press the HIGHEST key…",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    TextButton(onClick = state::cancelDetection) { Text("Cancel") }
                }
            }
        }

        OutlinedButton(onClick = { set(Settings(keyboardLowest = s.keyboardLowest, keyboardHighest = s.keyboardHighest)) }) {
            Text("Reset practice settings to defaults")
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoteStepper(label: String, midi: Int, onChange: (Int) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.widthIn(min = 110.dp))
        OutlinedButton(onClick = { onChange(midi - 12) }) { Text("− octave") }
        OutlinedButton(onClick = { onChange(midi - 1) }) { Text("−") }
        Text(fullNoteName(midi), Modifier.widthIn(min = 90.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = { onChange(midi + 1) }) { Text("+") }
        OutlinedButton(onClick = { onChange(midi + 12) }) { Text("+ octave") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NumberStepper(label: String, value: Int, unit: String, step: Int, onChange: (Int) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        OutlinedButton(onClick = { onChange(value - step) }) { Text("−") }
        Text("$value", Modifier.widthIn(min = 40.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = { onChange(value + step) }) { Text("+") }
        Text(unit)
    }
}
