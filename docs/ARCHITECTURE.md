# Архитектура решения

## Исполняемая система

Это нативное Android-приложение. Пользователь выбирает аудио через системный picker; `ProcessingService` выполняет длительную работу в фоне, а UI показывает состояние и результат. Задания и стенограммы хранятся локально через Room. APK не отправляет аудио или текст на сервер.

Путь обработки:

1. Источник открывается через Android `ContentResolver` и декодируется на устройстве в mono PCM 16 кГц.
2. GigaAM v3 CTC INT8 распознаёт речь и формирует пунктуацию.
3. Nemotron-3 Sortformer INT8 автоматически выделяет речевые отрезки и локальные ID голосов.
4. ERes2Net используется для сопоставления ID между внешними окнами длиной до 120 секунд.
5. Результат с текстом, временем и `speaker_id` сохраняется в Room и экспортируется в TXT/JSON.

Счётчик участников модели не задаётся. Эталонные тексты и разметка тестов в APK не входят. Распознавание выполняется CPU runtime sherpa-onnx; GPU/NPU ускорение в этой сборке не заявляется.

## Финальная конфигурация

- Android package: `ru.neurostenographer`, version `0.1.0`.
- `productionRelease`, minSdk 30, targetSdk 36, ABI `arm64-v8a`.
- ASR: встроенная GigaAM v3 e2e CTC INT8.
- Диаризация: NVIDIA Nemotron-3-Diarization, динамически квантизированный INT8 ONNX, threshold 0.50.
- Межоконное сопоставление: 3D-Speaker ERes2Net, threshold 0.55; many-to-one выключен.
- Sherpa-onnx Nemotron runtime закреплён локальным AAR; версии и SHA перечислены в [models/README.md](../models/README.md).

Production APK собрана без bench flavor. В merged manifest отсутствует `INTERNET`, а DEX не содержит `BenchHttpServer`, `BenchJobStore` и `BenchAudioStore`.
