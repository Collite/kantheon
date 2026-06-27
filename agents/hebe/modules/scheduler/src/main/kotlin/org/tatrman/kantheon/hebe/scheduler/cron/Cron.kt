@file:Suppress("NewLineAtEndOfFile")

package org.tatrman.kantheon.hebe.scheduler.cron

import kotlin.time.Duration

sealed interface Cron {
    data object Hourly : Cron

    data object Daily : Cron

    data class Every(
        val interval: kotlin.time.Duration,
    ) : Cron

    data class Standard(
        val minute: Int,
        val hour: Int,
        val dom: Int,
        val month: Int,
        val dow: Int,
    ) : Cron
}
