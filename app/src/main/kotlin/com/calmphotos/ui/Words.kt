package com.calmphotos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Words, not icons: the whole app is operated by tapping plain labels. */
@Composable
fun Word(text: String, quiet: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text = text,
        style = if (quiet || !enabled) Type.wordQuiet else Type.word,
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
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
            .then(if (selected) Modifier else Modifier.border(1.dp, Color(0xFFBBBBBB)))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(label, style = Type.label.copy(color = if (selected) Ink.white else Ink.black))
    }
}

/** Underlined tab word, the same device CalmLib uses for its format tabs. */
@Composable
fun TabWord(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(IntrinsicSize.Max).clickable(onClick = onClick).padding(horizontal = 6.dp),
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
