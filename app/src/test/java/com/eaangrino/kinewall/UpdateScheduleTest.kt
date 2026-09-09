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
    fun `daily at exact selected time schedules next day`() {
        val now = ZonedDateTime.of(2026, 9, 8, 14, 0, 0, 0, zone)
        val schedule = UpdateScheduleSettings(
            frequency = UpdateCheckFrequency.DAILY,
            hour = 14,
            minute = 0,
            weeklyDay = DayOfWeek.MONDAY
        )

        val next = UpdateScheduleCalculator.nextCheckAt(now, schedule)

        assertEquals(9, next.dayOfMonth)
        assertEquals(14, next.hour)
        assertEquals(0, next.minute)
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
    fun `weekly at exact selected time schedules following week`() {
        val now = ZonedDateTime.of(2026, 9, 11, 18, 30, 0, 0, zone)
        val schedule = UpdateScheduleSettings(
            frequency = UpdateCheckFrequency.WEEKLY,
            hour = 18,
            minute = 30,
            weeklyDay = DayOfWeek.FRIDAY
        )

        val next = UpdateScheduleCalculator.nextCheckAt(now, schedule)

        assertEquals(18, next.dayOfMonth)
        assertEquals(DayOfWeek.FRIDAY, next.dayOfWeek)
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
    fun `monthly at exact last day time schedules next month`() {
        val now = ZonedDateTime.of(2026, 9, 30, 21, 15, 0, 0, zone)
        val schedule = UpdateScheduleSettings(
            frequency = UpdateCheckFrequency.MONTHLY,
            hour = 21,
            minute = 15,
            weeklyDay = DayOfWeek.MONDAY
        )

        val next = UpdateScheduleCalculator.nextCheckAt(now, schedule)

        assertEquals(10, next.monthValue)
        assertEquals(31, next.dayOfMonth)
        assertEquals(21, next.hour)
        assertEquals(15, next.minute)
    }

    @Test
    fun `out of range time values are clamped`() {
        val now = ZonedDateTime.of(2026, 9, 8, 10, 0, 0, 0, zone)
        val schedule = UpdateScheduleSettings(
            frequency = UpdateCheckFrequency.DAILY,
            hour = 99,
            minute = -10,
            weeklyDay = DayOfWeek.MONDAY
        )

        val next = UpdateScheduleCalculator.nextCheckAt(now, schedule)

        assertEquals(23, next.hour)
        assertEquals(0, next.minute)
    }

    @Test
    fun `invalid stored frequency falls back to daily`() {
        assertEquals(UpdateCheckFrequency.DAILY, UpdateCheckFrequency.fromStoredValue("OTHER"))
        assertEquals(UpdateCheckFrequency.DAILY, UpdateCheckFrequency.fromStoredValue(null))
    }

    @Test
    fun `valid stored frequency is restored`() {
        assertEquals(UpdateCheckFrequency.DAILY, UpdateCheckFrequency.fromStoredValue("DAILY"))
        assertEquals(UpdateCheckFrequency.WEEKLY, UpdateCheckFrequency.fromStoredValue("WEEKLY"))
        assertEquals(UpdateCheckFrequency.MONTHLY, UpdateCheckFrequency.fromStoredValue("MONTHLY"))
    }
}
