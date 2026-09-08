package com.eaangrino.kinewall

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

internal object UpdateCheckPreferences {
    private const val PREFERENCES_NAME = "kinewall_update_preferences"
    private const val KEY_FREQUENCY = "frequency"
    private const val KEY_HOUR = "hour"
    private const val KEY_MINUTE = "minute"
    private const val KEY_WEEKLY_DAY = "weekly_day"
    private const val KEY_LAST_NOTIFIED_VERSION = "last_notified_version"

    fun getSchedule(context: Context): UpdateScheduleSettings {
        val preferences = preferences(context)
        val frequency = UpdateCheckFrequency.fromStoredValue(
            preferences.getString(KEY_FREQUENCY, null)
        )
        val weeklyDay = runCatching {
            DayOfWeek.valueOf(
                preferences.getString(
                    KEY_WEEKLY_DAY,
                    UpdateScheduleConfig.DEFAULT_WEEKLY_DAY.name
                ) ?: UpdateScheduleConfig.DEFAULT_WEEKLY_DAY.name
            )
        }.getOrDefault(UpdateScheduleConfig.DEFAULT_WEEKLY_DAY)

        return UpdateScheduleSettings(
            frequency = frequency,
            hour = preferences.getInt(
                KEY_HOUR,
                UpdateScheduleConfig.DEFAULT_CHECK_HOUR
            ).coerceIn(0, 23),
            minute = preferences.getInt(
                KEY_MINUTE,
                UpdateScheduleConfig.DEFAULT_CHECK_MINUTE
            ).coerceIn(0, 59),
            weeklyDay = weeklyDay
        )
    }

    fun setSchedule(context: Context, schedule: UpdateScheduleSettings) {
        preferences(context).edit()
            .putString(KEY_FREQUENCY, schedule.frequency.name)
            .putInt(KEY_HOUR, schedule.hour.coerceIn(0, 23))
            .putInt(KEY_MINUTE, schedule.minute.coerceIn(0, 59))
            .putString(KEY_WEEKLY_DAY, schedule.weeklyDay.name)
            .apply()
    }

    fun getLastNotifiedVersion(context: Context): String? {
        return preferences(context).getString(KEY_LAST_NOTIFIED_VERSION, null)
    }

    fun setLastNotifiedVersion(context: Context, version: String) {
        preferences(context).edit()
            .putString(KEY_LAST_NOTIFIED_VERSION, version)
            .apply()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}

internal object UpdateCheckScheduler {
    private const val UNIQUE_WORK_NAME = "kinewall-scheduled-update-check"

    fun ensureScheduled(context: Context) {
        enqueue(
            context = context,
            schedule = UpdateCheckPreferences.getSchedule(context),
            policy = ExistingWorkPolicy.KEEP
        )
    }

    fun setSchedule(context: Context, schedule: UpdateScheduleSettings) {
        UpdateCheckPreferences.setSchedule(context, schedule)
        enqueue(
            context = context,
            schedule = schedule,
            policy = ExistingWorkPolicy.REPLACE
        )
    }

    fun scheduleNext(context: Context) {
        enqueue(
            context = context,
            schedule = UpdateCheckPreferences.getSchedule(context),
            policy = ExistingWorkPolicy.APPEND_OR_REPLACE
        )
    }

    private fun enqueue(
        context: Context,
        schedule: UpdateScheduleSettings,
        policy: ExistingWorkPolicy
    ) {
        val now = ZonedDateTime.now()
        val nextCheckAt = UpdateScheduleCalculator.nextCheckAt(now, schedule)
        val delayMillis = Duration.between(now, nextCheckAt).toMillis().coerceAtLeast(0L)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequest.Builder(UpdateCheckWorker::class.java)
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, policy, request)
    }
}
