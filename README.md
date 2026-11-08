# Мой стенограф

Локальное Android-приложение для записи и расшифровки русской речи. Аудио, текст и определение говорящих обрабатываются на телефоне; данные не отправляются в облачный сервис.

## Материалы сдачи

1. **APK:** `Мой стенограф.apk` — передаётся отдельно. Локальная копия находится в `artifacts/`, GitHub её не хранит из-за размера (295 086 561 байт). SHA-256: `61021a5cc3dff79511993de281bea7dd41e94e62fa6e7a5d6348801f0e8f0ce8`.
2. **Исходный код:** Android Gradle-проект в `android/`; краткий указатель — [`src/README.md`](src/README.md).
3. **Установка и использование:** [`docs/INSTALL_AND_USAGE.md`](docs/INSTALL_AND_USAGE.md).
4. **Архитектура и модели:** [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md), [`models/README.md`](models/README.md).
5. **Ограничения:** [`docs/LIMITATIONS.md`](docs/LIMITATIONS.md).
6. **Результаты тестов:** [`docs/TEST_RESULTS.md`](docs/TEST_RESULTS.md), [`docs/TEST_RESULTS.csv`](docs/TEST_RESULTS.csv).
7. **Видеодемонстрация:** видеофайл в рабочей папке не найден; его нужно приложить отдельно после записи.

## Состав

- Основной вариант: `productionRelease`, package `ru.neurostenographer`.
- ASR: GigaAM v3 CTC INT8 с пунктуацией.
- Диаризация: frozen Nemotron-3 INT8.
- Android 11+ (`minSdk 30`), проверено на BRP-NX1 / Android 16 / ARM64.
- Bench APK не входит в комплект.

Веса моделей и локальные исследовательские данные не хранятся в Git. APK устанавливается отдельно; подпись текущей сборки — локальный Android Debug ключ, не ключ магазина.