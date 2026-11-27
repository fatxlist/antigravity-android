# Web Workers & Runtime Assets (`web/workers`)

## Overview
Browser worker and runtime asset directory hosting the Closure-to-ESM PDF.js loader bridge (`pdfjs_loader.mjs`), vendored Mozilla PDF.js v5.3.93 runtime/worker bundles (`pdf.min.mjs`, `pdf.worker.mjs`), and the Closure-compiled Shiki v4 syntax-highlighting Web Worker (`shiki_worker.js`) with 42 statically embedded language grammars and streaming chunked tokenization.

## Interface
- `[pdfjs_loader.mjs:globalThis['__resolvePdfjs']]` One-shot Closure-to-ESM bridge callback invoked with the full `./pdf.min.mjs` ES module namespace when defined as a function on `globalThis`.
- `[pdf.min.mjs:pdfjsVersion]` Vendored Mozilla PDF.js ESM core runtime (`pdfjsVersion = 5.3.93`, `pdfjsBuild = cbeef3233`, Apache-2.0).
- `[pdf.worker.mjs:pdfjsVersion]` Vendored Mozilla PDF.js worker runtime (`pdfjsVersion = 5.3.93`, `pdfjsBuild = cbeef3233`, Apache-2.0).
- `[shiki_worker.js:shikiWorker.AUIT_SYNTAX_THEME]` Exported Shiki CSS-variables theme (`name: "auit-syntax"`, `variablePrefix: "--syntax-"`, `editor.foreground: "var(--syntax-default-fg)"`, italic/bold rules, and 18 scope mappings from `[shiki_worker.js:shikiWorker_TOKEN_COLOR_SPECS]`).
- `[shiki_worker.js:shikiWorker.resolveShikiLanguage]` Exported `(language, filePath) => string | null` resolver mapping basenames (`[shiki_worker.js:shikiWorker_BASENAME_MAP]`, `dockerfile.*`, `makefile.*`), extensions, and aliases (`[shiki_worker.js:shikiWorker_ALIAS_MAP]`) onto the 42 IDs in `[shiki_worker.js:shikiWorker_SUPPORTED_LANGS]`.
- `[shiki_worker.js:shikiWorker.ShikiWorkerScope]` Exported worker scope type/symbol on `module$exports$google3$third_party$gemini_coder$agent_ui_toolkit$dev$tokenization$shikiWorker`.
- `[shiki_worker.js:shikiWorker.attachShikiWorkerScope]` Exported worker binder handling `{ type: "tokenize", requestId, content, language, filePath, priorityStartLine, priorityEndLine }` and `{ type: "cancel", requestId }` messages, and posting `{ type: "ready" }` plus per-request `{ type: "priority" | "chunk" | "unsupported" | "error", requestId, ... }` responses.
- `[shiki_worker.js:shikiWorker_STATIC_LANG_BUNDLES]` 42 statically embedded Shiki v4 language bundles (including `[shiki_worker.js:shikiWorker_rmdMarkdownLang]`, extending `markdown`'s R fenced-code regex for `{r}`/`{.r}`).

## Invariants
- `[pdfjs_loader.mjs:globalThis['__resolvePdfjs']]` Strictly one-shot and guarded by `typeof resolve === 'function'`: deletes `globalThis['__resolvePdfjs']` before invoking `resolve(pdfjs)` and silently no-ops when absent.
- `[shiki_worker.js:shikiWorker_MAX_BYTES]` Empty inputs or inputs exceeding `1048576` UTF-16 code units bypass Shiki and synchronously emit a single plain-text `{ type: "chunk", startLine: 1, done: true }` message.
- `[shiki_worker.js:shikiWorker_PRISM_ONLY_BASENAMES]` / `[shiki_worker.js:shikiWorker_PRISM_ONLY_EXTENSIONS]` `resolveShikiLanguage` checks `filePath` (stripping `?`/`#`) before `language` fallback and returns `null` for basename `metadata`, extensions `textproto`/`pbtxt`/`pbtext`/`txtpb`/`asciipb`/`pi`, and empty/`text`/`plaintext` inputs.
- `[shiki_worker.js:shikiWorker_getHighlighter]` Initializes Shiki with `createJavaScriptRegexEngine({ forgiving: true })` and resets `shikiWorker_highlighterPromise = null` on rejection so subsequent jobs can retry.
- `[shiki_worker.js:shikiWorker_toStyledLine]` Strips trailing `\r` (`[shiki_worker.js:shikiWorker_stripCr]`) prior to `codeToTokens` and restores `\r` on the final token of CRLF lines; assigns semantic token `type` only via `[shiki_worker.js:shikiWorker_COLOR_TO_TYPE]` (`comment`, `string`, or `""`) and formats inline CSS `style` from color and `fontStyle` bitmask (`1` italic, `2` bold, `4` underline).
- `[shiki_worker.js:shikiWorker_runTokenizeJob]` Priority pass clamps `[priorityStartLine - 80, (priorityEndLine ?? priorityStartLine) + 80]` (`[shiki_worker.js:shikiWorker_PRIO_PAD] = 80`) to `[1, lineCount]` with up to 40 preceding lines as `grammarContextCode`; full tokenization streams in `[shiki_worker.js:shikiWorker_CHUNK_LINES] = 500`-line chunks with sequential `grammarState` handoff and `setTimeout(..., 0)` yields.
- `[shiki_worker.js:shikiWorker.attachShikiWorkerScope]` Ignores messages unless `[shiki_worker.js:shikiWorker_isIncomingMessage]` holds and `typeof requestId === "number"`; `cancel` deletes `requestId` from the active `Set` to abort between chunks and suppress error reporting, and `finally` always removes `requestId`.
- `[shiki_worker.js:shikiWorker_isWorkerScope]` Auto-attaches to `globalThis` only when `Reflect.get(globalThis, "WorkerGlobalScope")` is a function and `globalThis instanceof WorkerGlobalScope`.
- `[shiki_worker.js:COMPILED]` Closure preamble pins `$jscomp.ASSUME_ES2020 = true`, `$jscomp.ISOLATE_POLYFILLS = false`, `COMPILED = true`, `goog.FEATURESET_YEAR = 2024`, `goog.DEBUG = true`, and `goog.DISALLOW_TEST_ONLY_CODE = false`.

## Side Effects
- `[pdfjs_loader.mjs:pdfjs]` Top-level ES module import evaluates `./pdf.min.mjs`.
- `[pdfjs_loader.mjs:globalThis['__resolvePdfjs']]` Deletes `globalThis['__resolvePdfjs']` and synchronously invokes `resolve(pdfjs)` on module evaluation when defined.
- `[shiki_worker.js:$jscomp.polyfill]` With `$jscomp.ISOLATE_POLYFILLS = false`, conditionally installs unisolated polyfills (`Symbol.dispose`, `SuppressedError`, `Iterator`, `Iterator.prototype.toArray`, `Iterator.prototype.map`) onto `globalThis`/prototypes and defines top-level `$jscomp`, `COMPILED`, and `goog` globals.
- `[shiki_worker.js:shikiWorker.attachShikiWorkerScope]` Inside a `WorkerGlobalScope`, assigns `globalThis.onmessage` and immediately posts `{ type: "ready" }`.
- `[shiki_worker.js:shikiWorker_runTokenizeJob]` Lazily caches the singleton highlighter (`shikiWorker_highlighterPromise`), schedules macrotask `setTimeout(..., 0)` yields between chunks, mutates the active-job `Set`, and calls `scope.postMessage` with `priority`/`chunk`/`unsupported`/`error` payloads.

## Verification
- Run: `bash web/build.sh && bash web/tests/selftest.sh && node web/tests/test_web_patches.mjs`
