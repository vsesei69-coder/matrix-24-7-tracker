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
