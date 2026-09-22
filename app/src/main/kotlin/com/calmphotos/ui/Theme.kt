package com.calmphotos.ui

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Literata for headings and dates, Atkinson for labels: the CalmLib pairing. */
object Fonts {
    var serif: FontFamily = FontFamily.Serif; private set
    var sans: FontFamily = FontFamily.SansSerif; private set

    fun init(context: Context) {
        serif = load(context, "literata", FontFamily.Serif)
        sans = load(context, "atkinson", FontFamily.SansSerif)
    }

    private fun load(context: Context, prefix: String, fallback: FontFamily): FontFamily = try {
        val names = context.assets.list("fonts")?.filter { it.startsWith(prefix) && it.endsWith(".ttf") } ?: emptyList()
        val fonts = names.map { n ->
            Font("fonts/$n", context.assets, if ("bold" in n) FontWeight.Bold else FontWeight.Normal)
        }
        if (fonts.isEmpty()) fallback else FontFamily(fonts)
    } catch (_: Exception) { fallback }
}

// E-Ink palette. Text never lighter than #777777: the HiBreak's text
// enhancement strokes paler greys into an outline.
object Ink {
    val black = Color.Black
    val white = Color.White
    val grey = Color(0xFF6E6E6E)
    val light = Color(0xFF777777)
    // Pale greys wash out entirely on E-Ink; this is the lightest that still
    // draws as a line rather than as nothing at all.
    val hairline = Color(0xFFB0B0B0)
    val paper = Color(0xFFF5F5F5)
    val scrim = Color(0x66000000)
}

object Type {
    val title get() = TextStyle(fontFamily = Fonts.serif, fontSize = 30.sp, letterSpacing = (-0.5).sp, color = Ink.black)
    val subtitle get() = TextStyle(fontFamily = Fonts.serif, fontStyle = FontStyle.Italic, fontSize = 13.sp, color = Ink.grey)
    val dayHeader get() = TextStyle(fontFamily = Fonts.serif, fontSize = 17.sp, color = Ink.black)
    val dayMeta get() = TextStyle(fontFamily = Fonts.sans, fontSize = 11.sp, color = Ink.light)
    val word get() = TextStyle(fontFamily = Fonts.sans, fontSize = 14.sp, color = Ink.black)
    val wordQuiet get() = TextStyle(fontFamily = Fonts.sans, fontSize = 14.sp, color = Ink.grey)
    val label get() = TextStyle(fontFamily = Fonts.sans, fontSize = 12.sp, color = Ink.grey)
    val body get() = TextStyle(fontFamily = Fonts.sans, fontSize = 14.sp, lineHeight = 20.sp, color = Ink.black)
    val albumName get() = TextStyle(fontFamily = Fonts.serif, fontSize = 15.sp, color = Ink.black)
    val badge get() = TextStyle(fontFamily = Fonts.sans, fontSize = 9.sp, letterSpacing = 0.5.sp, color = Ink.white)
}

val PagePadding = 18.dp
