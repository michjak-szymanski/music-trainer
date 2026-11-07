package com.musictrainer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.musictrainer.AppState
import com.musictrainer.analysis.Doctor
import com.musictrainer.analysis.DoctorReport
import com.musictrainer.analysis.PieceAnalysis
import com.musictrainer.analysis.Professor
import com.musictrainer.analysis.Repeat
import com.musictrainer.analysis.barsText
import com.musictrainer.library.PieceMeta
import com.musictrainer.model.SpelledNote
import com.musictrainer.notation.Score

// ---------------------------------------------------------------- shared bits

@Composable
private fun Card2(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** A clickable line that shows its bars on the sheet; [selected] marks the one shown. */
@Composable
private fun Pick(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) else Modifier)
            .clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp),
    ) { content() }
}

/** "🔁 Loop" and "▶ Practise here" for 0-based [bars] of [piece]. */
@Composable
private fun LoopPlay(state: AppState, piece: PieceMeta, bars: IntRange, label: String) {
    var added by remember(piece.id, bars) { mutableStateOf(false) }
    Row {
        TextButton(onClick = { state.addLoopFor(piece.id, bars.first + 1, bars.last + 1, label); added = true }, enabled = !added, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text(if (added) "✓ Loop added" else "🔁 Loop " + if (bars.first == bars.last) "bar ${bars.first + 1}" else "bars ${bars.first + 1}–${bars.last + 1}")
        }
        TextButton(onClick = { state.practiceFrom(piece, bars.first + 1) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("▶ Practise here") }
    }
}

/** Section list | sheet | section content. Narrow windows: sections as a row on top, the sheet, then the content. */
@Composable
private fun <T> ThreePanes(
    sections: List<T>, selected: T, title: (T) -> String, count: (T) -> Int, onSelect: (T) -> Unit,
    footer: @Composable () -> Unit,
    sheet: @Composable (Modifier) -> Unit, content: @Composable (Modifier) -> Unit,
) {
    @Composable
    fun Section(s: T, modifier: Modifier) {
        val on = s == selected
        Box(
            modifier.clip(RoundedCornerShape(8.dp))
                .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable { onSelect(s) }.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title(s), fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f, fill = false),
                    color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                if (count(s) >= 0) Text("  ${count(s)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth > 1000.dp) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.width(180.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                sections.forEach { Section(it, Modifier.fillMaxWidth()) }
                Spacer(Modifier.height(8.dp))
                footer()
            }
            Card(Modifier.weight(1f).fillMaxHeight()) { sheet(Modifier.fillMaxSize()) }
            Card(Modifier.width(420.dp).fillMaxHeight(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                content(Modifier.fillMaxSize().padding(12.dp))
            }
        } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { sections.forEach { Section(it, Modifier) } }
            Card(Modifier.weight(1.2f).fillMaxWidth()) { sheet(Modifier.fillMaxSize()) }
            Card(Modifier.weight(1f).fillMaxWidth()) { content(Modifier.fillMaxSize().padding(12.dp)) }
        }
    }
}

private fun firstStepOfBar(score: Score, bar: Int): Int = score.steps.indexOfFirst { it.measure >= bar }.coerceAtLeast(0)

/** Professor and Doctor need a piece: the one chosen in Practice. */
@Composable
private fun NoPiece(state: AppState) {
    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Choose a piece in Practice first.", style = MaterialTheme.typography.titleMedium)
        Hint("Professor and Doctor work on the piece you have open in the Practice tab.")
        androidx.compose.material3.Button(onClick = { state.screen = com.musictrainer.Screen.PRACTICE }) { Text("Go to Practice") }
    }
}

// ---------------------------------------------------------------- Professor

private val PHRASE_COLORS = listOf(Color(0xFF4C8DF6), Color(0xFF2E9E4F), Color(0xFFE08A00), Color(0xFFB04FD1), Color(0xFFD93A3A), Color(0xFF16A2A6))

/** What the sheet shows in Professor. */
private sealed interface ProfView {
    /** Just the sheet (with chords if on). */
    data object Plain : ProfView
    data object Form : ProfView
    data object HardSpots : ProfView
    data class Bars(val label: String, val ranges: List<IntRange>, val color: Color) : ProfView
    data class Again(val repeat: Repeat) : ProfView
}

/** 🎓 Professor (top-level tab): the analysis drawn on the piece's sheet music, findings beside it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfessorScreen(state: AppState) {
    // Always the piece chosen in Practice.
    val piece = state.openPiece
    val score = state.openScore
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("🎓 Professor", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (piece != null) Text(piece.title, style = MaterialTheme.typography.titleMedium)
            Hint("How the piece is built, and how to learn it. Click a finding to see it on the sheet.")
        }
        if (piece == null || score == null) { NoPiece(state); return@Column }
        val a = remember(score) { runCatching { Professor.analyze(score) }.getOrNull() }
        if (a == null) { Hint("This piece couldn't be analysed."); return@Column }
        var view by remember(piece.id) { mutableStateOf<ProfView>(ProfView.Plain) }
        var chords by remember { mutableStateOf(true) }
        val letterColor = a.form.map { it.second.trimEnd('′') }.distinct().withIndex().associate { (i, l) -> l to PHRASE_COLORS[i % PHRASE_COLORS.size] }
        val marks = when (val v = view) {
            ProfView.Plain -> emptyList()
            ProfView.Form -> a.form.map { (r, l) -> LoopMark(r, l, true, letterColor[l.trimEnd('′')], bracket = false) }
            ProfView.HardSpots -> a.hardSpots.map { LoopMark(it.bars, if (it.reasons.isEmpty()) "Hard spot" else "Hard: " + it.reasons.take(3).joinToString(", "), true, WrongRed, bracket = false) }
            is ProfView.Bars -> v.ranges.map { LoopMark(it, v.label, true, v.color, bracket = false) }
            is ProfView.Again -> listOf(
                LoopMark(v.repeat.first, "Original", true, PHRASE_COLORS[0], bracket = false),
                LoopMark(v.repeat.again, if (v.repeat.shift == 0) "Same as ${barsText(listOf(v.repeat.first))}" else "Moved ${if (v.repeat.shift > 0) "up" else "down"} ${kotlin.math.abs(v.repeat.shift)} semitones", true, PHRASE_COLORS[1], bracket = false),
            )
        }
        val focusBar = when (val v = view) {
            ProfView.Plain, ProfView.Form -> 0
            ProfView.HardSpots -> a.hardSpots.firstOrNull()?.bars?.first ?: 0
            is ProfView.Bars -> v.ranges.firstOrNull()?.first ?: 0
            is ProfView.Again -> v.repeat.again.first
        }
        val captions = if (chords) a.harmony.withIndex().associate { (i, h) -> i to h.substringBefore(" (") } else emptyMap()
        var section by remember(piece.id) { mutableStateOf(ProfSection.PATTERNS) }
        ThreePanes(
            ProfSection.entries, section, { it.title },
            count = { s ->
                when (s) {
                    ProfSection.PATTERNS -> a.patterns.size; ProfSection.HARD -> a.hardSpots.size; ProfSection.FORM -> a.form.size
                    ProfSection.HARMONY -> a.progressions.size; ProfSection.PLAN -> a.plan.size; ProfSection.OVERVIEW -> -1
                }
            },
            // A new section starts from its own default view, never the previous section's selection.
            onSelect = { s ->
                section = s
                view = when (s) { ProfSection.FORM -> ProfView.Form; ProfSection.HARD -> ProfView.HardSpots; else -> ProfView.Plain }
            },
            footer = {
                Hint("${a.keyName} · ${a.meter} · ${a.bars} bars")
                StarRow(a.difficulty, size = 14.dp)
            },
            sheet = { m ->
                ScoreView(
                    score, currentStep = firstStepOfBar(score, focusBar), modifier = m, stripeOpacity = 0f,
                    zoom = state.playerSettings.zoomPercent / 100f, style = state.playerSettings.notationStyle,
                    loopMarks = marks, barCaptions = captions,
                )
            },
            content = { m -> ProfessorContent(state, piece, a, section, view, { view = it }, chords, { chords = it }, letterColor, m) },
        )
    }
}

/** Sections of Professor's panel, in the order shown. */
private enum class ProfSection(val title: String, val help: String) {
    PATTERNS("Patterns", "Typical figures the piece is built from — scale runs, broken chords, Alberti bass, leaps, chords, " +
        "hand shifts, accidentals and tricky rhythms — with the hand and the bars where they appear. Click one to see it on the " +
        "sheet. Practise a pattern on its own before playing the bars around it."),
    HARD("Hard spots", "The bars that are clearly harder than the rest of the piece (many notes, leaps, chords, hand shifts, " +
        "accidentals, fast notes or triplets), shown in red. Loop them slowly, hands separately first."),
    FORM("Form", "How the piece is put together. It is cut into phrases (usually 4 bars); phrases that sound the same get the " +
        "same letter (A, B …), a variant gets ′ (A′). Repeats are bars that come back exactly or moved up/down. Each letter " +
        "only has to be learned once."),
    HARMONY("Harmony", "The chord under each bar, as a roman numeral in the key (I = the home chord, V = the dominant …), shown " +
        "under the bars on the sheet, plus common chord progressions like I–IV–V–I. Knowing the chords helps you read and " +
        "remember the notes. It's an estimate from the notes."),
    PLAN("Practice plan", "A suggested order to learn the piece in: repeated phrases first, then the rest, the hard spots and " +
        "the patterns, and a starting tempo. Use the loop buttons to practise each step."),
    OVERVIEW("Overview", "Key, time signature, tempo, length, how many notes each hand plays and in what range, and an overall " +
        "difficulty from 1 to 5 stars."),
}

/** What a section is about, at the top of its content. */
@Composable
private fun HelpBox(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))) {
        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ⓘ", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** The chosen Professor section's content (right pane). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfessorContent(
    state: AppState, piece: PieceMeta, a: PieceAnalysis, section: ProfSection, view: ProfView, onView: (ProfView) -> Unit,
    chords: Boolean, onChords: (Boolean) -> Unit, letterColor: Map<String, Color>, modifier: Modifier,
) {
    run {
        val list = rememberLazyListState()
        LazyColumn(modifier.dragToScroll(list), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Text(section.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item { HelpBox(section.help) }
            when (section) {
                ProfSection.PATTERNS -> {
                    if (a.patterns.isEmpty()) item { Hint("No special patterns found.") }
                    items(a.patterns.size) { i ->
                        val f = a.patterns[i]
                        val v = ProfView.Bars(f.title, f.bars, PHRASE_COLORS[2])
                        Pick(view == v, { onView(v) }) {
                            Column {
                                Text((f.hand?.let { "$it · " } ?: "") + f.title, fontWeight = FontWeight.Medium)
                                Hint("${f.detail} — ${barsText(f.bars)}")
                            }
                        }
                    }
                }
                ProfSection.HARD -> {
                    if (a.hardSpots.isEmpty()) item { Hint("No bar stands out as much harder than the rest.") }
                    item { Pick(view == ProfView.HardSpots, { onView(ProfView.HardSpots) }) { Hint("Show all on the sheet (red)") } }
                    items(a.hardSpots.size) { i ->
                        val h = a.hardSpots[i]
                        val v = ProfView.Bars("Hard spot", listOf(h.bars), WrongRed)
                        Pick(view == v, { onView(v) }) {
                            Column {
                                Text("${barsText(listOf(h.bars)).replaceFirstChar { it.uppercase() }}" + if (h.reasons.isNotEmpty()) ": ${h.reasons.joinToString(", ")}" else "")
                                LoopPlay(state, piece, h.bars, "Hard spot")
                            }
                        }
                    }
                }
                ProfSection.FORM -> {
                    item {
                        Pick(view == ProfView.Form, { onView(ProfView.Form) }) {
                            Column {
                                Text(a.formString, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Hint("Coloured on the sheet; ′ = a variant. ${a.repeatedPercent}% of the bars repeat earlier music exactly or transposed.")
                            }
                        }
                    }
                    items(a.form.size) { i ->
                        val (r, l) = a.form[i]
                        val v = ProfView.Bars("Phrase $l", listOf(r), letterColor[l.trimEnd('′')] ?: PHRASE_COLORS[0])
                        Pick(view == v, { onView(v) }) {
                            Text("$l  ${barsText(listOf(r))}", color = letterColor[l.trimEnd('′')] ?: Color.Unspecified, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (a.repeats.isNotEmpty()) item { Text("Repeats", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp)) }
                    items(a.repeats.size) { i ->
                        val r = a.repeats[i]
                        Pick(view == ProfView.Again(r), { onView(ProfView.Again(r)) }) {
                            Text(
                                "${barsText(listOf(r.again)).replaceFirstChar { it.uppercase() }} = ${barsText(listOf(r.first))}" +
                                    if (r.shift == 0) " (exact)" else " moved ${if (r.shift > 0) "up" else "down"} ${kotlin.math.abs(r.shift)} semitones",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                ProfSection.HARMONY -> {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Switch(checked = chords, onCheckedChange = onChords)
                            Text("  Chords under the bars")
                        }
                    }
                    item { Hint("The chord that best fits each bar (roman numeral in ${a.keyName}); an estimate from the notes.") }
                    if (a.progressions.isEmpty()) item { Hint("No common progression found.") }
                    items(a.progressions.size) { i ->
                        val p = a.progressions[i]
                        val v = ProfView.Bars(p.title, p.bars, PHRASE_COLORS[3])
                        Pick(view == v, { onView(v) }) { Text("${p.title}: ${p.detail}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
                ProfSection.PLAN -> items(a.plan.size) { i ->
                    val step = a.plan[i]
                    val loop = step.loop
                    val v = loop?.let { ProfView.Bars("Step ${i + 1}", listOf(it), PHRASE_COLORS[2]) }
                    Pick(v != null && view == v, { if (v != null) onView(v) }) {
                        Column {
                            Text("${i + 1}. ${step.text}")
                            if (loop != null) LoopPlay(state, piece, loop, "Step ${i + 1}")
                        }
                    }
                }
                ProfSection.OVERVIEW -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${a.keyName} · ${a.meter} · ${a.bpm} BPM · ${a.bars} bars")
                        Text("Right hand: ${a.notesRight} notes (${a.rangeRight})")
                        Text("Left hand: ${a.notesLeft} notes (${a.rangeLeft})")
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Difficulty"); StarRow(a.difficulty, size = 16.dp)
                            Hint(listOf("", "easy", "moderate", "intermediate", "hard", "very hard")[a.difficulty])
                        }
                        Text("Form ${a.formString}")
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Doctor

private val RANGES = listOf(1 to "Today", 7 to "7 days", 30 to "30 days", 90 to "90 days", 0 to "All")

/** What the sheet shows in Doctor. */
private sealed interface DocView {
    data object Heat : DocView
    /** Wrong keys on the sheet: all of them (null) or one confusion. */
    data class Wrong(val group: WrongGroup?) : DocView
    data class Pattern(val wp: com.musictrainer.analysis.WeakPattern) : DocView
    data class Bar(val bar: Int) : DocView
}

/** One wrong-note confusion (played instead of expected) and where it happens: step → count. */
data class WrongGroup(val expected: Int, val played: Int, val total: Int, val byStep: Map<Int, Int>)

/** Groups the recorded wrong keys by confusion; the expected note is the closest note of that step. */
private fun wrongGroups(score: Score, wrongAt: Map<Pair<Int, Int>, Int>): List<WrongGroup> =
    wrongAt.entries.mapNotNull { (k, n) ->
        val (step, played) = k
        val exp = score.steps.getOrNull(step)?.all?.minByOrNull { kotlin.math.abs(it - played) } ?: return@mapNotNull null
        Triple(exp, played, step to n)
    }.groupBy { it.first to it.second }.map { (pair, list) ->
        WrongGroup(pair.first, pair.second, list.sumOf { it.third.second }, list.associate { it.third })
    }.sortedByDescending { it.total }

private enum class DocSection(val title: String, val help: String) {
    SUGGESTIONS("Suggestions", "What to do next, based on your sessions with this piece in the chosen period."),
    PATTERNS("Weak patterns", "Patterns of the piece (from Professor) where you make clearly more mistakes than in the rest of " +
        "the piece. Click one to see its bars on the sheet."),
    BARS("Problem bars", "The bars with the highest share of mistakes (wrong keys, missed notes, timing). On the sheet they are " +
        "red — the redder, the worse; the notes that went wrong are red too."),
    NOTES("Wrong notes", "Notes you often play instead of the right one, e.g. F instead of F♯ (a forgotten sharp)."),
    CHECKUP("Check-up", "Totals for the period: sessions, days, time, accuracy and its trend, kinds of mistakes, accuracy per " +
        "hand and the tempo you play at."),
}

/**
 * 🩺 Doctor (top-level tab): your sessions in a period. One piece (the open one, or the one with most mistakes)
 * shows on its sheet: problem bars as a heat map, the notes that go wrong in red, and the patterns you
 * struggle with. "All pieces" gives the overview.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DoctorScreen(state: AppState) {
    val now = state.platform.now()
    val days = state.doctorDays
    val since = if (days == 0) 0L else now - days * 86_400_000L
    // Always the piece chosen in Practice.
    val piece = state.openPiece
    val report = remember(state.playerHistory.size, days, piece?.id) { Doctor.report(state.playerHistory.toList(), since, piece?.id) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text("🩺 Doctor", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (piece != null) Text(piece.title, style = MaterialTheme.typography.titleMedium)
            RANGES.forEach { (d, l) -> FilterChip(selected = days == d, onClick = { state.doctorDays = d }, label = { Text(l) }) }
        }
        if (piece == null) { NoPiece(state); return@Column }
        val sc = state.openScore
        if (sc == null) { Hint("Loading the sheet…"); return@Column }
        val analysis = remember(sc) { runCatching { Professor.analyze(sc) }.getOrNull() }
        val weak = remember(report, analysis) { analysis?.let { Doctor.weakPatterns(report, it) }.orEmpty() }
        var view by remember(piece.id) { mutableStateOf<DocView>(DocView.Heat) }
        // Notes that went wrong: red, stronger the more often.
        val maxStep = report.stepMistakes.values.maxOrNull() ?: 1
        val noteColors = buildMap {
            for ((step, n) in report.stepMistakes) {
                val st = sc.steps.getOrNull(step) ?: continue
                val c = androidx.compose.ui.graphics.lerp(Color(0xFFE8A0A0), WrongRed, n.toFloat() / maxStep)
                for (m in st.all) put(step * 128 + m, c)
            }
        }
        val groups = remember(report, sc) { wrongGroups(sc, report.wrongAt) }
        val maxWrong = report.wrongAt.values.maxOrNull() ?: 1
        val ghosts = when (val v = view) {
            is DocView.Wrong -> (v.group?.let { g -> g.byStep.map { (st, n) -> (st to g.played) to n } } ?: report.wrongAt.toList())
            DocView.Heat -> report.wrongAt.toList()
            else -> emptyList()
        }.sortedBy { it.first.first }.map { (k, n) -> GhostNote(k.first, k.second, "×$n", (0.45f + 0.55f * n / maxWrong).coerceAtMost(1f)) }
        val marks = when (val v = view) {
            is DocView.Wrong -> emptyList()
            DocView.Heat -> report.problemBars.map { pb ->
                LoopMark(pb.bar..pb.bar, "${(pb.errorRate * 100).toInt()}%", true, WrongRed.copy(alpha = (0.35f + pb.errorRate.toFloat()).coerceAtMost(1f)), bracket = false)
            }
            is DocView.Pattern -> v.wp.finding.bars.map { LoopMark(it, "${v.wp.finding.title} · ${(v.wp.rate * 100).toInt()}% mistakes", true, TimingAmber, bracket = false) }
            is DocView.Bar -> listOf(LoopMark(v.bar..v.bar, "Bar ${v.bar + 1}", true, WrongRed, bracket = false))
        }
        val focus = when (val v = view) {
            is DocView.Wrong -> (v.group?.byStep?.keys?.minOrNull() ?: report.wrongAt.keys.minOfOrNull { it.first })?.let { sc.steps.getOrNull(it)?.measure } ?: 0
            DocView.Heat -> report.problemBars.firstOrNull()?.bar ?: 0
            is DocView.Pattern -> v.wp.finding.bars.firstOrNull()?.first ?: 0
            is DocView.Bar -> v.bar
        }
        var section by remember(piece.id) { mutableStateOf(DocSection.SUGGESTIONS) }
        ThreePanes(
            DocSection.entries, section, { it.title },
            count = { s ->
                when (s) {
                    DocSection.SUGGESTIONS -> report.suggestions.size; DocSection.PATTERNS -> weak.size
                    DocSection.BARS -> report.problemBars.size; DocSection.NOTES -> maxOf(groups.size, report.confusions.size); DocSection.CHECKUP -> -1
                }
            },
            // Each section starts from its own default view: the heat map, or (Wrong notes) all wrong keys.
            onSelect = { s -> section = s; view = if (s == DocSection.NOTES) DocView.Wrong(null) else DocView.Heat },
            footer = {
                Hint("${report.sessions} sessions · accuracy ${report.accuracy}%")
                Pick(view == DocView.Heat, { view = DocView.Heat }) { Hint("Sheet: heat map of mistakes") }
            },
            sheet = { m ->
                ScoreView(
                    sc, currentStep = firstStepOfBar(sc, focus), modifier = m, stripeOpacity = 0f, noteColors = noteColors,
                    zoom = state.playerSettings.zoomPercent / 100f, style = state.playerSettings.notationStyle, loopMarks = marks,
                    ghosts = ghosts,
                )
            },
            content = { m -> DoctorContent(state, piece, sc, report, weak, groups, section, view, { view = it }, m) },
        )
    }
}

/** The chosen Doctor section's content (right pane). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DoctorContent(
    state: AppState, piece: PieceMeta, score: Score, report: DoctorReport, weak: List<com.musictrainer.analysis.WeakPattern>,
    groups: List<WrongGroup>, section: DocSection, view: DocView, onView: (DocView) -> Unit, modifier: Modifier,
) {
    run {
        val list = rememberLazyListState()
        LazyColumn(modifier.dragToScroll(list), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Text(section.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item { HelpBox(section.help) }
            when (section) {
                DocSection.SUGGESTIONS -> items(report.suggestions.size) { i ->
                    val sug = report.suggestions[i]
                    Column {
                        Text("• ${sug.text}")
                        sug.loop?.let { l -> LoopPlay(state, piece, l, "Doctor") }
                    }
                }
                DocSection.PATTERNS -> {
                    if (weak.isEmpty()) item {
                        Hint(if (report.barTotals.isEmpty()) "Not enough detailed sessions yet (they're recorded from version 2.5 on)." else "No pattern goes clearly worse than the rest of the piece.")
                    }
                    items(weak.size) { i ->
                        val w = weak[i]
                        Pick(view == DocView.Pattern(w), { onView(DocView.Pattern(w)) }) {
                            Column {
                                Text((w.finding.hand?.let { "$it · " } ?: "") + w.finding.title, fontWeight = FontWeight.Medium)
                                Text("${(w.rate * 100).toInt()}% mistakes here vs ${(w.elsewhere * 100).toInt()}% elsewhere (${w.mistakes} mistakes)", color = WrongRed, style = MaterialTheme.typography.bodySmall)
                                Hint("${w.finding.detail} — ${barsText(w.finding.bars)}")
                                w.finding.bars.firstOrNull()?.let { LoopPlay(state, piece, it, w.finding.title) }
                            }
                        }
                    }
                }
                DocSection.BARS -> {
                    if (report.problemBars.isEmpty()) item {
                        Hint(if (report.barTotals.isEmpty() && report.wrong + report.missed > 0) "Your sessions in this period are older and didn't record per-bar results. Play again to see the problem bars." else "No problem bars in this period.")
                    }
                    items(report.problemBars.size) { i ->
                        val pb = report.problemBars[i]
                        Pick(view == DocView.Bar(pb.bar), { onView(DocView.Bar(pb.bar)) }) {
                            Column {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("Bar ${pb.bar + 1}", fontWeight = FontWeight.Bold)
                                    Text("${(pb.errorRate * 100).toInt()}% wrong", color = WrongRed)
                                }
                                Hint("✗ ${pb.stat.wrong} wrong · ○ ${pb.stat.missed} missed · ${pb.stat.timing} timing, of ${pb.attempts} notes")
                                LoopPlay(state, piece, pb.bar..pb.bar, "Bar ${pb.bar + 1}")
                            }
                        }
                    }
                }
                DocSection.NOTES -> {
                    if (groups.isEmpty() && report.confusions.isEmpty()) item {
                        Hint(if (report.wrong > 0) "${report.wrong} wrong keys in this period, but those sessions are older and didn't record which keys or where. Play again to see them here and on the sheet." else "No wrong notes in this period.")
                    }
                    if (groups.isNotEmpty()) item {
                        Pick(view == DocView.Wrong(null), { onView(DocView.Wrong(null)) }) { Hint("Show all wrong keys on the sheet (red ghost notes, ×count)") }
                    }
                    items(groups.size) { i ->
                        val g = groups[i]
                        Pick(view == DocView.Wrong(g), { onView(DocView.Wrong(g)) }) {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("${SpelledNote.spell(g.played).name} instead of ${SpelledNote.spell(g.expected).name} — ${g.total}×", fontWeight = FontWeight.Medium)
                                // Where it happens, and how often.
                                val most = g.byStep.values.maxOrNull() ?: 1
                                g.byStep.entries.sortedByDescending { it.value }.take(6).forEach { (st, n) ->
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(describeFragment(score, st, st), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(120.dp))
                                        Box(Modifier.height(8.dp).width((110f * n / most).coerceAtLeast(4f).dp).clip(RoundedCornerShape(4.dp)).background(WrongRed))
                                        Text("$n", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                if (g.byStep.size > 6) Hint("…and ${g.byStep.size - 6} more places")
                            }
                        }
                    }
                    // Older sessions only know the pair, not the place.
                    val placed = groups.map { it.expected to it.played }.toSet()
                    val unplaced = report.confusions.filter { (it.expected to it.played) !in placed }
                    if (unplaced.isNotEmpty()) {
                        item { Text("Without place (older sessions)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp)) }
                        items(unplaced.size) { i ->
                            val c = unplaced[i]
                            Text("${SpelledNote.spell(c.played).name} instead of ${SpelledNote.spell(c.expected).name} — ${c.count}×", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                DocSection.CHECKUP -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (report.sessions == 0) Hint("No sessions in this period.")
                        Text("${report.sessions} session${if (report.sessions == 1) "" else "s"} on ${report.days} day${if (report.days == 1) "" else "s"} · ${report.minutes} min · accuracy ${report.accuracy}%")
                        report.trend?.let { (x, y) -> Text("Trend: $x% → $y%", color = if (y >= x) CorrectGreen else WrongRed) }
                        Text("Wrong keys ${report.wrong} · missed ${report.missed} · rhythm ${report.rhythm} · note length ${report.length}")
                        if (report.byHands.isNotEmpty()) Text(report.byHands.entries.joinToString(" · ") { "${it.key.label}: ${it.value}%" })
                        if (report.setTempo != null) Text("Tempo set to ${report.setTempo}% of the original" + (report.actualTempo?.let { ", actually played at $it%" } ?: ""))
                        if (report.withoutDetails > 0) Hint("${report.withoutDetails} older session(s) have no per-bar details.")
                    }
                }
            }
        }
    }
}
