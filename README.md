# Aria — голосовая виртуальная ассистентка (Windows + Android)

Kotlin Multiplatform + Compose Multiplatform. Один общий код (`shared`), два приложения:
`desktopApp` (Windows, MSI/EXE) и `androidApp` (APK).

## Режимы и роли
| Режим | Одежда | Роли |
|---|---|---|
| Флирт (18+, с подтверждением) | бельё (шёлк и кружево) | Роковая, Нежная, Озорная |
| Общение | повседневная | Подруга, Психолог, Наставница |
| Работа | деловой костюм | Ассистент, Аналитик, Инженер, Редактор |

Одежду можно переопределить вручную: Настройки → Ассистентка. Роли и промпты — `shared/.../Roles.kt`.

## Провайдеры и модели
OpenRouter, OpenAI, Groq, DeepSeek, Mistral, Ollama, LM Studio (всё через OpenAI-совместимый протокол),
нативные Anthropic и Google Gemini, плюс «Свой сервер». Список моделей подгружается кнопкой
(поиск по списку или ручной ввод id). Код: `Llm.kt`.

## Голос
* Распознавание: Whisper-совместимый эндпоинт (`/audio/transcriptions`): OpenAI, Groq или свой
  whisper.cpp / faster-whisper. Автоостановка записи по тишине.
* Озвучка: системная (Windows SAPI через PowerShell / Android TextToSpeech) или сервер `/audio/speech`
  (OpenAI, Kokoro, openedai-speech и др.). Ответ озвучивается по предложениям, пока он ещё генерируется.
  Код в блоках ``` не читается вслух.

## Синхронизация
WebDAV (Яндекс.Диск `https://webdav.yandex.ru/Aria/` + пароль приложения, Nextcloud и т.п.):
один файл `aria-sync.json`. Диалоги объединяются по id, настройки и провайдеры — по времени изменения.
API-ключи наружу не уходят.

## Сборка
Нужны JDK 21 и Android Studio / IntelliJ IDEA (Android SDK 37).

    ./gradlew :desktopApp:run               # запустить на Windows
    ./gradlew :desktopApp:packageMsi        # установщик (нужен WiX 3 на Windows)
    ./gradlew :androidApp:assembleDebug     # APK

CI: `.github/workflows/build.yml` собирает MSI/EXE и debug-APK.

## Где что
    shared/src/commonMain/kotlin/app/aria/
      Roles.kt                 роли и системные промпты
      Llm.kt                   клиенты провайдеров (OpenAI-совместимые, Anthropic, Gemini)
      AssistantController.kt   логика: режимы, диалог, голос, синхронизация
      Voice.kt, Sync.kt, Store.kt, Models.kt, Util.kt
      ui/Avatar.kt             анимированный аватар (Canvas)
      ui/App.kt, ui/SettingsScreen.kt
    shared/src/desktopMain/    микрофон, SAPI, воспроизведение (Windows)
    shared/src/androidMain/    микрофон, TextToSpeech, воспроизведение (Android)
