# Output Templates

## Overview
Reference Bash template (`template_output.sh`) showing how to implement a custom AGY event bus output sink that reads a single JSON event envelope line from stdin, extracts top-level `event` and `source` fields, and writes a formatted dispatch line to stdout.

## Interface
- `[template_output.sh:RAW_JSON]` — stdin input variable populated via `read -r RAW_JSON` (no CLI arguments or environment variables).
- `[template_output.sh:EVENT_NAME]` — extracted string value of the first `"event"` key in `RAW_JSON`.
- `[template_output.sh:SOURCE]` — extracted string value of the first `"source"` key in `RAW_JSON`.
- `[template_output.sh:echo]` — writes `[CustomOutput] Dispatched event: $EVENT_NAME from $SOURCE` to `stdout`.
- `[template_output.sh:exit]` — exits with status code `0`.

## Invariants
- `[template_output.sh:read]` Reads at most one raw line (`read -r RAW_JSON`) and exits `0` on read failure or EOF (`|| exit 0`).
- `[template_output.sh:RAW_JSON]` Terminates immediately with exit code `0` when `RAW_JSON` is empty (`[ -z "$RAW_JSON" ]`).
- `[template_output.sh:EVENT_NAME]` / `[template_output.sh:SOURCE]` Extract only the first matching string value (`head -n 1`) via `grep -o` and `cut -d'"' -f4`.
- `[template_output.sh:exit]` Every explicit exit path terminates with status `0`.

## Side Effects
- `[template_output.sh:read]` Consumes one line from `stdin`.
- `[template_output.sh:EVENT_NAME]` / `[template_output.sh:SOURCE]` Spawn `grep`, `head`, and `cut` subprocesses to parse `RAW_JSON`.
- `[template_output.sh:echo]` Writes the formatted dispatch line to `stdout` when `RAW_JSON` is non-empty.

## Verification
- Run: `bash modules/io/tests/run_all.sh`
