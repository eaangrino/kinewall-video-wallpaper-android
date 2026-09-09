package com.eaangrino.kinewall

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eaangrino.kinewall.ui.KinewallTheme
import java.time.DayOfWeek

class UpdateSettingsActivity : ComponentActivity() {

    private var latestReleaseVersion by mutableStateOf<String?>(null)
    private var releaseCheckCompleted by mutableStateOf(false)
    private var checkStatus by mutableStateOf(UpdateCheckStatus.IDLE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()

        setContent {
            KinewallTheme {
                UpdateSettingsScreen()
            }
        }

        checkForUpdates()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return

        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2101)
    }

    private fun checkForUpdates() {
        if (checkStatus == UpdateCheckStatus.CHECKING) return

        releaseCheckCompleted = false
        checkStatus = UpdateCheckStatus.CHECKING

        Thread(
            {
                val result = try {
                    UpdateChecker.check(BuildConfig.VERSION_NAME)
                } catch (error: Exception) {
                    DiagnosticLogger.log(this, "MANUAL_UPDATE_CHECK_FAILED", throwable = error)
                    null
                }

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread

                    latestReleaseVersion = result?.latestVersion
                    releaseCheckCompleted = true
                    checkStatus = when {
                        result == null -> UpdateCheckStatus.FAILED
                        result.availableUpdate != null -> UpdateCheckStatus.UPDATE_AVAILABLE
                        else -> UpdateCheckStatus.UP_TO_DATE
                    }
                }
            },
            "kinewall-manual-update-check"
        ).start()
    }

    private fun openAvailableUpdate() {
        startActivity(
            Intent(this, ComposeMainActivity::class.java).apply {
                putExtra(EXTRA_INSTALL_AVAILABLE_UPDATE, true)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        )
    }

    private fun showTimePicker(
        schedule: UpdateScheduleSettings,
        onSelected: (UpdateScheduleSettings) -> Unit
    ) {
        TimePickerDialog(
            this,
            { _, hour, minute ->
                onSelected(schedule.copy(hour = hour, minute = minute))
            },
            schedule.hour,
            schedule.minute,
            true
        ).show()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun UpdateSettingsScreen() {
        var schedule by remember {
            mutableStateOf(UpdateCheckPreferences.getSchedule(this))
        }
        val saveSchedule: (UpdateScheduleSettings) -> Unit = { updatedSchedule ->
            schedule = updatedSchedule
            UpdateCheckScheduler.setSchedule(this, updatedSchedule)
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.update_settings_title)) },
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back_24),
                                contentDescription = stringResource(R.string.back_to_settings)
                            )
                        }
                    }
                )
            }
        ) { contentPadding ->
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
            ) {
                val horizontalPadding = when {
                    maxWidth >= 840.dp -> 40.dp
                    maxWidth >= 600.dp -> 32.dp
                    else -> 20.dp
                }
                val targetWidth = minOf(maxWidth, 800.dp)

                Box(
                    contentAlignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(
                        modifier = Modifier
                            .width(targetWidth)
                            .verticalScroll(rememberScrollState())
                            .padding(
                                start = horizontalPadding,
                                top = 20.dp,
                                end = horizontalPadding,
                                bottom = 48.dp
                            )
                    ) {
                        Text(
                            text = stringResource(R.string.update_settings_title),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = stringResource(R.string.update_settings_subtitle),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )

                        Spacer(Modifier.height(28.dp))
                        VersionCard()

                        Spacer(Modifier.height(16.dp))
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp)) {
                                Text(
                                    text = stringResource(R.string.update_frequency_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = stringResource(R.string.update_frequency_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 6.dp, bottom = 10.dp)
                                )

                                FrequencyOption(
                                    selected = schedule.frequency == UpdateCheckFrequency.DAILY,
                                    title = stringResource(R.string.update_frequency_daily),
                                    onClick = {
                                        saveSchedule(
                                            schedule.copy(frequency = UpdateCheckFrequency.DAILY)
                                        )
                                    }
                                )
                                FrequencyOption(
                                    selected = schedule.frequency == UpdateCheckFrequency.WEEKLY,
                                    title = stringResource(R.string.update_frequency_weekly),
                                    onClick = {
                                        saveSchedule(
                                            schedule.copy(frequency = UpdateCheckFrequency.WEEKLY)
                                        )
                                    }
                                )
                                FrequencyOption(
                                    selected = schedule.frequency == UpdateCheckFrequency.MONTHLY,
                                    title = stringResource(R.string.update_frequency_monthly),
                                    onClick = {
                                        saveSchedule(
                                            schedule.copy(frequency = UpdateCheckFrequency.MONTHLY)
                                        )
                                    }
                                )

                                Spacer(Modifier.height(12.dp))

                                when (schedule.frequency) {
                                    UpdateCheckFrequency.DAILY -> {
                                        TimeSettingRow(
                                            schedule = schedule,
                                            onClick = {
                                                showTimePicker(schedule, saveSchedule)
                                            }
                                        )
                                    }

                                    UpdateCheckFrequency.WEEKLY -> {
                                        WeeklyDaySettingRow(
                                            selectedDay = schedule.weeklyDay,
                                            onDaySelected = { day ->
                                                saveSchedule(schedule.copy(weeklyDay = day))
                                            }
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        TimeSettingRow(
                                            schedule = schedule,
                                            onClick = {
                                                showTimePicker(schedule, saveSchedule)
                                            }
                                        )
                                    }

                                    UpdateCheckFrequency.MONTHLY -> {
                                        Text(
                                            text = stringResource(R.string.update_monthly_last_day_hint),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(bottom = 8.dp)
                                        )
                                        TimeSettingRow(
                                            schedule = schedule,
                                            onClick = {
                                                showTimePicker(schedule, saveSchedule)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun VersionCard() {
        val releaseVersionText = if (!releaseCheckCompleted) {
            stringResource(R.string.release_checking)
        } else {
            latestReleaseVersion ?: stringResource(R.string.release_unavailable)
        }
        val updateAvailable = checkStatus == UpdateCheckStatus.UPDATE_AVAILABLE
        val refreshRotation = if (checkStatus == UpdateCheckStatus.CHECKING) {
            val transition = rememberInfiniteTransition(label = "updateCheckRotation")
            val rotation by transition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 900,
                        easing = LinearEasing
                    ),
                    repeatMode = RepeatMode.Restart
                ),
                label = "updateCheckRotationValue"
            )
            rotation
        } else {
            0f
        }

        OutlinedCard(Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.installed_version, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = stringResource(R.string.github_release_version, releaseVersionText),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    CheckStatusText()
                }

                IconButton(
                    onClick = {
                        if (updateAvailable) {
                            openAvailableUpdate()
                        } else {
                            checkForUpdates()
                        }
                    },
                    enabled = checkStatus != UpdateCheckStatus.CHECKING
                ) {
                    Icon(
                        painter = painterResource(
                            if (updateAvailable) {
                                R.drawable.ic_download_24
                            } else {
                                R.drawable.ic_refresh_24
                            }
                        ),
                        modifier = Modifier.rotate(refreshRotation),
                        contentDescription = stringResource(
                            if (updateAvailable) {
                                R.string.download_available_update
                            } else {
                                R.string.check_for_updates_now
                            }
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    @Composable
    private fun FrequencyOption(
        selected: Boolean,
        title: String,
        onClick: () -> Unit
    ) {
        Row(
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 4.dp)
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }

    @Composable
    private fun TimeSettingRow(
        schedule: UpdateScheduleSettings,
        onClick: () -> Unit
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.update_time_title),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = stringResource(R.string.update_time_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            OutlinedButton(onClick = onClick) {
                Text("%02d:%02d".format(schedule.hour, schedule.minute))
            }
        }
    }

    @Composable
    private fun WeeklyDaySettingRow(
        selectedDay: DayOfWeek,
        onDaySelected: (DayOfWeek) -> Unit
    ) {
        var expanded by remember { mutableStateOf(false) }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.update_weekly_day_title),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = stringResource(R.string.update_weekly_day_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Box {
                OutlinedButton(onClick = { expanded = true }) {
                    Text(dayOfWeekLabel(selectedDay))
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    DayOfWeek.entries.forEach { day ->
                        DropdownMenuItem(
                            text = { Text(dayOfWeekLabel(day)) },
                            onClick = {
                                expanded = false
                                onDaySelected(day)
                            }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun dayOfWeekLabel(day: DayOfWeek): String {
        return stringResource(
            when (day) {
                DayOfWeek.MONDAY -> R.string.weekday_monday
                DayOfWeek.TUESDAY -> R.string.weekday_tuesday
                DayOfWeek.WEDNESDAY -> R.string.weekday_wednesday
                DayOfWeek.THURSDAY -> R.string.weekday_thursday
                DayOfWeek.FRIDAY -> R.string.weekday_friday
                DayOfWeek.SATURDAY -> R.string.weekday_saturday
                DayOfWeek.SUNDAY -> R.string.weekday_sunday
            }
        )
    }

    @Composable
    private fun CheckStatusText() {
        val text = when (checkStatus) {
            UpdateCheckStatus.IDLE,
            UpdateCheckStatus.CHECKING -> null
            UpdateCheckStatus.UP_TO_DATE -> stringResource(R.string.update_status_up_to_date)
            UpdateCheckStatus.UPDATE_AVAILABLE -> stringResource(R.string.update_status_available)
            UpdateCheckStatus.FAILED -> stringResource(R.string.update_status_failed)
        }

        if (text != null) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }

    private enum class UpdateCheckStatus {
        IDLE,
        CHECKING,
        UP_TO_DATE,
        UPDATE_AVAILABLE,
        FAILED
    }
}
