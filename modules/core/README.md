# Antigravity Core Module (`modules/core`)

> Базовый модуль дистрибуции ядра Antigravity v2: распаковка, валидация ELF/ABI AArch64, поставка glibc-окружения, шимов и криптографическая сборка runtime-директории.

---

## 1. Назначение и зона ответственности

Модуль `modules/core` является фундаментом рантайма Antigravity v2 на платформе Android (ARM64 / AArch64). Он поставляет исходный бинарник language server, системные библиотеки glibc, сертификаты, конфигурации и предоставляет инструменты для:

1. **Распаковки и извлечения (`unpack/`):** Детектирование формата архива (tar.gz / сырой ELF) и извлечение `language_server` с настройкой POSIX-прав.
2. **Валидации бинарников (`validate/`):** Контроль SHA-256 чексумм, валидация 64-bit Little-Endian структуры ELF, целевой архитектуры AArch64 (EM=183) и формата (ET_DYN/PIE).
3. **Криптографического манифестирования (`manifest/`):** Потоковое вычисление SHA-256 (чанками по 1 МБ), генерация `manifest.json` и строгая верификация целостности файлов.
4. **Формирования runtime-бандла (`bundle/`):** Сборка изолированной runtime-директории (`bin/`, `lib/`, `certs/`, `seed/`) для потребителей (`modules/runner`, `modules/builder`).
5. **Системных шимов (`shims/`):** Эмуляция утилит окружения Linux (`xdg-open`, браузерные вызовы, Shizuku `rish`).

---

## 2. Структура каталогов и файлов

```
modules/core/
├── spec/                       # Декларативные спецификации ядра
│   ├── version.env             # Переменные версии, тег сборки, имя загрузчика
│   ├── checksums.json          # Эталонные SHA-256 и размеры архива/бинарника
│   ├── glibc_deps.list         # Список требуемых glibc .so библиотек
│   └── shims.list              # Список устанавливаемых исполняемых шимов
├── pristine/                   # Оригинальный неизмененный архив ядра
│   └── language_server.tar.gz  # Pristine aarch64 language_server (~205 MB unpacked, 215438000 bytes)
├── unpack/                     # Модули распаковки и прав доступа
│   ├── archive_detector.py     # Определение формата (tar_gz, elf, unknown)
│   ├── extractor.py            # Извлечение core-бинарника из tar.gz / raw ELF
│   └── permissions.py          # Установка POSIX-прав (+x / +r)
├── validate/                   # Валидаторы ELF и контрольных сумм
│   ├── elf_header.py           # Проверка магии \x7fELF, EI_CLASS (64-bit), EI_DATA (LE)
│   ├── elf_arch.py             # Проверка e_machine == EM_AARCH64 (183)
│   ├── elf_compat.py           # Проверка e_type (ET_EXEC / ET_DYN)
│   ├── checksum_checker.py     # Потоковая сверка SHA-256 файлов
│   └── verify_pipeline.py      # Сквозной валидационный E2E-пайплайн (5 этапов)
├── manifest/                   # Генератор и верификатор манифеста
│   ├── file_hasher.py          # Потоковый расчет SHA-256 (1MB chunks)
│   ├── manifest_builder.py     # Сканирование директории и сборка manifest.json
│   └── manifest_verifier.py    # Проверка целостности директории по manifest.json
├── bundle/                     # Сборка финального рантайма
│   ├── layout.py               # Инициализация структуры директорий (bin, lib, certs, seed, etc)
│   ├── pack_binaries.py        # Упаковка language_server, загрузчика и шимов (симлинки/делегаторы) в bin/
│   ├── pack_libraries.py       # Копирование glibc библиотек в lib/ и сетевых конфигов в etc/
│   ├── pack_certs.py           # Копирование SSL-сертификатов в certs/
│   ├── pack_seed.py            # Копирование предзаполненного состояния в seed/
│   └── assemble.py             # CLI и Python оркестратор полной сборки рантайма
├── glibc/                      # Набор glibc библиотек под aarch64
│   ├── ld-linux-aarch64.so.1   # Динамический компоновщик (интерпретатор ELF)
│   ├── libc.so.6, libm.so.6... # Системные библиотеки glibc
│   ├── liblse_compat.so        # Эмулятор атомарных инструкций LSE для старых CPU
│   └── etc/                    # Конфигурационные файлы (hosts, nsswitch, resolv)
├── certs/                      # Сертификаты безопасности
│   └── ca-certificates.crt     # Системное SSL-хранилище CA
├── seed/                       # Начальное состояние, инструкции и встроенные скиллы
│   ├── AGENTS.md               # Системный промпт и правила окружения
│   ├── *.pbtxt                 # Начальные protobuf-состояния онбординга (`antigravity_state.pbtxt`, `antigravity_state.gemini.pbtxt`, `jetski_state.pbtxt`)
│   └── skills/                 # Встроенные Android-скиллы (`android-browser`, `android-daemon`, `android-device`)
├── shims/                      # Системные и браузерные заглушки
│   ├── browser/                # xdg-open и браузерные делегаторы (chrome, open, etc.)
│   ├── privilege/              # rish.sh (интерфейс к Shizuku ADB-оболочке)
│   └── system/                 # open_helper.sh, xdg_open.sh (URL-диспетчер)
└── tests/                      # Модульные тесты
    ├── test_extractor.py       # Тесты распаковки и archive_detector
    ├── test_elf_validator.py   # Тесты ELF-заголовков и архитектуры
    ├── test_manifest.py        # Тесты генерации и проверки манифеста
    ├── test_bundle.py          # Тесты layout, etc-конфигов и сборки через assemble.py
    ├── test_shims.py           # Тесты парсинга URL в xdg-open и браузерных делегаторов
    └── run_all.sh              # Скрипт синтаксической проверки и запуска тестов
```

---

## 3. Интерфейсы компонентов (API & CLI)

### 3.1. Распаковка (`unpack/`)

- **`extractor.extract_core(src_path: str, dest_path: str, binary_name: str = "language_server") -> bool`**
  - Извлекает исполняемый файл `binary_name` из `tar.gz` или копирует сырой `ELF`.
  - CLI: `python3 extractor.py <source_path> <dest_binary_path> [binary_name]`

- **`archive_detector.detect_type(file_path: str) -> str`**
  - Возвращает: `"tar_gz"`, `"elf"`, `"unknown"`, `"not_found"`.
  - CLI: `python3 archive_detector.py <file_path>`

- **`permissions.make_executable(file_path: str) -> None`**
  - Добавляет флаги `r-xr-xr-x` к файлу.
  - CLI: `python3 permissions.py [--exec|--read] <path>`

### 3.2. Валидация (`validate/`)

- **`elf_header.validate_elf_header(file_path: str) -> bool`**
  - Проверяет сигнатуру `\x7fELF`, 64-bit (`EI_CLASS=2`), Little-Endian (`EI_DATA=1`).
- **`elf_arch.validate_aarch64(file_path: str) -> bool`**
  - Проверяет `e_machine == 183` (ARM64/AArch64).
- **`elf_compat.validate_executable_type(file_path: str) -> str`**
  - Возвращает `"ET_DYN"` (PIE/SO) или `"ET_EXEC"`.
- **`checksum_checker.verify_checksum(file_path: str, expected_hash: str) -> bool`**
  - Вычисляет SHA-256 потоком (буфер 1 МБ) и сравнивает с эталоном.

### 3.3. Манифест (`manifest/`)

- **`manifest_builder.build_manifest(runtime_dir: str, out_file: str = None) -> dict`**
  - Рекурсивно сканирует `runtime_dir` (исключая сам `manifest.json`), рассчитывает SHA-256 каждого файла и записывает JSON со структурой:
    ```json
    {
      "schema": 1,
      "generated_at": "2026-10-15T20:00:00Z",
      "files": {
        "bin/liblanguage_server.so": "<sha256>",
        "lib/libc.so.6": "<sha256>"
      }
    }
    ```
  - CLI: `python3 manifest_builder.py <runtime_dir> [output_manifest_path]`

- **`manifest_verifier.verify_manifest(manifest_path: str, target_dir: str) -> bool`**
  - Проверяет наличие и соответствие контрольных сумм всех файлов, описанных в манифесте.
  - CLI: `python3 manifest_verifier.py <manifest_json_path> <target_dir>`

### 3.4. Сборка бандла (`bundle/`)

- **`assemble.assemble_runtime(core_bin_path: str, module_root: str, out_dir: str, dry_run: bool = False) -> dict`**
  - Оркестратор сборки рантайма:
    1. Инициализирует каталоги (`layout.py`), включая `bin`, `lib`, `certs`, `seed`, `etc`.
    2. Копирует ядро как `bin/liblanguage_server.so`, загрузчик как `bin/libloader.so` и `bin/ld-linux-aarch64.so.1`, шимы (`pack_binaries.py`). Браузерные утилиты связываются симлинками или компактными ссылками на `xdg-open`.
    3. Копирует все top-level `.so`-библиотеки из `glibc/` (включая `ld-linux-aarch64.so.1`) в `lib/`, а также сетевые и DNS-конфигурации (`hosts`, `resolv.conf`, `nsswitch.conf`) из `glibc/etc` в `etc/` (`pack_libraries.py`).
    4. Копирует сертификаты в `certs/` (`pack_certs.py`).
    5. Копирует seed-файлы в `seed/` (`pack_seed.py`).
    6. Формирует `manifest.json` (`manifest_builder.py`).
  - CLI:
    ```bash
    python3 modules/core/bundle/assemble.py \
      --core-bin /path/to/language_server \
      --out-dir /path/to/destination/runtime \
      [--module-root /path/to/modules/core] \
      [--dry-run]
    ```

---

## 4. Структура сформированного Runtime (`assets/runtime`)

После сборки модуль формирует директорию со следующей топологией:

| Каталог | Содержимое | Назначение |
| :--- | :--- | :--- |
| `bin/` | `liblanguage_server.so`, `libloader.so`, `ld-linux-aarch64.so.1`, `xdg-open`, `chrome`, `rish`... | Исполняемые бинарники, загрузчик glibc и shell-шимы |
| `lib/` | `libc.so.6`, `libm.so.6`, `libdl.so.2`, `libpthread.so.0`, `liblse_compat.so`, `ld-linux-aarch64.so.1`, ... | Зависимости glibc и LSE-атомиков под AArch64 |
| `etc/` | `hosts`, `resolv.conf`, `nsswitch.conf` | Сетевые и DNS-конфигурации для работы сетевого стека glibc |
| `certs/` | `ca-certificates.crt` | Корневые SSL/TLS сертификаты для Go TLS рантайма |
| `seed/` | `AGENTS.md`, `antigravity_state.pbtxt`, `antigravity_state.gemini.pbtxt`, `jetski_state.pbtxt`, `skills/` | Пресеты онбординга, конфигурации агента, скиллы и системные правила |
| `/` | `manifest.json` | Криптографический реестр целостности файлов |

> Для изоляции тестов шима `xdg-open` переменная окружения `AGY_SHIM_NO_AM=1` отключает fallback-вызовы `/system/bin/am`.

---

## 5. Тестирование и валидация

### Полный запуск модульных тестов
```bash
bash modules/core/tests/run_all.sh
```
Включает:
1. Синтаксическую компиляцию Python (`compileall`) и проверку shell-скриптов (`bash -n`).
2. Unit-тесты распаковки (`test_extractor.py`).
3. Unit-тесты заголовков ELF и архитектуры (`test_elf_validator.py`).
4. Unit-тесты манифеста и обнаружения повреждений (`test_manifest.py`).
5. Unit-тесты сборки бандла, layout и etc-конфигов (`test_bundle.py`).
6. Unit-тесты URL-парсинга в xdg-open и браузерных шимов (`test_shims.py`).

### Сквозной E2E-пайплайн верификации
```bash
python3 modules/core/validate/verify_pipeline.py
```
Выполняет 5-шаговую сквозную валидацию в изолированной песочнице: сверка хэша pristine-архива -> извлечение -> валидация ELF/ABI -> сборка тестового runtime -> проверка сгенерированного манифеста.

---

## Subdirectories & Boundaries
- Bundle: [bundle/README.md](./bundle/README.md) - Stages `bin/`, `lib/`, `certs/`, `seed/`, and `etc/` into a runtime bundle and writes `manifest.json`.
- Manifest: [manifest/README.md](./manifest/README.md) - Streaming 1 MiB SHA-256 hashing, schema-v1 `manifest.json` generation, and directory integrity verification.
- Shims: [shims/README.md](./shims/README.md) - Android POSIX shell shims for URL/browser openers (`browser/`, `system/`) and Shizuku `rish` (`privilege/`).
- Unpack: [unpack/README.md](./unpack/README.md) - Magic-byte archive/ELF detection, `language_server` extraction, and additive POSIX mode helpers.
- Validate: [validate/README.md](./validate/README.md) - SHA-256 verification, 64-bit little-endian AArch64 ELF validators, and the 5-step E2E `verify_pipeline.py`.
- Seed Skills (non-code assets): [android-browser/SKILL.md](./seed/skills/android-browser/SKILL.md), [android-daemon/SKILL.md](./seed/skills/android-daemon/SKILL.md), [android-device/SKILL.md](./seed/skills/android-device/SKILL.md) - Bundled Android skill definitions copied verbatim into `seed/skills/` by `pack_seed.py`.

## Invariants
- `[unpack/extractor.py:extract_core]` / `[unpack/archive_detector.py:detect_type]`: Only gzip tar archives (`b"\x1f\x8b"`) containing a matching basename member (default `"language_server"`) and raw ELF binaries (`b"\x7fELF"`) are accepted; extracted outputs must be non-empty regular files.
- `[validate/elf_header.py:validate_elf_header]` / `[validate/elf_arch.py:validate_aarch64]` / `[validate/elf_compat.py:validate_executable_type]`: Core binaries must be 64-bit (`EI_CLASS == 2`), little-endian (`EI_DATA == 1`), AArch64 (`EM_AARCH64 == 183`), and `ET_EXEC` (`2`) or `ET_DYN` (`3`), raising `ValueError` on violation.
- `[validate/verify_pipeline.py:run_pipeline]` / `[spec/checksums.json:archive]`: E2E verification pins `pristine/language_server.tar.gz` to SHA-256 `6847c8e03731e801465a253cbb8c286ffa63c9ee4567dd7a8d5f26ccadaba390` and validates both extracted `language_server` and `glibc/ld-linux-aarch64.so.1`.
- `[bundle/layout.py:SUBDIRS]` / `[bundle/assemble.py:assemble_runtime]`: Runtime bundle layout is fixed to `("bin", "lib", "certs", "seed", "etc")` plus root `manifest.json`; `pack_binaries.py` installs the loader as both `bin/libloader.so` and `bin/ld-linux-aarch64.so.1`, normalizes copied shell scripts to LF, and preserves symlinks/delegators to `xdg-open`.
- `[manifest/manifest_builder.py:build_manifest]` / `[manifest/manifest_verifier.py:verify_manifest]`: Manifest schema is `1` with UTC `generated_at` and POSIX `/` relative paths excluding only root `manifest.json`; verification checks all listed files without short-circuiting and does not flag unlisted extra files.
- `[shims/system/xdg_open.sh:LD_PRELOAD]` / `[shims/privilege/rish.sh:env]`: All shell shims strip `LD_PRELOAD` and `LD_LIBRARY_PATH` before invoking Android `/system/bin/am` or `/system/bin/app_process`; `AGY_SHIM_NO_AM` disables `/system/bin/am` dispatch for testing.

## Side Effects
- `[unpack/extractor.py:extract_core]` / `[unpack/permissions.py:make_executable]`: Creates destination directories, extracts/copies binaries to disk, and mutates POSIX permission bits via `os.chmod`.
- `[bundle/assemble.py:assemble_runtime]` / `[manifest/manifest_builder.py:build_manifest]`: Creates the bundle tree under `out_dir`, copies/normalizes binaries, libraries, configs, certs, and seed files, creates symlinks in `bin/`, and writes `<out_dir>/manifest.json`.
- `[validate/verify_pipeline.py:run_pipeline]`: Mutates `sys.path` at import time and creates/cleans a temporary sandbox (`tempfile.mkdtemp(prefix="agy_core_val_")`) for E2E extraction and assembly.
- `[shims/system/xdg_open.sh:com.agy.ACTION_OPEN_URL]` / `[shims/privilege/rish.sh:exec]`: At Android runtime, shims emit stdout URL markers, dispatch `/system/bin/am` broadcasts/intents, apply `chmod 400` to `rish_shizuku.dex` on Android 14+, and `exec` `/system/bin/app_process`.

## Verification
- Run: `bash modules/core/tests/run_all.sh`
- Run: `python3 modules/core/validate/verify_pipeline.py`
