/*
 * Copyright (c) 2024 vsesei69-coder
 * matrix-24-7-tracker
 */

package im.vector.app.features.location.tracker

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.location.Location
import android.os.ParcelUuid
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BLE Advertising Beacon для отправки координат по Bluetooth.
 *
 * Используется как last-resort fallback, когда нет ни интернета, ни GSM.
 * Координаты передаются в BLE Advertisement Data (радиус ~50-100м).
 *
 * Формат: UUID + 12 байт (lat: 4 bytes float, lon: 4 bytes float, timestamp: 4 bytes int)
 */
@Singleton
class BluetoothLocationBeacon @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "BluetoothLocationBeacon"
        
        // Custom UUID для Family Tracker
        private val BEACON_UUID = ParcelUuid.fromString("12345678-1234-5678-1234-567812345678")
    }

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
    
    private var advertiseCallback: AdvertiseCallback? = null

    /**
     * Начинает BLE Advertising с координатами.
     */
    fun advertise(location: Location) {
        if (advertiser == null) {
            Log.w(TAG, "BLE Advertiser not available")
            return
        }

        // Пакуем координаты в 12 байт
        val payload = encodeLocation(location)

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0) // бесконечно
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(BEACON_UUID)
            .addServiceData(BEACON_UUID, payload)
            .setIncludeDeviceName(false)
            .build()

        // Останавливаем предыдущее advertising
        stopAdvertising()

        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.i(TAG, "BLE Advertising started: ${location.latitude}, ${location.longitude}")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "BLE Advertising failed: error $errorCode")
            }
        }

        advertiser.startAdvertising(settings, data, advertiseCallback)
    }

    /**
     * Останавливает BLE Advertising.
     */
    fun stopAdvertising() {
        advertiseCallback?.let {
            advertiser?.stopAdvertising(it)
            Log.i(TAG, "BLE Advertising stopped")
        }
        advertiseCallback = null
    }

    /**
     * Кодирует координаты в 12 байт (lat, lon, timestamp).
     */
    private fun encodeLocation(location: Location): ByteArray {
        val buffer = ByteBuffer.allocate(12)
        buffer.putFloat(location.latitude.toFloat())   // 4 bytes
        buffer.putFloat(location.longitude.toFloat())  // 4 bytes
        buffer.putInt((System.currentTimeMillis() / 1000).toInt())  // 4 bytes (unix timestamp)
        return buffer.array()
    }
}
