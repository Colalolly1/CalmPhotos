package com.calmphotos.ui

import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationInstance
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Pressing draws nothing extra. The default press highlight is a grey wash
 * that fades out, and on E-Ink the fade leaves ghosts. MainActivity provides
 * this at the root, so every clickable in the app goes without.
 */
object NoIndication : Indication {
    private object Plain : IndicationInstance {
        override fun ContentDrawScope.drawIndication() { drawContent() }
    }

    @Composable
    override fun rememberUpdatedInstance(interactionSource: InteractionSource): IndicationInstance = Plain
}

/** Words, not icons: the whole app is operated by tapping plain labels. Never less than 48dp to hit. */
@Composable
fun Word(text: String, quiet: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text = text,
        style = if (quiet || !enabled) Type.wordQuiet else Type.word,
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick)
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .wrapContentSize()
            .padding(horizontal = 8.dp),
    )
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(0.5.dp).background(Ink.hairline))
}

@Composable
fun FilledWord(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text = text,
        style = Type.word.copy(color = Ink.white),
        textAlign = TextAlign.Center,
        modifier = modifier.background(Ink.black).clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 11.dp),
    )
}

@Composable
fun OutlinedWord(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text = text,
        style = Type.word,
        textAlign = TextAlign.Center,
        modifier = modifier.border(1.dp, Ink.black).clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 10.dp),
    )
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (selected) Ink.black else Ink.white)
            .then(if (selected) Modifier else Modifier.border(1.dp, Ink.hairline))
            .clickable(onClick = onClick)
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Type.label.copy(color = if (selected) Ink.white else Ink.black))
    }
}

/** Underlined tab word, the same device CalmLib uses for its format tabs. */
@Composable
fun TabWord(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(IntrinsicSize.Max).heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = 6.dp),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            style = Type.word.copy(color = if (selected) Ink.black else Ink.grey),
            modifier = Modifier.padding(top = 12.dp, bottom = 7.dp),
        )
        Box(Modifier.fillMaxWidth().height(2.dp).background(if (selected) Ink.black else Color.Transparent))
    }
}
