package com.murtdah.fit.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.murtdah.fit.domain.DayClock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

enum class StepSource(val label: String) {
    HEALTH_CONNECT("Health Connect (watch and phone)"),
    PHONE_SENSOR("Phone step sensor"),
    NONE("No step source"),
}

data class StepReading(val steps: Int, val source: StepSource, val problem: String? = null)

class StepReader(private val context: Context, private val repo: Repository) {
    val readPermission: String = HealthPermission.getReadPermission(StepsRecord::class)

    fun healthConnectStatus(): Int = HealthConnectClient.getSdkStatus(context)

    fun healthConnectAvailable(): Boolean = healthConnectStatus() == HealthConnectClient.SDK_AVAILABLE

    suspend fun hasHealthPermission(): Boolean {
        if (!healthConnectAvailable()) return false
        return runCatching {
            HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions().contains(readPermission)
        }.getOrDefault(false)
    }

    fun hasActivityPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    suspend fun read(): StepReading {
        val date = repo.today()
        if (!repo.prefs.preferPhoneSensor && hasHealthPermission()) {
            val hc = runCatching {
                val client = HealthConnectClient.getOrCreate(context)
                val start = DayClock.dayStart(date, repo.zone).toInstant()
                val end = java.time.Instant.now()
                val result = client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(start, end)))
                (result[StepsRecord.COUNT_TOTAL] ?: 0L).toInt()
            }
            if (hc.isSuccess) return save(StepReading(hc.getOrThrow(), StepSource.HEALTH_CONNECT))
        }
        if (hasActivityPermission()) {
            val raw = readSensorOnce()
            if (raw != null) {
                val key = date.toString()
                var base = repo.prefs.sensorBaseline(key)
                if (base == null || raw < base) {
                    val last = repo.prefs.sensorLast
                    base = when {
                        base != null -> 0f
                        last > 0f && last <= raw -> last
                        else -> raw
                    }
                    repo.prefs.setSensorBaseline(key, base)
                }
                repo.prefs.sensorLast = raw
                return save(StepReading((raw - base).toInt(), StepSource.PHONE_SENSOR,
                    "Phone sensor only counts steps after the app first reads it each day, and misses steps when the phone is not on you."))
            }
        }
        val saved = repo.day(date)
        return StepReading(saved.steps, StepSource.NONE, "Grant Health Connect or physical activity access in Settings.")
    }

    private suspend fun save(r: StepReading): StepReading {
        repo.updateDay { it.copy(steps = r.steps, stepSource = r.source.name) }
        return r
    }

    private suspend fun readSensorOnce(): Float? {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null
        return withTimeoutOrNull(3_000) {
            suspendCancellableCoroutine { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        sm.unregisterListener(this)
                        if (cont.isActive) cont.resume(event.values[0])
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                cont.invokeOnCancellation { sm.unregisterListener(listener) }
            }
        }
    }
}
