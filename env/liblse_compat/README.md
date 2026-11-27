# env/liblse_compat

## Overview
Builds a standalone AArch64 preloadable shared library (`liblse_compat.so`) compiled for baseline ARMv8.0-A (`-march=armv8-a -moutline-atomics`). At load time it installs a kernel `SIGILL` trap-and-emulate handler via raw `rt_sigaction` and interposes `sigaction` to prevent guest runtimes from overwriting `SIGILL`, software-emulating ARMv8.1–8.4 instructions (`FEAT_LSE`, `FEAT_LRCPC`, `FEAT_LRCPC2`, `FEAT_DotProd`, `FEAT_RDM`, `FEAT_FCMA`) on ARMv8.0-A CPUs.

## Interface
- `[liblse_compat.c:sigaction]` Sole exported (non-static) symbol: `int sigaction(int signum, const struct sigaction *act, struct sigaction *oldact)`; intercepts `SIGILL` registrations via shadow state `old_sigill_act` and forwards all other signals to `dlsym(RTLD_NEXT, "sigaction")`.
- `[liblse_compat.c:init_lse_compat]` Static library entrypoint marked `__attribute__((constructor(101)))` invoked automatically at shared-library load time.
- `[build.sh:CC]` Overridable C compiler environment variable defaulting to `clang`.
- `[build.sh:CFLAGS]` Overridable compiler/linker flags defaulting to `"-march=armv8-a -moutline-atomics -O2 -Wall -fPIC -shared"` with no explicit `-l*` library flags.
- `[build.sh:$DIR/liblse_compat.so]` Compiled shared-library artifact built from `$DIR/liblse_compat.c`.

## Invariants
- `[build.sh:set]` Enforces strict Bash execution via `set -euo pipefail`.
- `[build.sh:CFLAGS]` Targets baseline ARMv8.0-A with `-march=armv8-a -moutline-atomics` so internal `__atomic_*` primitives do not depend on hardware LSE instructions.
- `[liblse_compat.c:init_lse_compat]` Unconditionally installs the kernel `SIGILL` handler at constructor priority `101` without checking `HWCAP_ATOMICS` (defined but unused) and without copying the prior kernel disposition from `old_ksa` into `old_sigill_act`.
- `[liblse_compat.c:raw_rt_sigaction]` Invokes AArch64 syscall `134` (`__NR_rt_sigaction`) via inline `svc #0` with `x3=8`, installing `sigill_handler` with `SA_SIGINFO | SA_NODEFER | SA_ONSTACK`, `sa_restorer=NULL`, and `sa_mask=0`.
- `[liblse_compat.c:sigaction]` Keeps the kernel `SIGILL` handler pinned to `sigill_handler` by updating/reading only `old_sigill_act` and returning `0`; for non-`SIGILL` signals, returns `0` if `dlsym(RTLD_NEXT, "sigaction")` resolves to `NULL`.
- `[liblse_compat.c:sigill_handler]` Attempts emulators in strict order `emulate_lse -> emulate_dotprod -> emulate_rdm -> emulate_fcma`, advancing `uc->uc_mcontext.pc` by `4` bytes on any match.
- `[liblse_compat.c:get_reg]` and `[liblse_compat.c:set_reg]` Treat register index `31` as `uc->uc_mcontext.sp` when `is_sp` is true and `0ULL` (`XZR`/`WZR`) otherwise; discard writes to register `31` and zero-extend 8/16/32-bit writes (`sz=0/1/2`) to 64 bits.
- `[liblse_compat.c:emulate_lse]` Emulates `FEAT_LRCPC` (`LDAPR`/`LDAPRB`/`LDAPRH`), `FEAT_LSE` atomics (`LDADD`/`STADD`, `LDCLR`/`STCLR`, `LDEOR`/`STEOR`, `LDSET`/`STSET`, `LDSMAX`/`STSMAX`, `LDSMIN`/`STSMIN`, `LDUMAX`/`STUMAX`, `LDUMIN`/`STUMIN`, `SWP`, 8–64-bit `CAS*`, and even-pair 2×32-bit / 2×64-bit `CASP*` using `__ATOMIC_SEQ_CST`), and `FEAT_LRCPC2` unscaled `STLUR*`/`LDAPUR*` (9-bit signed offset with acquire/release fences).
- `[liblse_compat.c:emulate_dotprod]` Emulates vector and indexed-element `UDOT`/`SDOT` for 64-bit (`q=0`, upper 8 bytes zeroed) and 128-bit (`q=1`) SIMD vectors.
- `[liblse_compat.c:emulate_rdm]` Emulates vector and indexed-element `SQRDMLAH`/`SQRDMLSH` for 16-bit (`sz=1`, element `rm` masked to `V0`–`V15`) and 32-bit (`sz=2`) lanes only (no scalar form, no `FPSR.QC` update, upper 8 bytes zeroed when `q=0`).
- `[liblse_compat.c:emulate_fcma]` Emulates `FCADD`, vector `FCMLA`, and element `FCMLA` for `_Float16` (`sz=1`), `float` (`sz=2`), and 128-bit `double` (`sz=3` with `q=1`; element `FCMLA` handles only `sz=1`/`sz=2`; upper 8 bytes zeroed when `q=0`).
- `[liblse_compat.c:get_fpsimd_ctx]` Walks `uc->uc_mcontext.__reserved` records for `FPSIMD_MAGIC` (`0x46508001`), returning `NULL` (causing SIMD emulators to return `false`) if absent.

## Side Effects
- `[build.sh:$CC]` Compiles `liblse_compat.c` to `$DIR/liblse_compat.so` on disk and prints `"Built: $DIR/liblse_compat.so"` to stdout.
- `[liblse_compat.c:init_lse_compat]` Modifies process `SIGILL` kernel disposition via syscall `134`, sets `lse_handler_installed=true`, and writes diagnostic lines to stderr (fd `2`).
- `[liblse_compat.c:sigaction]` Mutates static `old_sigill_act` for `SIGILL` and lazily caches `real_sigaction` via `dlsym(RTLD_NEXT, "sigaction")` when forwarding non-`SIGILL` calls.
- `[liblse_compat.c:sigill_handler]` Executes atomic/volatile loads, stores, and RMWs against guest memory, mutates guest GPRs, FP/SIMD `vregs`, and `pc`, and on unhandled `SIGILL` writes PC/instruction hex to stderr before invoking chained `old_sigill_act` or resetting and re-raising `SIGILL`.

## Verification
- Run: `python3 -B -m unittest discover -s env/tests -v`
- Run: `bash env/build.sh assets && bash env/tests/invariant`
