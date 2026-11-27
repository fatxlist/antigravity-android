# Antigravity Core Binary Patcher (`modules/patches`)

Модуль точечного бинарного патчинга ядра Antigravity (`language_server`) под архитектуру **Android ARM64 (aarch64)**. Обеспечивает обход платформенных ограничений (Android seccomp, Bionic libc, отсутствие LSE atomics на ARMv8.0-A) и интеграцию с runtime-окружением.

---

## 1. Архитектура

```
modules/patches/
├── pipeline.py              # CLI-оркестратор и API запуска пайплайна
├── spec/
│   └── patch_manifest.json  # Спецификация ожидаемых патчей и политик
├── engine/                  # Низкоуровневые движки анализа и модификации
│   ├── elf_scanner.py       # Парсер ELF64/AArch64 и локатор секции .text
│   ├── pclntab_scanner.py   # Парсер таблицы функций Go pclntab и MOVZ-кодировщик
│   ├── byte_replacer.py     # Поиск и in-place замена байтовых паттернов
│   ├── reporter.py          # Формирование JSON-отчетов по выполненным патчам
│   └── patch_context.py     # Модель контекста выполнения
├── groups/                  # Функциональные группы патчей
│   ├── syscalls/            # Замена seccomp-блокируемых faccessat2/fchmodat2
│   ├── sigill/              # Защита обработчика SIGILL (для liblse_compat)
│   ├── resolv/              # Редирект /etc/resolv.conf на относительный путь
│   ├── failfast/            # Подавление проверок CPU ARMv8.0-A и preinit atomic
│   └── pty_shell/           # Retarget PTY shell path (/bin/bash -> /bin///sh)
└── tests/                   # Модульные и интеграционные тесты
    ├── test_byte_replacer.py
    ├── test_elf_scanner.py
    ├── test_pclntab_scanner.py
    ├── test_full_pipeline.py
    └── run_all.sh
```

---

## 2. Группы патчей

| Группа | Модуль | Цель и сигнатура | Действие |
|---|---|---|---|
| **syscalls** | `faccessat` | `syscall.faccessat2` (sys_439: `0xd28036e0`) | Ретаргетинг на `faccessat` (sys_48: `0xd2800600`) |
| **syscalls** | `fchmodat` | `syscall.fchmodat2` (sys_452: `0xd2803880`) | Ретаргетинг на `fchmodat` (sys_53: `0xd28006a0`) |
| **sigill** | `sigill_handler` | Вызов `rt_sigaction` в рантайме Go | Игнорирование сигнала `SIGILL` (4) для сохранения обработчика `liblse_compat.so` |
| **resolv** | `resolv_redirect` | Строка `/etc/resolv.conf` (16 байт) | Замена на `etc//resolv.conf` без изменения длины |
| **failfast** | `crash_suppressor` | Динамический поиск байт-сигнатур `TARGET_CPU` (ARMv8.0-A CPU feature check) и `TARGET_ATOMIC` (preinit atomic) | Замена на `REPLACEMENT_CPU` (`b +0x64` + NOP-заполнение) и `REPLACEMENT_ATOMIC` (`ldr`/`str` вместо `ldaddal`) для ARMv8.0-A (Snapdragon 660 и др.) без фиксированных смещений |
| **pty_shell** | `pty_shell` | Строка `/bin/bash` (9 байт) | Замена на `/bin///sh` без изменения длины для совместимости PTY spawner с Android |

---

## 3. Оптимизации движков анализа (Заход 1)

1. **`elf_scanner.py`**:
   - Прямая валидация заголовка `e_ident` по смещению 0 с поддержкой поиска встроенных ELF-контейнеров.
   - Поддержка stripped-бинарников (`e_shnum == 0`): автоматический fallback на Program Headers (`PT_LOAD` с флагом `PF_X`).
   - Строгая проверка границ смещений и размеров для секций `.text`, `.shstrtab` и таблиц заголовков.
2. **`pclntab_scanner.py`**:
   - Оптимизированный `find_function`: прямой поиск позиции имени функции в таблице строк `funcnametab`.
   - Внутреннее кэширование результатов поиска функций (`_cache`) в контексте pclntab, исключающее циклы декодирования десятков тысяч строк latin-1 на каждый сисколл.
3. **Zero-Copy в группах патчей**:
   - Полное устранение промежуточных аллокаций `bytes(data)` в циклах сканирования; прямая работа по протоколу буфера (`bytearray` / `memoryview`).
   - Устранение обхода верификации в `crash_suppressor.py`: строгое соблюдение политики fail-closed.

---

## 4. CLI & Python API

### CLI Runner
```bash
# Применение всех патчей к бинарнику:
python3 modules/patches/pipeline.py /path/to/language_server

# С сохранением отчета в manifest:
python3 modules/patches/pipeline.py /path/to/language_server --manifest report.json

# Проверка наличия патчей (exit code 0 / 1):
python3 modules/patches/pipeline.py /path/to/language_server --verify

# Тестовый прогон без записи изменений:
python3 modules/patches/pipeline.py /path/to/language_server --dry-run

# Толерантность к отсутствующим сигнатурам:
python3 modules/patches/pipeline.py /path/to/language_server --allow-missing
```

### Python API
```python
import pipeline

ok = pipeline.run_pipeline(
    target_path="/path/to/language_server",
    verify=False,        # Только верификация
    dry_run=False,       # Прогон без записи на диск
    allow_missing=False, # Не падать при отсутствии сигнатур
    manifest_path=None   # Путь для сохранения JSON-манифеста
)
```

---

## 5. Тестирование

Запуск полного тестового набора:
```bash
bash modules/patches/tests/run_all.sh
```

- `test_byte_replacer.py`: юнит-тесты сопоставления байт и точечной замены.
- `test_elf_scanner.py`: валидация ELF64, fallback на Program Headers для stripped-бинарников и проверка границ.
- `test_pclntab_scanner.py`: валидация кодирования опкодов AArch64 `MOVZ X0, #imm`, прямой поиск в `funcnametab` и кэширование.
- `test_full_pipeline.py`: E2E тест на реальном pristine-бинарнике `language_server` (dry-run, apply, sha256 check, verify, idempotence, fail-closed validation).

---

## Interface
- `[pipeline.py:SCRIPT_DIR]` — module constant holding `os.path.dirname(os.path.abspath(__file__))`.
- `[pipeline.py:ACTIVE_MODULES]` — ordered module list `[faccessat, fchmodat, sigill_handler, resolv_redirect, crash_suppressor, pty_shell]` executed sequentially.
- `[pipeline.py:sha256_bytes]` — `sha256_bytes(b: bytes) -> str`: returns the lowercase hex SHA-256 digest of `b`.
- `[pipeline.py:run_pipeline]` — `run_pipeline(target_path: str, verify: bool = False, dry_run: bool = False, allow_missing: bool = False, manifest_path: str = None) -> bool`: loads `target_path`, runs `ACTIVE_MODULES`, conditionally writes patched bytes and a JSON report built via `reporter.build_report`, and returns success status.
- `[pipeline.py:main]` — CLI entry point parsing positional `[pipeline.py:target]` and optional flags `[pipeline.py:--verify]`, `[pipeline.py:--dry-run]`, `[pipeline.py:--allow-missing]`, `[pipeline.py:--manifest]`, exiting `0` on success or `1` on failure.
- [Patch Engine](./engine/README.md) — ELF64/AArch64 scanner, Go `pclntab` resolver, byte replacer, session context, and JSON report builder.
- [Patch Groups](./groups/README.md) — modular patch groups (`failfast`, `pty_shell`, `resolv`, `sigill`, `syscalls`).

## Invariants
- `[pipeline.py:ACTIVE_MODULES]` Applies patch modules in strict fixed order (`faccessat`, `fchmodat`, `sigill_handler`, `resolv_redirect`, `crash_suppressor`, `pty_shell`) against a single shared `bytearray` via `mod.apply(data, verify=verify, dry_run=dry_run, allow_missing=allow_missing)`.
- `[pipeline.py:run_pipeline]` Checks `os.path.isfile(target_path)` before reading and fails immediately if absent; enforces fail-fast atomic abort on the first module returning `ok == False`, skipping remaining modules and never writing `target_path` or `manifest_path`.
- `[pipeline.py:--verify]` When `verify=True` and all modules pass, returns `True` immediately without modifying `target_path`, computing `sha_final`, calling `reporter.build_report`, or writing `manifest_path`.
- `[pipeline.py:--dry-run]` When `dry_run=True`, never writes `target_path`, while still computing `sha_final`, building the report, and writing `manifest_path` if supplied.
- `[pipeline.py:run_pipeline]` Overwrites `target_path` only when `verify=False`, `dry_run=False`, `overall_ok=True`, and `sha_init != sha_final` (idempotent no-op when already patched).
- `[pipeline.py:sha256_bytes]` Always returns lowercase hexadecimal SHA-256 strings; `[pipeline.py:main]` exits `0` iff `run_pipeline` returns `True`, else `1`.

## Side Effects
- `[pipeline.py:SCRIPT_DIR]` Prepends six subdirectories (`engine`, `groups/syscalls`, `groups/sigill`, `groups/resolv`, `groups/failfast`, `groups/pty_shell`) to `sys.path` at import time.
- `[pipeline.py:run_pipeline]` Reads the entire target binary into memory, mutates an in-memory `bytearray`, overwrites `target_path` in place (`"wb"`) when bytes changed in non-dry-run mode, prints progress/hashes to `stdout`, and prints `[FATAL]` errors to `stderr`.
- `[pipeline.py:--manifest]` Creates missing parent directories via `os.makedirs(..., exist_ok=True)` and writes a UTF-8 indented JSON report with trailing newline when `manifest_path` is provided (and `verify=False`, `overall_ok=True`).
- `[pipeline.py:main]` Parses CLI arguments and terminates the process with `sys.exit(0 if ok else 1)`.

## Verification
- Run: `bash modules/patches/tests/run_all.sh`
