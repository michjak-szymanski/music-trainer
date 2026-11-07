package com.musictrainer.ui

import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.RowScope
import com.musictrainer.player.LibrarySort
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.ExperimentalMaterial3Api
import com.musictrainer.player.RecordingInfo
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import com.musictrainer.library.FORMAT_MUSESCORE
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musictrainer.AppState
import com.musictrainer.PlayerPage
import com.musictrainer.Screen
import com.musictrainer.library.PieceMeta
import com.musictrainer.model.fullNoteName
import com.musictrainer.player.HandsMode
import com.musictrainer.player.NoteMark
import com.musictrainer.player.PlayStyle
import com.musictrainer.player.PlayerRecord
import com.musictrainer.player.PlayerSettings
import kotlin.math.roundToInt

fun formatDuration(ms: Long): String {
    val s = (ms / 1000).toInt()
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/** Practice tab of the Note player: library → piece → sheet. */
@Composable
fun PlayerPracticeScreen(state: AppState) {
    if (state.playerPage == PlayerPage.LIBRARY || state.openPiece == null) { LibraryScreen(state); return }
    Column(Modifier.fillMaxSize()) {
        // ← Library | Play · Professor · Doctor, for the open piece.
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = state::backToLibrary, modifier = Modifier.padding(start = 8.dp, end = 16.dp)) { Text("← Library") }
            androidx.compose.material3.SecondaryTabRow(selectedTabIndex = state.pieceView.ordinal, modifier = Modifier.weight(1f)) {
                com.musictrainer.PieceView.entries.forEach { v ->
                    Tab(selected = state.pieceView == v, onClick = { state.showPieceView(v) }, text = { Text(v.title) })
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (state.pieceView) {
                com.musictrainer.PieceView.PROFESSOR -> ProfessorScreen(state)
                com.musictrainer.PieceView.DOCTOR -> DoctorScreen(state)
                com.musictrainer.PieceView.PIECE -> when (state.playerPage) {
                    PlayerPage.SHEET -> SheetScreen(state)
                    else -> PieceDetailScreen(state)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Library

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryScreen(state: AppState) {
    val lib = state.library
    val folders = state.playerSettings.folders
    var query by remember { mutableStateOf("") }
    fun matches(p: PieceMeta) = query.isBlank() || query.trim().split(' ').all { w ->
        p.title.contains(w, ignoreCase = true) || p.fileName.contains(w, ignoreCase = true) || p.format.contains(w, ignoreCase = true)
    }
    // Per-piece stats from the session history, for the Last played / Best columns and sorting.
    val stats = remember(state.playerHistory.size) {
        state.playerHistory.groupBy { it.pieceId }.mapValues { (_, s) -> PieceStats(s.maxOf { it.timestamp }, s.maxOf { it.stars }, s.size) }
    }
    val sort = state.playerSettings.librarySort
    val descending = state.playerSettings.librarySortDescending
    val comparator: Comparator<PieceMeta> = when (sort) {
        LibrarySort.TITLE -> compareBy { it.title.lowercase() }
        LibrarySort.TYPE -> compareBy<PieceMeta> { it.format }.thenBy { it.title.lowercase() }
        LibrarySort.DURATION -> compareBy { it.durationMs }
        LibrarySort.NOTES -> compareBy { it.notes }
        LibrarySort.LAST_PLAYED -> compareBy { stats[it.id]?.lastPlayed ?: Long.MIN_VALUE }
        LibrarySort.BEST -> compareBy { stats[it.id]?.bestStars ?: -1 }
    }
    // Never-played pieces stay at the end when sorting by play history, in either direction.
    fun sorted(list: List<PieceMeta>): List<PieceMeta> {
        val byHistory = sort == LibrarySort.LAST_PLAYED || sort == LibrarySort.BEST
        val (played, never) = if (byHistory) list.partition { stats.containsKey(it.id) } else list to emptyList()
        return (if (descending) played.sortedWith(comparator.reversed()) else played.sortedWith(comparator)) + never.sortedBy { it.title.lowercase() }
    }
    fun onSort(column: LibrarySort) {
        val ps = state.playerSettings
        // Same column: reverse. New column: dates and stars start with the most recent/best.
        state.updatePlayerSettings(
            if (column == sort) ps.copy(librarySortDescending = !descending)
            else ps.copy(librarySort = column, librarySortDescending = column == LibrarySort.LAST_PLAYED || column == LibrarySort.BEST)
        )
    }
    val pieces = sorted(lib.pieces.filter(::matches))
    val builtIns = sorted(lib.builtIns.filter(::matches))
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize().dragToScroll(listState), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text("Library", style = MaterialTheme.typography.headlineSmall)
                Text("${lib.pieces.size} pieces in ${folders.size} folder${if (folders.size == 1) "" else "s"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { lib.scan(folders) }, enabled = !lib.scanning) { Text(if (lib.scanning) "Scanning…" else "Rescan") }
                OutlinedButton(onClick = state::addFolder) { Text("Add folder…") }
            }
            val keyboard = state.platform.touchKeyboard?.takeIf { it.available }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).onFocusChanged {
                    // In tablet posture, open the touch keyboard like native text boxes do.
                    if (it.isFocused && keyboard?.autoShow == true) keyboard.toggle()
                },
                singleLine = true,
                placeholder = { Text("Search by title, file name or type (MIDI / MuseScore)") },
                leadingIcon = { Text("⌕", fontSize = 20.sp) },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("Clear") }
                        if (keyboard != null) HoverHint("Show or hide the touch keyboard") {
                            TextButton(onClick = keyboard::toggle) { Text("⌨", fontSize = 22.sp) }
                        }
                    }
                },
            )
            state.openError?.let { Text(it, color = WrongRed) }
            if (lib.unreadable.isNotEmpty()) {
                var showSkipped by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${lib.unreadable.size} file(s) couldn't be read and were skipped.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { showSkipped = !showSkipped }) { Text(if (showSkipped) "Hide" else "Show which") }
                }
                if (showSkipped) Column(Modifier.padding(start = 8.dp)) {
                    lib.unreadable.forEach { (name, reason) ->
                        Text("$name — $reason", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (lib.pieces.isEmpty()) item {
            Text(
                if (folders.isEmpty()) "Add a folder with MIDI (.mid) or MuseScore (.mscz, .mscx) files to list your pieces here. Folders can also be managed in Settings."
                else if (lib.scanning) "Looking for music files…" else "No MIDI or MuseScore files found in your folders.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (pieces.isNotEmpty()) {
            item { SectionLabel("Your pieces" + if (query.isNotBlank()) " (${pieces.size})" else "") }
            item { LibraryHeader(sort, descending, ::onSort) }
            items(pieces, key = { it.id }) { PieceRow(state, it, stats[it.id]) }
        }
        if (builtIns.isNotEmpty()) {
            item { SectionLabel("Built-in") }
            if (pieces.isEmpty()) item { LibraryHeader(sort, descending, ::onSort) }
            items(builtIns, key = { it.id }) { PieceRow(state, it, stats[it.id]) }
        }
        if (query.isNotBlank() && pieces.isEmpty() && builtIns.isEmpty()) item {
            Text("No pieces match \"$query\".", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    ListScrollbar(listState)
    }
}

/** "MIDI" / "MuseScore" label. MuseScore files carry exact notation; MIDI sheets are reconstructed. */
@Composable
private fun FormatBadge(format: String) {
    val color = if (format == FORMAT_MUSESCORE) Color(0xFF2E9E4F) else MaterialTheme.colorScheme.primary
    Text(
        format,
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun SectionLabel(text: String) =
    Text(text, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

private class PieceStats(val lastPlayed: Long, val bestStars: Int, val sessions: Int)

// Column widths shared by the header and the rows; the title takes the rest.
private val COL_TYPE = 110.dp
private val COL_DURATION = 80.dp
private val COL_NOTES = 80.dp
private val COL_LAST = 150.dp
private val COL_BEST = 110.dp

/** Clickable column titles: click to sort, click again to reverse. */
@Composable
private fun LibraryHeader(sort: LibrarySort, descending: Boolean, onSort: (LibrarySort) -> Unit) {
    @Composable
    fun RowScope.cell(column: LibrarySort, modifier: Modifier) {
        val active = column == sort
        Text(
            column.label + if (active) (if (descending) " ▼" else " ▲") else "",
            modifier.clip(RoundedCornerShape(6.dp)).clickable { onSort(column) }.padding(horizontal = 4.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        cell(LibrarySort.TITLE, Modifier.weight(1f))
        cell(LibrarySort.TYPE, Modifier.width(COL_TYPE))
        cell(LibrarySort.DURATION, Modifier.width(COL_DURATION))
        cell(LibrarySort.NOTES, Modifier.width(COL_NOTES))
        cell(LibrarySort.LAST_PLAYED, Modifier.width(COL_LAST))
        cell(LibrarySort.BEST, Modifier.width(COL_BEST))
    }
}

@Composable
private fun PieceRow(state: AppState, piece: PieceMeta, stats: PieceStats?) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Card(Modifier.fillMaxWidth().clickable { state.openPiece(piece) }) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(piece.title, fontWeight = FontWeight.Bold, maxLines = 1)
                if (!piece.builtIn) Text(piece.fileName, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1)
            }
            Box(Modifier.width(COL_TYPE)) { FormatBadge(piece.format) }
            Text(formatDuration(piece.durationMs), Modifier.width(COL_DURATION))
            Text("${piece.notes}", Modifier.width(COL_NOTES))
            Column(Modifier.width(COL_LAST)) {
                if (stats == null) Text("—", color = muted)
                else {
                    Text(state.platform.formatDate(stats.lastPlayed), maxLines = 1)
                    Text("${stats.sessions} session${if (stats.sessions == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = muted)
                }
            }
            Box(Modifier.width(COL_BEST)) { if (stats != null) StarRow(stats.bestStars, size = 14.dp) else Text("—", color = muted) }
        }
    }
}

// ---------------------------------------------------------------- Piece details

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PieceDetailScreen(state: AppState) {
    val piece = state.openPiece ?: return
    val score = state.openScore ?: return
    var hands by remember(piece.id) { mutableStateOf(HandsMode.BOTH) }
    val sessions = state.playerHistory.filter { it.pieceId == piece.id }
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val wide = maxWidth > 1000.dp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text(piece.title, style = MaterialTheme.typography.headlineSmall)
                FormatBadge(piece.format)
            }
            val first = score.measures.first()
            Text(
                "${formatDuration(piece.durationMs)} · ${piece.notes} notes · ${score.measures.size} bars · " +
                    "${first.meter.signature} · ${keyName(score.key.fifths)} · ${score.bpm} BPM · " +
                    (if (piece.format == FORMAT_MUSESCORE) "exact notation" else "notation reconstructed from MIDI") +
                    if (!piece.builtIn) " · ${piece.fileName}" else "",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        HandsMode.entries.forEachIndexed { i, h ->
                            SegmentedButton(
                                selected = hands == h,
                                onClick = { hands = h },
                                shape = SegmentedButtonDefaults.itemShape(i, HandsMode.entries.size),
                            ) { Text(h.label) }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        PlayStyle.entries.forEach { st ->
                            FilterChip(
                                selected = state.playerSettings.playStyle == st,
                                onClick = { state.updatePlayerSettings(state.playerSettings.copy(playStyle = st)) },
                                label = { Text(st.label) },
                                enabled = hands != HandsMode.NONE,
                            )
                        }
                        NumberStepper("Tempo", state.chosenBpm, "BPM · ${pct(state.chosenBpm, score.bpm)}% of ${score.bpm}", step = 2) { state.chosenBpm = it.coerceIn(20, 400) }
                        if (state.chosenBpm != score.bpm) TextButton(onClick = { state.chosenBpm = score.bpm }) { Text("Reset") }
                        ZoomControl(state)
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { state.play(hands) }) {
                            Text(if (hands == HandsMode.NONE) "▶  Listen" else "▶  Play  ·  ${hands.label}")
                        }
                        val open = state.platform.openFile
                        if (piece.format == FORMAT_MUSESCORE && !piece.builtIn && open != null) {
                            OutlinedButton(onClick = { open(piece.id) }) { Text("Open in MuseScore") }
                        }
                    }
                }
            }
            // The sheet takes all remaining space; sessions beside it (wide) or below it (narrow).
            if (wide) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Card(Modifier.weight(1f).fillMaxHeight()) {
                        ScoreView(score, currentStep = 0, modifier = Modifier.fillMaxSize(), stripeOpacity = 0f, zoom = state.playerSettings.zoomPercent / 100f, style = state.playerSettings.notationStyle)
                    }
                    PiecePanel(state, sessions, Modifier.width(420.dp).fillMaxHeight())
                }
            } else {
                Card(Modifier.weight(1f).fillMaxWidth()) {
                    ScoreView(score, currentStep = 0, modifier = Modifier.fillMaxSize(), stripeOpacity = 0f, zoom = state.playerSettings.zoomPercent / 100f, style = state.playerSettings.notationStyle)
                }
                PiecePanel(state, sessions, Modifier.fillMaxWidth().heightIn(max = 360.dp))
            }
        }
    }
}

/** Side panel of the piece page: past sessions, and recordings with playback. */
@Composable
private fun PiecePanel(state: AppState, sessions: List<PlayerRecord>, modifier: Modifier) {
    val piece = state.openPiece ?: return
    val recordings = state.recordings.filter { it.pieceId == piece.id }
    var tab by remember(piece.id) { mutableStateOf(0) }
    val score = state.openScore
    val marks = state.marksOf(piece.id)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Sessions (${sessions.size})") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Recordings (${recordings.size})") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Notes (${marks.size})") })
        }
        val body = Modifier.weight(1f).fillMaxWidth()
        when (tab) {
            0 -> PieceSessions(state, sessions, body)
            1 -> PieceRecordings(state, recordings, body)
            else -> MarksPanel(state, piece.id, score?.measures?.size ?: 1, body, score)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PieceRecordings(state: AppState, recordings: List<RecordingInfo>, modifier: Modifier) {
    val rp = state.recordingPlayer
    val listState = rememberLazyListState()
    LazyColumn(modifier.dragToScroll(listState), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (recordings.isEmpty()) item {
            Text(
                "No recordings yet. Switch on \"● Record\" on the sheet; the next session you play is saved here.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(recordings, key = { it.id }) { r ->
            val playing = rp.playingId == r.id
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        Text(state.platform.formatDate(r.id), fontWeight = FontWeight.Medium)
                        Text(formatDuration(r.durationMs))
                        Text("${r.notes} notes", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${r.hands.label} · ${r.bpm} BPM", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    r.session?.let { s ->
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                            StarRow(s.stars, size = 14.dp)
                            Text("Score ${s.score}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text("${s.accuracyPercent}%", fontWeight = FontWeight.Bold)
                            Text("✓ ${s.hits}", color = CorrectGreen)
                            Text("✗ ${s.wrong}", color = WrongRed)
                            Text("○ ${s.missed}", color = WrongRed)
                            if (s.timingErrors > 0) Text("${s.rhythmErrors} rhythm · ${s.lengthErrors} length", color = TimingAmber)
                            if (!s.completed) Text("stopped early", color = WrongRed)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { if (playing) rp.stop() else state.playRecording(r) }) { Text(if (playing) "■ Stop" else "▶ Play") }
                        TextButton(onClick = { state.deleteRecording(r) }) { Text("Delete") }
                    }
                    if (playing) LinearProgressIndicator(progress = { rp.progress }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PieceSessions(state: AppState, sessions: List<PlayerRecord>, modifier: Modifier) {
    val listState = rememberLazyListState()
    LazyColumn(modifier.dragToScroll(listState), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (sessions.isEmpty()) {
            item { Text("No sessions yet for this piece.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    StatBlock("Sessions", "${sessions.size}")
                    StatBlock("Best score", "${sessions.maxOf { it.score }}")
                    StatBlock("Best accuracy", "${sessions.maxOf { it.accuracyPercent }}%")
                    StatBlock("Fewest errors", "${sessions.filter { it.completed }.minOfOrNull { it.errors } ?: "–"}")
                }
            }
            if (sessions.size >= 2) item {
                TrendChart(
                    "Accuracy",
                    sessions.reversed().map { TrendPoint(it.accuracyPercent.toDouble(), state.platform.formatDate(it.timestamp), !it.completed) },
                    { "${it.roundToInt()}%" }, fixedRange = 0.0..100.0, modifier = Modifier.fillMaxWidth(),
                )
            }
            items(sessions, key = { it.timestamp }) { PlayerRecordRow(it, state.platform.formatDate(it.timestamp), showTitle = false) }
        }
    }
}

private fun keyName(fifths: Int): String {
    val major = listOf("C♭", "G♭", "D♭", "A♭", "E♭", "B♭", "F", "C", "G", "D", "A", "E", "B", "F♯", "C♯")
    val minor = listOf("A♭", "E♭", "B♭", "F", "C", "G", "D", "A", "E", "B", "F♯", "C♯", "G♯", "D♯", "A♯")
    val i = (fifths + 7).coerceIn(0, 14)
    return "${major[i]} major / ${minor[i]} minor"
}

@Composable
private fun StatBlock(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerRecordRow(r: PlayerRecord, date: String, showTitle: Boolean, onClick: (() -> Unit)? = null) {
    // Clickable without hover/press highlight: rows sliding under the pointer while scrolling shouldn't light up.
    val quiet = if (onClick != null) Modifier
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
        .pointerHoverIcon(PointerIcon.Hand) else Modifier
    Card(Modifier.fillMaxWidth().then(quiet)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                if (showTitle) Text(r.title, fontWeight = FontWeight.Bold)
                Text(date, fontWeight = FontWeight.Medium)
                StarRow(r.stars, size = 14.dp)
                Text("Score ${r.score}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("${r.accuracyPercent}%", fontWeight = FontWeight.Bold)
                Text("✓ ${r.hits}", color = CorrectGreen, fontWeight = FontWeight.Bold)
                Text("✗ ${r.wrong} wrong", color = WrongRed)
                Text("○ ${r.missed} missed", color = WrongRed)
                if (r.timingErrors > 0) Text("${r.rhythmErrors} rhythm · ${r.lengthErrors} length", color = TimingAmber)
                if (!r.completed) Text("stopped early", color = WrongRed)
            }
            Text(
                "${r.hands.label} · ${r.style.label} · ${r.bpm} BPM (${r.setTempoPercent}% of ${r.fileBpm})" +
                    (if (r.actualTempoPercent > 0) " · actual ${r.actualTempoPercent}%" else "") + " · ${formatDuration(r.durationMs)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onClick != null) Text("›", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 16.dp))
        }
    }
}

// ---------------------------------------------------------------- Sheet

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SheetScreen(state: AppState) {
    val p = state.player
    val score = p.score ?: return
    val ps = state.playerSettings
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val keyboardMax = maxHeight * 0.45f
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            // One row of controls; the title is shown on the sheet itself.
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = state::leaveSheet) { Text("← Hands & tempo") }
                // Hands, switchable at any time without moving in the piece.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf(HandsMode.BOTH to "Both", HandsMode.RIGHT to "Right", HandsMode.LEFT to "Left", HandsMode.NONE to "Listen").forEach { (h, l) ->
                        FilterChip(selected = p.hands == h, onClick = { p.switchHands(h) }, label = { Text(l) }, modifier = Modifier.padding(end = 4.dp))
                    }
                    if (p.hands != HandsMode.NONE) Text("· ${p.style.label}", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
                }
                if (!p.running) NumberStepper("Tempo", p.bpm, "BPM · ${pct(p.bpm, score.bpm)}%", step = 2) { p.bpm = it.coerceIn(20, 400) }
                else Text("${p.bpm} BPM · ${pct(p.bpm, score.bpm)}%", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (p.hands != HandsMode.NONE) {
                    HoverHint("Correct notes: the right key for the chord.") { RollingCounter(p.hits, "✓ ", CorrectGreen) }
                    HoverHint("Wrong notes: keys that weren't in the chord you had to play.") { RollingCounter(p.wrong, "✗ ", WrongRed) }
                    HoverHint("Missed notes: notes not played before the cursor moved on (Follow tempo).") { RollingCounter(p.missed, "○ ", WrongRed) }
                    if (p.style == PlayStyle.WAIT && p.checkTiming) {
                        HoverHint(
                            "Rhythm errors: chords played clearly early or late. The time since your previous chord is " +
                                "compared with the score at ${p.bpm} BPM; off by more than 30% (at least 150 ms) counts. " +
                                "Lower the tempo if you play slower, or turn the check off in Settings."
                        ) { RollingCounter(p.rhythmErrors, "Rhythm ", TimingAmber) }
                        HoverHint(
                            "Length errors: notes held the wrong length — released before half their written value, " +
                                "or held much longer than written. Checked when you let go of the key."
                        ) { RollingCounter(p.lengthErrors, "Length ", TimingAmber) }
                    }
                }
                ZoomControl(state)
                LoopControls(state, score)
                MarksMenu(state, score, bookmarks = true)
                MarksMenu(state, score, bookmarks = false)
                if (p.hands != HandsMode.NONE) FilterChip(
                    selected = state.recordEnabled,
                    onClick = { state.recordEnabled = !state.recordEnabled },
                    label = { Text(if (state.recording) "● REC" else "● Record") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = WrongRed.copy(alpha = 0.2f), selectedLabelColor = WrongRed,
                    ),
                )
                HoverHint(if (p.running) "Restart from the beginning" else "Back to the beginning") {
                    OutlinedButton(onClick = p::startFromBeginning, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("⏮", fontSize = 18.sp) }
                }
                Button(onClick = { if (p.running) p.stop(record = true) else p.start() }) {
                    Text(
                        when {
                            p.running -> "■ Stop"
                            p.startStep > 0 -> "▶ Start from bar ${(score.steps.getOrNull(p.startStep)?.measure ?: 0) + 1}"
                            p.finished || p.hits + p.wrong + p.missed > 0 -> "↻ Restart"
                            else -> "▶ Start"
                        }
                    )
                }
            }

            // The note under the mouse while choosing a loop (for the preview on the sheet).
            var hoverStep by remember { mutableStateOf<Int?>(null) }
            LaunchedEffect(state.loopPick == null) { if (state.loopPick == null) hoverStep = null }
            // Choosing a loop's bars on the sheet: what to click next.
            state.loopPick?.let { pick ->
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)).padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        (if (pick.editId != null) "🔁 Edit loop: " else "🔁 New loop: ") +
                            if (pick.firstStep == null) "click the note where the loop starts" else "now click the note where it ends",
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = state::cancelLoopPick) { Text("Cancel") }
                }
            }
            // Editing a loop: move its start, its end, or the whole loop bar by bar.
            state.loopEditId?.let { id ->
                val loop = state.loopsOf(p.pieceId).firstOrNull { it.id == id }
                if (loop == null) { state.doneLoopEdit(); return@let }
                FlowRow(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)).padding(horizontal = 16.dp, vertical = 4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("🔁 " + state.loopPlace(loop), fontWeight = FontWeight.Bold)
                    @Composable
                    fun Nudge(label: String, part: com.musictrainer.AppState.LoopPart) {
                        Text(label, modifier = Modifier.padding(start = 10.dp))
                        OutlinedButton(onClick = { state.nudgeLoop(part, -1) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("◀") }
                        OutlinedButton(onClick = { state.nudgeLoop(part, 1) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("▶") }
                    }
                    Nudge("Start", com.musictrainer.AppState.LoopPart.START)
                    Nudge("End", com.musictrainer.AppState.LoopPart.END)
                    Nudge("Move", com.musictrainer.AppState.LoopPart.WHOLE)
                    Text("· one note per click · or click a note on the sheet to set the start (before it) or the end", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp))
                    TextButton(onClick = state::cancelLoopEdit) { Text("Cancel") }
                    Button(onClick = state::doneLoopEdit) { Text("Done") }
                }
            }
            // Choosing a fragment for a note / bookmark: what to click next.
            state.fragmentPick?.let { pick ->
                Row(
                    Modifier.fillMaxWidth().background(TimingAmber.copy(alpha = 0.15f)).padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        (if (pick.bookmark) "🔖 New bookmark: " else "📝 New note: ") + "click the note on the sheet",
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = state::cancelFragmentPick) { Text("Cancel") }
                }
            }
            state.pendingFragment?.let { (pick, range) ->
                FragmentTextDialog(pick.bookmark, describeFragment(score, range.first, range.last), onDismiss = state::cancelFragmentPick, onSave = state::saveFragment)
            }

            // After a wrong key, that key is shown on the staff as a red note (and flashes red on the keyboard).
            val flash = remember { Animatable(0f) }
            LaunchedEffect(p.wrongCount) { if (p.wrongCount > 0) { flash.snapTo(1f); flash.animateTo(0f, tween(1400)) } }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val colors = buildMap {
                    for ((k, v) in p.marks) put(k, when (v) { NoteMark.HIT -> CorrectGreen; NoteMark.TIMING -> TimingAmber; NoteMark.MISSED -> WrongRed })
                }
                ScoreView(
                    score, currentStep = p.cursorStep, cursorFraction = p.cursorFraction, noteColors = colors,
                    stripeOpacity = ps.stripeOpacity / 100f, modifier = Modifier.fillMaxSize(),
                    // While choosing a fragment, clicks pick notes instead of moving the start point.
                    onStepClick = { step ->
                        when {
                            state.loopPick != null -> state.pickLoopStep(step)
                            state.loopEditId != null -> state.clickLoopStep(step)
                            state.fragmentPick != null -> state.pickStep(step)
                            else -> p.seek(step)
                        }
                    },
                    zoom = ps.zoomPercent / 100f,
                    style = ps.notationStyle.effective(p.pieceId.hashCode() * 31 + p.runNumber),
                    loopMarks = state.loopsOf(p.pieceId).filter { it.id != state.loopPick?.editId }.map { l ->
                        if (l.id == state.loopEditId) LoopMark((l.startBar - 1)..(l.endBar - 1), "Editing: " + state.loopTitle(l), true, MaterialTheme.colorScheme.primary, steps = state.stepsOf(l))
                        else LoopMark(
                            (l.startBar - 1)..(l.endBar - 1),
                            state.loopTitle(l) + when {
                                !l.enabled -> "  · off — click to turn on"
                                !state.loopsActive -> "  · looping paused (🔁)"
                                else -> ""
                            },
                            l.enabled && state.loopsActive, steps = state.stepsOf(l), id = l.id,
                        )
                    } +
                        // Choosing a new loop: where it would start (under the mouse), then the span to the end.
                        listOfNotNull(state.loopPick?.let { pick ->
                            val first = pick.firstStep
                            val h = hoverStep
                            when {
                                first == null && h != null -> LoopMark(0..0, "Loop starts here", true, MaterialTheme.colorScheme.primary, bracket = false, steps = h..h)
                                first != null -> (h ?: first).let { end ->
                                    LoopMark(0..0, if (h == null) "Loop starts here" else "Loop ends here", true, MaterialTheme.colorScheme.primary, bracket = false, steps = minOf(first, end)..maxOf(first, end))
                                }
                                else -> null
                            }
                        }),
                    onStepHover = if (state.loopPick != null) ({ hoverStep = it }) else null,
                    // Click a loop's label or bracket to switch it on / off (not while picking or editing).
                    onLoopClick = if (state.loopPick == null && state.loopEditId == null) ({ id ->
                        state.loopsOf(p.pieceId).firstOrNull { it.id == id }?.let { l ->
                            state.updateLoop(l.copy(enabled = !l.enabled))
                            if (!l.enabled && !state.loopsActive) state.toggleLoopsActive()
                        }
                    }) else null,
                    barNotes = state.marksOf(p.pieceId).filter { !it.isFragment }.map { m -> (m.bar - 1) to ((if (m.bookmark) "🔖 " else "📝 ") + m.text.ifBlank { "Bar ${m.bar}" }) },
                    fragments = state.marksOf(p.pieceId).filter { it.isFragment }.map { m -> FragmentMark(m.fromStep, m.toStep, (if (m.bookmark) "🔖 " else "📝 ") + m.text.ifBlank { "Bookmark" }) },
                    pickedStep = state.fragmentPick?.from,
                    wrongNote = p.lastWrong?.takeIf { p.running && flash.value > 0f }?.let { WrongNote(p.currentStep, it, flash.value) },
                )
                // Start countdown (N … 0), then the optional one-bar count-in.
                AnimatedContent(
                    targetState = p.countdown?.let { "$it" } ?: p.countInBeat?.let { "$it" },
                    transitionSpec = { (scaleIn(initialScale = 1.6f) + fadeIn()) togetherWith fadeOut(tween(120)) },
                ) { beat ->
                    if (beat != null) Text("$beat", fontSize = 96.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                // Results.
                androidx.compose.animation.AnimatedVisibility(
                    visible = p.finished,
                    enter = fadeIn(tween(250)) + scaleIn(spring(dampingRatio = 0.6f, stiffness = 300f), initialScale = 0.6f),
                    exit = fadeOut(),
                ) {
                    PlayerResultsCard(p.lastRecord, onAgain = { p.start() }, onBack = state::leaveSheet)
                }
            }

            // Latest feedback: a wrong key (red) or a timing remark (amber), whichever happened last.
            var lastFeedback by remember { mutableStateOf<Pair<String, Color>?>(null) }
            LaunchedEffect(p.wrongCount) {
                p.lastWrong?.let { w ->
                    val expected = p.lastWrongExpected.sorted().joinToString(" + ") { fullNoteName(it) }
                    lastFeedback = ("Wrong: ${fullNoteName(w)}" + if (expected.isNotEmpty()) " — play $expected" else "") to WrongRed
                }
            }
            LaunchedEffect(p.timingCount) { p.timingMessage?.let { lastFeedback = it to TimingAmber } }
            LaunchedEffect(p.running) { if (!p.running) lastFeedback = null }
            AnimatedContent(targetState = p.wrongCount + p.timingCount, transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(400)) }) { _ ->
                val idle = "Press Start${if (ps.countdownSeconds > 0 && p.hands != HandsMode.NONE) " (${ps.countdownSeconds} s countdown)" else ""}${if (ps.countIn && p.style == PlayStyle.FOLLOW_TEMPO && p.hands != HandsMode.NONE) " (one bar of count-in)" else ""} · click the sheet to start from any bar"
                Text(
                    if (p.running) lastFeedback?.first ?: "" else if (!p.finished) idle else "",
                    Modifier.fillMaxWidth().padding(vertical = 4.dp), textAlign = TextAlign.Center,
                    color = if (p.running) lastFeedback?.second ?: WrongRed else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Keyboard: next keys hinted, after a wrong key the right keys flash green and the wrong one red.
            val press = remember { Animatable(0f) }
            var pressedKey by remember { mutableStateOf<Int?>(null) }
            LaunchedEffect(state.pressCount) { if (state.pressCount > 0) { pressedKey = state.pressedKey; press.snapTo(1f); press.animateTo(0f, tween(450)) } }
            val highlights = buildMap<Int, Color> {
                if (ps.hintNextKeys && p.running && p.hands != HandsMode.NONE) for (k in p.nextKeys) put(k, lerp(Color.White, CorrectGreen, 0.45f))
                pressedKey?.let { if (press.value > 0f) put(it, lerp(Color.White, Color(0xFF8AB4F8), press.value)) }
                p.lastWrong?.let { if (flash.value > 0f) put(it, lerp(Color.White, WrongRed, flash.value)) }
            }
            val ui = state.settings
            val range = (score.steps.flatMap { it.all }.minOrNull() ?: 48)..(score.steps.flatMap { it.all }.maxOrNull() ?: 84)
            val auto = ui.virtualKeys == 0
            if (ui.showVirtualKeyboard) VirtualKeyboard(
                low = if (auto) minOf(range.first, 48) else ui.virtualRange.first,
                high = if (auto) maxOf(range.last, 84) else ui.virtualRange.last,
                highlights = highlights,
                onKey = state::onVirtualKey,
                onKeyUp = state::onVirtualKeyUp,
                savedHeightDp = ui.pianoHeightDp,
                showNames = ui.showKeyLabels,
                showOctaves = ui.showOctaveNumbers,
                maxTotal = keyboardMax,
                onHeightCommitted = { state.updateSettings(state.settings.copy(pianoHeightDp = it)) },
            )
        }
    }
}

@Composable
private fun RollingCounter(value: Int, prefix: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(prefix, color = color, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        AnimatedContent(targetState = value, transitionSpec = { fadeIn() togetherWith fadeOut() }) {
            Text("$it", color = color, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
    }
}

/** [bpm] in percent of the piece's original [fileBpm]. */
private fun pct(bpm: Int, fileBpm: Int): Int = if (fileBpm <= 0) 100 else (bpm * 100.0 / fileBpm).roundToInt()

/** Set tempo and the tempo actually reached, both relative to the piece's original tempo. */
@Composable
private fun TempoSummary(r: PlayerRecord) {
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${r.setTempoPercent}%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("set tempo · ${r.bpm} of ${r.fileBpm} BPM", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (r.actualTempoPercent > 0) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val ratio = r.actualTempoPercent.toFloat() / r.setTempoPercent.coerceAtLeast(1)
            Text(
                "${r.actualTempoPercent}%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                color = if (ratio >= 0.9f) CorrectGreen else TimingAmber,
            )
            Text(
                "actual tempo · ≈${(r.fileBpm * r.actualTempoPercent / 100.0).roundToInt()} BPM",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PlayerResultsCard(r: PlayerRecord?, onAgain: () -> Unit, onBack: () -> Unit) {
    // Never taller than the space above the keyboard; scrolls if it still doesn't fit.
    Card(Modifier.widthIn(max = 480.dp).padding(8.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (r == null) "Finished" else "Piece complete", style = MaterialTheme.typography.headlineSmall)
            if (r != null) {
                StarRow(r.stars, size = 40.dp, animate = true)
                val shown = remember(r) { Animatable(0f) }
                LaunchedEffect(r) { shown.animateTo(r.score.toFloat(), tween(900)) }
                Text("${shown.value.roundToInt()}", fontSize = 60.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("points", style = MaterialTheme.typography.labelLarge)
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("✓ ${r.hits}", color = CorrectGreen, fontWeight = FontWeight.Bold)
                    Text("✗ ${r.wrong} wrong", color = WrongRed, fontWeight = FontWeight.Bold)
                    Text("○ ${r.missed} missed", color = WrongRed, fontWeight = FontWeight.Bold)
                }
                if (r.style == PlayStyle.WAIT && r.timingErrors > 0) Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("Rhythm: ${r.rhythmErrors}", color = TimingAmber, fontWeight = FontWeight.Bold)
                    Text("Note length: ${r.lengthErrors}", color = TimingAmber, fontWeight = FontWeight.Bold)
                }
                Text("Accuracy ${r.accuracyPercent}%", style = MaterialTheme.typography.titleLarge)
                Text("${r.hands.label} · ${r.style.label}")
                TempoSummary(r)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onBack) { Text("Exit") }
                Button(onClick = onAgain) { Text("Play again") }
            }
        }
    }
}

// ---------------------------------------------------------------- History & Settings

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerHistoryScreen(state: AppState) {
    val history = state.playerHistory
    if (history.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No Note player sessions yet. Pick a piece in the Practice tab.") }
        return
    }
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize().dragToScroll(listState), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                FlowRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    StatBlock("Sessions", "${history.size}")
                    StatBlock("Pieces", "${history.map { it.pieceId }.distinct().size}")
                    StatBlock("Best score", "${history.maxOf { it.score }}")
                    StatBlock("Notes played", "${history.sumOf { it.hits + it.wrong }}")
                    val total = history.sumOf { it.hits + it.errors }
                    StatBlock("Overall accuracy", if (total == 0) "–" else "${history.sumOf { it.hits } * 100 / total}%")
                }
            }
        }
        item {
            val points = { f: (PlayerRecord) -> Double -> history.reversed().map { TrendPoint(f(it), state.platform.formatDate(it.timestamp), !it.completed) } }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val charts: List<@Composable (Modifier) -> Unit> = listOf(
                    { m -> TrendChart("Score per session", points { it.score.toDouble() }, { "${it.roundToInt()}" }, modifier = m) },
                    { m -> TrendChart("Accuracy", points { it.accuracyPercent.toDouble() }, { "${it.roundToInt()}%" }, fixedRange = 0.0..100.0, modifier = m) },
                    { m -> TrendChart("Errors per session", points { it.errors.toDouble() }, { "${it.roundToInt()}" }, higherIsBetter = false, modifier = m) },
                )
                if (maxWidth > 900.dp) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { charts.forEach { it(Modifier.weight(1f)) } }
                else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { charts.forEach { it(Modifier.fillMaxWidth()) } }
            }
        }
        items(history, key = { it.timestamp }) { r ->
            Box(Modifier.animateItem()) {
                PlayerRecordRow(r, state.platform.formatDate(r.timestamp), showTitle = true) {
                    state.library.find(r.pieceId)?.let { state.openPiece(it); state.screen = Screen.PRACTICE }
                }
            }
        }
    }
    ListScrollbar(listState)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerSettingsScreen(state: AppState) {
    val s = state.playerSettings
    fun set(new: PlayerSettings) = state.updatePlayerSettings(new)
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().dragToScroll(scroll).verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GeneralSettings(state)
        NotationStyleSection(s.notationStyle) { set(s.copy(notationStyle = it)) }
        Section("Library folders") {
            if (s.folders.isEmpty()) Text("No folders yet. MIDI files (.mid, .midi, .kar) in these folders and their subfolders are listed in the library.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            s.folders.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.platform.files.label(f), Modifier.weight(1f))
                    TextButton(onClick = { set(s.copy(folders = s.folders - f)) }) { Text("Remove") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = state::addFolder) { Text("Add folder…") }
                OutlinedButton(onClick = { state.library.scan(s.folders) }, enabled = !state.library.scanning) { Text("Rescan") }
            }
        }
        Section("Playing") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text("Default style")
                PlayStyle.entries.forEach { st -> FilterChip(selected = s.playStyle == st, onClick = { set(s.copy(playStyle = st)) }, label = { Text(st.label) }) }
            }
            Text(
                "Wait for me: the cursor waits until you play each chord, and (if enabled) checks your rhythm and " +
                    "how long you hold the notes against the chosen tempo. Follow tempo: the cursor moves on by itself " +
                    "and each chord must be played in time.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SwitchRow("Wait for me: also check rhythm and note lengths at the chosen tempo", s.checkTiming) { set(s.copy(checkTiming = it)) }
            NumberStepper("Countdown before playing (Right / Left / Both hands)", s.countdownSeconds, "s  (0 = off)", step = 1) { set(s.copy(countdownSeconds = it)) }
            SwitchRow("Count in one bar before starting (Follow tempo)", s.countIn) { set(s.copy(countIn = it)) }
            NumberStepper("Tempo for files without a tempo marking", s.defaultBpm, "BPM", step = 5) { set(s.copy(defaultBpm = it)) }
            SwitchRow("Light up the next keys on the on-screen keyboard", s.hintNextKeys) { set(s.copy(hintNextKeys = it)) }
            NumberStepper("Cursor stripe opacity", s.stripeOpacity, "%  (0 = off)", step = 1) { set(s.copy(stripeOpacity = it)) }
        }
        Text(
            "On-screen keyboard options (size, note names) are in the bar at the bottom of the window.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    ColumnScrollbar(scroll)
    }
}

/** − 100% + : sheet zoom, remembered across pieces. Clicking the percentage resets to 100%. */
@Composable
private fun ZoomControl(state: AppState) {
    val z = state.playerSettings.zoomPercent
    fun set(v: Int) = state.updatePlayerSettings(state.playerSettings.copy(zoomPercent = v))
    val compact = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
    HoverHint("Zoom the sheet (kept for all pieces); click the percentage for 100%") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { set(z - 10) }, enabled = z > PlayerSettings.MIN_ZOOM, contentPadding = compact) { Text("−") }
            TextButton(onClick = { set(100) }, contentPadding = compact) { Text("$z%") }
            OutlinedButton(onClick = { set(z + 10) }, enabled = z < PlayerSettings.MAX_ZOOM, contentPadding = compact) { Text("+") }
        }
    }
}

/** Shows [text] in a tooltip on hover (desktop) or long-press (touch). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HoverHint(text: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip(Modifier.widthIn(max = 320.dp)) { Text(text) } },
        state = rememberTooltipState(),
    ) { content() }
}
