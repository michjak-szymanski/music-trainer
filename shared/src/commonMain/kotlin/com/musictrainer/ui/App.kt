package com.musictrainer.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFF8AB4F8), secondary = Color(0xFF81C995))
    } else {
        lightColorScheme(primary = Color(0xFF1A5FB4), secondary = Color(0xFF2E9E4F))
    }
    MaterialTheme(colorScheme = colors) {
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
                MidiStatusBar(state)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (state.screen) {
                        Screen.PRACTICE -> PracticeScreen(state)
                        Screen.HISTORY -> HistoryScreen(state)
                        Screen.SETTINGS -> SettingsScreen(state)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MidiStatusBar(state: AppState) {
    val devices by state.platform.midi.devices.collectAsState()
    val error by state.platform.midi.error.collectAsState()
    val settings = state.settings
    val connected = devices.isNotEmpty()
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.Center,
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(12.dp).clip(CircleShape)
                    .background(if (connected) CorrectGreen else WrongRed)
                    .align(Alignment.CenterVertically)
            )
            Text(
                if (connected) "MIDI connected: " + devices.joinToString { it.name }
                else "No MIDI device connected (you can tap the on-screen keys)",
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Keyboard: ${settings.keyboardKeys} keys (${rangeLabel(settings.keyboardLowest, settings.keyboardHighest)})",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { state.platform.midi.rescan() }) { Text("Refresh") }
        }
        if (state.detectStep != DetectStep.OFF) {
            Text(
                if (state.detectStep == DetectStep.LOWEST) "Detecting keyboard: press the LOWEST key on your keyboard…"
                else "Now press the HIGHEST key on your keyboard…",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }
        if (settings.rangeExceedsKeyboard) {
            Text(
                "⚠ Practice range ${rangeLabel(settings.lowestMidi, settings.highestMidi)} is wider than your keyboard " +
                    "(${rangeLabel(settings.keyboardLowest, settings.keyboardHighest)}): some notes can't be played. " +
                    "Adjust it in Settings.",
                color = Color(0xFFE08A00),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        error?.let {
            Text(it, color = WrongRed, style = MaterialTheme.typography.bodySmall)
        }
    }
}
