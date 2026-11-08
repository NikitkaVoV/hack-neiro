# Результаты проверки

Устройство: BRP-NX1 / Android 16 / ARM64. Дата финальной проверки: 8 ноября 2026. Метрики относятся к конкретным коротким выборкам.

## Финальная production APK

- `productionRelease`, APK 295 086 561 байт, SHA-256 `61021a5cc3dff79511993de281bea7dd41e94e62fa6e7a5d6348801f0e8f0ce8`.
- Установка и запуск основного package прошли. Production merged manifest не содержит `INTERNET`; DEX не содержит bench HTTP/storage классов.
- Импортировано 10,743-секундное RUSLAN WAV. GigaAM вернула 17/17 слов: WER 0,00 на этом одном примере; пунктуационный smoke F1 0,571. Диаризация завершилась, выдала один turn `speaker_1`. Полный JSON экспортирован и содержит Nemotron INT8 metadata. Engine wall 6,121 мс, RTF 0,570. Это функциональный smoke, не benchmark производительности.

## Историческая оценка frozen Nemotron

| Набор | Текст | Голоса ref → hyp | DER / JER | Примечание |
|---|---:|---:|---:|---|
| Dialogs dev, 47,075 с | WER 4/93 = 4,30%; punctuation F1 0,667 | 3 → 2 | 34,971% / 53,686% | Короткая контролируемая склейка реплик; Masha и Sveta объединены. |
| AMI ES2004a, 17:29 | WER не оценивался | 4 → 9 | 30,484% / 34,158% | Естественная встреча; сохранён лишний split участников. |

Официальные DER/JER приведены с нулевым collar и включённым overlap. Эти результаты — сохранённые измерения frozen Nemotron bench APK; весь multi-speaker набор не прогонялся повторно на финальной production APK. Сводные строки: [CSV](TEST_RESULTS.csv).
