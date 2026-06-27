@file:Suppress("MagicNumber")

package org.tatrman.kantheon.hebe.scheduler.cron

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

object CronParser {
    fun parse(expr: String): Cron {
        val trimmed = expr.trim()
        return when {
            trimmed == "@hourly" -> Cron.Hourly
            trimmed == "@daily" -> Cron.Daily
            trimmed.startsWith("@every ") -> parseEvery(trimmed)
            else -> parseStandard(trimmed)
        }
    }

    private fun parseEvery(expr: String): Cron.Every {
        val durationStr = expr.removePrefix("@every ").trim()
        val duration = parseDuration(durationStr)
        require(duration.inWholeMilliseconds > 0) {
            "Duration must be positive: '$durationStr'"
        }
        return Cron.Every(duration)
    }

    private fun parseDuration(s: String): Duration {
        val s = s.lowercase().trim()
        val regex = Regex("^(\\d+)\\s*(s|sec|second|seconds|m|min|minute|minutes|h|hour|hours|d|day|days)$")
        val match = regex.find(s) ?: throw IllegalArgumentException("Invalid duration: '$s'")
        val value = match.groupValues[1].toLong()
        val unit = match.groupValues[2]
        return when (unit) {
            "s", "sec", "second", "seconds" -> value.seconds
            "m", "min", "minute", "minutes" -> value.minutes
            "h", "hour", "hours" -> value.hours
            "d", "day", "days" -> value.days
            else -> throw IllegalArgumentException("Unknown unit: '$unit'")
        }
    }

    private fun parseStandard(expr: String): Cron.Standard {
        val fields = expr.trim().split(Regex("\\s+"))
        require(fields.size == 5) { "Expected 5 cron fields, got ${fields.size}: '$expr'" }
        val minute = parseField(fields[0], 0, 59, "minute")
        val hour = parseField(fields[1], 0, 23, "hour")
        val dom = parseField(fields[2], 1, 31, "dom")
        val month = parseField(fields[3], 1, 12, "month")
        val dow = parseField(fields[4], 0, 6, "dow")
        return Cron.Standard(minute, hour, dom, month, dow)
    }

    private fun parseField(
        value: String,
        min: Int,
        max: Int,
        name: String,
    ): Int {
        if (value == "*") return -1
        if (value.contains("/")) return parseStep(value, min, max, name)
        if (value.contains(",")) return parseList(value)
        if (value.contains("-")) return parseRange(value, min, max, name)
        return parseSingle(value, min, max, name)
    }

    private fun parseStep(
        value: String,
        min: Int,
        max: Int,
        name: String,
    ): Int {
        val parts = value.split("/")
        require(parts.size == 2) { "Invalid step: '$value'" }
        val step = parts[1].toIntOrNull() ?: throw IllegalArgumentException("Invalid step value: '${parts[1]}'")
        require(step > 0) { "Step must be positive: $step" }
        return if (parts[0] == "*") -1 else parseSingle(parts[0], min, max, name)
    }

    private fun parseList(value: String): Int = throw IllegalArgumentException("Comma lists not supported in v1: '$value'")

    private fun parseRange(
        value: String,
        min: Int,
        max: Int,
        name: String,
    ): Int {
        val parts = value.split("-")
        require(parts.size == 2) { "Invalid range: '$value'" }
        val start = parts[0].toIntOrNull() ?: throw IllegalArgumentException("Invalid range start: '${parts[0]}'")
        val end = parts[1].toIntOrNull() ?: throw IllegalArgumentException("Invalid range end: '${parts[1]}'")
        require(start in min..max) { "$name out of range: $start (min=$min, max=$max)" }
        require(end in min..max) { "$name out of range: $end (min=$min, max=$max)" }
        require(start <= end) { "Range start > end: $start-$end" }
        return start
    }

    private fun parseSingle(
        value: String,
        min: Int,
        max: Int,
        name: String,
    ): Int {
        val int = value.toIntOrNull() ?: throw IllegalArgumentException("Invalid $name value: '$value'")
        require(int in min..max) { "$name out of range: $int (min=$min, max=$max)" }
        return int
    }
}

fun Cron.nextFire(
    now: Instant,
    tz: TimeZone,
): Instant =
    when (this) {
        is Cron.Hourly -> this.nextFireHourly(now, tz)
        is Cron.Daily -> this.nextFireDaily(now, tz)
        is Cron.Every -> this.nextFireEvery(now)
        is Cron.Standard -> this.nextFireStandard(now, tz)
    }

private fun Cron.Hourly.nextFireHourly(
    now: Instant,
    tz: TimeZone,
): Instant {
    val local = now.toLocalDateTime(tz)
    val nextHour = if (local.hour < 23) local.hour + 1 else 0
    val dayOffset = if (local.hour == 23) 1.days else 0.days
    return LocalDateTime(local.year, local.month.number, local.day, nextHour, 0, 0)
        .toInstant(tz) + dayOffset
}

private fun Cron.nextFireDaily(
    now: Instant,
    tz: TimeZone,
): Instant {
    val local = now.toLocalDateTime(tz)
    val today =
        LocalDateTime(local.year, local.month.number, local.day, 0, 0, 0)
            .toInstant(tz)
    return if (today > now) today else today + 1.days
}

private fun Cron.Every.nextFireEvery(now: Instant): Instant {
    val interval = this.interval
    return now + interval
}

private fun Cron.Standard.nextFireStandard(
    now: Instant,
    tz: TimeZone,
): Instant {
    var current = now + 1.minutes
    repeat(366 * 24 * 60) {
        val local = current.toLocalDateTime(tz)
        if (matchesAllFields(local)) {
            return current
        }
        current = current + 1.minutes
    }
    error("nextFire: no fire time found within a year")
}

private fun Cron.Standard.matchesAllFields(local: kotlinx.datetime.LocalDateTime): Boolean {
    val minuteMatches = this.minute == -1 || minute == local.minute
    val hourMatches = hour == -1 || hour == local.hour
    val domMatches = dom == -1 || dom == local.day
    val monthMatches = month == -1 || month == local.month.number
    val dowMatches = dow == -1 || dow == local.dayOfWeek.ordinal
    return minuteMatches && hourMatches && domMatches && monthMatches && dowMatches
}
