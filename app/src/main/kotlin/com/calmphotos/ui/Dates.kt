package com.calmphotos.ui

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()
fun Long.toLocalDateTime(): LocalDateTime = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDateTime()

private val dayThisYear = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())
private val dayOtherYear = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault())
private val month = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
private val time = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
private val full = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.getDefault())

/** "Today", "Yesterday", "Thursday 17 September", "3 March 2024". */
fun dayLabel(date: LocalDate, today: LocalDate = LocalDate.now()): String = when {
    date == today -> "Today"
    date == today.minusDays(1) -> "Yesterday"
    date.year == today.year -> date.format(dayThisYear)
    else -> date.format(dayOtherYear)
}

fun monthLabel(date: LocalDate): String = date.format(month)
fun timeLabel(ms: Long): String = ms.toLocalDateTime().format(time)
fun fullDateLabel(ms: Long): String = ms.toLocalDateTime().format(full)

fun durationLabel(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}

fun sizeLabel(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%d KB".format(bytes / (1L shl 10))
    else -> "$bytes B"
}
