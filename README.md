# Мой стенограф

**Скачать приложение:** [GitHub Release v0.1.0](https://github.com/NikitkaVoV/hack-neiro/releases/tag/v0.1.0) — APK для Android 11+ (ARM64). Имя файла в релизе: `default.apk`.

**Видеодемонстрация:** [Смотреть или скачать MP4](artifacts/Видеодемонстрация.mp4).

**Обработка локальная:** распознавание речи и определение говорящих выполняются на Android-устройстве.

Локальное Android-приложение для записи и расшифровки русской речи. Аудио, текст и определение говорящих обрабатываются на телефоне; данные не отправляются в облачный сервис.

## Материалы сдачи

1. **APK:** [GitHub Release v0.1.0](https://github.com/NikitkaVoV/hack-neiro/releases/tag/v0.1.0), asset `default.apk`; локальная копия `artifacts/Мой стенограф.apk` (295 086 561 байт). SHA-256: `61021a5cc3dff79511993de281bea7dd41e94e62fa6e7a5d6348801f0e8f0ce8`.
2. **Исходный код:** Android Gradle-проект в `android/`; краткий указатель — [`src/README.md`](src/README.md).
3. **Установка и использование:** [`docs/INSTALL_AND_USAGE.md`](docs/INSTALL_AND_USAGE.md).
4. **Архитектура и модели:** [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md), [`models/README.md`](models/README.md).
5. **Ограничения:** [`docs/LIMITATIONS.md`](docs/LIMITATIONS.md).
6. **Результаты тестов:** [`docs/TEST_RESULTS.md`](docs/TEST_RESULTS.md), [`docs/TEST_RESULTS.csv`](docs/TEST_RESULTS.csv).
7. **Видеодемонстрация:** [`artifacts/Видеодемонстрация.mp4`](artifacts/Видеодемонстрация.mp4).

## Состав

- Основной вариант: `productionRelease`, package `ru.neurostenographer`.
- ASR: GigaAM v3 CTC INT8 с пунктуацией.
- Диаризация: frozen Nemotron-3 INT8.
- Android 11+ (`minSdk 30`), проверено на BRP-NX1 / Android 16 / ARM64.
- Bench APK не входит в комплект.

Веса моделей и локальные исследовательские данные не хранятся в Git. APK устанавливается отдельно; подпись текущей сборки — локальный Android Debug ключ, не ключ магазина.
