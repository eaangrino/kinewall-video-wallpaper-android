package com.eaangrino.kinewall

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

internal class UpdateCheckWorker(appContext: Context, workerParams: WorkerParameters) :
    Worker(appContext, workerParams) {

    override fun doWork(): Result {
        try {
            val result = UpdateChecker.check(BuildConfig.VERSION_NAME)
            val update = result?.availableUpdate

            if (
                update != null &&
                UpdateCheckPreferences.getLastNotifiedVersion(applicationContext) != update.version
            ) {
                val notified = UpdateNotification.show(applicationContext, update)
                if (notified) {
                    UpdateCheckPreferences.setLastNotifiedVersion(
                        applicationContext,
                        update.version
                    )
                }
            }
        } catch (error: Exception) {
            DiagnosticLogger.log(
                applicationContext,
                "SCHEDULED_UPDATE_CHECK_FAILED",
                throwable = error
            )
        } finally {
            if (!isStopped) {
                UpdateCheckScheduler.scheduleNext(applicationContext)
            }
        }

        return Result.success()
    }
}
