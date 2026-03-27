/*
 * Copyright (c) 2024 vsesei69-coder
 * matrix-24-7-tracker — семейный трекер геолокации
 * Лицензия: AGPL-3.0
 *
 * GeoTrackerService — Foreground-сервис с тройным каналом доставки:
 *   1. Matrix Live Location (основной, через интернет)
 *   2. SMS (резерв при отсутствии интернета)
 *   3. BLE Advertising (резерв при отсутствии GSM — ближний радиус)
 */

package im.vector.app.features.location.tracker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.extensions.vectorComponent
import im.vector.app.features.home.HomeActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.Session
import javax.inject.Inject

/**
 * Foreground-сервис непрерывного отслеживания геолокации.
 *
 * Каналы доставки координат (выбираются автоматически по доступности):
 * - [GeoTrackerChannel.MATRIX] — Matrix Live Location Sharing (интернет)
 * - [GeoTrackerChannel.SMS]    — SMS с координатами (GSM, без интернета)
 * - [GeoTrackerChannel.BLE]    — BLE Advertising (Bluetooth, без сети)
 *
 * Сервис запускается:
 * - при старте приложения (HomeActivity)
 * - после перезагрузки устройства (GeoTrackerBootReceiver)
 * - после обновления приложения (GeoTrackerBootReceiver)
 *
 * Уведомление всегда видно пользователю — честный семейный трекер.
 */
@AndroidEntryPoint
class GeoTrackerService : Service() {

    companion object {
        private const val TAG = "GeoTrackerService"
        const val NOTIFICATION_ID = 9001
        const val CHANNEL_ID = "geo_tracker_channel"
        const val CHANNEL_NAME = "Family Location Tracker"

        /** Интервал обновления координат (30 секунд). */
        const val LOCATION_INTERVAL_MS = 30_000L

        /** Минимальная дистанция для обновления (10 метров). */
        const val LOCATION_MIN_DISTANCE_M = 10f

        /** Максимальное время ожидания первого фикса (5 секунд). */
        const val LOCATION_MAX_WAIT_MS = 5_000L

        /** Номер телефона владельца для SMS-резерва (задаётся в настройках). */
        const val PREF_SMS_OWNER_PHONE = "geo_tracker_sms_owner_phone"

        fun startIntent(context: Context) =
            Intent(context, GeoTrackerService::class.java)

        fun stopIntent(context: Context) =
            Intent(context, GeoTrackerService::class.java).apply {
                action = ACTION_STOP
            }

        private const val ACTION_STOP = "im.vector.app.geo_tracker.STOP"
    }

    // -------------------------------------------------------------------------
    // Зависимости (Hilt)
    // -------------------------------------------------------------------------

    @Inject
    lateinit var geoTrackerRepository: GeoTrackerRepository

    @Inject
    lateinit var smsLocationSender: SmsLocationSender

    @Inject
    lateinit var bluetoothLocationBeacon: BluetoothLocationBeacon

    @Inject
    lateinit var session: Session

    // -------------------------------------------------------------------------
    // Внутренние поля
    // -------------------------------------------------------------------------

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var lastLocation: Location? = null
    private var consecutiveFailures = 0

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "GeoTrackerService created")
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        buildLocationRequest()
        buildLocationCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Stop action received — stopping service")
            stopSelf()
            return START_NOT_STICKY
        }
        Log.i(TAG, "GeoTrackerService started")
        startForeground(NOTIFICATION_ID, buildNotification())
        startLocationUpdates()
        return START_STICKY // перезапускается системой при убийстве
    }

    override fun onDestroy() {
        Log.i(TAG, "GeoTrackerService destroyed")
        fusedLocationClient.removeLocationUpdates(locationCallback)
        bluetoothLocationBeacon.stopAdvertising()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------------------------------------------------------------
    // Настройка запроса геолокации
    // -------------------------------------------------------------------------

    private fun buildLocationRequest() {
        locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            LOCATION_INTERVAL_MS
        ).apply {
            setMinUpdateDistanceMeters(LOCATION_MIN_DISTANCE_M)
            setMaxUpdateDelayMillis(LOCATION_MAX_WAIT_MS)
            setWaitForAccurateLocation(false)
        }.build()
    }

    private fun buildLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                Log.d(TAG, "Location update: lat=${location.latitude}, lon=${location.longitude}, acc=${location.accuracy}m")
                lastLocation = location
                consecutiveFailures = 0
                dispatchLocation(location)
            }
        }
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "ACCESS_FINE_LOCATION permission not granted — cannot start location updates")
            return
        }
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
        Log.i(TAG, "Location updates started (interval=${LOCATION_INTERVAL_MS}ms)")
    }

    // -------------------------------------------------------------------------
    // Диспетчер каналов доставки
    // -------------------------------------------------------------------------

    /**
     * Выбирает канал доставки и отправляет координаты.
     *
     * Приоритет каналов:
     * 1. Matrix (если есть интернет и активная сессия)
     * 2. SMS (если нет интернета, но есть GSM)
     * 3. BLE Advertising (если нет ни интернета, ни GSM)
     */
    private fun dispatchLocation(location: Location) {
        serviceScope.launch {
            val channel = selectChannel()
            Log.i(TAG, "Dispatching via channel: $channel")

            val result = when (channel) {
                GeoTrackerChannel.MATRIX -> geoTrackerRepository.sendViaMatrix(location)
                GeoTrackerChannel.SMS    -> smsLocationSender.send(location)
                GeoTrackerChannel.BLE    -> {
                    bluetoothLocationBeacon.advertise(location)
                    Result.success(Unit)
                }
            }

            result.onFailure { error ->
                consecutiveFailures++
                Log.e(TAG, "Dispatch failed via $channel (attempt #$consecutiveFailures): ${error.message}")
                // Fallback: если Matrix не сработал — пробуем SMS
                if (channel == GeoTrackerChannel.MATRIX) {
                    Log.i(TAG, "Matrix failed — fallback to SMS")
                    smsLocationSender.send(location)
                }
            }

            result.onSuccess {
                Log.i(TAG, "Location dispatched successfully via $channel")
                updateNotification(location, channel)
            }
        }
    }

    /**
     * Определяет доступный канал доставки.
     */
    private fun selectChannel(): GeoTrackerChannel {
        return when {
            hasInternetConnection() -> GeoTrackerChannel.MATRIX
            hasTelephony()          -> GeoTrackerChannel.SMS
            else                    -> GeoTrackerChannel.BLE
        }
    }

    // -------------------------------------------------------------------------
    // Проверка каналов связи
    // -------------------------------------------------------------------------

    private fun hasInternetConnection(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun hasTelephony(): Boolean {
        return packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    }

    // -------------------------------------------------------------------------
    // Уведомление Foreground-сервиса
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW // тихое уведомление без звука
            ).apply {
                description = "Family location tracking is active"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        location: Location? = null,
        channel: GeoTrackerChannel = GeoTrackerChannel.MATRIX
    ): Notification {
        val contentText = if (location != null) {
            val lat = String.format("%.5f", location.latitude)
            val lon = String.format("%.5f", location.longitude)
            "\uD83D\uDCCD $lat, $lon  ·  ${channel.label}"
        } else {
            "Ожидание геолокации..."
        }

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            HomeActivity.newIntent(this, null),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Family Tracker активен")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_location_pin) // иконка из element-android
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(location: Location, channel: GeoTrackerChannel) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(location, channel))
    }
}

/**
 * Перечисление каналов доставки координат.
 */
enum class GeoTrackerChannel(val label: String) {
    MATRIX("Matrix"),
    SMS("SMS"),
    BLE("Bluetooth")
}
