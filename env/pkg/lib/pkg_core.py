#!/usr/bin/env python3
"""High-speed core engine for AGY pkg (index, vercmp, resolver, db, staged .deb install)."""

from __future__ import annotations

import argparse
import bz2
import glob
import gzip
import hashlib
import io
import lzma
import os
import posixpath
import re
import shutil
import stat
import subprocess
import sys
import tarfile
from typing import Dict, List, Optional, Tuple

sys.dont_write_bytecode = True

PREFIX_RE_BYTES = re.compile(rb"/data/(?:data|user/[0-9]+)/[^/\s'\";:<>()]*/files/usr")
PREFIX_RE_STR = re.compile(r"/data/(?:data|user/[0-9]+)/[^/\s'\";:<>()]*/files/usr")
PKG_NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9+._-]*$")
TERMUX_PKG_COMP_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9+._-]*$")
VER_CONSTRAINT_RE = re.compile(r"^([^\s(\[:]+)(?:\s*\(([^)]+)\))?")
DPKG_META_RE = re.compile(r"^Package:\s*(.+)$|^Version:\s*(.+)$|^Architecture:\s*(.+)$|^Status:\s*(.+)$")


def _ord_char(ch: str) -> int:
    if not ch:
        return 0
    if ch == "~":
        return -1
    if ("A" <= ch <= "Z") or ("a" <= ch <= "z"):
        return ord(ch)
    return ord(ch) + 256


def _verrevcmp(s: str, t: str) -> int:
    la, lb = len(s), len(t)
    i = j = 0
    while i < la or j < lb:
        while (i < la and not s[i].isdigit()) or (j < lb and not t[j].isdigit()):
            ca = s[i] if i < la else ""
            cb = t[j] if j < lb else ""
            if not ca and not cb:
                break
            oa = _ord_char(ca)
            ob = _ord_char(cb)
            if oa != ob:
                return -1 if oa < ob else 1
            i += 1
            j += 1
        while i < la and s[i] == "0":
            i += 1
        while j < lb and t[j] == "0":
            j += 1
        si, sj = i, j
        while i < la and s[i].isdigit():
            i += 1
        while j < lb and t[j].isdigit():
            j += 1
        da, db = s[si:i], t[sj:j]
        if len(da) != len(db):
            return -1 if len(da) < len(db) else 1
        if da != db:
            return -1 if da < db else 1
    return 0


def vercmp(a: str, b: str) -> int:
    ea = eb = 0
    if ":" in a:
        es, a = a.split(":", 1)
        ea = int(es) if es.isdigit() else 0
    if ":" in b:
        es, b = b.split(":", 1)
        eb = int(es) if es.isdigit() else 0
    if ea != eb:
        return -1 if ea < eb else 1
    if "-" in a:
        ua, ra = a.rsplit("-", 1)
    else:
        ua, ra = a, ""
    if "-" in b:
        ub, rb = b.rsplit("-", 1)
    else:
        ub, rb = b, ""
    r = _verrevcmp(ua, ub)
    if r != 0:
        return r
    return _verrevcmp(ra, rb)


def ver_satisfies(ver: str, op: str, want: str) -> bool:
    c = vercmp(ver, want)
    if op == ">=":
        return c != -1
    if op == "<=":
        return c != 1
    if op in ("=", "=="):
        return c == 0
    if op in (">>", ">"):
        return c == 1
    if op in ("<<", "<"):
        return c == -1
    return True


_STZ_FIELDS = (
    "Package",
    "Version",
    "Architecture",
    "Essential",
    "Depends",
    "Pre-Depends",
    "Provides",
    "Conflicts",
    "Filename",
    "Size",
    "SHA256",
)


def parse_packages_to_tsv(packages_path: str, tsv_path: Optional[str] = None) -> List[str]:
    rows: List[str] = []
    rec: Dict[str, str] = {}
    last = ""

    def emit() -> None:
        pkg = rec.get("Package", "")
        if not pkg:
            return
        cols = [
            pkg,
            rec.get("Version", ""),
            rec.get("Architecture", ""),
            rec.get("Essential", ""),
            rec.get("Depends", "").replace("\t", " "),
            rec.get("Pre-Depends", "").replace("\t", " "),
            rec.get("Provides", "").replace("\t", " "),
            rec.get("Conflicts", "").replace("\t", " "),
            rec.get("Filename", ""),
            rec.get("Size", ""),
            rec.get("SHA256", ""),
        ]
        rows.append("\t".join(cols))

    with open(packages_path, "r", encoding="utf-8", errors="replace") as f:
        for raw in f:
            line = raw.rstrip("\r\n")
            if not line:
                emit()
                rec.clear()
                last = ""
                continue
            if line[0] in (" ", "\t"):
                if last in ("Depends", "Pre-Depends", "Provides", "Conflicts"):
                    prev = rec.get(last, "")
                    rec[last] = f"{prev} {line[1:]}" if prev else line[1:]
                continue
            colon = line.find(":")
            if colon <= 0:
                continue
            k = line[:colon]
            v = line[colon + 1 :].lstrip(" \t")
            if k in _STZ_FIELDS:
                rec[k] = v
            last = k
        emit()

    if tsv_path is not None:
        tmp = tsv_path + ".tmp"
        with open(tmp, "w", encoding="utf-8", newline="\n") as out:
            if rows:
                out.write("\n".join(rows) + "\n")
        os.replace(tmp, tsv_path)
    return rows


def ensure_indexes_fresh(pkg_index: str, force: bool = False) -> List[str]:
    tsvs: List[str] = []
    for pkg_file in sorted(glob.glob(os.path.join(pkg_index, "repo.*.Packages"))):
        tsv_file = pkg_file[: -len(".Packages")] + ".tsv"
        need = force or not os.path.isfile(tsv_file)
        if not need:
            try:
                need = os.path.getmtime(pkg_file) > os.path.getmtime(tsv_file)
            except OSError:
                need = True
        if need:
            parse_packages_to_tsv(pkg_file, tsv_file)
        if os.path.isfile(tsv_file):
            tsvs.append(tsv_file)
    return tsvs


def load_installed_db(pkg_db: str) -> Dict[str, str]:
    installed: Dict[str, str] = {}
    for meta_path in glob.glob(os.path.join(pkg_db, "*.meta")):
        name = os.path.basename(meta_path)[:-5]
        ver = ""
        try:
            with open(meta_path, "r", encoding="utf-8", errors="replace") as f:
                for line in f:
                    if line.startswith("version="):
                        ver = line[8:].strip()
                        break
        except OSError:
            continue
        installed[name] = ver
    return installed


def sync_dpkg_status(prefix: str, pkg_db: str) -> int:
    status_path = os.path.join(prefix, "var", "lib", "dpkg", "status")
    if not os.path.isfile(status_path):
        return 0
    os.makedirs(pkg_db, exist_ok=True)
    stamp_path = os.path.join(pkg_db, ".dpkg-sync.stamp")
    try:
        st_sig = f"{os.stat(status_path).st_mtime_ns}:{os.stat(status_path).st_size}"
        if os.path.isfile(stamp_path):
            with open(stamp_path, "r", encoding="utf-8") as sf:
                if sf.read().strip() == st_sig:
                    return 0
    except OSError:
        st_sig = ""

    existing = load_installed_db(pkg_db)
    added = 0
    pkg = ver = arch = status = ""
    with open(status_path, "r", encoding="utf-8", errors="replace") as f:
        for raw in f:
            line = raw.rstrip("\r\n")
            if not line:
                if pkg and ver and status.endswith("installed") and pkg not in existing:
                    _write_db_entry(pkg_db, pkg, ver, arch or "aarch64", [])
                    existing[pkg] = ver
                    added += 1
                pkg = ver = arch = status = ""
                continue
            if line.startswith("Package:"):
                pkg = line[8:].strip()
            elif line.startswith("Version:"):
                ver = line[8:].strip()
            elif line.startswith("Architecture:"):
                arch = line[13:].strip()
            elif line.startswith("Status:"):
                status = line[7:].strip()
        if pkg and ver and status.endswith("installed") and pkg not in existing:
            _write_db_entry(pkg_db, pkg, ver, arch or "aarch64", [])
            added += 1

    if st_sig:
        try:
            with open(stamp_path + ".tmp", "w", encoding="utf-8", newline="\n") as sf:
                sf.write(st_sig + "\n")
            os.replace(stamp_path + ".tmp", stamp_path)
        except OSError:
            pass
    return added


def _write_db_entry(pkg_db: str, name: str, ver: str, arch: str, files: List[str]) -> None:
    os.makedirs(pkg_db, exist_ok=True)
    meta = os.path.join(pkg_db, f"{name}.meta")
    flist = os.path.join(pkg_db, f"{name}.files")
    meta_tmp = meta + ".tmp"
    flist_tmp = flist + ".tmp"
    with open(meta_tmp, "w", encoding="utf-8", newline="\n") as mf:
        mf.write(f"name={name}\nversion={ver}\narch={arch}\n")
    with open(flist_tmp, "w", encoding="utf-8", newline="\n") as ff:
        if files:
            ff.write("\n".join(files) + "\n")
    os.replace(meta_tmp, meta)
    os.replace(flist_tmp, flist)


def load_index_map(pkg_index: str) -> Tuple[Dict[str, Tuple[str, List[str]]], Dict[str, Tuple[str, List[str]]]]:
    by_name: Dict[str, Tuple[str, List[str]]] = {}
    by_prov: Dict[str, Tuple[str, List[str]]] = {}
    for tsv_path in sorted(glob.glob(os.path.join(pkg_index, "repo.*.tsv"))):
        base = os.path.basename(tsv_path)[:-4]
        try:
            with open(tsv_path, "r", encoding="utf-8", errors="replace") as f:
                for raw in f:
                    line = raw.rstrip("\n")
                    if not line:
                        continue
                    cols = line.split("\t")
                    if len(cols) < 11:
                        cols.extend([""] * (11 - len(cols)))
                    name, ver = cols[0], cols[1]
                    if not name:
                        continue
                    prev = by_name.get(name)
                    if prev is None or vercmp(ver, prev[1][1]) == 1:
                        by_name[name] = (base, cols)
                    prov_str = cols[6]
                    if prov_str:
                        for p in prov_str.split(","):
                            pname = p.strip().split(" ", 1)[0].split("(", 1)[0].strip()
                            if pname and pname not in by_prov:
                                by_prov[pname] = (base, cols)
        except OSError:
            continue
    return by_name, by_prov


def resolve_packages(
    names: List[str],
    by_name: Dict[str, Tuple[str, List[str]]],
    by_prov: Dict[str, Tuple[str, List[str]]],
    installed: Dict[str, str],
) -> List[str]:
    done = set()
    doing = set()
    order: List[str] = []

    def choose_dep(term: str) -> Optional[List[str]]:
        for alt in term.split("|"):
            alt = alt.strip()
            if not alt:
                continue
            m = VER_CONSTRAINT_RE.match(alt)
            if not m:
                continue
            dep_name = m.group(1).split(":", 1)[0].strip()
            constraint = m.group(2) or ""
            entry = by_name.get(dep_name) or by_prov.get(dep_name)
            if not entry:
                continue
            cols = entry[1]
            if constraint:
                parts = constraint.strip().split()
                if len(parts) >= 2 and not ver_satisfies(cols[1], parts[0], parts[1]):
                    continue
            return cols
        return None

    def visit(pkg_name: str) -> None:
        if not PKG_NAME_RE.match(pkg_name):
            raise ValueError(f"invalid package name in index: {pkg_name}")
        if pkg_name in done or pkg_name in installed:
            return
        if pkg_name in doing:
            raise ValueError(f"dependency cycle at: {pkg_name}")
        entry = by_name.get(pkg_name)
        if not entry:
            raise ValueError(f"unknown package in dependency closure: {pkg_name}")
        doing.add(pkg_name)
        cols = entry[1]
        raw_deps = []
        for field in (cols[4], cols[5]):
            if field.strip():
                raw_deps.extend(field.split(","))
        for term in raw_deps:
            term = term.strip()
            if not term:
                continue
            cand = choose_dep(term)
            if cand is None:
                raise ValueError(f"cannot resolve dependency: {term} (for {pkg_name})")
            visit(cand[0])
        doing.remove(pkg_name)
        done.add(pkg_name)
        order.append(pkg_name)

    for req in names:
        if not PKG_NAME_RE.match(req):
            raise ValueError(f"invalid package name: {req}")
        if req in installed:
            continue
        entry = by_name.get(req) or by_prov.get(req)
        if not entry:
            raise ValueError(f"unknown package or provider: {req}")
        visit(entry[1][0])

    return order


def ar_list_members(deb_path: str) -> List[Tuple[str, int, int]]:
    out: List[Tuple[str, int, int]] = []
    with open(deb_path, "rb") as f:
        magic = f.read(8)
        if magic != b"!<arch>\n":
            raise ValueError(f"not an ar archive: {deb_path}")
        off = 8
        while True:
            f.seek(off)
            hdr = f.read(60)
            if len(hdr) < 60:
                break
            raw_name = hdr[:16].decode("ascii", errors="replace").rstrip(" ").rstrip("/")
            size_str = hdr[48:58].decode("ascii", errors="replace").strip()
            if not raw_name or not size_str.isdigit():
                break
            sz = int(size_str)
            data_off = off + 60
            out.append((raw_name, data_off, sz))
            off = data_off + sz + (sz % 2)
    return out


class _SliceReader(io.RawIOBase):
    def __init__(self, f, start: int, length: int):
        self._f = f
        self._end = start + length
        self._pos = start
        f.seek(start)

    def readable(self) -> bool:
        return True

    def read(self, n: int = -1) -> bytes:
        rem = self._end - self._pos
        if rem <= 0:
            return b""
        if n is None or n < 0 or n > rem:
            n = rem
        self._f.seek(self._pos)
        chunk = self._f.read(n)
        self._pos += len(chunk)
        return chunk


def _open_tar_stream_from_ar(deb_path: str, member_prefix: str):
    members = ar_list_members(deb_path)
    match = next((m for m in members if m[0].startswith(member_prefix + ".tar.") or m[0] == member_prefix + ".tar"), None)
    if match is None:
        raise ValueError(f"missing {member_prefix}.tar.* in {deb_path}")
    mname, off, sz = match
    raw_f = open(deb_path, "rb")
    slc = _SliceReader(raw_f, off, sz)
    if mname.endswith(".xz"):
        dec = lzma.LZMAFile(slc, "rb")
        return raw_f, None, dec, tarfile.open(fileobj=dec, mode="r|")
    if mname.endswith(".gz"):
        dec = gzip.GzipFile(fileobj=slc, mode="rb")
        return raw_f, None, dec, tarfile.open(fileobj=dec, mode="r|")
    if mname.endswith(".bz2"):
        dec = bz2.BZ2File(slc, "rb")
        return raw_f, None, dec, tarfile.open(fileobj=dec, mode="r|")
    if mname.endswith(".zst"):
        data = slc.read()
        raw_f.close()
        proc = subprocess.Popen(["zstd", "-dc"], stdin=subprocess.PIPE, stdout=subprocess.PIPE)
        stdout_data, _ = proc.communicate(data)
        if proc.returncode != 0:
            raise ValueError(f"zstd decode failed for {mname}")
        bio = io.BytesIO(stdout_data)
        return None, None, bio, tarfile.open(fileobj=bio, mode="r|")
    return raw_f, None, slc, tarfile.open(fileobj=slc, mode="r|")


def _close_ar_stream(raw_f, _unused, dec, tf) -> None:
    for obj in (tf, dec, raw_f):
        if obj is not None:
            try:
                obj.close()
            except Exception:
                pass


def strip_data_member_path(raw_path: str, strip_count: Optional[int] = 6) -> Tuple[bool, str]:
    if not raw_path or raw_path.startswith("/") or "\\" in raw_path:
        return False, ""
    if raw_path in (".", "./"):
        return True, ""
    if not raw_path.startswith("./"):
        return False, ""
    rel = raw_path[2:].rstrip("/")
    if not rel:
        return True, ""
    parts = rel.split("/")
    if any(p in (".", "..") for p in parts):
        return False, ""
    # Strictly enforce Android/Termux data prefix layout matching _deb_validate_and_list
    if len(parts) >= 1 and parts[0] != "data":
        return False, ""
    if len(parts) < 2:
        return True, ""
    if parts[1] not in ("data", "user"):
        return False, ""
    if parts[1] == "data":
        if len(parts) >= 3 and not TERMUX_PKG_COMP_RE.match(parts[2]):
            return False, ""
        if len(parts) >= 4 and parts[3] != "files":
            return False, ""
        if len(parts) >= 5 and parts[4] != "usr":
            return False, ""
        effective_strip = 5 if strip_count is None else max(5, strip_count - 1)
    else:
        # ./data/user/<id>/<pkg>/files/usr/...
        if len(parts) >= 3 and not parts[2].isdigit():
            return False, ""
        if len(parts) >= 4 and not TERMUX_PKG_COMP_RE.match(parts[3]):
            return False, ""
        if len(parts) >= 5 and parts[4] != "files":
            return False, ""
        if len(parts) >= 6 and parts[5] != "usr":
            return False, ""
        effective_strip = 6 if strip_count is None else strip_count

    if len(parts) <= effective_strip:
        return True, ""
    return True, "/".join(parts[effective_strip:])


def _validate_target_link(linkname: str, dest_root: str, rel_parent: str) -> Tuple[bool, str]:
    if not linkname or "\x00" in linkname:
        return False, linkname
    rewritten = PREFIX_RE_STR.sub(dest_root, linkname)
    if rewritten.startswith("/"):
        norm = posixpath.normpath(rewritten)
        root_norm = posixpath.normpath(dest_root)
        if norm != root_norm and not norm.startswith(root_norm + "/"):
            return False, linkname
        return True, rewritten
    resolved = posixpath.normpath(posixpath.join(rel_parent, rewritten))
    if resolved == ".." or resolved.startswith("../"):
        return False, linkname
    return True, rewritten


def _is_elf(data: bytes) -> bool:
    return data.startswith(b"\x7fELF")


def _patch_bytes(data: bytes, prefix: str) -> Optional[bytes]:
    if not data or len(data) > 2 * 1024 * 1024 or _is_elf(data) or b"\x00" in data[:8192]:
        return None
    if b"/files/usr" not in data:
        return None
    repl = prefix.encode("utf-8")
    updated = PREFIX_RE_BYTES.sub(repl, data)
    return updated if updated != data else None


def _patch_single_text(fp: str, prefix: str) -> None:
    if os.path.islink(fp) or not os.path.isfile(fp):
        return
    try:
        st = os.stat(fp)
    except OSError:
        return
    if st.st_size < len("/files/usr") or st.st_size > 2 * 1024 * 1024:
        return
    try:
        with open(fp, "rb") as f:
            head = f.read(8192)
            if _is_elf(head) or b"\x00" in head:
                return
            rest = f.read()
        data = head + rest
    except OSError:
        return
    updated = _patch_bytes(data, prefix)
    if updated is None:
        return
    tmp = fp + ".agy_rw"
    with open(tmp, "wb") as f:
        f.write(updated)
    try:
        os.chmod(tmp, stat.S_IMODE(st.st_mode))
    except OSError:
        pass
    os.replace(tmp, fp)


def read_control_field(deb_path: str, field: str) -> str:
    raw_f = unused = dec = tf = None
    try:
        raw_f, unused, dec, tf = _open_tar_stream_from_ar(deb_path, "control")
        prefix_key = f"{field}:"
        for member in tf:
            if not member.isfile():
                continue
            clean_name = member.name.lstrip("./")
            if clean_name != "control":
                continue
            fobj = tf.extractfile(member)
            if fobj is None:
                continue
            for raw in fobj:
                line = raw.decode("utf-8", errors="replace").rstrip("\r\n")
                if line.startswith(prefix_key):
                    return line[len(prefix_key) :].strip()
    finally:
        _close_ar_stream(raw_f, unused, dec, tf)
    return ""


def _list_data_files(deb_path: str, strip_count: Optional[int] = None) -> List[str]:
    files: List[str] = []
    seen = set()
    raw_f = unused = dec = tf = None
    try:
        raw_f, unused, dec, tf = _open_tar_stream_from_ar(deb_path, "data")
        eff = None if (strip_count is None or strip_count == 6) else strip_count
        for member in tf:
            ok, rel = strip_data_member_path(member.name, eff)
            if not ok:
                raise ValueError(f"unsafe deb path: {member.name}")
            if rel and rel not in seen:
                seen.add(rel)
                files.append(rel)
    finally:
        _close_ar_stream(raw_f, unused, dec, tf)
    files.sort()
    return files


def _merge_stage_into_prefix(stage_dir: str, prefix: str, dir_modes: Dict[str, int]) -> None:
    for root_dir, dirs, files in os.walk(stage_dir):
        rel_dir = os.path.relpath(root_dir, stage_dir)
        dst_dir = prefix if rel_dir == "." else os.path.join(prefix, rel_dir)
        os.makedirs(dst_dir, exist_ok=True)
        for dname in dirs:
            sp = os.path.join(root_dir, dname)
            dp = os.path.join(dst_dir, dname)
            if os.path.islink(sp):
                if os.path.lexists(dp):
                    os.unlink(dp)
                os.symlink(os.readlink(sp), dp)
            else:
                os.makedirs(dp, exist_ok=True)
                mode = dir_modes.get(os.path.relpath(sp, stage_dir))
                if mode is not None:
                    try:
                        os.chmod(dp, mode)
                    except OSError:
                        pass
        for fname in files:
            sp = os.path.join(root_dir, fname)
            dp = os.path.join(dst_dir, fname)
            if os.path.isdir(dp) and not os.path.islink(dp):
                shutil.rmtree(dp)
            if os.path.lexists(dp):
                os.unlink(dp)
            os.replace(sp, dp)


def _post_install_hooks(prefix: str, pkg_files: List[str]) -> None:
    # 1. Expose core OpenJDK tools directly in $PREFIX/bin
    jvm_tools = {"java", "javac", "jar", "jarsigner", "javap", "javadoc", "keytool", "jps"}
    for rel in pkg_files:
        if rel.startswith("lib/jvm/") and "-openjdk/bin/" in rel:
            tool = os.path.basename(rel)
            if tool in jvm_tools:
                target = os.path.join(prefix, rel)
                link_dst = os.path.join(prefix, "bin", tool)
                if os.access(target, os.X_OK) and not os.path.lexists(link_dst):
                    os.makedirs(os.path.join(prefix, "bin"), exist_ok=True)
                    try:
                        os.symlink(target, link_dst)
                    except OSError:
                        pass

    # 2. Generate runtime/bin Bionic bridges only for newly installed bin/* entries
    runtime_bin = os.path.normpath(os.path.join(prefix, "..", "runtime", "bin"))
    if not os.path.isdir(runtime_bin):
        return
    for rel in pkg_files:
        if not rel.startswith("bin/"):
            continue
        bname = rel[4:]
        if not bname or "/" in bname:
            continue
        bin_file = os.path.join(prefix, "bin", bname)
        if not (os.path.isfile(bin_file) or os.path.islink(bin_file)):
            continue
        bridge_path = os.path.join(runtime_bin, bname)
        if os.path.exists(bridge_path):
            continue
        script = (
            "#!/system/bin/sh\n"
            f"PREFIX='{prefix}'\n"
            "export PREFIX\n"
            'if [ -f "$PREFIX/lib/libtermux-exec.so" ]; then\n'
            '  export LD_PRELOAD="$PREFIX/lib/libtermux-exec.so"\n'
            "else\n"
            "  unset LD_PRELOAD\n"
            "fi\n"
            'export LD_LIBRARY_PATH="$PREFIX/lib"\n'
            'export PATH="$PREFIX/bin:/system/bin"\n'
            'export OPENSSL_CONF="$PREFIX/etc/tls/openssl.cnf"\n'
            'export SSL_CERT_FILE="$PREFIX/etc/tls/cert.pem"\n'
            'case ",${GODEBUG:-}," in\n'
            "  *,netdns=go,*)\n"
            '    if [ "$GODEBUG" = "netdns=go" ]; then\n'
            "      unset GODEBUG\n"
            "    else\n"
            '      GODEBUG="$(printf \'%s\' ",$GODEBUG," | sed \':a;s/,netdns=go,/,/g;ta;s/^,//;s/,$//\')"\n'
            '      [ -n "$GODEBUG" ] && export GODEBUG || unset GODEBUG\n'
            "    fi\n"
            "    ;;\n"
            "esac\n"
            f'exec "$PREFIX/bin/{bname}" "$@"\n'
        )
        with open(bridge_path, "w", encoding="utf-8", newline="\n") as bf:
            bf.write(script)
        os.chmod(bridge_path, 0o755)


def stage_install_deb(
    deb_path: str,
    prefix: str,
    pkg_db: str,
    name: str,
    ver: str,
    arch: str,
    strip_count: Optional[int] = None,
) -> List[str]:
    os.makedirs(prefix, exist_ok=True)
    tmp_root = os.path.join(prefix, "var", "lib", "pkg", "tmp")
    os.makedirs(tmp_root, exist_ok=True)
    stage_dir = os.path.join(tmp_root, f"stage-{name}-{os.getpid()}")
    if os.path.exists(stage_dir):
        shutil.rmtree(stage_dir)
    os.makedirs(stage_dir, exist_ok=True)

    rel_files: List[str] = []
    seen = set()
    dir_modes: Dict[str, int] = {}
    hardlinks: List[Tuple[str, str]] = []
    raw_f = unused = dec = tf = None

    try:
        raw_f, unused, dec, tf = _open_tar_stream_from_ar(deb_path, "data")
        for member in tf:
            ok, rel = strip_data_member_path(member.name, strip_count)
            if not ok:
                raise ValueError(f"unsafe archive path rejected: {member.name}")
            if not rel:
                continue
            dst = os.path.join(stage_dir, rel)
            rel_parent = posixpath.dirname(rel) or "."
            os.makedirs(os.path.dirname(dst), exist_ok=True)

            if member.isdir():
                os.makedirs(dst, exist_ok=True)
                dir_modes[rel] = stat.S_IMODE(member.mode) if member.mode else 0o755
            elif member.issym():
                ok_link, target = _validate_target_link(member.linkname, prefix, rel_parent)
                if not ok_link:
                    raise ValueError(f"unsafe symlink rejected: {rel} -> {member.linkname}")
                if os.path.lexists(dst):
                    os.unlink(dst)
                os.symlink(target, dst)
                if rel not in seen:
                    seen.add(rel)
                    rel_files.append(rel)
            elif member.islnk():
                ok_l, target_rel = strip_data_member_path("./" + member.linkname.lstrip("./"), strip_count)
                if not ok_l or not target_rel:
                    raise ValueError(f"unsafe hardlink rejected: {rel} -> {member.linkname}")
                hardlinks.append((rel, target_rel))
                if rel not in seen:
                    seen.add(rel)
                    rel_files.append(rel)
            elif member.isfile():
                fobj = tf.extractfile(member)
                data = fobj.read() if fobj is not None else b""
                patched = _patch_bytes(data, prefix)
                if patched is not None:
                    data = patched
                with open(dst, "wb") as out:
                    out.write(data)
                os.chmod(dst, stat.S_IMODE(member.mode) if member.mode else 0o644)
                if rel not in seen:
                    seen.add(rel)
                    rel_files.append(rel)

        for rel, target_rel in hardlinks:
            src_hl = os.path.join(stage_dir, target_rel)
            dst_hl = os.path.join(stage_dir, rel)
            if os.path.lexists(dst_hl):
                os.unlink(dst_hl)
            os.link(src_hl, dst_hl)

        rel_files.sort()
        _merge_stage_into_prefix(stage_dir, prefix, dir_modes)
        cver = read_control_field(deb_path, "Version")
        if cver:
            ver = cver
        _write_db_entry(pkg_db, name, ver, arch, rel_files)
        _post_install_hooks(prefix, rel_files)
        return rel_files
    finally:
        _close_ar_stream(raw_f, unused, dec, tf)
        shutil.rmtree(stage_dir, ignore_errors=True)


def extract_deb_to_dest(deb_path: str, dest_dir: str, strip_count: Optional[int] = None) -> List[str]:
    os.makedirs(dest_dir, exist_ok=True)
    rel_files: List[str] = []
    seen = set()
    raw_f = unused = dec = tf = None
    try:
        raw_f, unused, dec, tf = _open_tar_stream_from_ar(deb_path, "data")
        for member in tf:
            ok, rel = strip_data_member_path(member.name, strip_count)
            if not ok:
                raise ValueError(f"unsafe archive path rejected: {member.name}")
            if not rel:
                continue
            dst = os.path.join(dest_dir, rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            if member.isdir():
                os.makedirs(dst, exist_ok=True)
            elif member.issym():
                ok_link, target = _validate_target_link(member.linkname, dest_dir, posixpath.dirname(rel) or ".")
                if not ok_link:
                    raise ValueError(f"unsafe symlink rejected: {rel} -> {member.linkname}")
                if os.path.lexists(dst):
                    os.unlink(dst)
                os.symlink(target, dst)
                if rel not in seen:
                    seen.add(rel)
                    rel_files.append(rel)
            elif member.isfile():
                fobj = tf.extractfile(member)
                with open(dst, "wb") as out:
                    if fobj is not None:
                        shutil.copyfileobj(fobj, out)
                os.chmod(dst, stat.S_IMODE(member.mode) if member.mode else 0o644)
                if rel not in seen:
                    seen.add(rel)
                    rel_files.append(rel)
        rel_files.sort()
        return rel_files
    finally:
        _close_ar_stream(raw_f, unused, dec, tf)


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def check_free_space(prefix: str, needed_bytes: int) -> Tuple[bool, int, int]:
    probe = prefix if os.path.exists(prefix) else os.path.dirname(prefix) or "/"
    usage = shutil.disk_usage(probe)
    # Require extracted headroom (~2.5x archive size + 16 MiB cushion)
    required = int(needed_bytes * 2.5) + 16 * 1024 * 1024
    return usage.free >= required, usage.free, required


def _read_repo_url(pkg_index: str, base: str) -> str:
    url_file = os.path.join(pkg_index, f"{base}.url")
    try:
        with open(url_file, "r", encoding="utf-8") as f:
            return f.read().strip().rstrip("/")
    except OSError:
        return ""


def cmd_plan(args) -> int:
    ensure_indexes_fresh(args.pkg_index)
    sync_dpkg_status(args.prefix, args.pkg_db)
    installed = load_installed_db(args.pkg_db)
    by_name, by_prov = load_index_map(args.pkg_index)

    planned: List[str] = []
    if args.upgrade:
        for name, cur_ver in sorted(installed.items()):
            entry = by_name.get(name)
            if not entry:
                continue
            if vercmp(entry[1][1], cur_ver) == 1:
                planned.append(name)
    else:
        planned = resolve_packages(args.packages, by_name, by_prov, installed)

    needed_download = 0
    rows: List[str] = []
    for pkg in planned:
        entry = by_name.get(pkg)
        if not entry:
            continue
        base, cols = entry
        ver, arch, fn, sz_str, sha = cols[1], cols[2], cols[8], cols[9], cols[10]
        repo_base = _read_repo_url(args.pkg_index, base)
        url = f"{repo_base}/{fn}" if repo_base and fn else ""
        cache_path = os.path.join(args.pkg_cache, os.path.basename(fn)) if fn else ""
        sz = int(sz_str) if sz_str.isdigit() else 0
        cached = "0"
        if cache_path and os.path.isfile(cache_path) and sha and sha256_file(cache_path) == sha:
            cached = "1"
        else:
            needed_download += sz
        rows.append("|".join([pkg, fn, url, sha, cache_path, str(sz), ver, arch, cached]))

    if rows and needed_download > 0:
        ok, free_b, req_b = check_free_space(args.prefix, needed_download)
        if not ok:
            print(
                f"insufficient disk space: free={free_b // (1024 * 1024)}MB required~={req_b // (1024 * 1024)}MB",
                file=sys.stderr,
            )
            return 2

    for row in rows:
        print(row)
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(prog="pkg_core.py")
    sub = parser.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("vercmp")
    p.add_argument("a")
    p.add_argument("b")

    p = sub.add_parser("index-parse")
    p.add_argument("file")

    p = sub.add_parser("index-rebuild")
    p.add_argument("pkg_index")
    p.add_argument("--force", action="store_true")

    p = sub.add_parser("index-lookup")
    p.add_argument("pkg_index")
    p.add_argument("name")

    p = sub.add_parser("index-provider")
    p.add_argument("pkg_index")
    p.add_argument("name")

    p = sub.add_parser("search")
    p.add_argument("pkg_index")
    p.add_argument("pattern", nargs="?", default="")

    p = sub.add_parser("sync-dpkg")
    p.add_argument("prefix")
    p.add_argument("pkg_db")

    p = sub.add_parser("resolve")
    p.add_argument("pkg_index")
    p.add_argument("pkg_db")
    p.add_argument("packages", nargs="+")

    p = sub.add_parser("plan")
    p.add_argument("pkg_index")
    p.add_argument("pkg_db")
    p.add_argument("pkg_cache")
    p.add_argument("prefix")
    p.add_argument("--upgrade", action="store_true")
    p.add_argument("packages", nargs="*")

    p = sub.add_parser("deb-members")
    p.add_argument("file")

    p = sub.add_parser("deb-field")
    p.add_argument("file")
    p.add_argument("field")

    p = sub.add_parser("deb-validate")
    p.add_argument("file")

    p = sub.add_parser("deb-files")
    p.add_argument("file")
    p.add_argument("--strip", type=int, default=None)

    p = sub.add_parser("deb-extract")
    p.add_argument("file")
    p.add_argument("dest")
    p.add_argument("--strip", type=int, default=None)

    p = sub.add_parser("stage-install")
    p.add_argument("file")
    p.add_argument("prefix")
    p.add_argument("pkg_db")
    p.add_argument("name")
    p.add_argument("ver")
    p.add_argument("arch")
    p.add_argument("--strip", type=int, default=None)

    p = sub.add_parser("rewrite-prefix")
    p.add_argument("prefix")
    p.add_argument("targets", nargs="*")

    args = parser.parse_args()

    try:
        if args.cmd == "vercmp":
            print(vercmp(args.a, args.b))
            return 0
        if args.cmd == "index-parse":
            for row in parse_packages_to_tsv(args.file):
                print(row)
            return 0
        if args.cmd == "index-rebuild":
            ensure_indexes_fresh(args.pkg_index, force=args.force)
            return 0
        if args.cmd == "index-lookup":
            by_name, _ = load_index_map(args.pkg_index)
            entry = by_name.get(args.name)
            if entry:
                print(entry[0] + "\t" + "\t".join(entry[1]))
            return 0
        if args.cmd == "index-provider":
            _, by_prov = load_index_map(args.pkg_index)
            entry = by_prov.get(args.name)
            if entry:
                print(entry[0] + "\t" + "\t".join(entry[1]))
            return 0
        if args.cmd == "search":
            ensure_indexes_fresh(args.pkg_index)
            by_name, _ = load_index_map(args.pkg_index)
            rx = re.compile(args.pattern) if args.pattern else None
            for name in sorted(by_name):
                if rx is None or rx.search(name):
                    print(f"{name:<24} {by_name[name][1][1]}")
            return 0
        if args.cmd == "sync-dpkg":
            sync_dpkg_status(args.prefix, args.pkg_db)
            return 0
        if args.cmd == "resolve":
            ensure_indexes_fresh(args.pkg_index)
            installed = load_installed_db(args.pkg_db)
            by_name, by_prov = load_index_map(args.pkg_index)
            for p in resolve_packages(args.packages, by_name, by_prov, installed):
                print(p)
            return 0
        if args.cmd == "plan":
            return cmd_plan(args)
        if args.cmd == "deb-members":
            for name, _, _ in ar_list_members(args.file):
                print(name)
            return 0
        if args.cmd == "deb-field":
            val = read_control_field(args.file, args.field)
            if not val:
                return 1
            print(val)
            return 0
        if args.cmd == "deb-validate":
            strip_val = None if (args.file and os.environ.get("PKG_DEB_STRIP", "6") == "6") else int(os.environ.get("PKG_DEB_STRIP", "6"))
            _list_data_files(args.file, strip_val or 6)
            return 0
        if args.cmd == "deb-files":
            for rel in _list_data_files(args.file, args.strip or 6):
                print(rel)
            return 0
        if args.cmd == "deb-extract":
            extract_deb_to_dest(args.file, args.dest, args.strip)
            return 0
        if args.cmd == "stage-install":
            stage_install_deb(args.file, args.prefix, args.pkg_db, args.name, args.ver, args.arch, args.strip)
            return 0
        if args.cmd == "rewrite-prefix":
            targets = args.targets
            if not targets and not sys.stdin.isatty():
                targets = [l.strip() for l in sys.stdin.read().splitlines() if l.strip()]
            if not targets:
                targets = [args.prefix]
            for t in targets:
                if os.path.islink(t):
                    continue
                if os.path.isfile(t):
                    _patch_single_text(t, args.prefix)
                elif os.path.isdir(t):
                    for r, _, fns in os.walk(t, followlinks=False):
                        for fn in fns:
                            _patch_single_text(os.path.join(r, fn), args.prefix)
            return 0
    except Exception as exc:
        print(f"pkg_core error: {exc}", file=sys.stderr)
        return 1
    return 1


if __name__ == "__main__":
    sys.exit(main())
