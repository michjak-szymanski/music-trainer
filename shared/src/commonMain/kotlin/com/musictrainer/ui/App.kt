package com.musictrainer.ui

import androidx.compose.foundation.background
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import com.musictrainer.model.KeyboardSize
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.musictrainer.AppState
import com.musictrainer.DetectStep
import com.musictrainer.Platform
import com.musictrainer.Screen
import com.musictrainer.audio.loadPianoSamples
import com.musictrainer.model.rangeLabel

@Composable
fun App(platform: Platform) {
    val scope = rememberCoroutineScope()
    val state = remember { AppState(platform, scope) }
    LaunchedEffect(Unit) { state.startFirstSession() }
    LaunchedEffect(Unit) { platform.midi.noteOns.collect { state.onKey(it) } }
    LaunchedEffect(Unit) { loadPianoSamples()?.let(platform.sound::setSamples) }

    MaterialTheme(colorScheme = appColorScheme(isSystemInDarkTheme())) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                PrimaryTabRow(selectedTabIndex = state.screen.ordinal) {
                    Screen.entries.forEach { s ->
                        Tab(
                            selected = state.screen == s,
                            onClick = { state.screen = s },
                            text = { Text(s.title) },
                        )
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Crossfade(targetState = state.screen, animationSpec = tween(220)) { screen ->
                        when (screen) {
                            Screen.PRACTICE -> PracticeScreen(state)
                            Screen.HISTORY -> HistoryScreen(state)
                            Screen.SETTINGS -> SettingsScreen(state)
                        }
                    }
                }
                MidiStatusBar(state)
            }
        }
    }
}

fun appColorScheme(dark: Boolean): ColorScheme =
    if (dark) darkColorScheme(primary = Color(0xFF8AB4F8), secondary = Color(0xFF81C995))
    else lightColorScheme(primary = Color(0xFF1A5FB4), secondary = Color(0xFF2E9E4F))

/** Compact status line at the bottom of the window: MIDI connection, keyboard size, warnings. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MidiStatusBar(state: AppState) {
    val devices by state.platform.midi.devices.collectAsState()
    val error by state.platform.midi.error.collectAsState()
    val settings = state.settings
    val connected = devices.isNotEmpty()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val small = MaterialTheme.typography.labelSmall
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 12.dp, vertical = 3.dp)
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(7.dp).clip(CircleShape)
                    .background(if (connected) CorrectGreen else WrongRed)
            )
            Text(
                if (connected) "MIDI: " + devices.joinToString { it.name }
                else "No MIDI device (tap the on-screen keys)",
                style = small,
                color = muted,
            )
            Text(
                "Keyboard ${settings.keyboardKeys} keys (${rangeLabel(settings.keyboardLowest, settings.keyboardHighest)})",
                style = small,
                color = muted,
            )
            Text(
                "Refresh",
                Modifier.clip(MaterialTheme.shapes.small)
                    .clickable { state.platform.midi.rescan() }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                style = small,
                color = MaterialTheme.colorScheme.primary,
            )
            Text("·", style = small, color = muted)
            Text("On-screen keyboard", style = small, color = muted)
            BarToggle("Show", settings.showVirtualKeyboard) {
                state.updateSettings(settings.copy(showVirtualKeyboard = !settings.showVirtualKeyboard))
            }
            KeysMenu(settings.virtualKeys, enabled = settings.showVirtualKeyboard) {
                state.updateSettings(settings.copy(virtualKeys = it))
            }
            BarToggle("Note names", settings.showKeyLabels, enabled = settings.showVirtualKeyboard) {
                state.updateSettings(settings.copy(showKeyLabels = !settings.showKeyLabels))
            }
            BarToggle("Octave numbers", settings.showOctaveNumbers, enabled = settings.showVirtualKeyboard) {
                state.updateSettings(settings.copy(showOctaveNumbers = !settings.showOctaveNumbers))
            }
            if (state.detectStep != DetectStep.OFF) {
                Text(
                    if (state.detectStep == DetectStep.LOWEST) "Press the LOWEST key on your keyboard…"
                    else "Now press the HIGHEST key…",
                    style = small,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (settings.rangeExceedsKeyboard) {
                Text(
                    "⚠ Practice range ${rangeLabel(settings.lowestMidi, settings.highestMidi)} is wider than your keyboard",
                    style = small,
                    color = Color(0xFFE08A00),
                )
            }
            error?.let { Text(it, style = small, color = WrongRed) }
        }
    }
}

/** Small on/off pill for the bottom bar. */
@Composable
private fun BarToggle(label: String, checked: Boolean, enabled: Boolean = true, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (checked && enabled) colors.primary.copy(alpha = 0.18f) else Color.Transparent)
    val fg by animateColorAsState(
        when {
            !enabled -> colors.onSurfaceVariant.copy(alpha = 0.4f)
            checked -> colors.primary
            else -> colors.onSurfaceVariant
        }
    )
    Text(
        (if (checked) "✓ " else "") + label,
        Modifier.clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, fg.copy(alpha = 0.5f), RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = fg,
    )
}

/** "Keys: Auto ▾" dropdown choosing the on-screen keyboard size. */
@Composable
private fun KeysMenu(keys: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Box {
        Text(
            "Keys: ${if (keys == 0) "Auto" else keys} ▾",
            Modifier.clip(RoundedCornerShape(50))
                .border(1.dp, colors.onSurfaceVariant.copy(alpha = if (enabled) 0.5f else 0.2f), RoundedCornerShape(50))
                .clickable(enabled = enabled) { open = true }
                .padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) colors.onSurfaceVariant else colors.onSurfaceVariant.copy(alpha = 0.4f),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Auto (practice range)") }, onClick = { onSelect(0); open = false })
            KeyboardSize.entries.forEach { k ->
                DropdownMenuItem(
                    text = { Text("${k.keys} keys (${rangeLabel(k.lowest, k.highest)})") },
                    onClick = { onSelect(k.keys); open = false },
                )
            }
        }
    }
}
