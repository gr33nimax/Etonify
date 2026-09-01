# HydraBox 1.x → 2.0 — карта паритета

Режим работы: **1.x — референсная спецификация поведения**, а не источник вдохновения.
Каждая функция, настройка и сценарий 1.x имеет ровно один статус:

- **DONE** — перенесено и работает end-to-end;
- **PARTIAL** — работает часть, названа какая;
- **MISSING** — не перенесено, карточка ниже;
- **REMOVED(причина)** — сознательно удалено, причина записана.

Правило приоритета: сначала функциональный паритет, потом улучшения. Ни одна карточка
не считается закрытой по наличию экрана — только по работающему сценарию.

Числа в таблицах — строки исходников, они здесь как мера объёма переноса, а не как цель.

## 1. Подписки

### 1.1 Карта

| HB1 | Строк | HB2 | Статус |
|---|---|---|---|
| `subscription_parser.dart` — детект формата и разбор | 679 | `core/subscription/OutboundCatalogParser` | PARTIAL |
| `parsers/hydra_subscription_parser.dart` | 599 | `OutboundCatalogParser.hydra` + `HydraCoreGate` | DONE |
| `parsers/singbox_config_parser.dart` | 820 | `OutboundCatalogParser.singbox` | PARTIAL — проекция verbatim без валидации схемы |
| `parsers/link_parser.dart` — share-ссылки | 906 | `SubscriptionParser` + `ShareLinkOutbound` | PARTIAL |
| `parsers/sip008_parser.dart` | 77 | `OutboundCatalogParser.sip008` | DONE |
| `parsers/clash_parser.dart` — YAML | 443 | — | **MISSING** |
| `parsers/xray_config_parser.dart` | 711 | — | **MISSING** |
| `parsers/wireguard_config_parser.dart` | 183 | `SubscriptionParser.parseWireGuard` | PARTIAL — только как одна ссылка `[Interface]` |
| `outbound_schema.dart` — валидация ~40 типов | 1740 | `core/subscription/OutboundSchema` — только `call` | PARTIAL |
| `subscription_fetcher.dart` — HTTP, HWID, заголовки | 1157 | `platform/android/SubscriptionFetcher` | PARTIAL |
| `subscription_failure.dart` — 17 типов отказа | 293 | исключения с текстом + 4 `SourceProblem` | PARTIAL |
| `happ_crypto_link.dart` — RSA-ссылки Happ (ключи 2–4) | 415 | — | REMOVED(владелец, 2026-09-01: не используются) |
| `happ_crypt5_local.dart` — локальный crypt5 | 463 | — | REMOVED(владелец, 2026-09-01: не используются) |
| `location_aliases.dart` — страна по имени сервера | 847 | — | **MISSING** |
| `hydra_subscription_uri.dart` | 71 | `core/subscription/HydraSubscriptionUri` | DONE |
| `subscription_store.dart` — хранение, миграции, блокировки | 3770 | `core/subscription/SubscriptionStore` (23) + `AppStore` | PARTIAL |
| `strict_json.dart` — строгий разбор JSON | 315 | `Json { isLenient = true }` | REMOVED(намеренно: документ проецируется verbatim, строгость перенесена в `HydraCoreGate.validate` для Hydra-документов; для остальных форматов строгость даёт отказ там, где 1.x импортировал) — **пересмотреть в P1** |

### 1.2 Findings

- **P-01 BLOCKER. Clash YAML и Xray JSON не импортируются.** В `SubscriptionParser`
  есть `detectDocument`, который различает `CLASH` и `XRAY`, и `parseDocument`, который
  достаёт из них имена — но **этот код не вызывается ниоткуда, кроме тестов**. Реальный
  путь импорта идёт через `OutboundCatalogParser.parse`, где есть только hydra,
  sing-box, sip008, base64 и raw-ссылки. То есть подписка в формате Clash или Xray в 2.0
  не добавляется, хотя тесты создают впечатление поддержки.
- **P-02 MAJOR. Метаданные подписки из HTTP-заголовков не читаются.** 1.x разбирает
  `subscription-userinfo` (израсходовано / всего / до какого числа) и строку профиля из
  заголовков и комментариев тела. В 2.0 срок действия берётся только из Hydra-документа,
  а квота трафика не показывается вообще.
- **P-03 MAJOR. Идентификация устройства для Hydra-подписок не переносится.** 1.x
  добавляет `X-Hydra-HWID`/`X-HWID` для HTTPS-ссылок Hydra JWE, снимает эти заголовки
  при cross-origin redirect и требует HTTPS, если в URL есть креденшелы. В 2.0 запрос
  идёт без идентификации; политика редиректов сводится к счётчику 5.
- **P-04 MAJOR. Таксономия отказов свёрнута до текста исключения.** 17 видов отказа 1.x
  (`unsafeRedirect`, `credentialsRequireHttps`, `htmlResponse`, `responseTooLarge`,
  `timeout`, `dns`, `tls`, `noUsableProxies`, …) в 2.0 превращаются в сообщение
  исключения; продукт различает четыре `SourceProblem`. Пользователю нельзя сказать
  «сервер вернул HTML вместо подписки» или «ссылка требует HTTPS».
- **P-05 MINOR. Страна сервера не определяется.** 847 строк алиасов 1.x давали флаг и
  страну в списке серверов; в 2.0 сервер — это только тег.

## 2. Генерация конфигурации

`singbox_config_builder.dart` (2532) принимает 32 входа. `TunnelInput` в 2.0 — 13.

| Вход HB1 | HB2 | Статус |
|---|---|---|
| `outbounds`, `config`, `activeSubscription` | `TunnelInput.outbounds` | DONE |
| `selectedProxyTag` | `selectedTag` | DONE |
| `vpnMtu` | `mtu` | DONE |
| `dnsDirectResolver`, `dnsProxyResolver` | те же | DONE |
| `urlTestUrl`, `urlTestIntervalSeconds` | те же | DONE |
| `urlTestTimeoutSeconds`, `urlTestConcurrency`, `urlTestUnavailableCheckIntervalSeconds` | — | REMOVED(проверено по 1.x: билдер их принимает, но в конфигурацию не кладёт — это параметры прежнего клиентского цикла измерения; в 2.0 измеряет ядро группой `urltest`) |
| `urlTestStrictTolerance` | `urlTestToleranceMillis` | DONE |
| `interruptExistingConnections` | то же | DONE |
| `vpnStrictRoute` | `strictRoute` | DONE |
| `vpnTunImplementation` | `tunStack` | DONE |
| `tcpFastOpenEnabled`, `tcpMultiPathEnabled` | `tcpFastOpen`, `tcpMultiPath` | DONE |
| `tlsFragmentationMode` | `tlsFragmentation` | DONE |
| `blockLeaks` | `blockLeaks` | DONE |
| `bypassLocalNetwork` | `bypassLocalNetwork` | DONE |
| `splitRoutingMode`, `splitRoutingPackages` | `includePackages`/`excludePackages` | DONE |
| `logLevel` | `logLevel` | DONE |
| `vpnInboundEnabled` | — | **MISSING** |
| `proxyInboundEnabled`, `proxyMixedListen`, `proxyMixedPort` (proxy-only) | — | **MISSING** |
| `adBlockEnabled`, `useRussiaRouteData`, `markAllServersRussia` | — | **MISSING** (нужен конвейер rule-set) |
| `nativeDetoursByChainTag` (цепочки) | — | **MISSING** |
| `proxyOutboundTagsByIndex`, `visibleProxyOutboundCount` | — | **MISSING** |
| DNS `fakeip`, `prefer_ipv6`, пресеты | — | **MISSING** |
| `snowtunBinaryPath`/`snowtunProtectPath` | — | REMOVED(в 1.x передаются как `null` из `app.dart:3970`; мёртвый вход) |

**P-06 ЗАКРЫТ** (кроме proxy-only, который вынесен в P3). TLS-фрагментация, strict route,
tun-реализация, TCP fast open и multipath, допуск url-test и разрыв соединений при смене
сервера доезжают до конфигурации и покрыты тестами `TunnelConfigGeneratorTest`. Про
url-test timeout/concurrency/unavailable выяснилось при сверке: **их и 1.x в конфигурацию не
кладёт**, они относятся к прежнему клиентскому циклу измерения, которого в 2.0 нет.

**P-07 MAJOR.** Цепочки прокси (`nativeDetoursByChainTag`, `hydra_proxy_chain_resolver`)
не переносятся: outbound с `detour` проецируется verbatim и работает, только если цель
цепочки оказалась в том же документе, но продукт не умеет ни показать цепочку, ни
собрать её.

## 3. Настройки

55 ключей `app_settings_store.dart` против 28 полей `Settings` 2.0.

| Ключ HB1 | HB2 | Статус |
|---|---|---|
| `performance_mode` | `performanceMode` | DONE |
| `urltest_url`, `urltest_interval_seconds`, `urltest_timeout_seconds`, `urltest_concurrency` | те же | PARTIAL — хранятся, в конфиг доходят два из четырёх |
| `location_lookup_limit`, `location_lookup_concurrency` | те же | PARTIAL — ничего не читает |
| `dns_direct_resolver`, `dns_proxy_resolver`, `russia_dns_direct_resolver` | те же | DONE / последний не используется |
| `dns_direct_preset`, `dns_proxy_preset` | — | **MISSING** (в 1.x — выбор из списка вместо ручного ввода) |
| `dns_fake_ip_enabled`, `dns_prefer_ipv6` | — | **MISSING** |
| `block_leaks`, `bypass_local_network` | те же | DONE |
| `split_routing_mode`, `split_routing_packages` | те же | DONE |
| `ad_block_enabled`, `use_russia_route_data`, `route_exclude_russia_enabled` | — | **MISSING** (конвейер rule-set) |
| `vpn_inbound_enabled`, `vpn_strict_route`, `vpn_tun_implementation`, `vpn_mtu` | только `vpnMtu` | PARTIAL |
| `proxy_inbound_enabled`, `proxy_mixed_listen`, `proxy_mixed_port`, `proxy_allow_lan` | — | **MISSING** (proxy-only) |
| `proxy_username`, `proxy_password` | те же | PARTIAL — хранятся, inbound их не получает |
| `experimental_tcp_fast_open`, `experimental_tcp_multi_path` | — | **MISSING** |
| `tls_fragmentation_mode` | `tlsFragmentationMode` | PARTIAL — только хранится |
| `singbox_log_level` | `logLevel` в `TunnelInput` (константа `warn`) | **MISSING** как настройка |
| `hide_server_ip` | — | **MISSING** |
| `status_notification_enabled` | то же | DONE |
| `theme_preference`, `locale_code` | `themeMode`, `language` | DONE |
| `accent_color_hex`, `haptic_enabled`, `progressive_blur_enabled` | — | REMOVED(оформление 1.x: акцентный цвет заменён динамической палитрой Material You, блюр и отдельная тактильность не переносятся) |
| `memory_limit_enabled` | то же | PARTIAL — только хранится |
| `accepted_legal_version`, `accepted_legal_at_millis` | те же | DONE |
| `onboarding_completed` | выводится из согласия и наличия источника | DONE(намеренное отличие) |
| `selected_proxy_tag`, `active_profile_id` | метаданные `runtime.selected.outbound` | DONE |
| `proxy_sort` | `proxySort` | PARTIAL — только хранится |
| `update_install_mode` | — | см. §5, решение владельца |
| `hide_server_ip`, `mark_all_servers_russia` | — | **MISSING** |

## 4. Граница рантайма и интерфейса

Проверено grep'ом по `ui/`: `READY`, `STARTING`, `lanes`, `generation`, `transport`,
`outbound`, `snapshot` и коды ошибок в пользовательских строках отсутствуют; слово
transport встречается один раз — в строке экрана диагностики. Статус: **DONE**.

Осталось одно исключение, найденное в ходе аудита и записанное как G-10: состояние
«требуется капча VK» в 2.0 не отличимо от «Подключаюсь…», потому что контракт не несёт
`challengeId`/`challengeUri`, а `CoreObserver` не публикует состояние транспорта.

## 5. Карточки паритета, в порядке исполнения

Каждая карточка обязана начинаться с четырёх пунктов: что было в HB1, где это
реализовано, что переносится, какие отличия намеренные.

### P1 — импорт подписки: форматы и отказы

- **Было в HB1:** восемь форматов (`hydraV2`, `singboxConfig`, `xrayConfig`, `sip008`,
  `clashYaml`, `base64Links`, `rawLinks`, `wireguardConfig`), 17 типов отказа, разбор
  метаданных из HTTP-заголовков, HWID-заголовки для Hydra JWE, запрет креденшелов по
  HTTP и небезопасного редиректа.
- **Где:** `lib/data/subscription/subscription_parser.dart`,
  `parsers/clash_parser.dart`, `parsers/xray_config_parser.dart`,
  `subscription_fetcher.dart`, `subscription_failure.dart`.
- **Переносится:** Clash YAML и Xray JSON в `OutboundCatalogParser`; типизированный
  `SourceFailure` вместо текста исключения; чтение `subscription-userinfo`; HWID для
  Hydra-ссылок со снятием при cross-origin; отказ при креденшелах по HTTP и при
  https→http редиректе; различение пустого, HTML и слишком большого ответа.
- **Намеренные отличия:** мёртвый `SubscriptionParser.parseDocument` удаляется, а не
  «дорабатывается»: один путь импорта, а не два. Разбор Clash — свой минимальный YAML
  под подмножество `proxies:`, потому что тянуть YAML-парсер в `commonMain` ради одного
  формата дороже, чем 200 строк на подмножество.

### P2 — конфигурация: настройки, которые есть только в модели — СДЕЛАНО

- **Было в HB1:** билдер получал url-test timeout/concurrency/unavailable-interval,
  TLS-фрагментацию, strict route, реализацию tun, TCP fast open и multipath, уровень
  журнала как настройку.
- **Где:** `lib/singbox/singbox_config_builder.dart`, `lib/data/local/app_settings_store.dart`.
- **Переносится:** все перечисленные входы в `TunnelInput` и в генератор; настройки,
  которые уже лежат в `Settings`, получают путь до конфигурации.
- **Намеренные отличия:** нет.

### P3 — proxy-only и второй inbound

- **Было в HB1:** `vpn_inbound_enabled` и `proxy_inbound_enabled` независимы; при
  выключенном VPN поднимался только `mixed`-inbound на выбранном адресе и порту, с
  логином и паролем и опциональным доступом из локальной сети.
- **Где:** `singbox_config_builder.dart` (`mixed-in`), `settings_inbound_page.dart`.
- **Переносится:** генерация `mixed`-inbound, `RuntimeMode.PROXY` в сервисе, продуктовое
  состояние «работает как прокси» на Доме, настройки адреса, порта и доступа из сети.
- **Намеренные отличия:** «proxy-only» остаётся в разделе для продвинутых; на Доме это
  не третий режим кнопки, а состояние подключения с другим текстом.

### P4 — конвейер rule-set: блокировка рекламы и РФ-маршруты

- **Было в HB1:** два сервиса, которые скачивают, кэшируют, версионируют и валидируют
  наборы правил, и настройки, которые включают их в маршрутизацию.
- **Где:** `lib/data/adblock/ad_block_rule_set_service.dart` (710),
  `lib/data/routing/russia_route_data_service.dart` (1701),
  `settings_routing_page.dart`.
- **Переносится:** загрузка и кэш наборов, состояние «доступно / не скачано», правила в
  конфигурации, тумблеры с честным состоянием.
- **Намеренные отличия:** до появления конвейера тумблеров в UI нет — вместо тумблера,
  который ничего не делает, показывается кнопка «скачать наборы правил».

### P5 — хранилище подписок: миграции и одновременный доступ

- **Было в HB1:** блокировка записи, разрешение конфликтов идентификаторов, миграции
  схемы, политики источников, восстановление после частично записанного состояния.
- **Где:** `lib/data/subscription/subscription_store.dart` (3770),
  `subscription_storage_id.dart`.
- **Переносится:** валидация идентификатора источника, сериализация записи (в 2.0 два
  процесса пишут в одну SQLite), поведение при повторном добавлении того же URL.
- **Намеренные отличия:** миграции Hive/секретных боксов не переносятся — в 2.0 схему
  ведёт SQLDelight, а импорт данных из 1.x закрывает `H2-G01`.

### P6 — QR, шаринг, цепочки

- **Было в HB1:** импорт по QR, шаринг сервера ссылкой и QR, отображение и сбор цепочек.
- **Где:** `subscriptions_page_qr.dart`, `proxies_page_share.dart`,
  `proxies_page_chains.dart`, `hydra_proxy_chain_resolver.dart`.
- **Переносится:** решение по каждому пункту принимает владелец: QR требует камеры и
  библиотеки декодирования, шаринг — генерации ссылок, цепочки — модели цепочки в
  проекции.
- **Намеренные отличия:** предлагается перенести QR и шаринг, а цепочки оставить
  видимыми только в диагностике до отдельного решения.

### P7 — обновление приложения и капча

- **Было в HB1:** `app_update_service.dart` (1326) — проверка версии, скачивание APK,
  подтверждение установки; и открытие капчи VK при `waiting_user`.
- **Где:** `lib/data/update/app_update_service.dart`, `lib/app/app.dart:661`.
- **Переносится:** решение владельца. Капча — аддитивное расширение контракта (R14) плюс
  продуктовое состояние; обновление — либо перенос, либо REMOVED, если 2.0 раздаётся
  иначе.
- **Намеренные отличия:** —

## 6. Что делается прямо сейчас

P1. Порядок внутри карточки: сначала форматы (Clash, Xray) с тестами на реальных
образцах, потом типизированные отказы, потом заголовки и метаданные.
