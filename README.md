# HydraBox 2

HydraBox — Android-клиент для самостоятельного VPN-стека Hydra. Он принимает
подписки и отдельные ссылки, собирает конфигурацию sing-box и управляет
рантаймом [HydraCore](https://github.com/gr33nimax/hydracore), который создаёт
системный VPN-туннель.

Проект находится в альфа-стадии. Текущая Android-сборка: `2.0.0-alpha1`,
`minSdk 26`, `targetSdk 36`.

## Возможности

- импорт подписок sing-box, Xray, Clash, SIP008 и Hydra;
- импорт ссылок VLESS, VMess, Trojan, Shadowsocks/SSR, WireGuard/AmneziaWG,
  SOCKS, HTTP, Hysteria/Hysteria2, TUIC, AnyTLS и Naive;
- выбор сервера вручную или автоматически по результатам URL-test;
- режим системного VPN или локального mixed-прокси;
- отдельные DNS для bootstrap, прямого доступа и туннеля;
- FakeIP, строгая маршрутизация, исключения для локальной сети и приложений;
- локальные наборы правил, в том числе блокировка рекламы;
- журнал, диагностика, счётчики трафика и экспорт диагностического отчёта.

## Как устроено приложение

В Android используются два процесса:

- основной процесс показывает Compose UI, хранит настройки и подписки;
- процесс `:core` владеет `VpnService` и нативным рантаймом libbox.

Процессы общаются через Binder. UI отправляет команды, а `:core` публикует
снимки состояния и события. Поэтому экран не управляет libbox напрямую, а
перезапуск UI не обязан останавливать активный туннель.

Основной поток данных:

```text
подписки и настройки
        ↓
TunnelConfigGenerator
        ↓
конфигурация sing-box
        ↓
RuntimeReducer → Binder → VpnService → HydraCore
        ↑
ScreenState ← события и снимки рантайма
```

`RuntimeReducer` — стейтмашина запуска, остановки, восстановления и смены
сети. `ScreenState` — готовая проекция для интерфейса: выбранный сервер,
задержки, трафик, квота, внешний адрес и журнал.

## Структура проекта

| Каталог | Назначение |
|---|---|
| `core/subscription` | загрузка и разбор подписок и share-ссылок |
| `core/config` | сборка полной конфигурации sing-box |
| `core/runtime` | состояние и эффекты жизненного цикла туннеля |
| `core/contract` | команды, события и снимки между UI и `:core` |
| `core/storage`, `core/settings` | локальное хранение и настройки |
| `core/projection` | подготовка состояния для экранов |
| `core/ruleset`, `core/diagnostics` | наборы правил, журнал и диагностика |
| `ui/app`, `ui/design` | экраны, навигация, тема и компоненты |
| `platform/android` | Android application, VpnService, Binder и libbox |
| `hydracore` | git submodule с исходниками ядра |

Общий код KMP не обращается к Android API или libbox. Платформенные детали
остаются в `platform/android`; это проверяет `verifyCommonMainBoundaries`.

## Сборка

Требования: JDK 17, Android SDK 36 и Android build tools. Репозиторий включает
Gradle Wrapper.

```bash
./gradlew :platform:android:assembleDebug
```

Полная локальная проверка, совпадающая с CI:

```bash
python3 .github/scripts/hydrate_libbox.py
./gradlew ciCheck
```

Скрипт загружает проверенный AAR HydraCore. Перед сборкой задача
`verifyLibboxProvenance` сверяет версию ядра, исходный коммит и SHA-256.
