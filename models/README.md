# Модели и лицензии финальной APK

Финальная основная сборка `productionRelease` использует локальный стек GigaAM v3 + frozen Nemotron INT8. Все веса в APK поставляются отдельно от Git; контрольные суммы ниже позволяют проверить точные файлы.

## Распознавание речи

- Модель: GigaAM v3 e2e CTC INT8, русский язык, с пунктуацией.
- Upstream package: [`csukuangfj/sherpa-onnx-nemo-ctc-punct-giga-am-v3-russian-2025-12-16`](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-ctc-punct-giga-am-v3-russian-2025-12-16/tree/4fb5407ff028a69fec516cdf4c10fac9ddea7c16), revision `4fb5407ff028a69fec516cdf4c10fac9ddea7c16`.
- `model.int8.onnx`: 224 893 661 байт, SHA-256 `d5fea8df94263c285e54b21e5774b707c707192d3bdbeffd7b1eb07fb6743b35`.
- `tokens.txt`: SHA-256 `142de7570b3de5b3035ce111a89c228e80e6085273731d944093ddf24fa539cd`.
- Лицензия: MIT; локальная копия находится в `models/licenses/GigaAM-v3-ONNX-LICENSE.txt`.

## Диаризация

- Модель: [NVIDIA Nemotron-3-Diarization](https://huggingface.co/nvidia/Nemotron-3-Diarization/tree/f667ed73aee57d40cc39428eb768b4fd87a0a29e), checkpoint revision `f667ed73aee57d40cc39428eb768b4fd87a0a29e`.
- Runtime/export: sherpa-onnx [PR #4032](https://github.com/k2-fsa/sherpa-onnx/pull/4032), source commit `6a4ad68b2cf4c69b684c040db1a5f7227675c93b`.
- Dynamic INT8 ONNX: 103 967 391 байт, SHA-256 `764e5babf6c9c96d27180fa1fe35fb05ef613f148af9e7c036dc78ac8dba8949`; квантуются MatMul.
- Параметры финальной сборки: activity threshold 0,50; внешние окна 120 секунд; CPU threads 4; число участников заранее не задаётся.
- Лицензия модели: OpenMDW-1.1, локальная копия и model card — `models/licenses/nemotron-3/`.

Для межоконного сопоставления используется embedding модель 3D-Speaker ERes2Net Base, SHA-256 `1a331345f04805badbb495c775a6ddffcdf1a732567d5ec8b3d5749e3c7a5e4b`, Apache-2.0 (`models/licenses/3D-Speaker-LICENSE.txt`), threshold 0,55; many-to-one выключен. Точные параметры и диагностические ограничения перечислены в `docs/ARCHITECTURE.md` и `docs/LIMITATIONS.md`.

## Runtime и локальные файлы

- sherpa-onnx Android runtime `2.0.1`, Apache-2.0. Поставочный `sherpa-onnx-nemotron.aar`: 12 149 428 байт, SHA-256 `905672c93b36e214eb55ab6e89ab490151ec1b96bfbf82089cb2cc3296c49901`. Это отдельная AAR из сборки Nemotron; не путать с обычной sherpa-onnx AAR.
- Лицензии runtime и дополнительных моделей лежат в `models/licenses/`.
- ASR и speaker assets включаются локальной Gradle-конфигурацией из `android/app/src/modelAssets/` и `android/app/src/mnnPrototypeNemotron/assets/`; сами веса и библиотеки не коммитятся. Для повторной сборки сверять SHA-256 до упаковки.

## Известное качество

Это зафиксированная поставочная конфигурация, а не гарантия идеальной диаризации. На сохранённом Dialogs dev frozen Nemotron объединил два голоса (3 reference → 2 hypothesis; DER/JER 34,971%/53,686%); на AMI ES2004a создал лишние ID (4 → 9; DER/JER 30,484%/34,158%). Эти оценки получены на сохранённых frozen-model runs, не на полном повторном прогоне финальной production APK. Nemotron не обучался на пользовательских данных.
