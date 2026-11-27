use crate::gateway::{
    post_gateway_json, read_gateway_token, resolve_gateway_port, resolve_prefix_and_home,
};
use std::env;
use std::ffi::CString;
use std::fmt::Write as _;
use std::os::unix::ffi::OsStrExt;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::{SystemTime, UNIX_EPOCH};

const USAGE: &str = "Usage: agy-notify [post|cancel|list] [options]\n\
Commands:\n  \
  post    -t <title> [-s <subtitle>] [-m <message>] [--tag <tag>] [-c|--channel <channel>] [--cascade-id <id>]\n  \
  cancel  <tag>\n  \
  list    Show active notifications\n";

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum NotifyCmd {
    Post {
        title: String,
        subtitle: String,
        message: String,
        tag: String,
        channel: String,
        cascade_id: String,
    },
    Cancel {
        tag: String,
    },
    List,
}

pub fn posix_sh_quote(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    out.push('\'');
    for ch in s.chars() {
        if ch == '\'' {
            out.push_str("'\\''");
        } else {
            out.push(ch);
        }
    }
    out.push('\'');
    out
}

pub fn stable_notification_id(tag: &str) -> u32 {
    let crc = crc32fast::hash(tag.as_bytes()) & 0x7fff_ffff;
    if crc == 0 {
        1
    } else {
        crc
    }
}

/// Escape string identically to Python's `json.dumps(..., ensure_ascii=True)`.
fn py_json_escape(s: &str, out: &mut String) {
    out.push('"');
    for ch in s.chars() {
        match ch {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\x08' => out.push_str("\\b"),
            '\x0c' => out.push_str("\\f"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => {
                let _ = write!(out, "\\u{:04x}", c as u32);
            }
            c if (c as u32) <= 0x7e => out.push(c),
            c => {
                let code = c as u32;
                if code <= 0xffff {
                    let _ = write!(out, "\\u{:04x}", code);
                } else {
                    let v = code - 0x1_0000;
                    let high = 0xd800 + (v >> 10);
                    let low = 0xdc00 + (v & 0x3ff);
                    let _ = write!(out, "\\u{:04x}\\u{:04x}", high, low);
                }
            }
        }
    }
    out.push('"');
}

/// Build JSON envelope byte-for-byte identical to Python `json.dumps(envelope)` in `agy-notify`.
pub fn build_gateway_envelope_json(
    action: &str,
    title: &str,
    message: &str,
    tag: &str,
    channel: &str,
    cascade_id: &str,
) -> String {
    let stable_id = stable_notification_id(tag);
    let mut out = String::with_capacity(160 + title.len() + message.len() + tag.len());
    out.push_str("{\"command\": \"notify\", \"payload\": {\"action\": ");
    py_json_escape(action, &mut out);
    out.push_str(", \"tag\": ");
    py_json_escape(tag, &mut out);
    let _ = write!(out, ", \"id\": {}", stable_id);
    out.push_str(", \"title\": ");
    py_json_escape(title, &mut out);
    out.push_str(", \"message\": ");
    py_json_escape(message, &mut out);
    if !channel.is_empty() {
        out.push_str(", \"channel\": ");
        py_json_escape(channel, &mut out);
    }
    if !cascade_id.is_empty() {
        out.push_str(", \"cascade_id\": ");
        py_json_escape(cascade_id, &mut out);
    }
    out.push_str("}}");
    out
}

pub fn parse_args(args: &[String], default_ts: u64) -> Result<NotifyCmd, (i32, String)> {
    let cmd = args.first().map(String::as_str).unwrap_or("post");
    let rest = if args.is_empty() { &[][..] } else { &args[1..] };

    if cmd == "cancel" {
        if rest.len() != 1 || rest[0].is_empty() {
            return Err((
                2,
                format!("agy-notify: cancel requires exactly one tag\n{USAGE}"),
            ));
        }
        return Ok(NotifyCmd::Cancel {
            tag: rest[0].clone(),
        });
    }

    if cmd != "post" && cmd != "list" {
        return Err((1, USAGE.to_string()));
    }

    let mut title = "Antigravity".to_string();
    let mut subtitle = String::new();
    let mut message = String::new();
    let mut tag = format!("agy_{default_ts}");
    let mut channel = String::new();
    let mut cascade_id = String::new();

    let mut i = 0;
    while i < rest.len() {
        let arg = rest[i].as_str();
        match arg {
            "-t" | "--title" => {
                if i + 1 >= rest.len() {
                    return Err((2, format!("agy-notify: missing value for {arg}")));
                }
                title = rest[i + 1].clone();
                i += 2;
            }
            "-s" | "--subtitle" => {
                if i + 1 >= rest.len() {
                    return Err((2, format!("agy-notify: missing value for {arg}")));
                }
                subtitle = rest[i + 1].clone();
                i += 2;
            }
            "-m" | "--message" => {
                if i + 1 >= rest.len() {
                    return Err((2, format!("agy-notify: missing value for {arg}")));
                }
                message = rest[i + 1].clone();
                i += 2;
            }
            "--tag" => {
                if i + 1 >= rest.len() {
                    return Err((2, format!("agy-notify: missing value for {arg}")));
                }
                tag = rest[i + 1].clone();
                i += 2;
            }
            "-c" | "--channel" => {
                if i + 1 >= rest.len() {
                    return Err((2, format!("agy-notify: missing value for {arg}")));
                }
                channel = rest[i + 1].clone();
                i += 2;
            }
            "--cascade-id" => {
                if i + 1 >= rest.len() {
                    return Err((2, format!("agy-notify: missing value for {arg}")));
                }
                cascade_id = rest[i + 1].clone();
                i += 2;
            }
            other => {
                if message.is_empty() {
                    message = other.to_string();
                }
                i += 1;
            }
        }
    }

    if cmd == "list" {
        return Ok(NotifyCmd::List);
    }

    if message.is_empty() && !title.is_empty() {
        message = title;
        title = "Antigravity".to_string();
    }

    Ok(NotifyCmd::Post {
        title,
        subtitle,
        message,
        tag,
        channel,
        cascade_id,
    })
}

fn is_executable_file(path: &Path) -> bool {
    if !path.is_file() {
        return false;
    }
    match CString::new(path.as_os_str().as_bytes()) {
        Ok(c_path) => unsafe { libc::access(c_path.as_ptr(), libc::X_OK) == 0 },
        Err(_) => false,
    }
}

fn try_gateway_notify(
    home_dir: &Path,
    action: &str,
    title: &str,
    message: &str,
    tag: &str,
    channel: &str,
    cascade_id: &str,
) -> bool {
    let token = match read_gateway_token(home_dir) {
        Some(t) => t,
        None => return false,
    };
    let port = resolve_gateway_port(home_dir);
    let body = build_gateway_envelope_json(
        action,
        title,
        message,
        tag,
        channel.trim(),
        cascade_id.trim(),
    );
    matches!(post_gateway_json(port, &token, &body, 3), Ok(true))
}

fn resolve_logo_path(prefix: &Path) -> Option<PathBuf> {
    if let Ok(env_logo) = env::var("AGY_LOGO") {
        let trimmed = env_logo.trim();
        if !trimmed.is_empty() {
            let p = PathBuf::from(trimmed);
            if p.is_file() {
                return Some(p);
            }
        }
    }
    for rel in [
        "share/antigravity/logo.png",
        "share/pixmaps/antigravity.png",
    ] {
        let p = prefix.join(rel);
        if p.is_file() {
            return Some(p);
        }
    }
    None
}

fn rish_test_file(rish_bin: &Path, target: &str) -> bool {
    let cmd = format!("test -f {}", posix_sh_quote(target));
    Command::new(rish_bin)
        .args(["-c", &cmd])
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .status()
        .map(|s| s.success())
        .unwrap_or(false)
}

pub fn run(args: &[String]) -> i32 {
    let ts = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);

    let parsed = match parse_args(args, ts) {
        Ok(c) => c,
        Err((code, msg)) => {
            if code == 2 {
                eprint!("{msg}");
                if !msg.ends_with('\n') {
                    eprintln!();
                }
            } else {
                print!("{msg}");
                if !msg.ends_with('\n') {
                    println!();
                }
            }
            return code;
        }
    };

    let ph = resolve_prefix_and_home();
    let rish_bin = ph.prefix.join("bin").join("rish");

    match parsed {
        NotifyCmd::Post {
            title,
            subtitle,
            message,
            tag,
            channel,
            cascade_id,
        } => {
            let body_text = if subtitle.is_empty() {
                message.clone()
            } else {
                format!("[{subtitle}] {message}")
            };

            if try_gateway_notify(
                &ph.home,
                "post",
                &title,
                &body_text,
                &tag,
                &channel,
                &cascade_id,
            ) {
                return 0;
            }

            if is_executable_file(&rish_bin) {
                let logo_cache = "/data/local/tmp/agy_logo.png";
                let mut large_icon_opt = String::new();
                if let Some(src_logo) = resolve_logo_path(&ph.prefix) {
                    if !rish_test_file(&rish_bin, logo_cache) {
                        let q_src = posix_sh_quote(&src_logo.to_string_lossy());
                        let q_dst = posix_sh_quote(logo_cache);
                        let cp_cmd = format!("cp -f {q_src} {q_dst} && chmod 644 {q_dst}");
                        let _ = Command::new(&rish_bin)
                            .args(["-c", &cp_cmd])
                            .stdout(std::process::Stdio::null())
                            .stderr(std::process::Stdio::null())
                            .status();
                    }
                    if rish_test_file(&rish_bin, logo_cache) {
                        large_icon_opt = format!("-I file://{logo_cache}");
                    }
                }

                let rish_cmd = format!(
                    "cmd notification post -S bigtext {} -t {} {} {}",
                    large_icon_opt,
                    posix_sh_quote(&title),
                    posix_sh_quote(&tag),
                    posix_sh_quote(&body_text),
                );
                if Command::new(&rish_bin)
                    .args(["-c", &rish_cmd])
                    .stdout(std::process::Stdio::null())
                    .stderr(std::process::Stdio::null())
                    .status()
                    .map(|s| s.success())
                    .unwrap_or(false)
                {
                    return 0;
                }
            }

            if Command::new("cmd")
                .args([
                    "notification",
                    "post",
                    "-S",
                    "bigtext",
                    "-t",
                    &title,
                    &tag,
                    &body_text,
                ])
                .stdout(std::process::Stdio::null())
                .stderr(std::process::Stdio::null())
                .status()
                .map(|s| s.success())
                .unwrap_or(false)
            {
                return 0;
            }

            println!("[agy-notify] notification queued: {title} - {message}");
            0
        }
        NotifyCmd::Cancel { tag } => {
            if try_gateway_notify(&ph.home, "cancel", "", "", &tag, "", "") {
                return 0;
            }
            if is_executable_file(&rish_bin) {
                let rish_cmd = format!("cmd notification cancel {}", posix_sh_quote(&tag));
                let _ = Command::new(&rish_bin)
                    .args(["-c", &rish_cmd])
                    .stdout(std::process::Stdio::null())
                    .stderr(std::process::Stdio::null())
                    .status();
            }
            0
        }
        NotifyCmd::List => {
            if is_executable_file(&rish_bin) {
                if let Ok(out) = Command::new(&rish_bin)
                    .args(["-c", "dumpsys notification --noredact"])
                    .stderr(std::process::Stdio::null())
                    .output()
                {
                    let text = String::from_utf8_lossy(&out.stdout);
                    let mut printed = 0usize;
                    for line in text.lines() {
                        if line.contains("pkg=")
                            || line.contains("android.title=")
                            || line.contains("android.text=")
                        {
                            println!("{line}");
                            printed += 1;
                            if printed >= 40 {
                                break;
                            }
                        }
                    }
                }
            } else {
                println!("[agy-notify] rish unavailable to inspect system notification tray");
            }
            0
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn crc32_stable_id_matches_python_zlib() {
        // Verified against Python (zlib.crc32(s.encode('utf-8')) & 0x7fffffff) or 1
        assert_eq!(stable_notification_id("turn_abc"), 1250647373);
        assert_eq!(stable_notification_id("post_123"), 1359998625);
    }

    #[test]
    fn json_envelope_matches_python_json_dumps_byte_for_byte() {
        let json = build_gateway_envelope_json(
            "post",
            "Agent \"Done\" \n",
            "Привет 😀 'test'",
            "turn_abc",
            "agy_alerts",
            "conv-1",
        );
        let expected = concat!(
            r#"{"command": "notify", "payload": {"action": "post", "tag": "turn_abc", "#,
            r#""id": 1250647373, "title": "Agent \"Done\" \n", "#,
            r#""message": "\u041f\u0440\u0438\u0432\u0435\u0442 \ud83d\ude00 'test'", "#,
            r#""channel": "agy_alerts", "cascade_id": "conv-1"}}"#,
        );
        assert_eq!(json, expected);
    }

    #[test]
    fn posix_sh_quote_is_strict_single_quoted() {
        assert_eq!(posix_sh_quote(""), "''");
        assert_eq!(posix_sh_quote("simple"), "'simple'");
        assert_eq!(
            posix_sh_quote("hi'; touch $(id) `x`"),
            "'hi'\\''; touch $(id) `x`'"
        );
    }

    #[test]
    fn parse_args_behaviors() {
        let err = parse_args(&["cancel".into()], 100).unwrap_err();
        assert_eq!(err.0, 2);

        let err2 = parse_args(&["cancel".into(), "".into()], 100).unwrap_err();
        assert_eq!(err2.0, 2);

        let ok = parse_args(&["post".into(), "-t".into(), "OnlyTitle".into()], 123).unwrap();
        assert_eq!(
            ok,
            NotifyCmd::Post {
                title: "Antigravity".into(),
                subtitle: String::new(),
                message: "OnlyTitle".into(),
                tag: "agy_123".into(),
                channel: String::new(),
                cascade_id: String::new(),
            }
        );

        let unknown = parse_args(&["bogus".into()], 100).unwrap_err();
        assert_eq!(unknown.0, 1);
    }
}
