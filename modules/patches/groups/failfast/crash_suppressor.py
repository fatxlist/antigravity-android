"""
crash_suppressor.py - Bypasses preinit CPU feature check and preinit atomic flag initialization for ARMv8.0-A CPUs.
"""
import os
import struct
import sys
from typing import Tuple, Dict, Any, List, Union

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PATCHES_ROOT = os.path.dirname(os.path.dirname(SCRIPT_DIR))
ENGINE_DIR = os.path.join(PATCHES_ROOT, "engine")
if ENGINE_DIR not in sys.path:
    sys.path.insert(0, ENGINE_DIR)

import elf_scanner  # noqa: E402

# Reference v2.35.2 sequences (32-byte CPU feature check, 28-byte preinit atomic init)
TARGET_CPU = bytes.fromhex("f30d01d00a609052683e40f9e903282a3f010aeae1020054e8033836e8045036")
REPLACEMENT_CPU = bytes.fromhex("190000141f2003d51f2003d51f2003d51f2003d51f2003d51f2003d51f2003d5")

TARGET_ATOMIC = bytes.fromhex("a80a01f008a12891290080520801e9f8480000b4c0035fd61f2003d5")
REPLACEMENT_ATOMIC = bytes.fromhex("a80a01f008a12891090140f92a008052490000b4c0035fd60a0100f9")

CPU_RAW_TAIL = bytes.fromhex("e903282a3f010aeae1020054e8033836e8045036")
CPU_EPILOGUE = bytes.fromhex("f30b40f9fd7bc2a8c0035fd6")

ATOMIC_RAW_TAIL = bytes.fromhex("290080520801e9f8480000b4c0035fd61f2003d5")
ATOMIC_PATCHED_TAIL = bytes.fromhex("090140f92a008052490000b4c0035fd60a0100f9")


def find_all(
    data: Union[bytes, bytearray, memoryview],
    pattern: bytes,
    start: int = 0,
    end: int = -1,
) -> List[int]:
    """Return non-overlapping ascending offsets of `pattern` in `data[start:end]`."""
    matches = []
    limit = len(data) if end < 0 else min(end, len(data))
    pos = max(0, start)
    while True:
        pos = data.find(pattern, pos, limit)
        if pos == -1:
            break
        matches.append(pos)
        pos += len(pattern)
    return matches


def _scan_bounds(data: Union[bytes, bytearray, memoryview]) -> Tuple[int, int, bool]:
    """Return `(scan_start, scan_end, is_elf)` scoped to `.text` for ELF64 AArch64 binaries."""
    if elf_scanner.is_valid_elf64(data, 0):
        text_info = elf_scanner.get_text_section(data, 0)
        if text_info is not None:
            return text_info[0], text_info[0] + text_info[1], True
    return 0, len(data), False


def _match_cpu_offsets(
    data: Union[bytes, bytearray, memoryview],
    scan_start: int,
    scan_end: int,
    is_elf: bool,
) -> Tuple[List[int], List[int]]:
    """Locate unpatched and patched preinit CPU feature-check sites."""
    raw_cpu: List[int] = []
    for tail_off in find_all(data, CPU_RAW_TAIL, scan_start, scan_end):
        pos = tail_off - 12
        if pos < scan_start or pos + 32 > scan_end:
            continue
        w0, w1, w2 = struct.unpack_from("<III", data, pos)
        if (w0 & 0x9F00001F) != 0x90000013:  # ADRP x19, <page>
            continue
        if w1 != 0x5290600A:  # MOVZ w10, #0x8300
            continue
        if (w2 & 0xFFC003FF) != 0xF9400268:  # LDR x8, [x19, #imm12]
            continue
        if is_elf or pos + 0x70 <= scan_end:
            if pos + 0x70 > scan_end or bytes(data[pos + 0x64 : pos + 0x70]) != CPU_EPILOGUE:
                continue
        raw_cpu.append(pos)

    repl_cpu: List[int] = []
    for pos in find_all(data, REPLACEMENT_CPU, scan_start, scan_end):
        if is_elf or pos + 0x70 <= scan_end:
            if pos + 0x70 > scan_end or bytes(data[pos + 0x64 : pos + 0x70]) != CPU_EPILOGUE:
                continue
        repl_cpu.append(pos)

    return raw_cpu, repl_cpu


def _match_atomic_tail(
    data: Union[bytes, bytearray, memoryview],
    tail: bytes,
    scan_start: int,
    scan_end: int,
) -> List[int]:
    """Locate preinit atomic sites matching `ADRP x8; ADD x8, x8, #imm12` followed by `tail`."""
    hits: List[int] = []
    for tail_off in find_all(data, tail, scan_start, scan_end):
        pos = tail_off - 8
        if pos < scan_start or pos + 28 > scan_end:
            continue
        w0, w1 = struct.unpack_from("<II", data, pos)
        if (w0 & 0x9F00001F) != 0x90000008:  # ADRP x8, <page>
            continue
        if (w1 & 0xFF0003FF) != 0x91000108:  # ADD x8, x8, #imm12
            continue
        hits.append(pos)
    return hits


def apply(data: bytearray, verify: bool = False, dry_run: bool = False, allow_missing: bool = False) -> Tuple[bool, Dict[str, Any]]:
    scan_start, scan_end, is_elf = _scan_bounds(data)
    raw_cpu, repl_cpu = _match_cpu_offsets(data, scan_start, scan_end, is_elf)
    raw_atomic = _match_atomic_tail(data, ATOMIC_RAW_TAIL, scan_start, scan_end)
    repl_atomic = _match_atomic_tail(data, ATOMIC_PATCHED_TAIL, scan_start, scan_end)

    info = {
        "name": "failfast_cpu_and_atomic",
        "cpu_raw": [hex(x) for x in raw_cpu],
        "cpu_patched": [hex(x) for x in repl_cpu],
        "atomic_raw": [hex(x) for x in raw_atomic],
        "atomic_patched": [hex(x) for x in repl_atomic]
    }

    cpu_total = len(raw_cpu) + len(repl_cpu)
    atomic_total = len(raw_atomic) + len(repl_atomic)

    if cpu_total == 0 or atomic_total == 0:
        if allow_missing:
            info["status"] = "skipped_missing"
            return True, info
        info["status"] = "missing_signature"
        return False, info

    if cpu_total != 1 or atomic_total != 1:
        info["status"] = f"unexpected_count_cpu_{cpu_total}_atomic_{atomic_total}"
        return False, info

    if verify:
        v_ok = (len(raw_cpu) == 0 and len(repl_cpu) == 1 and len(raw_atomic) == 0 and len(repl_atomic) == 1)
        info["status"] = "verified" if v_ok else "unverified"
        return v_ok, info

    if len(raw_cpu) == 0 and len(raw_atomic) == 0:
        info["status"] = "already_patched"
        return True, info

    if not dry_run:
        for pos in raw_cpu:
            data[pos:pos + len(REPLACEMENT_CPU)] = REPLACEMENT_CPU
        for pos in raw_atomic:
            # Preserve the target binary's ADRP x8 / ADD x8 prefix (first 8 bytes).
            data[pos + 8:pos + 8 + len(ATOMIC_PATCHED_TAIL)] = ATOMIC_PATCHED_TAIL
        info["status"] = "patched"
    else:
        info["status"] = "dry_run"

    return True, info
