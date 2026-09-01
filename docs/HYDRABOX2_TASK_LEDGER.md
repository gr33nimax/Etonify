# HydraBox 2.0 — task ledger

Машиночитаемый журнал исполнения `docs/HYDRABOX2_PLAN.md`. Единственный источник
ответа на вопрос «выполнен ли предшественник». Строку добавляет сам агент — последним
шагом задачи, после коммита. Поля `STATUS` в карточках плана истиной не являются.

Формат: `<TASK ID> | <repo> | <commit> | <RESULT>`

`RESULT`: `DONE`, `BLOCKED(<что ждёт>)`, `CANCELLED(<причина>)`,
`DEFERRED(<причина>)`.

| TASK ID | REPO | COMMIT | RESULT |
|---|---|---|---|
| H2-000 | hydrabox | 0297082c5a48a0be7674122fa9dc93dae1de0102 | DONE |
| H2-001 | HydraBox2 | HEAD | DONE |
| H2-002 | HydraBox2 | fffcdd5 | DONE |
| H2-003 | HydraBox2 | b5d10c7 | DONE |
| H2-001A | HydraBox2 | 0c0deaf | DONE |
| H2-100 | HydraBox2 | 41fe904 | DONE |
| H2-A01 | HydraBox2 | 27577a6 | DONE |
| H2-A02 | HydraBox2 | 91757c0 | DONE |
| H2-A03 | HydraBox2 | fffcdd5 | DONE |
| H2-A04 | HydraBox2 | 41fe904 | DONE |
| H2-A05 | HydraBox2 | b5d10c7 | DONE |
| H2-B01 | HydraBox2 | 321fa7b, 3f820bd | DONE |
| H2-B02 | HydraBox2 | 15eb0fc | DONE |
| H2-B03 | HydraBox2 | e74948a, f2bdaba | DONE |
| H2-B04 | HydraBox2 | 94ccdc2, 718af8d | DONE |
| H2-B04-ACC | HydraBox2 | — | DEFERRED(по разрешению владельца; device-приёмка перенесена в финальную валидацию) |
| H2-C01 | HydraBox2 | 1dd5716 | DONE |
| H2-C02 | HydraBox2 | 1dd5716 | DONE |
| H2-C03 | HydraBox2 | 206e896 | DONE |
| H2-C05 | HydraBox2 | d4fc25d | DONE |
| H2-C04 | HydraBox2 | 4fbb071 | DONE(device-приёмка отложена по разрешению владельца) |
| H2-D01 | HydraBox2 | 1edcaf5, 994a438 | DONE |
| H2-D02 | HydraBox2 | 1795f05, 7d5e0a7 | DONE |
| H2-D03 | HydraBox2 | 701fa1a, 5fde2c5 | DONE |
| H2-D04 | HydraBox2 | 0202524, bb6f9a5, a7e6191, d377ea0, 5c0c9ad, 355510e, 9ff4043, e4d5b48, 817151f, cc19d51, 549555a, 690642c | DONE |
| H2-D05 | HydraBox2 | b6d7a1b, fd41a73 | DONE |
| H2-D07 | HydraBox2 | 83eb60a, a003ff7 | DONE |
| H2-D06 | HydraBox2 | 2f0fbe7, 9546c18 | DEFERRED(M4 device-приёмка отложена по разрешению владельца; нет доступного устройства или AVD) |
| H2-A06 | HydraBox2 | ec3ac8f | DONE |
| H2-D08 | HydraBox2 | 616db59 | DONE |
| H2-D10 | HydraBox2 | 7910d47 | DONE |
| H2-D09 | HydraBox2 | 6a63a23 | DONE |
| H2-C06 | HydraBox2 | — | PARTIAL — снимок расширен полем traffic в проекции, но ядро счётчики ещё не публикует; см. §11 |
| H2-E01 | HydraBox2 | (alpha) | DONE — токены, тема, brand seed, две схемы моции, два уровня компонентов |
| H2-E02 | HydraBox2 | (alpha) | DONE — компоненты по инвентарю; аудит MD3 формально не прогонялся |
| H2-E03 | HydraBox2 | (alpha) | PARTIAL — навигация во всех трёх классах ширины (bottom bar / rail); матрица expressive на desktop не снята, desktop вне цели |
| H2-E04 | HydraBox2 | (alpha) | DONE — экран подключения и дашборд трафика поверх ScreenState |
| H2-E05 | HydraBox2 | (alpha) | PARTIAL — подписки ссылкой и список, выбор прокси; QR и импорт файла не реализованы |
| H2-E06 | HydraBox2 | (alpha) | PARTIAL — общие, DNS, inbound/MTU, диагностика, правовое согласие; бэкап и обновления только read-модели |
| H2-E07 | HydraBox2 | (alpha) | DONE — android-debug.apk, arm64-v8a, 2.0.0-alpha1 (versionCode 200) |
| ANDROID-ALPHA | HydraBox2 | (alpha) | DONE — цель владельца: доведён Android-фронт до собираемой alpha, ветка hb2 |
| H2-UX01 | HydraBox2 | 551bd85 | DONE — 25 findings по десяти осям + 9 функциональных разрывов; docs/HYDRABOX2_UX_REVIEW.md |
| H2-E08a | HydraBox2 | 551bd85 | DONE — реальная иконка и логотип из 1.x, edge-to-edge, TopAppBar, иконки навигации, три destination |
| H2-E10 | HydraBox2 | 551bd85 | DONE — composeResources, ru+en, правовые документы перенесены из app_ru.arb/app_en.arb |
| H2-E08b | HydraBox2 | 551bd85 | DONE — Connection вместо ScreenPhase, 35 кодов → 6 ситуаций, транспорт и коды только в диагностике |
| H2-E08c | HydraBox2 | 551bd85, 5cb11e8 | DONE — блокирующий первый запуск; баннер согласия с экранов убран |
| H2-E08d | HydraBox2 | 551bd85 | DONE — серверы и подписки объединены, переключение сервера без ручного разрыва, подтверждения опасных действий |
| H2-E08e | HydraBox2 | 551bd85, a75e7e9 | DONE — базовые и продвинутые настройки, следствия в подписях, предупреждение о переподключении |
| H2-D12 | HydraBox2 | a75e7e9 | DONE — blockLeaks, bypassLocalNetwork, режимы приложений, тема, язык через per-app locale Android 13+ |
| H2-D11 | HydraBox2 | a75e7e9 | PARTIAL — правила без rule-set: hijack по порту, icmp, STUN, локальная сеть, режимы приложений; adblock и РФ-маршруты ждут конвейера правил (G-02) |
| H2-E11 | HydraBox2 | 5cb11e8 | PARTIAL — импорт файлом, бэкап с парольной фразой, экран трафика; QR, цепочки и шаринг не реализованы (G-07) |
| H2-E12 | HydraBox2 | 5cb11e8 | PARTIAL — contentDescription, liveRegion, заголовки для ридера; контраст и цели нажатия на устройстве не измерены |
| H2-E09 | HydraBox2 | — | DEFERRED(тесты экранов снимаются после приёмки на устройстве; в окружении нет устройства и эмулятора) |
