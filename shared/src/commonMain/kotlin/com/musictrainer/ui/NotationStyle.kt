package com.musictrainer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.musictrainer.model.NotationFont
import com.musictrainer.model.NotationStyle
import com.musictrainer.model.SheetColors
import com.musictrainer.resources.Res
import com.musictrainer.resources.bravura
import com.musictrainer.resources.leland
import com.musictrainer.resources.petaluma
import org.jetbrains.compose.resources.Font
import kotlin.random.Random
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.musictrainer.notation.DemoPieces
import com.musictrainer.notation.MidiParser
import com.musictrainer.notation.ScoreBuilder

/** The SMuFL music font for [font]. */
@Composable
fun musicFont(font: NotationFont): FontFamily = FontFamily(
    Font(
        when (font) {
            NotationFont.BRAVURA -> Res.font.bravura
            NotationFont.LELAND -> Res.font.leland
            NotationFont.PETALUMA -> Res.font.petaluma
        }
    )
)

/** Ink and (optional) paper colour of the sheet. */
class SheetPalette(val ink: Color, val paper: Color?)

@Composable
fun sheetPalette(colors: SheetColors): SheetPalette = when (colors) {
    SheetColors.THEME -> SheetPalette(MaterialTheme.colorScheme.onSurface, null)
    SheetColors.PAPER -> SheetPalette(Color(0xFF1F1A14), Color(0xFFF6F0E1))
    SheetColors.CONTRAST -> SheetPalette(Color.White, Color.Black)
}

/** With "mix it up", a random font and colours per [seed] (one session / one run of a piece). */
fun NotationStyle.effective(seed: Int): NotationStyle {
    if (!mixItUp) return this
    val r = Random(seed)
    return copy(font = NotationFont.entries.random(r), colors = SheetColors.entries.random(r))
}

/** Settings section: music font, sheet colours and "mix it up". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NotationStyleSection(style: NotationStyle, onChange: (NotationStyle) -> Unit) {
    Section("Notation style") {
        Text(
            "Reading the same notes in a different look trains you to recognise the notes, not their pictures.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text("Music font")
            NotationFont.entries.forEach { f -> FilterChip(selected = style.font == f, onClick = { onChange(style.copy(font = f)) }, label = { Text(f.label) }) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text("Sheet colours")
            SheetColors.entries.forEach { c -> FilterChip(selected = style.colors == c, onClick = { onChange(style.copy(colors = c)) }, label = { Text(c.label) }) }
        }
        SwitchRow("Mix it up: random font and colours for every session", style.mixItUp) { onChange(style.copy(mixItUp = it)) }
        Text("Preview", style = MaterialTheme.typography.titleSmall)
        val preview = remember {
            val demo = DemoPieces.all.first { it.id == "demo:rhythm" }
            ScoreBuilder.build(MidiParser.parse(demo.bytes), demo.title)
        }
        Box(
            Modifier.fillMaxWidth().height(165.dp).clip(RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
        ) {
            ScoreView(preview, currentStep = 0, modifier = Modifier.fillMaxSize(), stripeOpacity = 0f, zoom = 0.8f, showTitle = false, style = style)
        }
        if (style.mixItUp) Text(
            "With Mix it up on, each session picks its own look; this shows the style chosen above.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
