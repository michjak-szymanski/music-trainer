package com.musictrainer.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import kotlin.math.roundToInt
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
            NumberStepper(
                "Show the correct answer after a mistake for",
                value = s.mistakePauseMs / 100,
                unit = "s",
                step = 1,
                format = { "${it / 10}.${it % 10}" },
                parse = { t -> t.trim().replace(',', '.').toDoubleOrNull()?.let { (it * 10).roundToInt() } },
            ) { set(s.copy(mistakePauseMs = it * 100)) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = s.includeAccidentals, onCheckedChange = { set(s.copy(includeAccidentals = it)) })
                Text("Include sharps and flats (black keys)")
            }
        }

        Section("On-screen keyboard") {
            SwitchRow("Show the on-screen keyboard", s.showVirtualKeyboard) { set(s.copy(showVirtualKeyboard = it)) }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Keys")
                FilterChip(
                    selected = s.virtualKeys == 0,
                    onClick = { set(s.copy(virtualKeys = 0)) },
                    label = { Text("Auto (practice range)") },
                )
                KeyboardSize.entries.forEach { k ->
                    FilterChip(
                        selected = s.virtualKeys == k.keys,
                        onClick = { set(s.copy(virtualKeys = k.keys)) },
                        label = { Text("${k.keys}") },
                    )
                }
            }
            SwitchRow("Show note names on keys", s.showKeyLabels) { set(s.copy(showKeyLabels = it)) }
            SwitchRow("Show octave numbers on keys", s.showOctaveNumbers) { set(s.copy(showOctaveNumbers = it)) }
            SwitchRow("Piano sound when tapping on-screen keys", s.virtualKeySound) { set(s.copy(virtualKeySound = it)) }
            Text(
                "These can also be changed from the bar at the bottom of the window.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Switch(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

/** − / editable number / + . A typed value is applied on Enter or when the field loses focus. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NumberStepper(
    label: String,
    value: Int,
    unit: String,
    step: Int,
    format: (Int) -> String = { "$it" },
    parse: (String) -> Int? = { it.trim().toIntOrNull() },
    onChange: (Int) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        OutlinedButton(onClick = { onChange(value - step) }) { Text("−") }
        NumberField(value, format, parse, onChange)
        OutlinedButton(onClick = { onChange(value + step) }) { Text("+") }
        Text(unit)
    }
}

@Composable
private fun NumberField(value: Int, format: (Int) -> String, parse: (String) -> Int?, onCommit: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(format(value)) }
    val focus = LocalFocusManager.current
    fun commit() {
        val parsed = parse(text)
        text = format(value) // shows the stored (clamped) value if the input was invalid or unchanged
        if (parsed != null && parsed != value) onCommit(parsed)
    }
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = colors.onSurface,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        ),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit(); focus.clearFocus() }),
        modifier = Modifier
            .width(64.dp)
            .onFocusChanged { if (!it.isFocused) commit() }
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) {
                    commit(); focus.clearFocus(); true
                } else false
            },
        decorationBox = { inner ->
            Box(
                Modifier.border(1.dp, colors.outline, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { inner() }
        },
    )
}
