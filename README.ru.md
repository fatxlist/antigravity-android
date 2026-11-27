<div align="center">

  <p>
    <a href="README.md">English</a> &nbsp;&bull;&nbsp; <b>Русский</b>
  </p>

  <br/>

  <img src="docs/assets/logo.png" alt="Antigravity" width="88" height="88" style="border-radius: 20px;" />

  # Antigravity для Android

  **Автономная среда разработки на базе ИИ, работающая нативно на Android и ARM64.**

  [![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://android.com)
  [![Arch](https://img.shields.io/badge/ARM64-v8.0--A%2B-blue?style=flat-square)](https://arm.com)
  [![Release](https://img.shields.io/badge/Версия-2.6.1-blueviolet?style=flat-square)](https://github.com/fatxlist/antigravity-android/releases)
  [![Core](https://img.shields.io/badge/Ядро-v2.35.2-0A7EA4?style=flat-square)](modules/core)
  [![CI](https://github.com/fatxlist/antigravity-android/actions/workflows/build-and-release.yml/badge.svg)](https://github.com/fatxlist/antigravity-android/actions/workflows/build-and-release.yml)
  [![License](https://img.shields.io/badge/Лицензия-Apache_2.0-orange?style=flat-square)](LICENSE)

  <br/>

  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/hero_dark.png">
    <source media="(prefers-color-scheme: light)" srcset="docs/assets/hero_light.png">
    <img alt="Интерфейс Antigravity" src="docs/assets/hero_dark.png" width="100%" style="border-radius: 12px; border: 1px solid rgba(128,128,128,0.2);" />
  </picture>

</div>

---

### Обзор

**Antigravity для Android** переносит автономное агентное программирование прямо на мобильное устройство.

Никаких PRoot, chroot или накладных расходов виртуализации. Antigravity исполняется **нативно в пространстве пользователя Android (Bionic)** с glibc-загрузчиком для встроенного `language_server`, раскрывая полную скорость накопителей UFS и физических ядер CPU. Агент на базе моделей Gemini анализирует репозитории, правит код по всему дереву проекта, использует инструменты терминала/браузера и запускает тесты прямо на устройстве.

---

### Основные возможности

* **Ядро и веб-слой v2.35.2:** Обновлённый `language_server` и веб-бандл с loopback sidecar-прокси, восстановлением потоков SSE (`resume-stream-recovery`), поддержкой BiDi/RTL-разметки и улучшениями апстрима v2.35.x.
* **Нативные MCP-серверы:** Автоматическая настройка `agy-mcp-android` (устройство, UI, уведомления, шлюз) и `agy-mcp-browser` (`chrome_devtools`) в `~/.gemini/config/mcp_config.json` со встроенными скиллами агента.
* **Мобильный UX:** Хранилище аккаунтов и переключатель профилей в Settings, кликабельные локальные пути и карточка установки APK, расширенная поддержка вложений в чате, значок для видео-вложений, баннер переподключения при обрыве сети, голосовой ввод и фоновый голосовой сервис, интерактивные уведомления подтверждения действий и субагентов (`Approve` / `Deny` / ответ прямо в шторке / `Cancel`), панель доп. клавиш, вставка изображений из буфера, приём файлов через Share Sheet, локальный предпросмотр HTML, переключатель Desktop Mode, пассивная проверка обновлений на GitHub и защита от случайного выхода двойным нажатием «Назад».
* **Шлюз и микро-демон:** Многопоточный `SystemGatewayServer` (`doctor`/`repair`, асинхронные задачи `exec_*`, уведомления), ротация отчётов о сбоях (`CrashRecorder`, не более 10 файлов) и легковесный `agy-daemon` (`fcntl.flock`, диспетчеризация задач через ConnectRPC, триггеры по расписанию/файлам/GitHub и контроль `resolv.conf`).
* **Поддержка ARMv8.0-A:** Прозрачная эмуляция атомиков и SIMD (`liblse_compat.so` с флагом `SA_ONSTACK`), совместимость системных вызовов seccomp (`faccessat`/`fchmodat`) и перенацеливание PTY-оболочки.
* **Встроенный `pkg` и опциональный Shizuku:** Ускоренный менеджер пакетов Termux (`pkg_core.py`, параллельная загрузка `curl -Z --http2`, `--keep-cache`), а также необязательный ADB-доступ (`uid=2000`) через Shizuku без root.

---

### Безопасность и укрепление архитектуры

* **Аутентификация локального шлюза:** Для каждой установки генерируется 256-битный токен в `files/logs/gateway.token` (права `0600`), проверяемый за константное время через заголовки `X-Agy-Gateway-Token` или `Authorization: Bearer` во всех клиентах (`/gateway/*`, `agy-mcp-android`, `agy-mcp-browser`, `agy-notify`, Python CLI/bridge).
* **Certificate Pinning на Loopback:** Callback-запросы апрувов и ConnectRPC в `ApprovalActionReceiver` привязаны к встроенному сертификату `res/raw/agy_core_cert` и строго проверяют loopback-хост (`127.0.0.1` / `localhost`) через `CoreReadyProbe.configurePinnedLoopbackTls`.
* **Строгая изоляция `AppFileProvider`:** Отказ при отсутствии `Context`, исключён широкий доступ ко всему `appDataDir`, а пути `.gemini`, `logs/gateway.token`, `logs/core.state.json`, `logs/approvals.json`, `shared_prefs` и `databases` заблокированы явным deny-листом.
* **Изоляция установок и лимиты процессов:** `OnboardingSeeder` подменяет шаблонный UUID на уникальный `UUID.randomUUID()` при первичной инициализации; `RishExecutor` ограничивает выполнение команд таймаутом 60 с (`destroyForcibly()`) и буфером 256 КБ; `agy-upgrade` защищён от shell/Python-инъекций (`<< 'PYEOF'`, переменные окружения, `printf %q`).
* **Fail-closed подпись релизов и отказ от обхода ограничений:** Модули и сборки для обхода проверок учётной записи/региона удалены. Релизная сборка `build.sh` завершается ошибкой, если не заданы учётные данные подписи `AGY_RELEASE_*` (для локальной отладки используйте `--dev` или `--allow-debug-sign`).

---

### Быстрый старт

1. **Скачайте `antigravity.apk`** со страницы [Releases](https://github.com/fatxlist/antigravity-android/releases) (или из артефактов GitHub Actions CI).
2. **Выдайте разрешения:**
   * Доступ **«Ко всем файлам»** (`MANAGE_EXTERNAL_STORAGE`) для работы с проектами и загрузками на Android 11+.
   * Разрешение на **уведомления** на Android 13+ (`POST_NOTIFICATIONS`) для интерактивных апрувов и фоновых задач.
   * *(Опционально)* Подключите **Shizuku** для привилегий ADB (`uid=2000`) без root.
3. **Установите рабочее окружение** в терминале или через агента:
   ```bash
   pkg update && pkg install git python nodejs
   ```

---

### Сборка, подпись и тестирование

#### Сборка APK

```bash
# Dev-сборка для отладки (com.agy.dev, порт 45158, debuggable):
bash build.sh --dev

# Локальная релизная сборка с debug.keystore (только для тестирования на своём устройстве):
bash build.sh --allow-debug-sign

# Продакшен-сборка (dist/antigravity.apk; без переменных окружения прерывается ошибкой):
export AGY_RELEASE_KEYSTORE=/path/to/release.keystore
export AGY_RELEASE_KEY_ALIAS=release_alias
export AGY_RELEASE_KEY_PASS=...
export AGY_RELEASE_STORE_PASS=...
bash build.sh
```

> [!IMPORTANT]
> Для публикации теговых релизов (`v*`) в GitHub Actions необходимо задать секреты репозитория: `AGY_RELEASE_KEYSTORE_B64`, `AGY_RELEASE_KEY_ALIAS`, `AGY_RELEASE_KEY_PASS` и `AGY_RELEASE_STORE_PASS`. Не распространяйте APK, собранные с `--allow-debug-sign`, как продакшен-релизы. Описание релиза берётся из `RELEASE_NOTES.md`, а совпадение версий с `config/app.env` проверяет `modules/builder/check_versions.py`.

#### Автоматические тесты

```bash
# Полный прогон всех CI-проверок и E2E-теста ядра:
bash test_all_modules.sh

# Отдельные наборы проверок (также выполняются в GitHub Actions CI):
python3 modules/builder/check_versions.py
bash modules/core/tests/run_all.sh
bash modules/patches/tests/run_all.sh
bash modules/io/tests/run_all.sh
bash modules/env/tests/run_all.sh
bash modules/builder/tests/run_all.sh
python3 -B -m unittest discover -s env/tests -v
bash env/build.sh assets && bash env/tests/invariant
bash web/build.sh && bash web/tests/selftest.sh && node web/tests/test_web_patches.mjs
bash app/src/test/run_java_tests.sh
```

---

### Структура проекта

```text
antigravity-android/
├── .github/workflows/  # CI-тесты, сборка/верификация APK и теговые релизы
├── config/             # Константы приложения (app.env, tools.env) и конфиг подписи
├── modules/            # Модульная архитектура ядра
│   ├── core/           # Исходный бинарник ядра, glibc-линковщик, шимы, сиды, SHA-256
│   ├── patches/        # Движок совместимости ядра (seccomp, SIGILL, resolv, failfast, PTY)
│   ├── io/             # Шина ввода-вывода (I/O Bus): event broker, FIFO, сокеты
│   ├── env/            # Резолвер путей, защищённый bridge-клиент, диагностика, CLI
│   ├── builder/        # Сборщик APK без Gradle (javac, D8, aapt, align.py, fail-closed подпись)
│   └── runner/         # Подготовка рантайма, health probe, E2E-тест
├── app/                # Android-хост
│   ├── src/main/       # Нативный сплэш, WebView, шлюз, уведомления, мосты MCP/Shizuku
│   └── src/test/       # Host-JVM unit-тесты (UnitTests.java, run_java_tests.sh)
├── web/                # Веб-интерфейс, восстановление SSE, синхронизация тем, BiDi, Desktop Mode
├── env/                # Bionic userspace, оверлей Termux, менеджер pkg, agy-daemon, liblse_compat
├── docs/assets/        # Скриншоты и графические ресурсы
├── build.sh            # Корневой скрипт сборки APK
└── test_all_modules.sh # Полный раннер модульных тестов
```

> [!TIP]
> Каждый модуль в `modules/`, `app/`, `web/` и `env/` содержит собственный файл `README.md` с подробной технической документацией.

---

<div align="center">
  <sub>Лицензия <a href="LICENSE">Apache 2.0</a> &bull; Форк репозитория <a href="https://github.com/werdio325-png/antigravity-android">werdio325-png/antigravity-android</a></sub>
</div>
