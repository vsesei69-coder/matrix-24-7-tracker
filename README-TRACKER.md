# matrix-24-7-tracker

> Fork of [element-hq/element-android](https://github.com/element-hq/element-android) — семейный трекер геолокации 24/7 на базе Matrix/Element.

## Цель проекта

Семейный трекер с открытым UI — все участники видят, что геолокация активна. Форк Element Android с модифицированным модулем Live Location Sharing:
- геолокация включена по умолчанию
- кнопка "остановить" убрана из основного UI (только через настройки)
- фоновый сервис отправляет координаты с заданным интервалом
- работает поверх своего Synapse-сервера (self-hosted)

## Архитектура изменений

```
element-android (форк)
├── vector/src/main/java/im/vector/app/
│   ├── features/location/live/
│   │   ├── LiveLocationSharingService.kt      <- фоновый сервис (изменить)
│   │   ├── LiveLocationSharingViewModel.kt    <- логика (изменить)
│   │   └── ui/
│   │       └── LiveLocationBottomSheet.kt     <- убрать кнопку Stop (изменить)
│   └── features/settings/
│       └── VectorSettingsLocationPrivacyFragment.kt  <- настройки геолокации
```

## Что нужно изменить (TODO)

### 1. Убрать кнопку "Stop sharing" из UI
**Файл:** `vector/src/main/java/im/vector/app/features/location/live/map/LiveLocationMapViewFragment.kt`
```kotlin
// Найти и закомментировать/удалить:
// binding.stopSharingButton.isVisible = true
// binding.stopSharingButton.setOnClickListener { viewModel.handle(LiveLocationMapAction.StopSharing) }
```

### 2. Запуск фонового сервиса при старте приложения
**Файл:** `vector/src/main/java/im/vector/app/features/home/HomeActivity.kt`
```kotlin
// В onCreate() добавить:
startForegroundService(Intent(this, LiveLocationSharingService::class.java))
```

### 3. Изменить интервал отправки координат
**Файл:** `vector/src/main/java/im/vector/app/features/location/live/LiveLocationSharingService.kt`
```kotlin
// Найти константу интервала и изменить:
const val UPDATE_INTERVAL_MS = 30_000L  // 30 секунд (было 5 минут)
```

### 4. Увеличить duration маячка (по умолчанию: 8 часов -> неограниченно)
**Файл:** `vector/src/main/res/values/config.xml`
```xml
<!-- Изменить duration по умолчанию -->
<integer name="live_location_default_duration_ms">28800000</integer>
<!-- На: Long.MAX_VALUE или повторный автозапуск -->
```

## Стек

| Компонент | Технология |
|---|---|
| Android-клиент | Element Android fork (Kotlin) |
| Протокол | Matrix (MSC3489 — live location) |
| Сервер | Synapse (self-hosted, Нидерланды) |
| Карты | MapLibre / OpenStreetMap |

## Сборка

```bash
git clone https://github.com/vsesei69-coder/matrix-24-7-tracker
cd matrix-24-7-tracker
./gradlew assembleGplayDebug
# APK: vector/build/outputs/apk/gplay/debug/
```

## Ветки

- `develop` — основная ветка (upstream element-android)
- `feature/geo-tracker` — ветка с изменениями трекера (создать)

## Лицензия

AGPL-3.0 (наследуется от element-android)

---

## Статус реализации (feature/geo-tracker)

| Файл | Описание | Статус |
|---|---|---|
| `GeoTrackerService.kt` | Foreground-сервис, диспетчер каналов Matrix/SMS/BLE | DONE |
| `GeoTrackerRepository.kt` | Matrix Live Location Beacon MSC3489 | DONE |
| `SmsLocationSender.kt` | SMS резерв без интернета (SmsManager) | DONE |
| `BluetoothLocationBeacon.kt` | BLE Advertising резерв без GSM | DONE |
| `GeoTrackerBootReceiver.kt` | Автозапуск после ребута/обновления | DONE |

## Следующие шаги (TODO)

- [ ] Прикрутить к `HomeActivity.onCreate()` -> запуск сервиса
- [ ] Добавить пермиссии в `AndroidManifest.xml` (ACCESS_FINE_LOCATION, SEND_SMS, BLUETOOTH_ADVERTISE, RECEIVE_BOOT_COMPLETED, FOREGROUND_SERVICE_LOCATION)
- [ ] Создать экран настроек: roomId + номер телефона для SMS
- [ ] Написать unit-тесты для SmsLocationSender и GeoTrackerService
## Инструкция по завершению реализации (ТЗ)

### Что УЖЕ сделано:

| Компонент | Статус |
|---|---|
| Foreground Service с диспетчером каналов | ✅ DONE |
| Matrix Live Location Beacon (MSC3489) | ✅ DONE |
| SMS резерв при отсутствии интернета | ✅ DONE |
| BLE Advertising резерв при отсутствии GSM | ✅ DONE |
| Автозапуск после ребута/обновления | ✅ DONE |
| GitLab CI/CD пайплайн | ✅ DONE |

---

### Что НУЖНО доделать:

#### 1. **GeoTrackerService.kt** — улучшения

```kotlin
override fun onCreate() {
    super.onCreate()
    Log.i(TAG, "GeoTrackerService created")
    fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
    createNotificationChannel()
    buildLocationRequest()
    buildLocationCallback()
    
    // Автозапуск трекинга БЕЗ UI-флага
    startGeoTracking()
}

private fun startGeoTracking() {
    startForeground(NOTIFICATION_ID, buildNotification())
    startLocationUpdates()
    Log.i(TAG, "Geo tracking started automatically")
}
```

**Что сделать:**
- Добавь метод `startGeoTracking()` в `onCreate()`
- Убедись, что `onStartCommand()` тоже вызывает `startGeoTracking()` если сервис ещё не запущен

---

#### 2. **GeoTrackerRepository.kt** — ретраи + оффлайн-кэш

```kotlin
private val offlineQueue = mutableListOf<Location>()
private val maxRetries = 3

suspend fun sendViaMatrix(
    location: Location,
    roomId: String? = null,
    retryCount: Int = 0
): Result<Unit> = withContext(Dispatchers.IO) {
    try {
        // ... основной код ...
        
        // Отправляем очередь оффлайн-координат
        flushOfflineQueue()
        
        Log.i(TAG, "Location sent via Matrix: ${location.latitude}, ${location.longitude}")
        Result.success(Unit)
        
    } catch (e: Exception) {
        Log.e(TAG, "Failed to send location (attempt #$retryCount)", e)
        
        // Ретрай
        if (retryCount < maxRetries) {
            delay(2000L * (retryCount + 1))
            return@withContext sendViaMatrix(location, roomId, retryCount + 1)
        }
        
        // Сохраняем оффлайн
        offlineQueue.add(location)
        Log.w(TAG, "Location added to offline queue (size: ${offlineQueue.size})")
        Result.failure(e)
    }
}
```

---

#### 3. **AndroidManifest.xml** — пермиссии

Добавь в `vector/src/main/AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.SEND_SMS" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.WAKE_LOCK" />

<application ...>
    <!-- GeoTrackerService -->
    <service
        android:name=".features.location.tracker.GeoTrackerService"
        android:enabled="true"
        android:exported="false"
        android:foregroundServiceType="location" />
    
    <!-- BootReceiver -->
    <receiver
        android:name=".features.location.tracker.GeoTrackerBootReceiver"
        android:enabled="true"
        android:exported="false">
        <intent-filter>
            <action android:name="android.intent.action.BOOT_COMPLETED" />
            <action android:name="android.intent.action.QUICKBOOT_POWERON" />
            <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
        </intent-filter>
    </receiver>
</application>
```

---

#### 4. **HomeActivity.kt** — запуск сервиса

Добавь в `vector/src/main/java/im/vector/app/features/home/HomeActivity.kt`:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // ... остальной код ...
    
    // Автозапуск GeoTrackerService
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        startForegroundService(GeoTrackerService.startIntent(this))
    } else {
        startService(GeoTrackerService.startIntent(this))
    }
    Log.i("HomeActivity", "GeoTrackerService started")
}
```

---

#### 5. **Настройки (опционально)**

Создать экран настроек для задания:
- `roomId` (куда отправлять координаты в Matrix)
- `smsOwnerPhone` (номер для SMS-резерва)

НО: не добавляй кнопки "вкл/выкл" трекинга — он всегда включён.

---

### Сборка и запуск CI/CD

```bash
# Локальная сборка
./gradlew :vector:assembleGplayDebug

# APK будет здесь:
# vector/build/outputs/apk/gplay/debug/vector-gplay-debug.apk
```

**Запусти GitLab CI:**
1. Перенеси репо на GitLab
2. Push ветку `feature/geo-tracker`
3. Pipeline автоматически запустит: lint → test → build
4. APK скачается из artifacts
