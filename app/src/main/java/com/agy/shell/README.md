# Shell (`com.agy.shell`)

## Overview
Provides rootless elevated shell command execution by locating and invoking the bundled Shizuku `rish` binary as a child process without linking any Shizuku Java API library. `RishLocator` resolves the `rish` executable path under app storage, `RishExecutor` runs commands with sanitized environment variables, timeouts, and bounded output capture, and `ShizukuBridge` exposes a compact facade over both.

## Interface
- `public final class RishLocator` with `public RishLocator(Context context)` — locates the bundled `rish` binary relative to `appContext.getFilesDir()` `[RishLocator.java:RishLocator]`
- `public File rishBinary()` — searches `runtime/bin/rish`, `usr/bin/rish`, and `bin/rish` under `getFilesDir()` in order, returning the first existing file or defaulting to `runtime/bin/rish` `[RishLocator.java:rishBinary]`
- `public final class RishExecutor` with `public RishExecutor(Context context)` — executes commands via `rish -c` with merged stdout/stderr, a 60-second timeout, and a 256 KiB output cap `[RishExecutor.java:RishExecutor]`
- `public String exec(String cmd)` — validates `cmd` and binary availability, runs `rish -c "env -u LD_LIBRARY_PATH " + cmd`, and returns trimmed UTF-8 output or an `"error: ..."` message `[RishExecutor.java:exec]`
- `public final class ShizukuBridge` with `public ShizukuBridge(Context context)` — facade composing `RishLocator` and `RishExecutor` `[ShizukuBridge.java:ShizukuBridge]`
- `public boolean isAvailable()` — returns `true` when `locator.rishBinary()` exists and is executable `[ShizukuBridge.java:isAvailable]`
- `public String exec(String cmd)` — delegates directly to `RishExecutor.exec(cmd)` `[ShizukuBridge.java:exec]`

## Invariants
- `RishLocator` and `RishExecutor` retain `context.getApplicationContext()` rather than the caller's `Context` `[RishLocator.java:RishLocator]` `[RishExecutor.java:RishExecutor]`
- `RishLocator.rishBinary()` checks `runtime/bin/rish`, `usr/bin/rish`, then `bin/rish` strictly in order and always returns a non-null `File` (falling back to `runtime/bin/rish`) `[RishLocator.java:rishBinary]`
- `RishExecutor.exec(String)` immediately rejects null, empty, or whitespace-only commands with `"error: empty command"`, and requires `rish.isFile()` and `rish.canExecute()` before launching `[RishExecutor.java:exec]`
- `ShizukuBridge.isAvailable()` checks `rish.exists() && rish.canExecute()`, which does not enforce `isFile()` unlike `RishExecutor.exec(String)` `[ShizukuBridge.java:isAvailable]` `[RishExecutor.java:exec]`
- `LD_LIBRARY_PATH` is stripped from both `ProcessBuilder.environment()` and the shell command prefix (`env -u LD_LIBRARY_PATH `); `RISH_APPLICATION_ID` is set to `appContext.getPackageName()`, working directory is `appContext.getFilesDir()`, and `redirectErrorStream(true)` merges stderr into stdout `[RishExecutor.java:exec]`
- Process execution is bounded by `TIMEOUT_SECONDS = 60` (`process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)`); timeouts forcibly destroy the process, interrupt/join the drain thread up to 1000 ms, and return `"error: rish timed out after 60s"` `[RishExecutor.java:TIMEOUT_SECONDS]` `[RishExecutor.java:exec]`
- Captured output is bounded by `MAX_OUTPUT_BYTES = 256 * 1024` bytes on daemon thread `"RishExecutor-drain"` (which continues draining excess bytes to avoid pipe blocking) and appends `\n[output truncated]` (or `"[output truncated]"` if empty) when truncated `[RishExecutor.java:MAX_OUTPUT_BYTES]` `[RishExecutor.java:exec]`
- Non-empty trimmed output is returned verbatim even on non-zero exit codes; `"error: rish exited <code>"` is returned only when `code != 0 && text.isEmpty()`, and any thrown `Exception` returns `"error: " + e.getMessage()` `[RishExecutor.java:exec]`
- The `finally` block unconditionally interrupts the drain thread and calls `process.destroyForcibly()` if non-null `[RishExecutor.java:exec]`

## Side Effects
- Probes filesystem existence/executability metadata on candidate `rish` paths under `appContext.getFilesDir()` `[RishLocator.java:rishBinary]` `[ShizukuBridge.java:isAvailable]` `[RishExecutor.java:exec]`
- Spawns a child OS process (`rish -c "env -u LD_LIBRARY_PATH " + cmd`) with elevated Shizuku shell privileges, starts and interrupts daemon thread `"RishExecutor-drain"`, and forcibly terminates the subprocess during timeout or `finally` cleanup `[RishExecutor.java:exec]`

## Verification
- Run: `bash app/src/test/run_java_tests.sh`
