package com.eaangrino.kinewall

import android.content.Context

object DiagnosticSettings {

    private const val PREFERENCES_NAME = "kinewall_preferences"
    private const val KEY_LOGGING_ENABLED = "diagnostic_logging_enabled"

    fun isLoggingEnabled(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_LOGGING_ENABLED, false)
    }

    fun setLoggingEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LOGGING_ENABLED, enabled)
            .apply()
    }
}
