/*
 * Copyright (c) 2024 vsesei69-coder
 * matrix-24-7-tracker
 */

package im.vector.app.features.location.tracker

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.telephony.SmsManager
import android.util.Log
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SMS-резерв для отправки координат, когда нет интернета.
 *
 * Формат SMS: "LOC: <lat>,<lon> | acc: <accuracy>m | time: <timestamp>"
 * Пример: "LOC: 59.94408,30.36093 | acc: 15m | time: 2026-03-27 17:15:42"
 */
@Singleton
class SmsLocationSender @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "SmsLocationSender"
        const val PREF_SMS_OWNER_PHONE = "geo_tracker_sms_owner_phone"
    }

    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    /**
     * Отправляет SMS с координатами на номер владельца.
     */
    suspend fun send(location: Location): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val ownerPhone = prefs.getString(PREF_SMS_OWNER_PHONE, null)
                ?: return@withContext Result.failure(IllegalStateException("Owner phone not configured"))

            val message = formatLocationMessage(location)
            val smsManager = SmsManager.getDefault()
            smsManager.sendTextMessage(ownerPhone, null, message, null, null)

            Log.i(TAG, "SMS sent to $ownerPhone: $message")
            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS", e)
            Result.failure(e)
        }
    }

    private fun formatLocationMessage(location: Location): String {
        val lat = String.format("%.5f", location.latitude)
        val lon = String.format("%.5f", location.longitude)
        val acc = String.format("%.0f", location.accuracy)
        val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(location.time))
        return "LOC: $lat,$lon | acc: ${acc}m | time: $time"
    }
}
