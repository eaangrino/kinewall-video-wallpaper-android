package com.eaangrino.kinewall

import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateScheduleTest {

    private val zone = ZoneId.of("America/Bogota")

    @Test
    fun `daily uses selected time`() {
        val now = ZonedDateTime.of(2026, 9, 8, 9, 0, 0, 0, zone)
        val schedule = UpdateScheduleSettings(
            frequency = UpdateCheckFrequency.DAILY,
            hour = 9,
            minute = 45,
            weeklyDay = DayOfWeek.MONDAY
        )

        val next = UpdateScheduleCalculator.nextCheckAt(now, schedule)

        assertEquals(8, next.dayOfMonth)
        assertEquals(9, next.hour)
        assertEquals(45, next.minute)
    }

    @Test
    fun `daily after selected time schedules next day`() {
        val now = ZonedDateTime.of(2026, 9, 8, 14, 30, 0, 0, zone)
        val schedule = UpdateScheduleSettings(
            frequency = UpdateCheckFrequency.DAILY,
            hour = 14,
            minute = 0,
            weeklyDay = DayOfWeek.MONDAY
        )

        val next = UpdateScheduleCalculator.nextCheckAt(now, schedule)

        assertEquals(9, next.dayOfMonth)
    }

    @Test
    fun `weekly uses selected weekday and time`() {
        val now = ZonedDateTime.of(2026, 9, 8, 10, 0, 0, 0, zone)
        val schedule = UpdateScheduleSettings(
            frequency = UpdateCheckFrequency.WEEKLY,
            hour = 18,
            minute = 30,
            weeklyDay = DayOfWeek.FRIDAY
        )

        val next = UpdateScheduleCalculator.nextCheckAt(now, schedule)

        assertEquals(DayOfWeek.FRIDAY, next.dayOfWeek)
        assertEquals(11, next.dayOfMonth)
        assertEquals(18, next.hour)
        assertEquals(30, next.minute)
    }

    @Test
    fun `weekly after selected weekday time schedules following week`() {
        val now = ZonedDateTime.of(2026, 9, 11, 19, 0, 0, 0, zone)
        val schedule = UpdateScheduleSettings(
            frequency = UpdateCheckFrequency.WEEKLY,
            hour = 18,
            minute = 30,
            weeklyDay = DayOfWeek.FRIDAY
        )

        val next = UpdateScheduleCalculator.nextCheckAt(now, schedule)

        assertEquals(18, next.dayOfMonth)
    }

    @Test
    fun `monthly uses last day of month`() {
        val now = ZonedDateTime.of(2026, 2, 10, 10, 0, 0, 0, zone)
        val schedule = UpdateScheduleSettings(
            frequency = UpdateCheckFrequency.MONTHLY,
            hour = 21,
            minute = 15,
            weeklyDay = DayOfWeek.MONDAY
        )

        val next = UpdateScheduleCalculator.nextCheckAt(now, schedule)

        assertEquals(28, next.dayOfMonth)
        assertEquals(21, next.hour)
        assertEquals(15, next.minute)
    }

    @Test
    fun `monthly handles leap year`() {
        val now = ZonedDateTime.of(2028, 2, 10, 10, 0, 0, 0, zone)
        val schedule = UpdateScheduleSettings(
            frequency = UpdateCheckFrequency.MONTHLY,
            hour = 21,
            minute = 15,
            weeklyDay = DayOfWeek.MONDAY
        )

        val next = UpdateScheduleCalculator.nextCheckAt(now, schedule)

        assertEquals(29, next.dayOfMonth)
    }

    @Test
    fun `monthly after last day time schedules last day of next month`() {
        val now = ZonedDateTime.of(2026, 9, 30, 22, 0, 0, 0, zone)
        val schedule = UpdateScheduleSettings(
            frequency = UpdateCheckFrequency.MONTHLY,
            hour = 21,
            minute = 15,
            weeklyDay = DayOfWeek.MONDAY
        )

        val next = UpdateScheduleCalculator.nextCheckAt(now, schedule)

        assertEquals(10, next.monthValue)
        assertEquals(31, next.dayOfMonth)
    }

    @Test
    fun `invalid stored frequency falls back to daily`() {
        assertEquals(UpdateCheckFrequency.DAILY, UpdateCheckFrequency.fromStoredValue("OTHER"))
    }
}
