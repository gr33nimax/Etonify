# HydraBox 1.x → 2.0 — независимый аудит расхождений

Дата: 2026-09-01. Метод: чтение исходников обоих репозиториев, `./gradlew --offline ciCheck`
(BUILD SUCCESSFUL, 430 unit-тестов), сверка с `hydracore` d0de962 (sing-box 1.13).
Устройства и эмулятора в окружении нет — пункты, требующие проверки на устройстве,
помечены `[нужна проверка на устройстве]`.

Этот файл не заменяет `HYDRABOX2_HB1_PARITY.md`: там карта функций 1.x. Здесь — что
именно ломается в 2.0 при попытке им пользоваться, включая класс расхождений, которого в
карте паритета нет вообще (интеграция с платформой Android).

## 1. Итог

Сборка проходит, тесты зелёные, экраны есть. Неработоспособность 2.0 не в отсутствии
кода, а в четырёх местах, где код есть, но путь до устройства не замкнут:

1. **DNS-бутстрап**: ядру не отдан платформенный DNS-транспорт, а вся схема DNS в
   конфигурации построена на `type: local` (§2.1). Это единственный кандидат на «подключается,
   но интернета нет».
2. **Конфигурация отвергается целиком**, если в подписке есть WireGuard: `endpoints`
   ядра 1.13 кладутся в `outbounds` (§2.2).
3. **Ссылки vmess:// и SIP002 ss:// разбираются с потерей транспорта** — самые
   распространённые серверы не подключаются (§2.3).
4. **Таймеры и дедлайны редьюсера не исполняются платформой** — зависший старт остаётся
   «Подключаюсь…» навсегда (§2.4).

Плюс отсутствует весь слой платформенной интеграции 1.x: deep links, фоновое обновление
подписок, `onRevoke`, запрос разрешения на уведомления, запрет Android-бэкапа (§3).

Объём: 1.x — 78 523 строки Dart + 20 325 Kotlin, 78 dart-тестов и 49 kotlin-тестов,
674 строки локализации. 2.0 — 11 160 строк Kotlin (core 3 696, platform 2 693, ui 4 771),
430 unit-тестов, 0 тестов на `platform/`, 12 строк тестов на `ui/`, 219 строк локализации.
Числа — мера покрытия переноса, не цель.

## 2. Блокеры

### 2.1 DNS: ядру не отдан платформенный резолвер, а конфигурация на него опирается

`AndroidVpnPlatform.kt:107` — `localDNSTransport(): LocalDNSTransport? = null`.
В `hydracore/experimental/libbox/config.go:29` тип `local` подменяется платформенной
реализацией только если она не `null`; иначе работает `dns/transport/local` для Linux,
который читает `/etc/resolv.conf` и `/etc/hosts` — на Android этого файла нет, и
резолвинг уходит в bionic-резолвер процесса `:core`, не привязанный к нижележащей сети.

При этом `TunnelConfig.kt` завязан на `dns-local` в трёх местах: `dns.servers[0]`
(строка 132), `domain_resolver` для `dns-proxy` (строка 152) и
`route.default_domain_resolver` (строка 237). То есть от него зависит и разрешение имени
`dns.cloudflare.com`, и разрешение адресов самих серверов подписки.

1.x решала это явно: `HydraBoxPlatformInterface.kt:176` возвращает `HydraBoxLocalResolver`
— ~150 строк поверх `android.net.DnsResolver`, привязанные к текущей сети, с комментарием
в билдере (`singbox_config_builder.dart:489`) о том, что именно бутстрап через
пользовательский DNS создаёт отказ на старте.

Следствие: сервер, заданный IP, вероятно заработает; сервер по имени и любой DNS
приложений — нет. **[нужна проверка на устройстве]**, но исправление не зависит от
проверки: транспорт надо реализовать.

### 2.2 WireGuard из подписки отвергает всю конфигурацию

`OutboundCatalog.kt:108,139` читает и `outbounds`, и `endpoints` — правильно.
`TunnelConfig.kt:96-97` кладёт **все** записи каталога в массив `outbounds`.
В ядре (`hydracore/protocol/wireguard/endpoint.go:47`) `wireguard` зарегистрирован только
как endpoint; `Libbox.checkConfig` отвергает документ → `startCore` падает на первом же
шаге (`HydraVpnService.kt:100-113`), причём для всей подписки, а не для одного сервера.

Тот же путь у ссылок: `ShareLinkOutbound.kt:69` собирает `{"type":"wireguard"}` как
outbound. Секции `endpoints` генератор не создаёт вообще. В 1.x `endpoints` — отдельная
секция билдера (`singbox_config_builder.dart:449`).

### 2.3 Разбор ссылок теряет транспорт

- `SubscriptionParser.kt:68` (`parseVmess`): из base64-JSON читаются только `add`, `port`,
  `id`, `ps`, `tls`. Поля `net`, `path`, `host`, `sni`, `alpn`, `fp`, `type`, `aid`, `scy`
  игнорируются, и `ShareLink.Proxy` создаётся с пустым `query` → `ShareLinkOutbound`
  не добавит ни `transport`, ни `server_name`. VMess over WS+TLS — а это основная форма
  vmess в реальных подписках — соберётся как чистый TCP и не подключится.
  В 1.x это `link_parser.dart:_parseVmess` плюс маппинг транспортов на строках 657-715
  (`ws`, `grpc`, `h2`, `httpupgrade`, `xhttp`/`splithttp`, `kcp`/`mkcp`, `tcp`, `quic`).
- `ss://` в форме SIP002 несёт `base64(method:password)` в userinfo. `SubscriptionParser.proxy`
  (строка 95) делит userinfo по `:` без base64-декодирования → в `method` попадает мусор.
  Legacy-форма `ss://base64(method:password@host:port)` не проходит регулярку строки 33 вообще.
- Транспорты `httpupgrade`, `xhttp`, `kcp`, `quic` не поддержаны (`ShareLinkOutbound.kt:104`).
- Плагины shadowsocks (obfs) не поддержаны.

### 2.4 Таймеры редьюсера платформой не исполняются

`RuntimeReducer` возвращает `TimerOp.Arm/Cancel` и ждёт `RuntimeInput.Deadline`
(`RuntimeDeadline.START = 45s`, `RECOVERY = 60s`, `CLOSE = 5s`, `RELOAD = 15s`).
Grep по `platform/`: `TimerOp` не упоминается, `RuntimeInput.Deadline` не диспатчится,
`RuntimeInput.DeviceIdleExit` не диспатчится, `RuntimeCommand.NetworkChanged` никто не
submit'ит — значит `Effect.RebindNetwork` (`HydraVpnService.kt:89`) мёртв.

Следствия: зависший `startOrReloadService` не имеет тайм-аута — состояние «Подключаюсь…»
без выхода; выход из Doze не вызывает восстановление; смена сети не приводит к
`resetNetwork()` (частично спасает `InterfaceUpdateListener`, который ядро получает
напрямую, но `networkGeneration` в снимке всегда 0).

`RuntimeInvariantTest` и `RuntimeReducerTest` проверяют редьюсер в изоляции и дают
ложное ощущение готовности: контракт исполняется наполовину.

### 2.5 «Подключено» не подтверждено транспортом

`HydraVpnService.kt:120` диспатчит `TransportHealth(applicable = false)`, а
`TransportHealth.isReady` при `applicable == false` возвращает `true`
(`RuntimeContract.kt:44`). То есть `RUNNING` наступает сразу после того, как libbox принял
конфигурацию, и проекция показывает «Подключено» — включительно для случая, когда ни один
сервер не отвечает. Комментарий в `ScreenProjection` утверждает обратное («readiness — not
the phase name — decides»), но в текущей сборке readiness всегда `true`.
В 1.x за это отвечает `TransportHealthBridge.kt` + состояние `READY`.
Проверка G-10 из UX-review — про капчу; здесь речь о самом факте готовности.

## 3. Платформенная интеграция: класс расхождений, которого нет в карте паритета

Сверка манифестов (`platform/android/src/androidMain/AndroidManifest.xml` против
`hydrabox/android/app/src/main/AndroidManifest.xml`).

| 1.x | 2.0 | Следствие |
|---|---|---|
| deep links `hydrabox://import`, `happ://`, `sing-box://import-remote-profile` + `deep_link_import.dart` (248) | нет | подписку нельзя добавить по ссылке из браузера или мессенджера — основной путь у пользователей провайдеров |
| `SubscriptionRefreshScheduler` (WorkManager, периодически) | нет | подписки не обновляются в фоне; истёкшая подписка ломает подключение молча |
| `HydraBoxVpnService.onRevoke()` | нет | отзыв разрешения или запуск другого VPN оставляет 2.0 в состоянии «Подключено» с мёртвым tun |
| запрос `POST_NOTIFICATIONS` в рантайме | разрешение объявлено, но никогда не запрашивается (grep по `platform/`, `ui/` — ни одного вызова) | на Android 13+ уведомление foreground-сервиса не показывается: нет ни статуса, ни кнопки «Отключить» вне приложения |
| `android:allowBackup="false"`, `dataExtractionRules` | атрибуты отсутствуют → значение по умолчанию `true` | `hydrabox.db` уезжает в Google-бэкап; ключ из Keystore не уезжает, но имена подписок, настройки и метаданные — да |
| `networkSecurityConfig` | нет | политика cleartext по умолчанию платформы |
| `WAKE_LOCK` + `performance_mode` | разрешения нет | `performanceMode`/`economyMode` — тумблер без последствий |
| `foregroundServiceType="systemExempted"` для VPN | `specialUse` | несоответствие типа FGS назначению; отдельный риск на ревью в сторе |
| alias `QS_TILE_PREFERENCES` | нет | тайл без настроек |
| `applicationId = io.hydrabox.client` | `io.hydrabox.platform.android` | 2.0 не обновляет 1.x, а ставится рядом; id — служебное имя модуля, не продуктовое |
| `QUERY_ALL_PACKAGES` | `<queries>` по LAUNCHER | намеренное и разумное отличие; следствие — не-launcher-приложения не выбираются в split-туннеле |

Отдельно: миграции данных из 1.x нет (`H2-G01` не начат), формат бэкапа свой
(`hydrabox.backup.v1`, `BackupTransfer.kt:24`) и с файлами 1.x несовместим. При другом
`applicationId` это значит, что действующий пользователь 1.x переносит всё руками.

## 4. Major

- **Дневник ядра не собирается вообще.** `CoreObserver` подписан только на `CommandStatus`
  и `CommandGroup`; `Libbox.CommandLog` не запрошен, `onLog` в `HydraVpnService` не передан
  (значение по умолчанию — пустая лямбда). Экран диагностики поэтому синтетический:
  `RuntimeControlActivity.kt:222` жёстко задаёт `level = "warn"` и `exportState = "idle"`,
  а `recentEvents` собирается из полей снимка. В 1.x это `app_log_store.dart` (297) +
  `settings_logs_page.dart` (570) + `HydraBoxDiagnostics.kt`. Пользователь не может
  прислать лог, а мы не можем попросить.
- **Литеральная строка вместо чисел в квоте подписки.** `AppStore.kt:383`:
  `"${'$'}{value} B"` — это литерал `${value} B`, а не интерполяция. В строке источника
  показывается `${scaled / 10}.${scaled % 10} ${units[unit]}`. Рядом в
  `ScreenProjection.formatBytes` та же логика написана правильно — надо просто её вызвать.
- **Модель читается синхронно в композиции.** `RuntimeControlActivity.setContent` вызывает
  `readModel()` (строка 186), который на каждую рекомпозицию делает `store.settings()`,
  `store.summaries()`, `store.serverGroups()`, `store.ruleSetStatus()`, а `summaries()` ещё
  и парсит документ каждой подписки (`AppStore.kt:322` → `OutboundCatalogParser.parse`).
  Это SQLite и разбор JSON в главном потоке, инвалидация — через счётчик `revision`.
  Реактивности нет: изменение в `:core`-процессе UI не увидит до следующего `revision += 1`.
- **`experimental.cache_file` не генерируется.** 1.x пишет `cache_file` с `store_rdrc` и
  `cache_id` (`singbox_config_builder.dart:610`). Без него ядро на каждом старте заново
  измеряет всю группу и теряет кэш отказов.
- **Стратегия DNS не задана.** 1.x по умолчанию ставит `strategy: ipv4_only`
  (`singbox_config_builder.dart:418`) именно потому, что AAAA-ответы приводят к выбору
  недостижимого IPv6 через выход без IPv6, плюс `independent_cache` и `cache_capacity`.
  В 2.0 ничего этого нет — классическая картина «подключено, часть сайтов не открывается».
- **Импорт полного sing-box документа теряет всё, кроме outbounds.** 1.x сливает
  пользовательский документ с генерируемым без потерь (`_mergeRawCoreConfig`, ~400 строк):
  `route`, `dns`, `inbounds`, `services`, `endpoints`. 2.0 берёт только outbounds/endpoints
  каталогом — маршруты и DNS документа провайдера молча исчезают.
- **PlatformInterface — заглушки в четырёх методах.** `findConnectionOwner` возвращает
  пустой `ConnectionOwner` при `useProcFS() = false` (строки 54, 134) → правила по процессу
  и по uid не работают; `readWIFIState` пустой → правила по SSID не работают;
  `sendNotification` игнорируется → уведомления ядра теряются; `includeAllNetworks` всегда
  `false`. В `openTun` нет `setMetered`, `setUnderlyingNetworks`, `setConfigureIntent`,
  `setHttpProxy` (в 1.x — `HydraBoxPlatformInterface.kt:263,399`).
- **Настройки, которые хранятся и ни на что не влияют** (сверх списка G-05 в UX-review):
  `urlTestTimeoutSeconds`, `urlTestConcurrency`, `urlTestUnavailableCheckIntervalSeconds`,
  `locationLookup*`, `russiaDnsDirectResolver`, `memoryLimitEnabled`, `proxySort`,
  `notificationTrafficDisplayMode`, `statusNotificationEnabled` (тумблер есть, уведомление
  показывается всегда), `performanceMode`. Проверено grep'ом: за пределами `defaultSettings`
  и `settingsSummary` они не читаются.

## 5. Minor

- Уведомление пересоздаёт канал на каждом снимке (`HydraVpnService.notification`), иконка
  действия `null`, тексты «starting/running» — имена состояний рантайма в UI, вопреки
  правилу §4 карты паритета.
- `direct`-outbound не получает `tcp_fast_open`/`tcp_multi_path`, хотя все остальные
  получают (в 1.x получает: `singbox_config_builder.dart:481`).
- Кросс-процессная запись сериализуется только внутри процесса (`AppStore.mutate`,
  `synchronized`). Сама SQLite согласованность даёт, но read-modify-write настроек
  (`settings().copy(...)`) между процессами теряет обновления.
- `START_NOT_STICKY` + отсутствие восстановления после смерти `:core`: при
  `onServiceDisconnected` UI показывает «Остановлено», перезапуска нет. В 1.x —
  `CoreRuntimeService` (2560) + `CoreRuntimeClient` (1032) + инструментальный тест
  изоляции процессов.
- `platform/desktop` — пустой модуль (0 файлов `.kt`) при заявленной цели «Android и
  Windows первыми».
- Страна/флаг сервера, внешний IP активного прокси, `hide_server_ip`, сортировка списка,
  цепочки, QR, шаринг — отсутствуют (это уже зафиксировано в карте паритета, повторяю для
  полноты картины экрана серверов).

## 6. Что не так с самой картой паритета

- Статусы `DONE` в `HYDRABOX2_TASK_LEDGER.md` проверяют факт коммита, а не работу на
  устройстве: `H2-B04-ACC`, `H2-D06`, `H2-E09`, `H2-C04` отложены по устройству, и именно в
  этом зазоре живут §2.1, §2.4, §2.5.
- Карта паритета сравнивает Dart-код 1.x с Kotlin-кодом 2.0 и не рассматривает
  манифест, разрешения, WorkManager, deep links, `onRevoke`, бэкап платформы — весь §3.
- `P2` объявлен СДЕЛАНО, но `experimental.cache_file` и `dns.strategy` — входы билдера 1.x,
  которых нет в `TunnelInput`, и в карте они не упомянуты ни как перенос, ни как REMOVED.
- `P1` объявлен DONE по Clash/Xray, но регресс разбора ссылок (§2.3) картой не покрыт:
  ссылки считались перенесёнными ещё на `PARTIAL` без разбора того, что теряется.

## 7. Порядок исправления, по цене отказа

1. `localDNSTransport` на `android.net.DnsResolver` поверх нижележащей сети + тест на
   бутстрап по имени. Без этого проверять остальное на устройстве бессмысленно.
2. `endpoints` в генераторе: WireGuard из каталога и из ссылок — в `endpoints`, а
   `checkConfig` — в тест на реальных документах подписок.
3. Разбор `vmess://` (net/path/host/sni/alpn/fp/aid/scy) и SIP002 `ss://`; транспорты
   `httpupgrade`, `xhttp`, `kcp`, `quic`. Тесты — на реальных ссылках, не на синтетике.
4. Исполнение `TimerOp` и `RuntimeInput.Deadline` на платформе, `NetworkChanged` от
   `DefaultNetworkMonitor`, `DeviceIdleExit`.
5. Реальный `TransportHealth` вместо `applicable = false`, либо честное продуктовое
   состояние «туннель поднят, готовность не подтверждена».
6. `dns.strategy = ipv4_only`, `independent_cache`, `cache_capacity`,
   `experimental.cache_file`.
7. `CommandLog` в `CoreObserver` и настоящий экран диагностики.
8. Платформенный минимум: запрос `POST_NOTIFICATIONS`, `allowBackup="false"`, `onRevoke`,
   deep links, фоновое обновление подписок, продуктовый `applicationId`.
9. `readableBytes` — вызвать `formatBytes`; чтение модели вынести из композиции в поток.

## 8. Устройственная проверка — 2026-09-01

Проверено на подключённом Galaxy S25 (SM-S931B), debug APK от 2026-09-01 16:03.

- Зашифрованная Hydra-подписка с корректно переданным ключом во фрагменте URL не импортируется. После нажатия «Добавить» форма остаётся открытой и показывает общий отказ. Источник не сохранён.
- Диагностики причины нет: `HydraCoreGate.open` отбрасывает исходное исключение библиотеки, а `RuntimeControlActivity.background` сводит его к `Notice.OPERATION_FAILED`. В logcat нет прикладного события импорта или причины отказа.
- На реальном экране подтверждены дефекты из §4: белый квадрат вокруг логотипа, чрезмерный размер логотипа, и вертикально сжатая кнопка «Открыть файл» в строке действий.
- Переход в политику/соглашение и возврат назад сбрасывает onboarding на первый экран: текущий шаг живёт только в композиции.

Этот результат блокирует релиз: пока JWE-подписка не проходит реальный импорт с конкретной понятной ошибкой при отказе, перенос HB1 нельзя считать функционально завершённым.

## 8. Исправлено и проверено на устройстве (2026-09-01, Galaxy S25, Android 16)

Проверка: `./gradlew ciCheck` — BUILD SUCCESSFUL, 487 unit-тестов; установка на
SM-S931B, обе подписки владельца, туннель поднят, трафик идёт.

### 8.1 Настоящая причина неработоспособности, которой в аудите не было

**Приложение не поставляло `go.HydraNativeLoader`.** Android-артефакт HydraCore
патчится при сборке (`cmd/internal/build_libbox/android_loader_patch.go`): статический
инициализатор сгенерированного `go.Seq` переписывается с
`System.loadLibrary("box")` на `HydraNativeLoader.loadLibrary("box")`. Класс обязан
предоставить приложение; 1.x держит его в
`android/app/src/main/java/go/HydraNativeLoader.java`, а 2.0 его не перенёс. Итог:

```
NoClassDefFoundError: io.nekohasekai.libbox.Libbox
  Caused by: ClassNotFoundException: Didn't find class "go.HydraNativeLoader"
```

Это ломало **любое** обращение к ядру в любом процессе: открытие зашифрованной
подписки, `Libbox.checkConfig`, `Libbox.setup`, `newCommandServer`. То есть туннель не
мог подняться вообще — ровно то, что владелец описал как «фактически не работоспособен».
Один отсутствующий файл, невидимый в сборке и в тестах.

Перенесён минимальный вариант (2.0 не подменяет ядро на ходу, поэтому машинерия
проверки кандидата из 1.x не переносится), добавлено правило R8 и задача
`:platform:android:verifyNativeLoaderSeam`, которая падает, если AAR пропатчен, а класса
в проекте нет. Проверено: без файла задача падает, с файлом — проходит.

### 8.2 Что ещё закрыто

| Пункт | Что сделано | Как проверено |
|---|---|---|
| §2.1 DNS-бутстрап | `AndroidLocalResolver` на `android.net.DnsResolver`, привязанный к нижележащей сети; `raw()` для Android 10+, `getAllByName` через `Network` ниже | в журнале ядра `dns: exchanged A dns.cloudflare.com` и `lookup succeed` при поднятом туннеле |
| §2.2 WireGuard | секция `endpoints` в генераторе, флаг `CatalogOutbound.endpoint`, `wg://`/`wireguard://` и `[Interface]` → endpoint с полями AmneziaWG, Clash-`wireguard` переписан со старой формы outbound на endpoint | `endpoint/wireguard[amneziawg-desktop-gr33nimax]: received handshake response` на реальной подписке |
| §2.3 Разбор ссылок | `vmess://` читает `net/path/host/sni/alpn/fp/scy/aid/mode`; `ss://` SIP002 и legacy; транспорты `ws` (+early data), `grpc`, `http`, `httpupgrade`, `xhttp` (+`extra`), `quic`, `mkcp`; плагины shadowsocks | 9 тестов `ShareLinkFidelityTest` на формах реальных провайдеров |
| §2.4 Таймеры | `AndroidRuntime` исполняет `TimerOp`, диспатчит `RuntimeInput.Deadline`; `NetworkChanged` от монитора сети; `DeviceIdleExit` по выходу из Doze | в журнале `default network is now wlan0, generation 1`; дедлайн старта логируется при истечении |
| §2.5 Готовность | `TransportHealth` больше не `applicable = false`: готовность объявляется по первому статусному сообщению от ядра, то есть по факту, что ядро отвечает | `core-observer: the core is answering, traffic available true` |
| §4 Журнал | `HydraLog` (logcat под тегом `HydraBox` + кольцевой буфер), `CommandLog` у ядра, кросс-процессный слив предупреждений в БД, экран диагностики на реальных данных, экспорт журнала | вся диагностика в §8.1 и §8.3 получена именно так |
| §4 Байты | `${'$'}{value} B` заменён вызовом `readableBytes` из проекции | «Использовано 246.7 GiB» в строке источника |
| §4 Модель в композиции | чтение хранилища вынесено в `refresh()` на io-поток, живая половина снимка отделена от хранимой | — |
| §4 DNS-стратегия и кэши | `strategy: ipv4_only`, `independent_cache`, `cache_capacity`, `experimental.cache_file` с `store_rdrc` | `TunnelConfigSectionsTest` |
| §4 `direct` | получает `tcp_fast_open`/`tcp_multi_path` | там же |
| §3 Платформа | запрос `POST_NOTIFICATIONS`; `allowBackup="false"` и `dataExtractionRules`; `onRevoke`; always-on VPN; deep links `hydrabox://import`, `sing-box://import-remote-profile`, `ACTION_SEND`; периодическое обновление подписок через `JobScheduler`; `systemExempted` для VPN-сервиса; alias настроек тайла; `applicationId = io.hydrabox.client` | импорт обеих подписок выполнен через deep link; уведомление запрошено и выдано |
| §3 HWID | отправляется только `X-Hydra-HWID` (как в 1.x), `X-HWID` убран; `Accept` с Hydra-типами только для ссылок с ключом; идентификатор предлагается один раз, если сервер ответил 400/403 со словом HWID | на «обычной» подписке провайдер требует HWID — в журнале `asked for a device identifier; offering it once`, затем 200 |
| §5 Уведомление | канал создаётся один раз, тексты продуктовые (`Под защитой`, `Подключаюсь…`), иконка действия, `statusNotificationEnabled` управляет живыми счётчиками | — |
| Первый запуск | шаг выводится из состояния, а не запоминается: чтение документов и возврат больше не отбрасывают прогресс; `legalAccepted` по умолчанию `false`, а не `true` | на устройстве: «Перед началом» → документ → назад → «Перед началом» |
| Строка импорта | `ActionRow` переносит кнопки по ширине вместо обрезания | на устройстве видны все три действия |
| Логотип | прозрачный силуэт из 1.x, тонируется `colorScheme.primary` | белый квадрат исчез, знак виден на тёмной теме |
| Имя подписки | читается `display.name` (с локализациями) из Hydra-документа | «HYDRA — gr33nimax» вместо «Subscription 1» |
| Дубли тегов | одноимённые серверы получают уникальные теги | `MixedSubscriptionImportTest` |

### 8.3 Поправка к §2.3 аудита

Утверждение, что одна неподдерживаемая ссылка рушит весь импорт, **неверно**:
`SubscriptionParser.parseAll` — API, который вызывается только из теста, а реальный путь
(`OutboundCatalogParser.links`) и до правок пропускал неразобранные строки. Ошибка была в
другом — пропуск был **молчаливым**. Теперь пропущенное попадает в журнал и в
`CatalogParse.skipped`: на подписке владельца это `sn: not a share link`, и в строке
источника честно стоит «Серверов: 3», а не 4.

### 8.4 Найдено самой проверкой на устройстве

Две вещи, которые видны только когда приложение запускается на телефоне с реальной
подпиской, и обе закрыты здесь же:

- **`x_padding_bytes cannot be disabled`.** Провайдер передаёт параметры xhttp в
  `extra` словарём Xray — `xPaddingBytes`, `scStreamUpServerSecs`, `xmux.maxConcurrency` —
  а ядро читает их под snake_case-именами. Скопированные как есть, они для ядра не
  существуют, диапазон набивки оказывается нулевым, и ядро отвергает **весь** документ:
  один сервер уносит с собой все остальные. Теперь имена переводятся (таблица плюс
  snake_case для остального), и это проверено тестом.
- **Первый запуск мигал у человека, который его давно прошёл.** Хранимая половина модели
  читалась асинхронно, поэтому первый кадр рисовался со значениями по умолчанию, а
  `refresh()` стоял в одной очереди с сетевыми операциями — на время загрузки подписки
  экран оставался в первом запуске. Модель теперь читается один раз синхронно до первого
  кадра, а обновление живёт на своём потоке.

### 8.5 Что осталось открытым

- **G-02 РФ-маршруты (P4d)** — 1701 строка политики у донора, отдельная карточка.
- **QR, шаринг, цепочки прокси** (`P6`, G-07) — решение владельца.
- **Обновление приложения** (`P7`) — решение владельца.
- **Восстановление после смерти процесса `:core`** — сервис `START_NOT_STICKY`, автозапуска
  нет; в 1.x это `CoreRuntimeService` с клиентом и инструментальным тестом изоляции.
- **`platform/desktop`** — по-прежнему пустой модуль.
- **Тесты экранов и инструментальные тесты** — `platform/` и `ui/` остаются без них
  (`H2-E09`); проверка на устройстве в этой сессии была ручной.
- **Настройки, которые только хранятся** — часть из списка §4 (url-test timeout/concurrency,
  `locationLookup*`, `memoryLimitEnabled`, `proxySort`, `notificationTrafficDisplayMode`)
  по-прежнему ни на что не влияют.

## 9. Смена сети: порядок операций HB1 против HB2

Проверялось по требованию владельца: «доказать, что после смены сети HB2 делает ровно тот
же порядок операций, что HB1». Ниже — код обеих сторон, а не рассуждение.

### 9.1 Что делает 1.x

`CoreRuntimeService.handleNetworkChanged` (`android/app/src/main/kotlin/io/hydrabox/client/runtime/CoreRuntimeService.kt`):

1. `reduce(...)` → решение по состоянию рантайма (строки 319-328):
   `STOPPING` → **Reject**; `STARTING`/`RECOVERING` → `ApplyUnderlying`;
   `RUNNING` → `ApplyUnderlyingAndRebind`; остальное → **Reject**.
2. `rejectsNetworkChange(...)`: отбрасывает событие, если `!replay && ng <= appliedNg` —
   дубликаты и устаревшие генерации до ядра не доходят.
3. `appliedNetworkGeneration = ng`.
4. `HydraBoxVpnService.setUnderlyingNetwork(network)` (+ повтор с `null`, если отказ).
5. `applyNetworkChangeAction`: при `ApplyUnderlyingAndRebind` —
   `setNetworkGenerationBeforeRebind(ng, { Libbox.hydraCoreSetNetworkGeneration(it) }) { publishDefaultInterface(...) }`,
   то есть **сначала генерация в ядро, потом публикация интерфейса**. При `ApplyUnderlying` —
   только публикация.
6. `publishDefaultInterface` отказывается публиковать `interfaceIndex < 0` и `tun0`.

**`resetNetwork()` в 1.x не вызывается ни разу** — `grep -rn "resetNetwork" android lib` даёт
ноль совпадений.

### 9.2 Что делал alpha 2.0

1. `DefaultNetworkMonitor.refresh()` сам публиковал `updateDefaultInterface` слушателям
   ядра — на любом состоянии рантайма, включая `STOPPED` и `STOPPING`, и **включая
   `("", -1)`**, то есть «интерфейса нет», который 1.x публиковать отказывается.
2. Генерация сети в ядро не передавалась вообще: `Libbox.hydraCoreSetNetworkGeneration`
   не вызывался, `H.CurrentNetworkGeneration()` оставался нулём.
3. `Effect.RebindNetwork` → `commandServer.resetNetwork()`, то есть
   `Router.ResetNetwork()` → `NetworkManager.ResetNetwork()`:
   `connectionManager.CloseAll()` — **закрытие всех живых соединений** — плюс принудительный
   `InterfaceUpdated()` на всех endpoints/inbounds/outbounds, независимо от того, сменился
   интерфейс или нет, плюс `dns.ResetNetwork()`.
4. Никакого `setUnderlyingNetworks`: туннель не следовал за сетью.

Следствие для vk_parasite видно в коде ядра: `QUICRelay.RebindNetwork(gen)` при `gen == 0`
пропускает собственную дедупликацию (`if gen != 0 { ... if gen <= applied return }`) и
безусловно рвёт все lane'ы (`CloseWithError(0, "network changed")`). Каждый вернувшийся
worker заново проходит `vk_join_conversation` и inner-auth. На устройстве это выглядело как
`path connect failed for worker N: inner auth rejected`, затем — когда VK перестал выдавать
credentials — `code=incomplete_credentials`, и в итоге `no active QUIC paths` на каждый
запрос. Туннель при этом «Подключено».

### 9.3 Что сделано

`RuntimeReducer.network(...)` теперь повторяет таблицу 1.x буквально: `RUNNING` →
`Effect.RebindNetwork`, `STARTING`/`RECOVERING` → `Effect.PublishNetwork`,
`STOPPING`/`STOPPED`/`FAILED` → отказ без запоминания генерации; генерация не новее текущей
отбрасывается. Таблица закреплена тестом `NetworkChangeTest` (4 случая).

`HydraVpnService.rebind(...)` выполняет ровно порядок 1.x:
`setUnderlyingNetworks(network)` → `Libbox.hydraCoreSetNetworkGeneration(ng)` →
`monitor.publishCurrent()`. `resetNetwork()` удалён. Публикация интерфейса ушла из
`DefaultNetworkMonitor.refresh()` — монитор только считает генерацию и сообщает рантайму,
решение принимает рантайм. `updateDefaultInterface` с индексом `<= 0` и с именем `tun*`
больше не отправляется. Генерация передаётся ядру и при старте, чтобы первый lane не
жил в нулевой генерации.

Остановка перестала быть мгновенной: `stop()` уходит с главного потока и вызывает
`stopSelf()` только после того, как рантайм подтвердил освобождение. До этого дедлайн
закрытия (5 с) истекал на каждом отключении — процесс `:core` уходил, не дав ядру закрыть
сессию, и следующее подключение упиралось в лимит сессий на пользователе.

### 9.4 Проверено на устройстве

Реальный переход LTE → Wi-Fi на собранном билде: в журнале одна строка
`[network] default network is now wlan0, generation 2` и одна
`[runtime] rebinding the core to network generation 2`; туннель остался «Подключено»,
счётчик времени не сбросился, повторных тердаунов нет.

### 9.5 Что относится к ядру, а не к приложению

- **Измерение задержки `call`-outbound'а.** `Outbound.DialContext` ждёт `awaitBridge`, поэтому
  первый замер url-test включает подъём моста и join в VK — отсюда 14 458 мс у `call-vk-out`
  при живом транспорте. Правильная величина — время доставки одного пакета по уже
  установленному пути (`QUICRelay` уже отдаёт `ActivePaths()`); это изменение в HydraCore,
  в приложении его сделать нечем.
- **Различимость отказов inner-auth.** Сервер отвечает одним и тем же `ack(false)` и на
  неверные креденшелы, и на лимит сессий пользователя (`getOrCreateSession`), поэтому клиент
  печатает «authentication rejected» в обоих случаях. Код причины в ack сделал бы разницу
  видимой без доступа к серверу.
- **Суперсидинг сессии.** Клиент отправляет `WorkerEpoch: 1, LaneGeneration: 1` константами
  (`transport/call/vk-parasite/client.go:213`), поэтому новая сессия не может вытеснить
  зависшую — приходится ждать её истечения на сервере.
