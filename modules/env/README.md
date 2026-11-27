# Antigravity v2 - Environment, Diagnostics & System Gateway Subsystem (`modules/env`)

> **Модуль `env`** — фундаментальная подсистема управления системным окружением, динамического разрешения путей Android/Linux, аппаратной и сетевой диагностики (preflight/port checker), структурированного телеметрического логирования, аудита выполнения команд и межпроцессного взаимодействия с Android System Gateway для Google Antigravity v2.

---

## 1. Архитектурный обзор

Модуль `modules/env` устраняет зависимость компонентов рантайма от фиксированных путей файловой системы Android и обеспечивает стандартизированную среду выполнения:

```
 +-----------------------------------------------------------------------------------+
 |                             SPECIFICATION LAYER                                   |
 |          [spec/paths.json]                     [spec/env_vars.json]               |
 |    (Базовые шаблоны путей Android)        (Static vars, PATH, LD_LIBRARY_PATH)    |
 +--------------------------+---------------------------------+----------------------+
                            |                                 |
                            v                                 v
 +-----------------------------------------------------------------------------------+
 |                              CONTEXT & RESOLVER                                   |
 |         [path_resolver.py]                          [env_builder.py]              |
 |   Динамический расчет package_id,         Сборка процесса: HOME, PREFIX, PATH,    |
 |   app_root, runtime, prefix, cache        TMPDIR, GODEBUG, GIT_CONFIG_NOSYSTEM    |
 +--------------------------+---------------------------------+----------------------+
                            |                                 |
       +--------------------+----------------+----------------+----------------+
       |                                     |                                 |
       v                                     v                                 v
+--------------------------+  +--------------------------+  +--------------------------+
|  DIAGNOSTICS & PREFLIGHT |  |   LOGGING & CRASH DUMP   |  |     COMMAND RUNNER       |
|      [preflight.py]      |  |         [log.py]         |  |       [runner.py]        |
|  - Архитектура (ARM64)   |  |  - ANSI Logger / JSONL   |  |  - Исполнение $ cmd      |
|  - Свободное место (>250)|  |   [flight_recorder.py]   |  |  - Метрики: duration_ms  |
|  - Права записи W_OK     |  |  - Аварийный дамп памяти |  |  - Аудит commands.jsonl  |
|    [port_checker.py]     |  |    и состояния диска     |  |  - Fallback & list       |
|  - Тест TCP Loopback     |  +--------------------------+  +--------------------------+
+--------------------------+                 |                                 |
                                             v                                 v
 +-----------------------------------------------------------------------------------+
 |                             IPC & SYSTEM GATEWAY                                  |
 |           [bridge/client.py]                           [bridge/commands.py]       |
 |    Авто-определение порта gateway.port           Высокоуровневые типизированные   |
 |    Транспорт: POST http://127.0.0.1:port/gateway  команды: notify, open, wakelock  |
 +-----------------------------------------------------------------------------------+
                                             |
                                             v
 +-----------------------------------------------------------------------------------+
 |                                CLI UTILITIES                                      |
 |          [cli/agy_system.py]                           [cli/agy_logs.py]          |
 |    Управление Android-уведомлениями,             Инспектор логов ядра core.log,   |
 |    Wakelock, URL, Clipboard, Doctor              дампов сбоев и commands.jsonl    |
 +-----------------------------------------------------------------------------------+
```

---

## 2. Структура каталогов модуля

```
modules/env/
├── spec/
│   ├── paths.json               # JSON-спецификация файловой структуры приложения
│   └── env_vars.json            # JSON-спецификация базовых переменных окружения
├── context/
│   ├── path_resolver.py         # Резолвер путей без хардкода, детектор package_id
│   └── env_builder.py           # Конструктор изолированного окружения процессов
├── diagnostics/
│   ├── preflight.py             # Префлайт-чекер архитектуры (ARM64), прав и памяти
│   └── port_checker.py          # Проверка доступности локальных TCP-портов
├── logging/
│   ├── log.py                   # Легковесный цветной ANSI + JSONL логгер
│   └── flight_recorder.py       # Генератор аварийных JSON-снимков (crash dump)
├── bridge/
│   ├── client.py                # IPC-клиент к Android Gateway с авто-fallback
│   └── commands.py              # Типизированные системные вызовы к Android OS
├── cmd/
│   └── runner.py                # Телеметрический движок выполнения команд
├── cli/
│   ├── agy_system.py            # CLI-контроллер Android Gateway (notify, open, wakelock, info, status, doctor, clipboard)
│   └── agy_logs.py              # CLI-инспектор логов, истории команд и сбоев
└── tests/
    ├── run_all.sh               # Главный скрипт синтаксической и модульной проверки
    ├── test_context.py          # Тесты резолвера путей и сборки env
    ├── test_diagnostics.py      # Тесты префлайта и чекера портов
    ├── test_logging.py          # Тесты логгера и flight recorder
    ├── test_bridge.py           # Тесты клиента шлюза и типизации команд
    └── test_cli.py              # Тесты CLI-утилит agy_system и agy_logs
```

---

## 3. Спецификация окружения и путей (`spec/`)

### 3.1. Карта файловой системы (`spec/paths.json`)
Шаблоны путей используют плейсхолдер `{package_name}` (или `{app_id}`), динамически подменяемый в рантайме:
```json
{
  "app_root": "/data/user/0/{package_name}/files",
  "prefix": "/data/user/0/{package_name}/files/usr",
  "runtime": "/data/user/0/{package_name}/files/runtime",
  "cache": "/data/user/0/{package_name}/cache",
  "gemini_home": "/data/user/0/{package_name}/files/.gemini",
  "shared_storage": "/storage/emulated/0",
  "default_projects": "/storage/emulated/0/Documents"
}
```

### 3.2. Спецификация переменных окружения (`spec/env_vars.json`)
Определяет базовые переменные, порядок поиска в `PATH` и библиотеки:
```json
{
  "static": {
    "LANG": "C.UTF-8",
    "LC_ALL": "C.UTF-8",
    "GODEBUG": "netdns=go",
    "ANTIGRAVITY_VSCODE_HOST": "1"
  },
  "relative_to_app": {
    "HOME": ".",
    "TMPDIR": "../cache"
  },
  "path_order": [
    "{runtime}/bin",
    "{prefix}/bin",
    "/system/bin"
  ],
  "ld_library_paths": [
    "{runtime}/lib",
    "{prefix}/lib"
  ]
}
```

---

## 4. Контекст и динамический резолвер путей (`context/`)

### 4.1. `path_resolver.py`
Отвечает за устранение жестких привязок к имени пакета (`com.agy` vs `com.agy.dev` vs кастомные сборки):
- `get_application_id() -> str`: Определение идентификатора пакета по приоритетам:
  1. Переменная окружения `AGY_APPLICATION_ID`
  2. Переменная окружения `APPLICATION_ID`
  3. Флаг `DEV_MODE == "1"` (возвращает `com.agy.dev`)
  4. По умолчанию: `com.agy`
- `load_paths(custom_spec=None, app_id=None) -> Dict[str, str]`: Загрузка и интерполяция путей из `paths.json` с гарантированным получением абсолютных путей через `os.path.abspath(os.path.expanduser(...))`.
- `resolve_subpath(key: str, rel_path: str, custom_spec=None, app_id=None) -> str`: Безопасное присоединение подпутей. Для предотвращения сброса корневого каталога при передаче ведущего слэша (`/bin/tool`) вызывается `rel_path.lstrip("/")`.

### 4.2. `env_builder.py`
- `build_process_env(custom_spec_dir=None, extra_env=None, app_id=None) -> Dict[str, str]`:
  Формирует чистый и детерминированный словарь окружения для порождаемых процессов:
  - `HOME`: Привязан к `paths["app_root"]`
  - `PREFIX`: Linux-префикс `paths["prefix"]` (`/data/user/0/{package}/files/usr`)
  - `TMPDIR`: Каталог кэша `paths["cache"]`
  - `GIT_CONFIG_NOSYSTEM`: Значение `"1"` (блокирует ошибки отсутствия системного etc Termux)
  - `AGY_APPLICATION_ID`: Идентификатор активного пакета
  - `PATH`: Собран из `{runtime}/bin:{prefix}/bin:/system/bin`
  - `LD_LIBRARY_PATH`: Каталоги `{runtime}/lib` и `{prefix}/lib` (если директории существуют)
  - `extra_env`: Дополнительные пользовательские переменные

---

## 5. Системная диагностика (`diagnostics/`)

### 5.1. Префлайт-чекер (`diagnostics/preflight.py`)
Проверяет готовность хоста Android перед развертыванием или запуском ядра:
```python
def run_preflight(app_root: Optional[str] = None, min_mb_free: int = 250) -> Dict[str, Any]
```
- **Архитектура процессора**: Проверяет `platform.machine()`. Допускаются только `aarch64` / `arm64`.
- **Свободное дисковое пространство**: Проверяет доступную память на разделе `app_root` через `shutil.disk_usage()`. Порог по умолчанию — не менее 250 МБ.
- **Права на запись**: Проверяет `os.access(app_root, os.W_OK)`.
- **Динамический fallback**: Если `app_root` не задан, резолвит его через `path_resolver`, а затем через `$HOME`.

### 5.2. Валидатор портов (`diagnostics/port_checker.py`)
```python
def check_port_available(port: int, host: str = "127.0.0.1") -> Dict[str, Any]
```
- Проверяет возможность привязки TCP-сокета (`socket.bind`) к loopback-интерфейсу с таймаутом 0.5с.
- Возвращает `{"port": port, "available": True/False, "error": None/str}`.

---

## 6. Логирование и постмортем аварий (`logging/`)

### 6.1. Структурированный логгер (`logging/log.py`)
```python
def log(level: str, tag: str, message: str, meta: Dict[str, Any] = None, jsonl_file: str = None) -> None
```
- **ANSI Цвета**: `DEBUG` (серый), `INFO` (синий), `OK` (зеленый), `WARN` (желтый), `ERROR` (красный).
- **Поддержка `NO_COLOR`**: При установке переменной окружения `NO_COLOR` или `TERM=dumb` экранирующие символы автоматически отключаются.
- **Разделение потоков**: Логи уровня `ERROR` выводятся в `sys.stderr`, остальные уровни — в `sys.stdout`.
- **JSONL Streaming**: При указании `jsonl_file` запись формируется в атомарном формате JSON Lines с меткой времени в миллисекундах (`ts`). Операции защищены блоком `try-except`.

### 6.2. Аварийный регистратор (`logging/flight_recorder.py`)
```python
def record_crash_snapshot(error_msg: str, dump_path: str, extra_context: Dict[str, Any] = None) -> str
```
- Создает детальный постмортем снимок инцидента:
  - Точное время (epoch ms и ISO 8601 UTC)
  - Сообщение об ошибке и контекст (`extra_context`)
  - Данные платформы, архитектура, версия Python, PID
  - Состояние файловой системы (`shutil.disk_usage`) целевого каталога
- Сохраняет форматированный JSON по пути `dump_path`.

---

## 7. Системный шлюз и IPC-мост (`bridge/`)

Обеспечивает межпроцессную связь между Python-скриптами, фоновыми задачами и системными службами Android OS через `CoreServerService` (Android System Gateway).

### 7.1. IPC-клиент (`bridge/client.py`)
- `resolve_gateway_url() -> str`:
  1. Проверяет переменную окружения `AGY_GATEWAY_URL`.
  2. Ищет файл динамического порта `gateway.port` в `$HOME/logs/gateway.port`, `app_root/logs/gateway.port`, стандартных директориях `com.agy.dev` / `com.agy` и профилях `/data/user/*/com.agy*/files/logs/gateway.port`.
  3. При отсутствии файла выбирает порт по умолчанию: `45161` для `DEV_MODE=1` / `AGY_APPLICATION_ID=com.agy.dev` / `$HOME` с `com.agy.dev`, либо `45160` для production.
- `resolve_gateway_token() -> Optional[str]`: читает токен аутентификации из `AGY_GATEWAY_TOKEN`, `AGY_GATEWAY_TOKEN_FILE` или файлов `gateway.token` и передает его в заголовке `X-Agy-Gateway-Token` (`TOKEN_HEADER`).
- `send_command(command: str, payload: dict = None, gateway_url: str = None) -> dict`:
  - Отправляет HTTP POST запрос с JSON-конвертом `{command, payload}` на эндпоинт `/gateway`.
  - Автоматический failover: при недоступности основного порта (если `gateway_url` не задан явно) выполняет попытку переподключения на альтернативный порт (`45160` <-> `45161`).
  - Возвращает `{ok: False, fallback: True, error: ..., packet: ...}` при невозможности связаться со шлюзом без генерации необработанных исключений.
- Дополнительные методы клиента: `exec_start()`, `exec_status()`, `exec_cancel()`, `exec_list()`, `exec_command()` (синхронный и асинхронный режим с поллингом/таймаутом), `get_status()`, `doctor()`, `repair()`.

### 7.2. Типизированный фасад команд (`bridge/commands.py`)
- `notify(title, message, progress=None, tag="agy_status", channel="agy_tasks", ongoing=False) -> dict`: Отправка Android-уведомления в статус-бар с поддержкой прогресс-бара (clamped `0..100`) и закрепления.
- `cancel_notify(tag="agy_status", notification_id=1001) -> dict`: Отзыв активного уведомления.
- `open_url(url: str) -> dict`: Открытие веб-ссылки или диплинка через Android Intent (`ACTION_VIEW`).
- `wakelock(acquire=True, tag="agy_task") -> dict`: Удержание/освобождение процессорного `PARTIAL_WAKE_LOCK`.
- `clipboard_set(text: str) -> dict`: Копирование текста в системный буфер обмена Android.
- `clipboard_get() -> dict`: Чтение текста из системного буфера обмена Android.
- `system_info() -> dict`: Запрос состояния батареи, сети, свободной памяти и диска.
- `status() -> dict`, `doctor() -> dict`, `repair() -> dict`: Проверка состояния шлюза/ядра и выполнение безопасных исправлений.

---

## 8. Движок аудита и выполнения команд (`cmd/runner.py`)

Утилита и библиотека для прозрачного исполнения команд оболочки с расширенным профилированием и аудитом:

```bash
# Исполнение команды с телеметрией:
python3 modules/env/cmd/runner.py run "echo 'Hello Antigravity'"

# Прямой вызов:
python3 modules/env/cmd/runner.py pkg list

# Просмотр последних выполненных команд:
python3 modules/env/cmd/runner.py list 20
```

- Вычисляет точную продолжительность выполнения в миллисекундах (`duration_ms`).
- Записывает каждую команду в append-only аудит-лог `commands.jsonl` в директории логов приложения (`$HOME/logs/commands.jsonl`).
- Выводит форматированное дерево с кодом возврата, длительностью и первыми строками вывода.

---

## 9. CLI интерфейсы (`cli/`)

### 9.1. Системный контроллер (`cli/agy_system.py`)

Единый терминальный интерфейс для управления Android-функциями:

```bash
# 1. Системные уведомления:
python3 modules/env/cli/agy_system.py notify -t "Сборка завершена" -m "APK успешно скомпилирован" --tag "build"
python3 modules/env/cli/agy_system.py notify -t "Загрузка" --progress 75 --ongoing
python3 modules/env/cli/agy_system.py notify cancel --tag "build"

# 2. Открытие URL в системном браузере / Custom Tabs:
python3 modules/env/cli/agy_system.py open "https://github.com"

# 3. Управление фоновым режимом (Wakelock):
python3 modules/env/cli/agy_system.py wakelock on --tag "long_job"
python3 modules/env/cli/agy_system.py wakelock off --tag "long_job"

# 4. Диагностика хоста Android:
python3 modules/env/cli/agy_system.py info
python3 modules/env/cli/agy_system.py --json info
python3 modules/env/cli/agy_system.py status
python3 modules/env/cli/agy_system.py doctor [--repair]

# 5. Буфер обмена:
echo "секретный токен" | python3 modules/env/cli/agy_system.py clipboard set
python3 modules/env/cli/agy_system.py clipboard get
```

### 9.2. Инспектор телеметрии и логов (`cli/agy_logs.py`)

Инструмент анализа состояния рантайма ядра Antigravity:

```bash
# 1. Просмотр последних строк общего лога:
python3 modules/env/cli/agy_logs.py -n 50

# 2. Фильтрация по подсистеме и уровню важности:
python3 modules/env/cli/agy_logs.py --channel core --level ERROR

# 3. Аудит выполненных команд:
python3 modules/env/cli/agy_logs.py --commands -n 25

# 4. Детальный разбор конкретной команды (STDOUT, STDERR, время):
python3 modules/env/cli/agy_logs.py --cmd-id "cmd_1792097231_1ec8"

# 5. Проверка последнего отчета об аварии (Crash Report):
python3 modules/env/cli/agy_logs.py --crash
python3 modules/env/cli/agy_logs.py --crash --json
```

---

## 10. Модульное тестирование (`tests/`)

Тестовый набор покрывает 100% компонентов подсистемы `env`.

### Запуск тестов
```bash
bash modules/env/tests/run_all.sh
```

### Состав тестового набора
| Тестовый файл | Компонент | Проверяемый сценарий |
| :--- | :--- | :--- |
| `test_context.py` | `path_resolver`, `env_builder` | 9 тестов: резолв путей, subpath с ведущим слэшем, переменные `HOME`, `PREFIX`, `TMPDIR`, `GIT_CONFIG_NOSYSTEM`, динамический `AGY_APPLICATION_ID`, `DEV_MODE` и переопределение `app_id`. |
| `test_diagnostics.py` | `preflight`, `port_checker` | 3 теста: проверка текущей ФС, дефолтный префлайт без аргументов, валидация свободных TCP loopback портов. |
| `test_logging.py` | `log`, `flight_recorder` | 2 теста: запись структурированного JSONL потока, генерация снимка аварии со сбором метаданных платформы и диска. |
| `test_bridge.py` | `client`, `commands` | 5 тестов: авто-fallback шлюза в оффлайн-режиме, форматирование URL, wakelock, интроспекция `typing.get_type_hints()` и валидация всех методов команд. |
| `test_cli.py` | `agy_system`, `agy_logs` | 6 тестов: вызов справки CLI, парсинг системной информации в JSON, проверка отчетов о сбоях, чтение буфера в JSON, аудит команд через `--commands`. |

---

## 11. Аудит надёжности, выявленные баги и исправления

В ходе глубокого аудита исходного кода подсистемы `modules/env` были обнаружены и устранены критические архитектурные дефекты, ошибки путей в Android и проблемы типизации:

### 11.1. Критический хардкод путей в `preflight.py`
- **Проблема**: В сигнатуре функции `run_preflight` аргумент по умолчанию был жестко зафиксирован: `app_root: str = "/data/user/0/com.agy/files"`.
- **Следствие**: В дебаг-сборках (`com.agy.dev`) или любых пользовательских пакетах вызов `run_preflight()` без аргументов проверял несуществующий каталог production-пакета `com.agy`. В результате `os.path.exists()` возвращал `False`, `free_mb = 0`, и префлайт-чекер безусловно рапортовал об ошибке `ok: False`, блокируя запуск ядра.
- **Исправление**: Параметр изменен на `app_root: Optional[str] = None`. Добавлено каскадное динамическое определение каталога через `path_resolver.load_paths()["app_root"]` -> `$HOME` -> fallback.

### 11.2. Ошибка типизации `NameError: Optional` в `bridge/commands.py`
- **Проблема**: В сигнатурах функций использовались аннотации типов `Optional[int]`, `Dict[str, Any]`, однако импорт из модуля `typing` отсутствовал.
- **Следствие**: При интроспекции типов (например, через `typing.get_type_hints(commands.notify)` или библиотеки валидации Pydantic/FastAPI) в Python 3.10+ возбуждалось исключение `NameError: name 'Optional' is not defined`.
- **Исправление**: Добавлен явный импорт `from typing import Dict, Any, Optional`, а также реализованы недостающие методы `clipboard_get()`, `cancel_notify()` и `system_info()`.

### 11.3. Подавление логирования и хардкод в `cmd/runner.py`
- **Проблема**: Путь `LOG_DIR` был жестко задан как `/data/user/0/com.agy/files/logs`.
- **Следствие**: При работе в окружении `com.agy.dev` создание директории или запись в `commands.jsonl` завершалась отказом в доступе (`PermissionDenied`), который молча глушился блоком `except Exception: pass`. Все команды, запущенные через `runner.py`, теряли историю аудита.
- **Исправление**: Реализована функция `_resolve_log_dir()`, приоритетно использующая `$HOME/logs` и `path_resolver`.

### 11.4. Недоступность логов и дампов сбоев в `cli/agy_logs.py`
- **Проблема**: Пути к файлам `commands.jsonl`, `logs/core.log` и каталогу `logs/crashes` были жестко привязаны к `com.agy`. При этом в `read_file_fallback` проверялся путь `~/.gemini/logs/core.log`, тогда как реальный файл создается в `$HOME/logs/core.log`.
- **Следствие**: Если шлюз Android Gateway был оффлайн, команда `agy_logs.py` не могла обнаружить ни один лог-файл и выводила пустоту.
- **Исправление**: Внедрена функция `_find_commands_log()`, пути объединены с динамическим резолвером, а в список кандидатов `core.log` добавлен `$HOME/logs/core.log`.

### 11.5. Дублирование логики поиска порта Gateway
- **Проблема**: Функция `resolve_gateway_url()` в `bridge/client.py` и `get_gateway_base()` в `cli/agy_logs.py` содержали идентичный дублированный код, причем обе функции проверяли только статические пути к `gateway.port`.
- **Исправление**: `agy_logs.py` теперь импортирует централизованный метод из `bridge.client`, а сам резолвер порта проверяет `$HOME/logs/gateway.port` и флаг окружения `AGY_APPLICATION_ID == "com.agy.dev"`.

### 11.6. Отсутствие `$PREFIX` и `$GIT_CONFIG_NOSYSTEM` в `env_builder.py`
- **Проблема**: Переменная `PREFIX` (`/data/user/0/{package}/files/usr`) является ключевой переменной окружения в Android userland (Termux / AGY). Несмотря на наличие ключа `"prefix"` в `paths.json`, `env_builder.py` не устанавливал `PREFIX`. Также отсутствовал флаг `GIT_CONFIG_NOSYSTEM=1`.
- **Исправление**: Добавлена явная установка `env["PREFIX"] = paths["prefix"]` и `env["GIT_CONFIG_NOSYSTEM"] = "1"`.

### 11.7. Баг сброса пути в `path_resolver.resolve_subpath`
- **Проблема**: В стандартной библиотеке Python вызов `os.path.join("/a/b", "/c")` возвращает `"/c"`. Передача относительного пути с ведущим слэшем (например, `resolve_subpath("runtime", "/bin/sh")`) приводила к отбрасыванию базового каталога.
- **Исправление**: Добавлена нормализация `rel_path.lstrip("/")`.

### 11.8. Игнорирование флага `--json` и тихие ошибки в `cli/agy_system.py`
- **Проблема**: Подкоманда `clipboard` игнорировала аргумент `--json` и ничего не сообщала пользователю при ошибках связи со шлюзом.
- **Исправление**: Реализован вывод сырого JSON при флаге `--json` и вывод сообщений об ошибках в `stderr` с ненулевым кодом выхода.

---

## Subdirectories & Boundaries
- **Bridge**: [./bridge/README.md](./bridge/README.md) — Authenticated HTTP IPC client (`client.py`) and typed Android System Gateway command wrappers (`commands.py`).
- **CLI**: [./cli/README.md](./cli/README.md) — Runtime log/crash/command inspector (`agy_logs.py`) and Android System Gateway CLI controller (`agy_system.py`).
- **Command Runner**: [./cmd/README.md](./cmd/README.md) — Shell command execution engine and CLI wrapper (`runner.py`) with JSONL audit logging.
- **Context**: [./context/README.md](./context/README.md) — Dynamic Android package ID & path resolver (`path_resolver.py`) and isolated process environment builder (`env_builder.py`) backed by `spec/paths.json` and `spec/env_vars.json`.
- **Diagnostics**: [./diagnostics/README.md](./diagnostics/README.md) — ARM64/storage/writability pre-flight checker (`preflight.py`) and loopback TCP port availability validator (`port_checker.py`).
- **Logging**: [./logging/README.md](./logging/README.md) — Structured ANSI/JSONL console logger (`log.py`) and post-mortem JSON crash snapshot recorder (`flight_recorder.py`).

## Invariants
- `[context/path_resolver.py:get_application_id]`: Android package ID resolution follows strict precedence `AGY_APPLICATION_ID` -> `APPLICATION_ID` -> `"com.agy.dev"` (when `DEV_MODE == "1"`) -> `"com.agy"`; `[context/path_resolver.py:resolve_subpath]` strips leading `/` so relative subpaths cannot discard the resolved root.
- `[context/env_builder.py:build_process_env]`: Always constructs a clean process environment dictionary (`HOME`, `PREFIX`, `TMPDIR`, `GIT_CONFIG_NOSYSTEM="1"`, `AGY_APPLICATION_ID`, `PATH`, existing-only `LD_LIBRARY_PATH`, plus `extra_env` last) without mutating `os.environ`.
- `[diagnostics/preflight.py:run_preflight]`: Preflight succeeds (`ok=True`) strictly when CPU architecture is `aarch64`/`arm64`, free space on `app_root` is `>= min_mb_free` (default `250` MB), and `app_root` is writable (`os.W_OK`); `[diagnostics/port_checker.py:check_port_available]` validates IPv4 TCP bind availability on `127.0.0.1` by default with a `0.5s` timeout.
- `[bridge/client.py:resolve_gateway_url]`: Gateway discovery resolves `AGY_GATEWAY_URL`, then numeric `gateway.port` files, then port `45161` (dev) or `45160` (release); `[bridge/client.py:send_command]` attaches `[bridge/client.py:TOKEN_HEADER]` (`X-Agy-Gateway-Token`) when resolved, retries once on the alternate port (`45160` <-> `45161`) if `gateway_url is None`, and returns a fallback error dict instead of raising transport exceptions.
- `[cmd/runner.py:run]`: Maps subprocess outcomes strictly to `SUCCESS` (`0`), `FAILED` (non-zero), `TIMEOUT` (`124`), or `ERROR` (`1`), and leaves parent `os.environ` unchanged; `[logging/log.py:log]` disables ANSI colors when `NO_COLOR` is set or `TERM == "dumb"` and routes `ERROR` strictly to `sys.stderr`.
- `[cli/agy_logs.py:main]`: Mode precedence is `--status` > `--commands` > `--cmd-id` > `--crash` > default log tail, falling back to local files (`core.log`, `commands.jsonl`, `logs/crashes`) when gateway endpoints are unavailable; `[cli/agy_system.py:cmd_status]` and `[cli/agy_system.py:cmd_doctor]` exit `1` on falsy `res.get("ok")` even under `--json`.

## Side Effects
- `[bridge/client.py:DEFAULT_GATEWAY_URL]` & `[cli/agy_logs.py:GATEWAY_BASE]`: Perform import-time environment reads, `gateway.port` file probes, and `/data/user/*/com.agy*/files/logs/` filesystem globs; `[cli/agy_logs.py:MODULES_ENV_DIR]`, `[cli/agy_system.py:MODULES_ENV_DIR]`, and `[cmd/runner.py:_resolve_log_dir]` may prepend module paths to `sys.path`.
- `[bridge/client.py:send_command]` & `[cli/agy_system.py:main]`: Issue outbound loopback HTTP POST requests to Android System Gateway, triggering host Android notifications, URL intents, wakelocks, clipboard reads/writes, health checks, repairs, and async shell execution; `[cli/agy_logs.py:fetch_from_gateway]` issues outbound loopback HTTP GET requests.
- `[cmd/runner.py:run]`: Executes arbitrary shell commands (`subprocess.run(..., shell=True)`) and appends UTF-8 JSONL audit records to `<LOG_DIR>/commands.jsonl`.
- `[logging/log.py:log]` & `[logging/flight_recorder.py:record_crash_snapshot]`: Write log lines to `sys.stdout`/`sys.stderr`, create parent directories (`os.makedirs(..., exist_ok=True)`), append JSONL log files, and write JSON crash dumps to disk.
- `[diagnostics/port_checker.py:check_port_available]`: Transiently allocates and binds an IPv4 TCP socket on `(host, port)`.

## Verification
- Run: `bash modules/env/tests/run_all.sh`
