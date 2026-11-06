package com.barrettcoats.waffle.data

import java.io.File

/**
 * The photos allowed on screen during [part]: the ones set to show then, if there are any;
 * otherwise the "any time" ones; and if every photo is set to other times, all of them (better a
 * picture out of its time than none).
 */
fun wallpaperPool(files: List<File>, photoTimes: Map<String, PhotoSchedule>, part: DayPart): List<File> {
    val timed = files.filter { part in photoTimes[it.name]?.parts.orEmpty() }
    if (timed.isNotEmpty()) return timed
    val anyTime = files.filter { photoTimes[it.name]?.parts.isNullOrEmpty() }
    return anyTime.ifEmpty { files }
}

/** "Any time", or the parts in day order ("Evening · Night"). */
fun PhotoSchedule?.describe(): String {
    val parts = this?.parts.orEmpty()
    return if (parts.isEmpty()) "Any time" else DayPart.entries.filter { it in parts }.joinToString(" · ") { it.label }
}

/** [photoTimes] with [name]'s parts replaced; an empty set drops the entry. */
fun Map<String, PhotoSchedule>.withParts(name: String, parts: Set<DayPart>): Map<String, PhotoSchedule> =
    if (parts.isEmpty()) this - name else this + (name to (this[name] ?: PhotoSchedule()).copy(parts = parts))
