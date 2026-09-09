package com.eaangrino.kinewall

import java.time.DayOfWeek
import java.time.YearMonth
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

internal enum class UpdateCheckFrequency {
    DAILY,
    WEEKLY,
    MONTHLY;

    companion object {
        fun fromStoredValue(value: String?): UpdateCheckFrequency =
            entries.firstOrNull { it.name == value } ?: DAILY
    }
}

internal data class UpdateScheduleSettings(
    val frequency: UpdateCheckFrequency,
    val hour: Int,
    val minute: Int,
    val weeklyDay: DayOfWeek
)

internal object UpdateScheduleConfig {
    const val DEFAULT_CHECK_HOUR = 14
    const val DEFAULT_CHECK_MINUTE = 0
    val DEFAULT_WEEKLY_DAY: DayOfWeek = DayOfWeek.MONDAY
}

internal object UpdateScheduleCalculator {
    fun nextCheckAt(now: ZonedDateTime, settings: UpdateScheduleSettings): ZonedDateTime =
        when (settings.frequency) {
            UpdateCheckFrequency.DAILY -> nextDailyCheck(now, settings)
            UpdateCheckFrequency.WEEKLY -> nextWeeklyCheck(now, settings)
            UpdateCheckFrequency.MONTHLY -> nextMonthlyCheck(now, settings)
        }

    private fun nextDailyCheck(
        now: ZonedDateTime,
        settings: UpdateScheduleSettings
    ): ZonedDateTime {
        val candidate = now.atConfiguredTime(settings)
        return if (candidate.isAfter(now)) candidate else candidate.plusDays(1)
    }

    private fun nextWeeklyCheck(
        now: ZonedDateTime,
        settings: UpdateScheduleSettings
    ): ZonedDateTime {
        val candidate = now
            .with(TemporalAdjusters.nextOrSame(settings.weeklyDay))
            .atConfiguredTime(settings)
        return if (candidate.isAfter(now)) candidate else candidate.plusWeeks(1)
    }

    private fun nextMonthlyCheck(
        now: ZonedDateTime,
        settings: UpdateScheduleSettings
    ): ZonedDateTime {
        val thisMonth = YearMonth.from(now)
        val candidate = now
            .withDayOfMonth(thisMonth.lengthOfMonth())
            .atConfiguredTime(settings)

        if (candidate.isAfter(now)) return candidate

        val nextMonth = thisMonth.plusMonths(1)
        return now
            .plusMonths(1)
            .withDayOfMonth(nextMonth.lengthOfMonth())
            .atConfiguredTime(settings)
    }

    private fun ZonedDateTime.atConfiguredTime(settings: UpdateScheduleSettings): ZonedDateTime =
        withHour(settings.hour.coerceIn(0, 23))
            .withMinute(settings.minute.coerceIn(0, 59))
            .withSecond(0)
            .withNano(0)
}
