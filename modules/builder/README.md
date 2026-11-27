# Модуль сборщика APK: `modules/builder`

> Автономный конвейер сборки Android APK без Gradle и Android Studio.
> Оптимизирован для нативного выполнения непосредственно в окружении Android (ARM64 / Linux).

---

## 1. Обзор архитектуры

Сборка Antigravity APK выполняется полностью независимо от тяжелых систем сборки (Gradle, AGP, Groovy/Kotlin daemon). Оркестратор [`build_apk.sh`](build_apk.sh) вызывает цепочку атомарных скриптов, напрямую управляя низкоуровневыми бинарными утилитами Android SDK (`aapt`, `javac`, `d8`/`r8`, `zipalign`, `apksigner`) и специализированными Python-утилитами.

### Схема конвейера (Pipeline Diagram)

```mermaid
flowchart TD
    Env[config/app.env] --> P01[01_gen_config.py]
    Src[app/src/main/...] --> P01
    
    subgraph Config & Resources
        P01 -->|BuildConfig.java| GenDir[build/gen]
        P01 -->|gen.xml| BuildRes[build/res/values]
        P01 -->|Dev-aware Manifest| TargetManifest[build/AndroidManifest.xml]
        TargetManifest & ResDir[app/src/main/res] & BuildRes --> P02[02_r_java.sh: aapt overlay]
        P02 -->|R.java| GenDir
    end

    subgraph Bytecode & Dexing
        GenDir & Src --> JavaArg[@build/java_sources.txt]
        JavaArg --> P03[03_compile_java.sh: javac]
        P03 -->|*.class| ObjDir[build/obj]
        ObjDir --> ClassArg[@build/class_files.txt]
        ClassArg --> P04[04_dex.sh: D8 via r8.jar]
        P04 -->|classes.dex| DexDir[build/dex]
    end

    subgraph Assets & Packaging
        Runtime[modules/runner/prepare_runtime] --> P05[05_stage_assets.py]
        Web[web/] & EnvDist[env/dist] --> P05
        P05 -->|staged assets| StagingDir[build/staging/assets]
        
        TargetManifest & ResDir & BuildRes & StagingDir & DexDir --> P06[06_package.sh: aapt + zip]
        P06 -->|raw APK| Unaligned[build/unaligned.apk]
    end

    subgraph Align, Sign & Verify
        Unaligned --> P07[align.py: zipalign / python]
        P07 -->|4-byte aligned APK| Aligned[build/aligned.apk]
        Aligned --> P08[08_sign.sh: apksigner]
        P08 -->|Signed APK (v1+v2+v3)| Dist[dist/antigravity.apk / antigravity-dev.apk]
        Dist --> P09[09_verify.sh: apksigner verify]
    end
```

---

## 2. Этапы конвейера сборки (Build Stages)

| Этап | Скрипт | Главный инструмент | Входы | Выходы | Назначение |
|---|---|---|---|---|---|
| **01** | [`01_gen_config.py`](01_gen_config.py) | Python 3 | `config/app.env`, `AndroidManifest.xml` | `build/gen/**/BuildConfig.java`, `build/res/values/gen.xml`, `build/AndroidManifest.xml` | Генерация констант, портов и dev-манифеста в build-дерево (без загрязнения git) |
| **02** | [`02_r_java.sh`](02_r_java.sh) | `aapt` | `AndroidManifest.xml`, `res/`, `build/res/`, `android.jar` | `build/gen/**/R.java` | Компиляция идентификаторов ресурсов с оверлеем и пропуском дубля в релизе |
| **03** | [`03_compile_java.sh`](03_compile_java.sh) | `javac` | `@build/java_sources.txt`, `android.jar` | `build/obj/**/*.class` | Компиляция Java-исходников через аргументный файл (защита от `ARG_MAX`) |
| **04** | [`04_dex.sh`](04_dex.sh) | `D8` (`tools/r8.jar`) | `@build/class_files.txt`, `android.jar` | `build/dex/classes.dex` | Трансляция Java-байткода в DEX через аргументный файл D8 без переполнения CLI |
| **05** | [`05_stage_assets.py`](05_stage_assets.py) | Python 3 + `modules/runner` | Ядро рантайма, `web/`, `env/dist/env` | `build/staging/assets/` | Сборка и верификация ассетов (runtime, web, env) |
| **06** | [`06_package.sh`](06_package.sh) | `aapt` + `zipfile` | Ресурсы + оверлей `build/res`, манифест, `classes.dex`, `staging/assets/` | `build/unaligned.apk` | Создание базового APK с оверлеем и инъекция dex и ассетов |
| **07** | [`align.py`](align.py) | `zipalign` / Python | `build/unaligned.apk` | `build/aligned.apk` | 4-байтовое выравнивание ZIP-архива для `mmap` |
| **08** | [`08_sign.sh`](08_sign.sh) | `apksigner` | `build/aligned.apk`, release keystore (`config/tools.env`) или `tools/debug.keystore` (`--dev`/`--allow-debug-sign`) | `dist/antigravity.apk` или `dist/antigravity-dev.apk` | Подпись схемами v1, v2 и v3 |
| **09** | [`09_verify.sh`](09_verify.sh) | `apksigner` | `dist/*.apk` | Результат проверки в stdout | Полная проверка валидности и схемы подписи |

---

## 3. Детальное описание шагов

### Шаг 01: Генерация конфигурации (`01_gen_config.py`)
- **Вход**: Переменные из `config/app.env` и `DEV_MODE` (0 или 1).
- **Выход**:
  - `build/gen/com/agy/BuildConfig.java`: статические поля `APPLICATION_ID`, `PORT`, `PORT_DEV`, `GATEWAY_PORT`, `CORE_VERSION`, `APP_VERSION_NAME`, `APP_VERSION_CODE`, `OAUTH_SCHEME`, `APP_NAME`, `MIN_API`, `TARGET_SDK`, `DEBUG` (всегда в пакете базового `PKG`, по умолчанию `com.agy`, для совместимости исходного кода).
  - `build/res/values/gen.xml`: строковые и целочисленные XML-ресурсы для Android framework (генерируются в дерево сборки, исключая загрязнение репозитория и `git status`).
  - `build/AndroidManifest.xml`: подстановка `${applicationId}`, `android:versionName` и `android:versionCode`, а в dev-режиме — изменение пакета на `com.agy.dev`, установка `android:debuggable="true"` и изоляция `permission.INTERNAL_BROADCAST`.

### Шаг 02: Генерация R.java (`02_r_java.sh`)
- Запускает `aapt package -m -J <gen_dir>` для разбора ресурсов и создания числовых ID с оверлеем `-S "$BUILD_DIR/res" -S "$RES_DIR" --auto-add-overlay`.
- Проверяет имя пакета в целевом манифесте: если пакет равен базовому `com.agy`, второй дублирующий запуск `aapt` опускается. Если имя пакета отличается (например, `com.agy.dev`), генерируется второй фоллбэк `com.agy.R` через `--custom-package com.agy` для совместимости со старыми вызовами.

### Шаг 03: Компиляция Java (`03_compile_java.sh`)
- Собирает список исходников приложения и сгенерированных классов (`BuildConfig`, `R`) в аргументный файл `@$BUILD_DIR/java_sources.txt`, полностью устраняя риск ошибки `ARG_MAX` (`Argument list too long`).
- Компилирует код компилятором `javac`: `-encoding UTF-8 -source 1.8 -target 1.8 -Xlint:all,-deprecation,-options,-serial -Werror -cp tools/android.jar @$BUILD_DIR/java_sources.txt`.

### Шаг 04: Дексирование D8 (`04_dex.sh`)
- Сканирует директорию классов `$OBJ_DIR` и генерирует аргументный файл `@$BUILD_DIR/class_files.txt`, устраняя ограничение на размер командной строки.
- Преобразует скомпилированные `.class` файлы в `classes.dex` через автономный D8 (`com.android.tools.r8.D8` из `tools/r8.jar`).
- Параметры: `--output $DEX_DIR`, `--lib tools/android.jar`, `--min-api 24`, `@$BUILD_DIR/class_files.txt`.

### Шаг 05: Стейджинг ассетов (`05_stage_assets.py`)
- Собирает три группы ресурсов в `build/staging/assets/`:
  1. `runtime/`: бинарное ядро `language_server` (пропатченное и криптографически проверенное через `prepare_runtime.py`).
  2. `web/`: фронтенд-интерфейс (`index.html`, `compiled_tailwind.css`, `generated/main.js` и т.д.). Перед копированием автоматически вызывает сборку бандлов `bash web/build.sh`, гарантируя актуальность веб-ресурсов из `web/src/`. Исключает служебные файлы (`main.js`, `src/`, `tests/`, `build.sh`, `patch_main.py`, а также `src.manifest`) через фильтр `web_ignore`.
  3. `env/`: оверлеи окружения, `libtermux-exec.so` и `manifest.json`.

### Шаг 06: Упаковка APK (`06_package.sh`)
- `aapt package -f` формирует каркас APK с поддержкой оверлея ресурсов (`-S "$BUILD_DIR/res" -S "$RES_DIR" --auto-add-overlay`), бинарным `AndroidManifest.xml` и скомпилированными ресурсами (`resources.arsc`).
- Потоковый Python ZIP-инжектор добавляет `classes.dex` и дерево `assets/`.
- Файлы, уже сжатые алгоритмами gzip/png/jpeg/zip, сохраняются без повторного сжатия (`ZIP_STORED`), остальные сжимаются через `ZIP_DEFLATED`.

### Шаг 07: Выравнивание ZipAlign (`align.py`)
- Выравнивает заголовки некомпилированных данных на границу 4 байт (и 4096 байт для библиотек `.so`), чтобы ОС Android могла обращаться к файлам напрямую через `mmap()` без распаковки в RAM.
- **Двухуровневая стратегия**:
  1. Системный бинарник `zipalign` (если установлен в `$PATH`).
  2. Встроенный кроссплатформенный чистый Python-алгоритм без внешних зависимостей.

### Шаги 08 и 09: Подпись и проверка (`08_sign.sh` & `09_verify.sh`)
- `apksigner sign` подписывает APK: в релизном режиме требуются переменные `AGY_RELEASE_KEYSTORE`, `AGY_RELEASE_KEY_ALIAS`, `AGY_RELEASE_KEY_PASS`, `AGY_RELEASE_STORE_PASS` (маппятся через `config/tools.env` в `KEYSTORE`, `KEY_ALIAS`, `KEY_PASS`, `STORE_PASS`); в режиме `--dev` (`DEV_MODE=1`) или `--allow-debug-sign` (`ALLOW_DEBUG_SIGN=1`) используется `tools/debug.keystore` (`DEV_KEYSTORE`). Пароли передаются через `env:AGY_SIGN_STORE_PASS` и `env:AGY_SIGN_KEY_PASS`.
- Поддерживаются схемы:
  - **v1**: JAR-сигнатура (совместимость).
  - **v2**: APK Signature Scheme v2 (Android 7.0+).
  - **v3**: APK Signature Scheme v3 (Android 9.0+).
- `apksigner verify --verbose` производит верификацию сигнатуры и дайджестов манифеста.

---

## 4. Зависимости и инструменты

| Инструмент | Минимальная версия | Назначение | Расположение |
|---|---|---|---|
| **Python** | 3.8+ | Конфигурация, стейджинг, упаковка, ZipAlign | Системный `$PATH` |
| **aapt** | v0.2+ (v1) | Парсинг XML, компиляция ресурсов, каркас APK | Системный `$PATH` |
| **javac** | OpenJDK 8 / 11 / 17 | Компиляция `.java` -> `.class` | Системный `$PATH` |
| **java** | JRE 8+ | Запуск R8/D8 | Системный `$PATH` |
| **r8.jar** | Android R8/D8 | Дексер (bytecode -> DEX) | `tools/r8.jar` |
| **android.jar** | API 24+ (актуально API 28) | Android Framework Stubs | `tools/android.jar` |
| **zipalign** | Любая (опционально) | Быстрое нативное выравнивание | Системный `$PATH` |
| **apksigner** | 0.9+ | Подпись и проверка APK | Системный `$PATH` |
| **debug.keystore**| JKS/PKCS12 | Тестовый сертификат подписи | `tools/debug.keystore` |

---

## 5. Использование

```bash
# Стандартная сборка релизного APK (dist/antigravity.apk, порт 45157; требует AGY_RELEASE_* или --allow-debug-sign):
bash modules/builder/build_apk.sh

# Сборка dev-версии с debug-подписью (dist/antigravity-dev.apk, порт 45158):
bash modules/builder/build_apk.sh --dev

# Запуск тестов модуля builder:
bash modules/builder/tests/run_all.sh

# Проверка консистентности версий (config/app.env, README, RELEASE_NOTES.md, GITHUB_REF):
python3 modules/builder/check_versions.py
```

---

## 6. Результаты аудита: баги, костыли и оптимизации

### 1. Критический баг в `align.py` (исправлен)
- **Проблема**: В чистой Python-реализации `align_with_python` для каждого элемента вызывался метод `zin.read()`. Этот метод распаковывает сжатые потоки `ZIP_DEFLATED`. Затем эти распакованные байты записывались в выходной файл с исходными заголовками `compress_type=8` и размером `compress_size`. В результате при отсутствии бинарника `zipalign` любой собранный APK был полностью поврежден (`zlib.error: invalid stored block lengths`).
- **Исправление**: Метод переписан на прямое чтение сырого потока сжатых байт (`raw payload`) по смещению локального заголовка с буферизацией чанками по 64 КБ. Повреждение данных устранено, потребление RAM снижено с O(N) до O(1).

### 2. Загрязнение исходного кода (`gen.xml`) (исправлено)
- **Проблема**: `01_gen_config.py` записывал `gen.xml` напрямую в каталог репозитория `app/src/main/res/values/gen.xml`, что приводило к загрязнению `git status` и риску случайного коммита dev-конфигурации.
- **Исправление**: Перенос генерации в дерево сборки `$BUILD_DIR/res/values/gen.xml`. В `02_r_java.sh` и `06_package.sh` добавлена поддержка ресурсного оверлея `-S "$BUILD_DIR/res" -S "$RES_DIR" --auto-add-overlay`, благодаря чему сгенерированные значения подставляются динамически, а рабочее дерево Git остается чистым.

### 3. Двойной прогон `aapt` в `02_r_java.sh` (исправлено)
- **Проблема**: В релизном режиме `aapt` запускался дважды с идентичными параметрами (`com.agy`), создавая избыточную нагрузку на CPU.
- **Исправление**: В `02_r_java.sh` добавлена проверка манифеста (`PKG=$(sed -n -E 's/.*package="([^"]+)".*/\1/p' "$MANIFEST" | head -n 1)`). Если целевой пакет уже равен `com.agy`, повторный запуск пропускается. Если пакет отличается (например, `com.agy.dev`), выполняется генерация обоих пакетов (`com.agy.dev.R` и совместимого фоллбэка `com.agy.R`).

### 4. Риск переполнения командной строки (`ARG_MAX`) (исправлено)
- **Проблема**: В `03_compile_java.sh` и `04_dex.sh` все исходные файлы и классы подставлялись массивами bash `${JAVA_SOURCES[@]}` и `${CLASS_FILES[@]}`. При масштабировании кодовой базы это вызывало системную ошибку ядра Linux `Argument list too long` (`E2BIG`).
- **Исправление**:
  - Для `javac` в `03_compile_java.sh`: сформирован аргументный файл `@$BUILD_DIR/java_sources.txt`, в который записываются все пути с экранированием.
  - Для `D8` в `04_dex.sh`: сканируется `$OBJ_DIR` и генерируется аргументный файл `@$BUILD_DIR/class_files.txt`, передаваемый в `com.android.tools.r8.D8` (устраняя ограничение длины команды и обходя несовместимость D8 с передачей сырых каталогов).

### 5. Множественные запуски JVM (JVM Overhead)
- **Проблема**: Конвейер запускает виртуальную машину Java 4 раза подряд (`javac`, `d8`, `apksigner sign`, `apksigner verify`). На мобильном CPU каждый запуск JVM занимает 0.8–1.5 секунды.
- **Решение**: Объединение фаз подписи и верификации, использование объединенных скриптов или демона компиляции.

### 6. Инкрементальный режим `--fast` (реализовано)
- **Проблема**: Ранее флаг `--fast` не использовался дочерними скриптами, а `build_apk.sh` полностью удалял `build/` при каждом запуске, повторно копируя и перепаковывая ~240 МБ ассетов.
- **Решение**: При `--fast` (`FAST_BUILD=1`) `build_apk.sh` сохраняет кэш `build/staging/assets/{runtime,env}`. `05_stage_assets.py` переиспользует их при совпадении `TARGET_SHA`, верификации `manifest.json` (`can_reuse_staged_runtime`) и неизменности mtime исходников (`env_assets_need_rebuild`, `can_reuse_staged_env`), а `06_package.sh` передаёт `--fast-store` в `align.py --package` для упаковки ассетов и `classes.dex` с `ZIP_STORED`.

### 7. Однопроходная сборка и выравнивание (реализовано)
- **Проблема**: Полная запись APK на диск ранее выполнялась 3 раза подряд (`unaligned.apk` -> `aligned.apk` -> подписанный APK), создавая избыточную нагрузку на flash-память и время на ZipAlign.
- **Решение**: `06_package.sh` упаковывает ресурсы через `aapt` во временный `$OUT_APK.base.tmp`, затем `align.py --package` (`package_aligned_apk`) в один поток дописывает выровненные на 4B/4KB `classes.dex` и ассеты прямо в `$BUILD_DIR/aligned.apk`, а Фаза 7 верифицирует результат через `align.py --check` (`check_alignment`).

### 8. Интеграция веб-сборщика и предотвращение утечки манифестов (исправлено)
- **Проблема**: В `05_stage_assets.py` веб-ассеты копировались «как есть» из `web/` без предварительного запуска сборки, из-за чего изменения в `web/src/` могли не попадать в APK. Кроме того, в фильтре `web_ignore` отсутствовал `src.manifest`, в результате чего манифест исходников попадал в ресурсы APK.
- **Исправление**: В `05_stage_assets.py` внедрен автоматический запуск `bash web/build.sh` перед стадированием ассетов, а файл `src.manifest` добавлен в список игнорирования `web_ignore`.

---

## Interface
- `[build_apk.sh:DEV_MODE]` / `[build_apk.sh:FAST_BUILD]` / `[build_apk.sh:ALLOW_DEBUG_SIGN]` / `[build_apk.sh:OUT_APK]`: Master 8-phase APK build orchestrator (`--dev|-d`, `--allow-debug-sign`, `--fast`, `--prefix`, `--out|-o`); sources `config/tools.env`, resolves `KEYSTORE`/`KEY_ALIAS`/`KEY_PASS`/`STORE_PASS` (or `DEV_*`), and exports `PROJECT_ROOT`, `BUILD_DIR`, `DIST_DIR`, `APP_DIR`, `TOOLS_DIR`, `DEV_MODE`, `FAST_BUILD`, `ALLOW_DEBUG_SIGN`, `CUSTOM_PREFIX`, `CUSTOM_OUT`, `AGY_SIGN_KEY_PASS`, `AGY_SIGN_STORE_PASS`.
- `[01_gen_config.py:parse_env]` / `[01_gen_config.py:java_string]` / `[01_gen_config.py:main]`: CLI (`01_gen_config.py [build_dir]`) reading `DEV_MODE` and `config/app.env` (`PKG`, `PKG_DEV`, `PORT`, `PORT_DEV`, `GATEWAY_PORT`, `GATEWAY_PORT_DEV`, `CORE_VERSION`, `APP_VERSION_NAME`, `APP_VERSION_CODE`, `OAUTH_SCHEME`, `APP_NAME`, `APP_NAME_DEV`, `MIN_API`, `TARGET_SDK`) to emit `BuildConfig.java`, `gen.xml`, and `build/AndroidManifest.xml`.
- `[02_r_java.sh:GEN_DIR]` / `[02_r_java.sh:PKG]`: Accepts positional args (`GEN_DIR`, `MANIFEST`, `RES_DIR`, `ANDROID_JAR`, `BUILD_RES_DIR`) and runs `aapt package -m -J` with overlay support.
- `[03_compile_java.sh:OBJ_DIR]` / `[03_compile_java.sh:ARG_FILE]`: Accepts (`OBJ_DIR`, `GEN_DIR`, `SRC_DIR`, `ANDROID_JAR`), writes `@$BUILD_DIR/java_sources.txt`, and compiles via `javac`.
- `[04_dex.sh:DEX_DIR]` / `[04_dex.sh:CLASS_LIST]` / `[04_dex.sh:MIN_API]`: Accepts (`DEX_DIR`, `OBJ_DIR`, `R8_JAR`, `ANDROID_JAR`, `MIN_API` default `24`), writes `@$BUILD_DIR/class_files.txt`, and invokes `com.android.tools.r8.D8`.
- `[05_stage_assets.py:stage_assets]` / `[05_stage_assets.py:can_reuse_staged_runtime]` / `[05_stage_assets.py:env_assets_need_rebuild]` / `[05_stage_assets.py:can_reuse_staged_env]` / `[05_stage_assets.py:main]`: Stages `runtime/` (via `runner.prepare_runtime.prepare`), `web/` (after `bash web/build.sh`), and `env/` (rebuilding via `bash env/build.sh assets` if stale/missing) into `<staging_dir>/assets`, honoring `FAST_BUILD=1` / `--fast`.
- `[06_package.sh:OUT_APK]` / `[06_package.sh:BASE_APK]` / `[06_package.sh:PACKAGE_FLAGS]`: Accepts (`OUT_APK`, `MANIFEST`, `RES_DIR`, `ANDROID_JAR`, `STAGE_ASSETS`, `DEX_FILE`, `BUILD_RES_DIR`), packs resources into `$OUT_APK.base.tmp` via `aapt package -f`, and invokes `align.py --package` (with `--fast-store` when `FAST_BUILD=1`).
- `[align.py:package_aligned_apk]` / `[align.py:check_alignment]` / `[align.py:align_with_binary]` / `[align.py:dostime]` / `[align.py:align_with_python]` / `[align.py:main]`: CLI supporting `<in_apk> <out_apk> [alignment]`, `--package <base_apk> <out_apk> <dex_path> <assets_dir> [--alignment N] [--fast-store]`, and `--check <apk_path> [alignment]` (4B general / 4096B stored `.so`).
- `[08_sign.sh:ALIGNED_APK]` / `[08_sign.sh:OUT_APK]` / `[08_sign.sh:KEYSTORE_PATH]`: Accepts (`ALIGNED_APK`, `OUT_APK`, `KEYSTORE_PATH`, `KEY_ALIAS_VAL`, `AGY_SIGN_KEY_PASS`, `AGY_SIGN_STORE_PASS`), sources `config/tools.env`, and signs via `apksigner sign`.
- `[09_verify.sh:APK]`: Validates signature (`APK`, default `$DIST_DIR/antigravity.apk`) via `apksigner verify --verbose`.
- `[check_versions.py:parse_env]` / `[check_versions.py:read_text]` / `[check_versions.py:main]`: Standalone CLI (`python3 modules/builder/check_versions.py`) verifying version consistency across `config/app.env`, `README.md`, `README.ru.md`, `RELEASE_NOTES.md`, and optional `GITHUB_REF`.

## Invariants
- `[build_apk.sh:BUILD_DIR]`: Requires `python3`, `aapt`, `javac`, `java`, and `apksigner` on `PATH` (`zipalign` is optional via `align.py`); unknown CLI flags warn on `stderr` without aborting.
- `[build_apk.sh:KEYSTORE]` / `[build_apk.sh:ALLOW_DEBUG_SIGN]` / `[08_sign.sh:KEYSTORE_PATH]`: Release builds (`DEV_MODE=0`, `ALLOW_DEBUG_SIGN=0`) fail closed unless keystore path, alias, key pass, and store pass are non-empty and the keystore file exists; passwords reach `apksigner` exclusively via `env:AGY_SIGN_STORE_PASS` and `env:AGY_SIGN_KEY_PASS`.
- `[build_apk.sh:FAST_BUILD]` / `[05_stage_assets.py:can_reuse_staged_runtime]` / `[05_stage_assets.py:can_reuse_staged_env]`: `FAST_BUILD=1` preserves `build/staging/assets/{runtime,env}` across cleanups and reuses them only when `manifest.json` + SHA-256 verification and source mtimes match; `web/` is still rebuilt and re-staged every run.
- `[build_apk.sh:SIGN_KEYSTORE]` / `[04_dex.sh:MIN_API]`: Phase 4 passes `MIN_API=24` to `04_dex.sh`.
- `[01_gen_config.py:main]`: `BuildConfig.java` is always emitted in base package `pkg_base` (`com.agy`) even in `DEV_MODE=1`; dev manifest rewriting uses literal replacements for `package="com.agy"`, `com.agy.permission.INTERNAL_BROADCAST`, and `<application`.
- `[02_r_java.sh:BUILD_RES_DIR]`: When `BUILD_RES_DIR` exists, `-S "$BUILD_RES_DIR"` precedes `-S "$RES_DIR"` with `--auto-add-overlay`; if the manifest package is not `com.agy`, a second `aapt` pass with `--custom-package com.agy` guarantees `com.agy.R` exists.
- `[03_compile_java.sh:ARG_FILE]`: Requires at least one `.java` file in both `SRC_DIR` and `GEN_DIR` and compiles strictly with `-encoding UTF-8 -source 1.8 -target 1.8 -Xlint:all,-deprecation,-options,-serial -Werror`.
- `[04_dex.sh:CLASS_LIST]`: Requires at least one `.class` file under `OBJ_DIR`, passes inputs via `@$BUILD_DIR/class_files.txt`, and verifies `$DEX_DIR/classes.dex` exists afterward.
- `[05_stage_assets.py:stage_assets]`: Raises `RuntimeError` if `prepare_runtime.prepare` returns falsy; `web_ignore` excludes exactly `("main.js", "src", "tests", "build.sh", "patch_main.py", "src.manifest")` at top-level `web/`.
- `[06_package.sh:BASE_APK]` / `[align.py:package_aligned_apk]`: In default mode `classes.dex` uses `ZIP_DEFLATED` and stored suffixes (`_STORED_SUFFIXES = (".gz", ".tgz", ".zip", ".png", ".jpg")`) use `ZIP_STORED`; when `--fast-store` is set (`FAST_BUILD=1`), `classes.dex` and all staged assets are stored uncompressed (`ZIP_STORED`) and aligned in one pass.
- `[align.py:align_with_python]` / `[align.py:check_alignment]`: Aligns stored `.so` entries (`compress_type == 0`) to `4096` bytes and other stored entries to `alignment` bytes (default `4`) via `0xd9d5` extra-field padding while copying raw compressed payloads verbatim in 64 KiB chunks; Phase 7 verifies via `check_alignment` and only rewrites `aligned.apk` if an alignment violation is found.
- `[check_versions.py:main]`: Requires non-empty `APP_VERSION_NAME` and `CORE_VERSION` in `config/app.env`; validates `badge/App-v{app_ver}-` and `badge/Core-v{core_ver}-` in `README.md`, `badge/Версия-{app_ver}-` and `badge/Ядро-v{core_ver}-` in `README.ru.md`, `v{app_ver}` on line 1 of `RELEASE_NOTES.md`, and `refs/tags/v{app_ver}` when `GITHUB_REF` starts with `refs/tags/v`.

## Side Effects
- `[build_apk.sh:PROJECT_ROOT]`: Sources `config/tools.env`, exports build environment variables (updating `HOME`/`PATH` if `/data/user/0/com.agy.dev/files` or `/data/user/0/com.agy/files` is writable), cleans `build/` (keeping `staging/assets/{runtime,env}` when `FAST_BUILD=1`) and recreates `dist/`, and executes all 8 pipeline phases.
- `[01_gen_config.py:main]`: Creates `<build_dir>/gen/<pkg_base>/` and `<build_dir>/res/values/` and writes `BuildConfig.java`, `gen.xml`, and `<build_dir>/AndroidManifest.xml`.
- `[02_r_java.sh:GEN_DIR]` / `[03_compile_java.sh:OBJ_DIR]` / `[04_dex.sh:DEX_DIR]`: Exports `ANDROID_DATA`, writes `@java_sources.txt` and `@class_files.txt`, and invokes `aapt`, `javac`, and `java` (`D8`) generating `R.java`, `.class` files, and `classes.dex`.
- `[05_stage_assets.py:stage_assets]`: Prepends `modules/` to `sys.path`, conditionally reuses or rebuilds `runtime/` and `env/` (`prepare_runtime.prepare`, `bash env/build.sh assets`), runs `bash web/build.sh`, and updates `<staging_dir>/assets/{runtime,web,env}`.
- `[06_package.sh:OUT_APK]` / `[align.py:package_aligned_apk]` / `[08_sign.sh:OUT_APK]`: Runs `aapt package` to temporary `BASE_APK` (removed via `EXIT` trap), streams `aligned.apk` in one pass via `align.py --package`, verifies alignment via `align.py --check`, and invokes `apksigner sign` writing `OUT_APK`.
- `[09_verify.sh:APK]` / `[check_versions.py:main]`: Read-only verification writing results/errors to `stdout`/`stderr` and exiting `0` or `1`.

## Verification
- Run: `bash modules/builder/tests/run_all.sh`
- Run: `python3 modules/builder/check_versions.py`
