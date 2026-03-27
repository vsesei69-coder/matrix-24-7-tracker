/*
 * Copyright (c) 2024 vsesei69-coder
 * matrix-24-7-tracker
 */

package im.vector.app.features.location.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * BroadcastReceiver для автозапуска GeoTrackerService после перезагрузки устройства.
 *
 * Слушает:
 * - BOOT_COMPLETED — после загрузки устройства
 * - QUICKBOOT_POWERON — после быстрой перезагрузки (HTC)
 * - MY_PACKAGE_REPLACED — после обновления приложения
 */
class GeoTrackerBootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "GeoTrackerBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "Received broadcast: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "Starting GeoTrackerService after $action")
                startGeoTrackerService(context)
            }
        }
    }

    private fun startGeoTrackerService(context: Context) {
        val serviceIntent = GeoTrackerService.startIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
        Log.i(TAG, "GeoTrackerService started")
    }
}
