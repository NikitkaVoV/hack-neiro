# Конфигурация

`example.env` документирует параметры текущей production-сборки. Это справочный файл без секретов; Gradle читает значения из `android/gradle.properties` и project properties, а не загружает `.env`.

Финальные defaults: flavor `production`, release/R8, ARM64, Android API 30+, Nemotron dynamic INT8, activity threshold 0.50, ERes2Net matcher threshold 0.55, many-to-one выключен.
