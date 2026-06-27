@file:Suppress("NewLineAtEndOfFile")

package org.tatrman.kantheon.hebe.scheduler.cron

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

class CronParserTest :
    StringSpec({
        val utc = TimeZone.UTC

        @Suppress("LongParameterList")
        fun instant(
            year: Int,
            month: Int,
            day: Int,
            hour: Int,
            min: Int,
            sec: Int = 0,
        ): Instant = kotlinx.datetime.LocalDateTime(year, month, day, hour, min, sec).toInstant(utc)

        "@hourly parses to CronHourly" {
            CronParser.parse("@hourly") shouldBe Cron.Hourly
        }

        "@daily parses to CronDaily" {
            CronParser.parse("@daily") shouldBe Cron.Daily
        }

        "@every parses with duration" {
            val result = CronParser.parse("@every 5 minutes")
            result shouldBe Cron.Every(5.minutes)
        }

        "@every supports h unit" {
            val result = CronParser.parse("@every 2h")
            result shouldBe Cron.Every(2.hours)
        }

        "@every supports d unit" {
            val result = CronParser.parse("@every 1d")
            result shouldBe Cron.Every(1.days)
        }

        "@every rejects zero duration" {
            runCatching { CronParser.parse("@every 0 seconds") }.isFailure shouldBe true
        }

        "5-field standard cron parses correctly" {
            val result = CronParser.parse("30 14 * * *")
            result as Cron.Standard
            result.minute shouldBe 30
            result.hour shouldBe 14
            result.dom shouldBe -1
        }

        "5-field with specific dom parses correctly" {
            val result = CronParser.parse("0 9 15 * *")
            result as Cron.Standard
            result.minute shouldBe 0
            result.hour shouldBe 9
            result.dom shouldBe 15
        }

        "step syntax parses correctly" {
            val result = CronParser.parse("*/15 * * * *")
            result as Cron.Standard
            result.minute shouldBe -1
        }

        "range syntax parses correctly" {
            val result = CronParser.parse("0 9-17 * * *")
            result as Cron.Standard
            result.hour shouldBe 9
        }

        "invalid field count throws" {
            runCatching { CronParser.parse("0 9 * *") }.isFailure shouldBe true
        }

        "out-of-range minute throws" {
            runCatching { CronParser.parse("60 * * * *") }.isFailure shouldBe true
        }

        "out-of-range hour throws" {
            runCatching { CronParser.parse("* 25 * * *") }.isFailure shouldBe true
        }

        "@hourly fires at next hour" {
            val now = instant(2025, 6, 15, 10, 23)
            val next = Cron.Hourly.nextFire(now, utc)
            next.toEpochMilliseconds() shouldBe instant(2025, 6, 15, 11, 0).toEpochMilliseconds()
        }

        "@hourly rolls hour when minute is 59" {
            val now = instant(2025, 6, 15, 10, 59)
            val next = Cron.Hourly.nextFire(now, utc)
            next.toEpochMilliseconds() shouldBe instant(2025, 6, 15, 11, 0).toEpochMilliseconds()
        }

        "@daily fires at midnight next day" {
            val now = instant(2025, 6, 15, 14, 30)
            val next = Cron.Daily.nextFire(now, utc)
            next.toEpochMilliseconds() shouldBe instant(2025, 6, 16, 0, 0).toEpochMilliseconds()
        }

        "@daily fires today if before midnight" {
            val now = instant(2025, 6, 15, 0, 0, 0) - 1.minutes
            val next = Cron.Daily.nextFire(now, utc)
            next shouldBe instant(2025, 6, 15, 0, 0, 0)
        }

        "@every 5 minutes fires 5 minutes later" {
            val now = instant(2025, 6, 15, 10, 0)
            val next = Cron.Every(5.minutes).nextFire(now, utc)
            next.toEpochMilliseconds() shouldBe instant(2025, 6, 15, 10, 5).toEpochMilliseconds()
        }

        "standard cron fires at exact minute" {
            val cron = Cron.Standard(minute = 30, hour = 14, dom = -1, month = -1, dow = -1)
            val now = instant(2025, 6, 15, 14, 0)
            val next = cron.nextFire(now, utc)
            next.toEpochMilliseconds() shouldBe instant(2025, 6, 15, 14, 30).toEpochMilliseconds()
        }

        "standard cron advances to next day if time has passed" {
            val cron = Cron.Standard(minute = 30, hour = 14, dom = -1, month = -1, dow = -1)
            val now = instant(2025, 6, 15, 15, 0)
            val next = cron.nextFire(now, utc)
            next.toEpochMilliseconds() shouldBe instant(2025, 6, 16, 14, 30).toEpochMilliseconds()
        }

        "standard cron with specific dom fires only on that day" {
            val cron = Cron.Standard(minute = 0, hour = 9, dom = 15, month = -1, dow = -1)
            val now = instant(2025, 6, 10, 10, 0)
            val next = cron.nextFire(now, utc)
            next.toEpochMilliseconds() shouldBe instant(2025, 6, 15, 9, 0).toEpochMilliseconds()
        }

        "standard cron ignores dom when set to wildcard" {
            val cron = Cron.Standard(minute = 0, hour = 9, dom = -1, month = -1, dow = -1)
            val now = instant(2025, 6, 15, 8, 0)
            val next = cron.nextFire(now, utc)
            next.toEpochMilliseconds() shouldBe instant(2025, 6, 15, 9, 0).toEpochMilliseconds()
        }
    })
