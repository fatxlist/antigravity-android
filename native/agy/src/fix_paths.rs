use crate::gateway::resolve_prefix_and_home;
use memchr::memmem;
use regex::bytes::Regex;
use std::borrow::Cow;
use std::ffi::CString;
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Write};
use std::os::unix::ffi::OsStrExt;
use std::os::unix::fs::{MetadataExt, OpenOptionsExt};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

const BINARY_PROBE_BYTES: usize = 8192;
const PREFIX_REGEX: &str = r"/data/(?:data|user/[0-9]+)/[^/\n]*/files/usr";
const ENV_SHEBANG_REGEX: &str = r"^#! */usr/bin/env[ \t]+(?:-S[ \t]+)?([^ \t\r\n]+)";

static TEMP_SEQ: AtomicU64 = AtomicU64::new(0);

#[derive(Debug, Clone, Default)]
pub struct FixOptions {
    pub dry_run: bool,
    pub targets: Vec<PathBuf>,
}

pub fn run(args: &[String]) -> i32 {
    let mut opts = FixOptions::default();
    let mut stop_flags = false;

    for arg in args {
        if !stop_flags {
            match arg.as_str() {
                "--dry-run" | "-n" => {
                    opts.dry_run = true;
                    continue;
                }
                "-h" | "--help" => {
                    println!(
                        "Usage: agy-fix-paths [--dry-run] [PATH...]\n\n\
                         Rewrite hardcoded Android app prefixes and line-1 /usr/bin/env shebangs to $PREFIX."
                    );
                    return 0;
                }
                "--" => {
                    stop_flags = true;
                    continue;
                }
                s if s.starts_with('-') && s.len() > 1 => {
                    eprintln!("agy-fix-paths: unknown option: {s}");
                    return 2;
                }
                _ => {}
            }
        }
        opts.targets.push(PathBuf::from(arg));
    }

    let ph = resolve_prefix_and_home();
    if opts.targets.is_empty() {
        opts.targets.push(ph.prefix.clone());
    }

    let count = execute_fix_paths(&ph.prefix, &opts);
    if opts.dry_run {
        println!(
            "agy-fix-paths: would rewrite {} file(s) -> {}",
            count,
            ph.prefix.display()
        );
    } else {
        println!(
            "agy-fix-paths: rewrote {} file(s) -> {}",
            count,
            ph.prefix.display()
        );
    }
    0
}

pub fn execute_fix_paths(prefix: &Path, opts: &FixOptions) -> usize {
    let re_prefix = Regex::new(PREFIX_REGEX).expect("valid prefix regex");
    let re_env = Regex::new(ENV_SHEBANG_REGEX).expect("valid env shebang regex");
    let prefix_bytes = prefix.as_os_str().as_bytes();
    let finder_data = memmem::Finder::new(b"/data/");

    let mut count = 0usize;
    let mut stack: Vec<PathBuf> = opts.targets.iter().rev().cloned().collect();

    while let Some(path) = stack.pop() {
        let meta = match fs::symlink_metadata(&path) {
            Ok(m) => m,
            Err(_) => continue,
        };
        let ft = meta.file_type();
        if ft.is_symlink() {
            continue;
        }
        if ft.is_dir() {
            let entries = match fs::read_dir(&path) {
                Ok(rd) => rd,
                Err(_) => continue,
            };
            let mut children = Vec::new();
            for entry in entries.flatten() {
                if let Ok(eft) = entry.file_type() {
                    if !eft.is_symlink() && (eft.is_dir() || eft.is_file()) {
                        children.push(entry.path());
                    }
                }
            }
            for child in children.into_iter().rev() {
                stack.push(child);
            }
            continue;
        }
        if !ft.is_file() {
            continue;
        }

        if process_file(&path, &meta, prefix_bytes, &re_prefix, &re_env, &finder_data, opts.dry_run) {
            count += 1;
        }
    }

    count
}

fn process_file(
    path: &Path,
    meta: &fs::Metadata,
    prefix_bytes: &[u8],
    re_prefix: &Regex,
    re_env: &Regex,
    finder_data: &memmem::Finder<'_>,
    dry_run: bool,
) -> bool {
    let mut f = match File::open(path) {
        Ok(file) => file,
        Err(_) => return false,
    };

    // Read only the binary-probe header first so large ELFs/archives are skipped
    // without loading tens of megabytes into RAM.
    let mut probe = [0u8; BINARY_PROBE_BYTES];
    let mut probe_len = 0usize;
    while probe_len < BINARY_PROBE_BYTES {
        match f.read(&mut probe[probe_len..]) {
            Ok(0) => break,
            Ok(n) => probe_len += n,
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(_) => return false,
        }
    }
    if probe_len == 0 {
        return false;
    }
    if memchr::memchr(0, &probe[..probe_len]).is_some() {
        return false;
    }

    let mut data = Vec::with_capacity(meta.len().min(64 * 1024) as usize + probe_len);
    data.extend_from_slice(&probe[..probe_len]);
    if probe_len == BINARY_PROBE_BYTES && f.read_to_end(&mut data).is_err() {
        return false;
    }

    let has_shebang = data.starts_with(b"#!");
    let has_data = finder_data.find(&data).is_some();
    if !has_shebang && !has_data {
        return false;
    }

    let line1_end = memchr::memchr(b'\n', &data).unwrap_or(data.len());
    let env_cap = if has_shebang {
        re_env.captures(&data[..line1_end])
    } else {
        None
    };

    let has_stale_prefix = has_data
        && re_prefix
            .find_iter(&data)
            .any(|m| m.as_bytes() != prefix_bytes);

    if env_cap.is_none() && !has_stale_prefix {
        return false;
    }

    // Step 1: rewrite line-1 #!/usr/bin/env [-S] <interp> -> #!<prefix>/bin/<interp>
    let after_env: Cow<'_, [u8]> = if let Some(cap) = env_cap {
        let full = cap.get(0).unwrap();
        let interp = cap.get(1).unwrap().as_bytes();
        let mut buf = Vec::with_capacity(data.len() + prefix_bytes.len() + 8);
        buf.extend_from_slice(b"#!");
        buf.extend_from_slice(prefix_bytes);
        buf.extend_from_slice(b"/bin/");
        buf.extend_from_slice(interp);
        buf.extend_from_slice(&data[full.end()..]);
        Cow::Owned(buf)
    } else {
        Cow::Borrowed(&data)
    };

    // Step 2: rewrite stale /data/(data|user/<id>)/<pkg>/files/usr prefixes
    let final_bytes: Cow<'_, [u8]> = if has_stale_prefix {
        replace_bytes_if_changed(re_prefix, after_env.as_ref(), prefix_bytes)
            .map(Cow::Owned)
            .unwrap_or(after_env)
    } else {
        after_env
    };

    if final_bytes.as_ref() == data.as_slice() {
        return false;
    }

    if dry_run {
        println!("[dry-run] {}", path.display());
        return true;
    }

    atomic_replace_preserving_meta(path, meta, final_bytes.as_ref())
}

fn replace_bytes_if_changed(re: &Regex, input: &[u8], replacement: &[u8]) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity(input.len());
    let mut last = 0usize;
    let mut changed = false;

    for m in re.find_iter(input) {
        if m.as_bytes() == replacement {
            continue;
        }
        changed = true;
        out.extend_from_slice(&input[last..m.start()]);
        out.extend_from_slice(replacement);
        last = m.end();
    }

    if !changed {
        return None;
    }
    out.extend_from_slice(&input[last..]);
    Some(out)
}

fn atomic_replace_preserving_meta(path: &Path, meta: &fs::Metadata, content: &[u8]) -> bool {
    let parent = path
        .parent()
        .filter(|p| !p.as_os_str().is_empty())
        .unwrap_or_else(|| Path::new("."));
    let pid = std::process::id();

    let (mut file, tmp_path) = loop {
        let seq = TEMP_SEQ.fetch_add(1, Ordering::Relaxed);
        let candidate = parent.join(format!(".agyfix.{pid}.{seq}"));
        match OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&candidate)
        {
            Ok(f) => break (f, candidate),
            Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => continue,
            Err(_) => return false,
        }
    };

    if file.write_all(content).is_err() {
        drop(file);
        let _ = fs::remove_file(&tmp_path);
        return false;
    }
    drop(file);

    if let Ok(c_tmp) = CString::new(tmp_path.as_os_str().as_bytes()) {
        unsafe {
            let _ = libc::chown(
                c_tmp.as_ptr(),
                meta.uid() as libc::uid_t,
                meta.gid() as libc::gid_t,
            );
            let _ = libc::chmod(c_tmp.as_ptr(), (meta.mode() & 0o7777) as libc::mode_t);
        }
    }

    if fs::rename(&tmp_path, path).is_err() {
        let _ = fs::remove_file(&tmp_path);
        return false;
    }
    true
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::PermissionsExt;

    struct TempDirGuard(PathBuf);
    impl TempDirGuard {
        fn new(prefix: &str) -> Self {
            let mut p = std::env::temp_dir();
            let seq = TEMP_SEQ.fetch_add(1, Ordering::Relaxed);
            p.push(format!("{}_{}_{}", prefix, std::process::id(), seq));
            let _ = fs::remove_dir_all(&p);
            fs::create_dir_all(&p).unwrap();
            Self(p)
        }
    }
    impl Drop for TempDirGuard {
        fn drop(&mut self) {
            fn make_writable(dir: &Path) {
                if let Ok(entries) = fs::read_dir(dir) {
                    for e in entries.flatten() {
                        let p = e.path();
                        if let Ok(m) = fs::symlink_metadata(&p) {
                            if m.file_type().is_symlink() {
                                continue;
                            }
                            if m.is_dir() {
                                let _ = fs::set_permissions(&p, fs::Permissions::from_mode(0o700));
                                make_writable(&p);
                            } else {
                                let _ = fs::set_permissions(&p, fs::Permissions::from_mode(0o600));
                            }
                        }
                    }
                }
            }
            make_writable(&self.0);
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    #[test]
    fn fix_paths_golden_and_dry_run() {
        let tmp = TempDirGuard::new("agy_fp_rust");
        let prefix = tmp.0.join("user0").join("com.agy.new").join("files").join("usr");
        let tree = prefix.join("tree");
        let ext_dir = tmp.0.join("ext");
        fs::create_dir_all(&tree).unwrap();
        fs::create_dir_all(&ext_dir).unwrap();

        let p_str = prefix.to_str().unwrap();

        let f_stale = tree.join("run.sh");
        fs::write(
            &f_stale,
            "#!/data/data/com.termux/files/usr/bin/bash\nX=/data/user/10/com.other/files/usr/lib\n",
        )
        .unwrap();
        fs::set_permissions(&f_stale, fs::Permissions::from_mode(0o755)).unwrap();

        let f_clean = tree.join("clean.txt");
        fs::write(&f_clean, format!("#!{p_str}/bin/python3\nP={p_str}/etc\n")).unwrap();
        let clean_ino = fs::symlink_metadata(&f_clean).unwrap().ino();

        let f_env_s = tree.join("tool.py");
        fs::write(&f_env_s, "#! /usr/bin/env -S python3 -u\nprint(1)\n").unwrap();
        fs::set_permissions(&f_env_s, fs::Permissions::from_mode(0o750)).unwrap();

        let f_line2 = tree.join("line2.txt");
        fs::write(&f_line2, "# hdr\n#!/usr/bin/env bash\n").unwrap();
        let line2_ino = fs::symlink_metadata(&f_line2).unwrap().ino();

        let f_bin = tree.join("elf.bin");
        fs::write(&f_bin, b"\x7fELF\x00/data/data/com.termux/files/usr/bin/sh\x00").unwrap();
        let bin_ino = fs::symlink_metadata(&f_bin).unwrap().ino();

        let f_ro = tree.join("ro.conf");
        fs::write(&f_ro, "a=/data/user/0/com.old/files/usr/etc\n").unwrap();
        fs::set_permissions(&f_ro, fs::Permissions::from_mode(0o444)).unwrap();

        let ext_file = ext_dir.join("outside.sh");
        fs::write(&ext_file, "#!/data/data/com.termux/files/usr/bin/sh\n").unwrap();
        let link = tree.join("sym.sh");
        std::os::unix::fs::symlink(&ext_file, &link).unwrap();

        let special_dir = tree.join("dir with spaces").join("a & @ b");
        fs::create_dir_all(&special_dir).unwrap();
        let f_special = special_dir.join("x & @ \\.sh");
        fs::write(&f_special, "#!/data/data/com.termux/files/usr/bin/sh\n").unwrap();

        // Dry run must count 4 files without changing anything
        let dry_opts = FixOptions {
            dry_run: true,
            targets: vec![tree.clone()],
        };
        assert_eq!(execute_fix_paths(&prefix, &dry_opts), 4);
        assert!(fs::read_to_string(&f_stale).unwrap().contains("com.termux"));

        // Real run
        let real_opts = FixOptions {
            dry_run: false,
            targets: vec![tree.clone()],
        };
        assert_eq!(execute_fix_paths(&prefix, &real_opts), 4);

        assert_eq!(
            fs::read_to_string(&f_stale).unwrap(),
            format!("#!{p_str}/bin/bash\nX={p_str}/lib\n")
        );
        assert_eq!(fs::symlink_metadata(&f_stale).unwrap().mode() & 0o7777, 0o755);
        assert_eq!(
            fs::read_to_string(&f_env_s).unwrap(),
            format!("#!{p_str}/bin/python3 -u\nprint(1)\n")
        );
        assert_eq!(fs::symlink_metadata(&f_env_s).unwrap().mode() & 0o7777, 0o750);
        assert_eq!(fs::symlink_metadata(&f_ro).unwrap().mode() & 0o7777, 0o444);
        assert_eq!(fs::symlink_metadata(&f_clean).unwrap().ino(), clean_ino);
        assert_eq!(fs::symlink_metadata(&f_line2).unwrap().ino(), line2_ino);
        assert_eq!(fs::symlink_metadata(&f_bin).unwrap().ino(), bin_ino);
        assert!(fs::symlink_metadata(&link).unwrap().file_type().is_symlink());
        assert!(fs::read_to_string(&ext_file).unwrap().contains("com.termux"));
        assert_eq!(
            fs::read_to_string(&f_special).unwrap(),
            format!("#!{p_str}/bin/sh\n")
        );

        // Idempotent second run
        assert_eq!(execute_fix_paths(&prefix, &real_opts), 0);
    }

    #[test]
    fn special_chars_in_prefix() {
        let tmp = TempDirGuard::new("agy_fp_sp_pfx");
        let prefix = tmp.0.join("dir space").join("a&b@c\\d").join("files").join("usr");
        fs::create_dir_all(&prefix).unwrap();
        let fp = prefix.join("combo.py");
        fs::write(&fp, "#!/usr/bin/env python3\nP='/data/data/com.termux/files/usr'\n").unwrap();

        let opts = FixOptions {
            dry_run: false,
            targets: vec![prefix.clone()],
        };
        assert_eq!(execute_fix_paths(&prefix, &opts), 1);
        let p_str = prefix.to_str().unwrap();
        assert_eq!(
            fs::read_to_string(&fp).unwrap(),
            format!("#!{p_str}/bin/python3\nP='{p_str}'\n")
        );
        assert_eq!(execute_fix_paths(&prefix, &opts), 0);
    }
}
