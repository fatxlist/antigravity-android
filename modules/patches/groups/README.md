# Patch Groups

## Overview
Collection of modular binary patch groups applied to the AArch64 `language_server` binary for Android compatibility. Each child package implements an idempotent `apply(data: bytearray, verify: bool = False, dry_run: bool = False, allow_missing: bool = False) -> Tuple[bool, Dict[str, Any]]` contract for its target subsystem.

## Subdirectories & Boundaries
- [Failfast](./failfast/README.md) - Bypasses ARMv8.0-A preinit CPU feature check and preinit atomic flag initialization sequences (`crash_suppressor.py`).
- [PTY Shell](./pty_shell/README.md) - Length-preserving 9-byte in-place replacement of `/bin/bash` with `/bin///sh` for the PTY spawner (`pty_shell.py`).
- [Resolv Redirect](./resolv/README.md) - Length-preserving 16-byte in-place rewrite of `/etc/resolv.conf` to relative `etc//resolv.conf` with adjacent-character filtering (`resolv_redirect.py`).
- [SIGILL Handler](./sigill/README.md) - Patches Go `rt_sigaction` to preserve the `LD_PRELOAD` `SIGILL` handler for `liblse_compat.so` (`sigill_handler.py`).
- [Syscalls](./syscalls/README.md) - Retargets Android-seccomp-trapped `syscall.faccessat2` (`439 -> 48`) and `syscall.fchmodat2` (`452 -> 53`) `MOVZ X0` instructions via ELF and Go `pclntab` lookup (`faccessat.py`, `fchmodat.py`).
