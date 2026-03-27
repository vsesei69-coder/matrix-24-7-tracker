/*
 * Copyright (c) 2024 vsesei69-coder
 * matrix-24-7-tracker
 */

package im.vector.app.features.location.tracker

import android.location.Location
import android.util.Log
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.features.location.LocationData
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.model.message.LocationInfo
import org.matrix.android.sdk.api.session.room.model.message.MessageBeaconInfoContent
import org.matrix.android.sdk.api.session.room.model.message.MessageLocationContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository для отправки геолокации через Matrix Live Location Sharing.
 *
 * Использует MSC3489 (Live Location Beacons) для отправки координат в комнату.
 * Координаты отправляются в заданную room (настраивается в настройках приложения).
 */
@Singleton
class GeoTrackerRepository @Inject constructor(
    private val activeSessionHolder: ActiveSessionHolder
) {
    companion object {
        private const val TAG = "GeoTrackerRepository"

        /** SharedPrefs key для хранения room ID, куда отправляются координаты. */
        const val PREF_GEO_TRACKER_ROOM_ID = "geo_tracker_room_id"

        /** Duration для Live Location Beacon (вечный маячок, 1 год). */
        const val BEACON_DURATION_MS = 365L * 24 * 60 * 60 * 1000 // 1 year
    }

    private var activeBeaconEventId: String? = null

    /**
     * Отправляет координаты в Matrix room через Live Location Beacon.
     *
     * @param location текущая геолокация.
     * @param roomId (опционально) room ID. Если null — берётся из SharedPrefs.
     * @return [Result] с успехом или ошибкой.
     */
    suspend fun sendViaMatrix(
        location: Location,
        roomId: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val session = activeSessionHolder.getSafeActiveSession()
                ?: return@withContext Result.failure(IllegalStateException("No active Matrix session"))

            // TODO: загрузить roomId из SharedPrefs, если roomId == null
            val targetRoomId = roomId ?: "!defaultRoomId:matrix.org" // placeholder

            val room = session.getRoom(targetRoomId)
                ?: return@withContext Result.failure(IllegalStateException("Room $targetRoomId not found"))

            // Если beacon ещё не создан — создаём
            if (activeBeaconEventId == null) {
                val beaconContent = MessageBeaconInfoContent(
                    timeout = BEACON_DURATION_MS,
                    isLive = true,
                    description = "Family Tracker",
                    unstableTimestampAsMilliseconds = System.currentTimeMillis()
                )
                val beaconEvent = room.sendService().sendEvent(
                    eventType = "m.beacon_info",
                    content = beaconContent.toContent()
                )
                activeBeaconEventId = beaconEvent.eventId
                Log.i(TAG, "Beacon created: ${beaconEvent.eventId}")
            }

            // Отправляем обновление координат (beacon update)
            val locationInfo = LocationInfo(
                geoUri = "geo:${location.latitude},${location.longitude}",
                description = null
            )
            val locationContent = MessageLocationContent(
                unstableLocationInfo = locationInfo,
                unstableText = "Current location",
                relatesTo = activeBeaconEventId?.let {
                    // m.relates_to по спецификации MSC3489
                    mapOf("rel_type" to "m.reference", "event_id" to it)
                }
            )

            room.sendService().sendEvent(
                eventType = MessageType.MSGTYPE_LOCATION,
                content = locationContent.toContent()
            )

            Log.i(TAG, "Location sent via Matrix: ${location.latitude}, ${location.longitude}")
            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send location via Matrix", e)
            Result.failure(e)
        }
    }

    /**
     * Останавливает активный beacon (отправляет isLive: false).
     */
    suspend fun stopBeacon(roomId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val session = activeSessionHolder.getSafeActiveSession()
                ?: return@withContext Result.failure(IllegalStateException("No active session"))

            val room = session.getRoom(roomId)
                ?: return@withContext Result.failure(IllegalStateException("Room not found"))

            activeBeaconEventId?.let { eventId ->
                val stopBeaconContent = MessageBeaconInfoContent(
                    timeout = BEACON_DURATION_MS,
                    isLive = false,
                    description = "Family Tracker (stopped)",
                    unstableTimestampAsMilliseconds = System.currentTimeMillis()
                )
                room.sendService().sendEvent(
                    eventType = "m.beacon_info",
                    content = stopBeaconContent.toContent()
                )
                Log.i(TAG, "Beacon stopped: $eventId")
            }

            activeBeaconEventId = null
            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop beacon", e)
            Result.failure(e)
        }
    }
}
