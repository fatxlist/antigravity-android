#!/usr/bin/env python3
"""
align.py - 4-byte/4KB APK ZipAlign utility and single-pass aligned APK packager.
Uses system zipalign when re-aligning existing archives if available; otherwise
performs pure-Python alignment and direct aligned streaming from base APK + assets.
"""
import argparse
import os
import shutil
import struct
import subprocess
import sys
import time
import zipfile
import zlib

_STORED_SUFFIXES = (".gz", ".tgz", ".zip", ".png", ".jpg")
_CHUNK_SIZE = 65536


def align_with_binary(in_apk, out_apk, alignment=4):
    if shutil.which("zipalign"):
        res = subprocess.run(["zipalign", "-f", "-p", str(alignment), in_apk, out_apk], capture_output=True)
        return res.returncode == 0
    return False


def dostime(dt):
    year, month, day, hour, minute, second = dt
    year = max(1980, year)
    dosdate = max(0, min(65535, ((year - 1980) << 9) | (month << 5) | day))
    dostime_val = max(0, min(65535, (hour << 11) | (minute << 5) | (second // 2)))
    return dostime_val, dosdate


def _entry_alignment(filename, compress_type, default_alignment):
    if compress_type != zipfile.ZIP_STORED:
        return 1
    return 4096 if filename.endswith(".so") else default_alignment


def _pad_extra_for_alignment(local_header_offset, filename_len, extra, entry_align):
    extra_buf = bytearray(extra or b"")
    if entry_align <= 1:
        return extra_buf
    data_pos = local_header_offset + 30 + filename_len + len(extra_buf)
    rem = data_pos % entry_align
    if rem != 0:
        pad_len = entry_align - rem
        if pad_len < 4:
            pad_len += entry_align
        extra_buf.extend(b"\xd5\xd9" + struct.pack("<H", pad_len - 4) + (b"\x00" * (pad_len - 4)))
    return extra_buf


def _write_central_directory_and_eocd(fout, central_dirs):
    cd_start = fout.tell()
    for info, filename_bytes, extra, local_header_offset in central_dirs:
        mtime, mdate = dostime(info.date_time)
        cd_header = struct.pack(
            "<IHHHHHHIIIHHHHHII",
            0x02014b50,
            info.create_version,
            info.extract_version,
            info.flag_bits,
            info.compress_type,
            mtime,
            mdate,
            info.CRC,
            info.compress_size,
            info.file_size,
            len(filename_bytes),
            len(extra),
            len(info.comment or b""),
            0,
            info.internal_attr,
            info.external_attr,
            local_header_offset,
        )
        fout.write(cd_header)
        fout.write(filename_bytes)
        fout.write(extra)
        if info.comment:
            fout.write(info.comment)

    cd_size = fout.tell() - cd_start
    eocd = struct.pack(
        "<IHHHHIIH",
        0x06054b50,
        0,
        0,
        len(central_dirs),
        len(central_dirs),
        cd_size,
        cd_start,
        0,
    )
    fout.write(eocd)


def _copy_zip_entry(zin_fin, info, fout, alignment):
    zin_fin.seek(info.header_offset)
    hdr = zin_fin.read(30)
    if len(hdr) < 30:
        raise ValueError(f"Corrupt local header at offset {info.header_offset}")
    _sig, _ver, _flag, _method, _t, _d, _crc, csize, _usize, nlen, elen = struct.unpack("<IHHHHHIIIHH", hdr)
    zin_fin.seek(info.header_offset + 30 + nlen + elen)

    entry_align = _entry_alignment(info.filename, info.compress_type, alignment)
    local_header_offset = fout.tell()
    filename_bytes = info.filename.encode("utf-8")
    extra = _pad_extra_for_alignment(local_header_offset, len(filename_bytes), info.extra, entry_align)

    mtime, mdate = dostime(info.date_time)
    header = struct.pack(
        "<IHHHHHIIIHH",
        0x04034b50,
        info.extract_version,
        info.flag_bits,
        info.compress_type,
        mtime,
        mdate,
        info.CRC,
        info.compress_size,
        info.file_size,
        len(filename_bytes),
        len(extra),
    )
    fout.write(header)
    fout.write(filename_bytes)
    fout.write(extra)

    bytes_left = csize
    while bytes_left > 0:
        chunk = zin_fin.read(min(bytes_left, _CHUNK_SIZE))
        if not chunk:
            raise IOError(f"Unexpected EOF while reading {info.filename}")
        fout.write(chunk)
        bytes_left -= len(chunk)

    return (info, filename_bytes, bytes(extra), local_header_offset)


def _stream_file_entry(src_path, arcname, comp_type, fout, alignment):
    st = os.stat(src_path)
    dt = time.localtime(st.st_mtime)[:6]
    if dt[0] < 1980:
        dt = (1980, 1, 1, 0, 0, 0)
    mtime, mdate = dostime(dt)

    try:
        filename_bytes = arcname.encode("ascii")
        flag_bits = 0
    except UnicodeEncodeError:
        filename_bytes = arcname.encode("utf-8")
        flag_bits = 0x0800

    entry_align = _entry_alignment(arcname, comp_type, alignment)
    local_header_offset = fout.tell()
    extra = bytes(_pad_extra_for_alignment(local_header_offset, len(filename_bytes), b"", entry_align))

    placeholder = struct.pack(
        "<IHHHHHIIIHH",
        0x04034b50,
        20,
        flag_bits,
        comp_type,
        mtime,
        mdate,
        0,
        0,
        0,
        len(filename_bytes),
        len(extra),
    )
    fout.write(placeholder)
    fout.write(filename_bytes)
    fout.write(extra)

    crc = 0
    usize = 0
    csize = 0
    compressor = zlib.compressobj(zlib.Z_DEFAULT_COMPRESSION, zlib.DEFLATED, -zlib.MAX_WBITS) if comp_type == zipfile.ZIP_DEFLATED else None
    with open(src_path, "rb") as f:
        while True:
            chunk = f.read(_CHUNK_SIZE)
            if not chunk:
                break
            usize += len(chunk)
            crc = zlib.crc32(chunk, crc)
            if compressor is not None:
                out_chunk = compressor.compress(chunk)
                if out_chunk:
                    fout.write(out_chunk)
                    csize += len(out_chunk)
            else:
                fout.write(chunk)
                csize += len(chunk)
    if compressor is not None:
        tail = compressor.flush()
        if tail:
            fout.write(tail)
            csize += len(tail)

    crc &= 0xFFFFFFFF
    end_pos = fout.tell()
    fout.seek(local_header_offset)
    patched = struct.pack(
        "<IHHHHHIIIHH",
        0x04034b50,
        20,
        flag_bits,
        comp_type,
        mtime,
        mdate,
        crc,
        csize,
        usize,
        len(filename_bytes),
        len(extra),
    )
    fout.write(patched)
    fout.seek(end_pos)

    info = zipfile.ZipInfo(arcname, date_time=dt)
    info.create_version = 20
    info.extract_version = 20
    info.flag_bits = flag_bits
    info.compress_type = comp_type
    info.CRC = crc
    info.compress_size = csize
    info.file_size = usize
    info.external_attr = (0o100644 & 0xFFFF) << 16
    return (info, filename_bytes, extra, local_header_offset)


def align_with_python(in_apk, out_apk, alignment=4):
    """
    Pure Python 4-byte/4KB zip alignment according to Android ZIP alignment specification.
    """
    tmp_out = out_apk + ".align.tmp"
    try:
        with zipfile.ZipFile(in_apk, "r") as zin, open(in_apk, "rb") as fin, open(tmp_out, "wb") as fout:
            central_dirs = [_copy_zip_entry(zin_fin=fin, info=info, fout=fout, alignment=alignment) for info in zin.infolist()]
            _write_central_directory_and_eocd(fout, central_dirs)
        os.replace(tmp_out, out_apk)
        return True
    except Exception:
        if os.path.exists(tmp_out):
            try:
                os.remove(tmp_out)
            except OSError:
                pass
        raise


def package_aligned_apk(base_apk, out_apk, dex_path, assets_dir, alignment=4, fast_store=False):
    """Stream base APK entries, classes.dex, and assets directly into a 4-byte/4KB aligned APK in one pass."""
    tmp_out = out_apk + ".pkg.tmp"
    try:
        with zipfile.ZipFile(base_apk, "r") as zbase, open(base_apk, "rb") as fbase, open(tmp_out, "wb") as fout:
            central_dirs = []
            seen = set()
            for info in zbase.infolist():
                seen.add(info.filename)
                central_dirs.append(_copy_zip_entry(fbase, info, fout, alignment))

            dex_comp = zipfile.ZIP_STORED if fast_store else zipfile.ZIP_DEFLATED
            if dex_path and os.path.isfile(dex_path) and "classes.dex" not in seen:
                central_dirs.append(_stream_file_entry(dex_path, "classes.dex", dex_comp, fout, alignment))
                seen.add("classes.dex")

            if assets_dir and os.path.isdir(assets_dir):
                staging_parent = os.path.dirname(os.path.abspath(assets_dir))
                for root, dirs, files in os.walk(assets_dir):
                    dirs.sort()
                    for fname in sorted(files):
                        full = os.path.join(root, fname)
                        rel = os.path.relpath(full, staging_parent).replace(os.sep, "/")
                        if rel in seen:
                            continue
                        seen.add(rel)
                        if fast_store or fname.endswith(_STORED_SUFFIXES):
                            comp = zipfile.ZIP_STORED
                        else:
                            comp = zipfile.ZIP_DEFLATED
                        central_dirs.append(_stream_file_entry(full, rel, comp, fout, alignment))

            _write_central_directory_and_eocd(fout, central_dirs)
        os.replace(tmp_out, out_apk)
        return True
    except Exception:
        if os.path.exists(tmp_out):
            try:
                os.remove(tmp_out)
            except OSError:
                pass
        raise


def check_alignment(apk_path, alignment=4):
    """Verify every uncompressed entry obeys 4KB (.so) or N-byte alignment. Returns (ok, errors)."""
    errors = []
    with zipfile.ZipFile(apk_path, "r") as zin, open(apk_path, "rb") as fin:
        for info in zin.infolist():
            if info.compress_type != zipfile.ZIP_STORED:
                continue
            fin.seek(info.header_offset)
            hdr = fin.read(30)
            if len(hdr) < 30:
                errors.append(f"{info.filename}: truncated local header")
                continue
            nlen, elen = struct.unpack("<HH", hdr[26:30])
            data_pos = info.header_offset + 30 + nlen + elen
            req_align = _entry_alignment(info.filename, info.compress_type, alignment)
            if req_align > 1 and (data_pos % req_align) != 0:
                errors.append(f"{info.filename}: offset {data_pos} not {req_align}-byte aligned")
    return (len(errors) == 0, errors)


def main():
    if len(sys.argv) >= 2 and sys.argv[1] == "--check":
        if len(sys.argv) < 3:
            print(f"Usage: {sys.argv[0]} --check <apk_path> [alignment]", file=sys.stderr)
            sys.exit(1)
        apk_path = sys.argv[2]
        alignment = int(sys.argv[3]) if len(sys.argv) > 3 else 4
        ok, errors = check_alignment(apk_path, alignment)
        if ok:
            print(f"[OK] Alignment verified ({alignment}B / 4096B .so): {apk_path}")
            sys.exit(0)
        for err in errors[:20]:
            print(f"[FAIL] {err}", file=sys.stderr)
        sys.exit(1)

    if len(sys.argv) >= 2 and sys.argv[1] == "--package":
        parser = argparse.ArgumentParser(prog=f"{sys.argv[0]} --package")
        parser.add_argument("base_apk")
        parser.add_argument("out_apk")
        parser.add_argument("dex_path")
        parser.add_argument("assets_dir")
        parser.add_argument("--alignment", type=int, default=4)
        parser.add_argument("--fast-store", action="store_true")
        args = parser.parse_args(sys.argv[2:])
        package_aligned_apk(args.base_apk, args.out_apk, args.dex_path, args.assets_dir, args.alignment, args.fast_store)
        print(f"[OK] Packaged and aligned APK in single pass: {args.out_apk}")
        sys.exit(0)

    if len(sys.argv) < 3:
        print(f"Usage: {sys.argv[0]} <in_apk> <out_apk> [alignment]", file=sys.stderr)
        sys.exit(1)

    in_apk = sys.argv[1]
    out_apk = sys.argv[2]
    alignment = int(sys.argv[3]) if len(sys.argv) > 3 else 4

    if not os.path.isfile(in_apk):
        print(f"Error: input file {in_apk} not found", file=sys.stderr)
        sys.exit(1)

    if align_with_binary(in_apk, out_apk, alignment):
        print(f"[OK] ZipAligned using binary: {out_apk}")
    else:
        align_with_python(in_apk, out_apk, alignment)
        print(f"[OK] ZipAligned using Python 4-byte aligner: {out_apk}")


if __name__ == "__main__":
    main()
