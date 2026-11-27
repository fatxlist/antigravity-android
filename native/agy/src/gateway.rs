use std::env;
use std::fs;
use std::path::{Component, Path, PathBuf};
use std::time::Duration;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PrefixHome {
    pub prefix: PathBuf,
    pub home: PathBuf,
}

pub fn resolve_prefix_and_home() -> PrefixHome {
    let prefix = match env::var_os("PREFIX") {
        Some(val) if !val.is_empty() => PathBuf::from(val),
        _ => infer_prefix_from_exe().unwrap_or_else(|| PathBuf::from("/usr")),
    };

    let home = match env::var_os("HOME") {
        Some(val) if !val.is_empty() => PathBuf::from(val),
        _ => prefix
            .parent()
            .filter(|p| !p.as_os_str().is_empty())
            .map(Path::to_path_buf)
            .unwrap_or_else(|| PathBuf::from("/")),
    };

    PrefixHome { prefix, home }
}

fn infer_prefix_from_exe() -> Option<PathBuf> {
    let exe = env::current_exe().ok()?;
    let real = fs::canonicalize(&exe).unwrap_or(exe);
    let bin_dir = real.parent()?;
    let prefix = bin_dir.parent()?;
    if prefix.as_os_str().is_empty() {
        None
    } else {
        Some(prefix.to_path_buf())
    }
}

pub fn read_gateway_token(home_dir: &Path) -> Option<String> {
    let env_tok = env::var("AGY_GATEWAY_TOKEN").ok();
    let env_file = env::var_os("AGY_GATEWAY_TOKEN_FILE").map(PathBuf::from);
    read_gateway_token_from(home_dir, env_tok.as_deref(), env_file.as_deref())
}

pub fn read_gateway_token_from(
    home_dir: &Path,
    env_token: Option<&str>,
    env_token_file: Option<&Path>,
) -> Option<String> {
    if let Some(tok) = env_token {
        let trimmed = tok.trim();
        if !trimmed.is_empty() {
            return Some(trimmed.to_string());
        }
    }

    let token_path = match env_token_file {
        Some(p) if !p.as_os_str().is_empty() => p.to_path_buf(),
        _ => home_dir.join("logs").join("gateway.token"),
    };

    let raw = fs::read_to_string(token_path).ok()?;
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        None
    } else {
        Some(trimmed.to_string())
    }
}

pub fn resolve_gateway_port(home_dir: &Path) -> u16 {
    let env_port = env::var("AGY_GATEWAY_PORT").ok();
    let app_id_env = env::var("AGY_APPLICATION_ID")
        .ok()
        .filter(|s| !s.trim().is_empty())
        .or_else(|| {
            env::var("RISH_APPLICATION_ID")
                .ok()
                .filter(|s| !s.trim().is_empty())
        });
    resolve_gateway_port_from(home_dir, env_port.as_deref(), app_id_env.as_deref())
}

pub fn resolve_gateway_port_from(
    home_dir: &Path,
    env_port: Option<&str>,
    app_id_override: Option<&str>,
) -> u16 {
    if let Some(raw) = env_port {
        if let Some(port) = parse_valid_port(raw) {
            return port;
        }
    }

    let port_file = home_dir.join("logs").join("gateway.port");
    if let Ok(raw) = fs::read_to_string(port_file) {
        if let Some(port) = parse_valid_port(&raw) {
            return port;
        }
    }

    let state_file = home_dir.join("logs").join("core.state.json");
    if let Ok(raw) = fs::read_to_string(state_file) {
        if let Ok(val) = serde_json::from_str::<serde_json::Value>(&raw) {
            if let Some(gp) = val.get("gateway_port") {
                if let Some(n) = gp.as_u64() {
                    if (1..=65535).contains(&n) {
                        return n as u16;
                    }
                } else if let Some(s) = gp.as_str() {
                    if let Some(port) = parse_valid_port(s) {
                        return port;
                    }
                }
            }
        }
    }

    let app_id = app_id_override
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| infer_app_id_from_home(home_dir));

    if app_id.ends_with(".dev") {
        45161
    } else {
        45160
    }
}

fn parse_valid_port(raw: &str) -> Option<u16> {
    let trimmed = raw.trim();
    if trimmed.is_empty() || !trimmed.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    let val: u32 = trimmed.parse().ok()?;
    if (1..=65535).contains(&val) {
        Some(val as u16)
    } else {
        None
    }
}

fn infer_app_id_from_home(home_dir: &Path) -> String {
    let mut parts: Vec<String> = Vec::new();
    for comp in home_dir.components() {
        match comp {
            Component::Normal(s) => parts.push(s.to_string_lossy().into_owned()),
            Component::CurDir => {}
            Component::ParentDir => {
                parts.pop();
            }
            Component::RootDir | Component::Prefix(_) => {}
        }
    }
    if parts.last().map(String::as_str) == Some("files") && parts.len() >= 2 {
        parts[parts.len() - 2].clone()
    } else {
        String::new()
    }
}

pub fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut diff: u8 = 0;
    for (&x, &y) in a.iter().zip(b.iter()) {
        diff |= x ^ y;
    }
    diff == 0
}

pub fn post_gateway_json(
    port: u16,
    token: &str,
    json_body: &str,
    timeout_secs: u64,
) -> Result<bool, String> {
    let agent = ureq::AgentBuilder::new()
        .timeout(Duration::from_secs(timeout_secs))
        .build();
    let url = format!("http://127.0.0.1:{}/gateway", port);
    let resp = agent
        .post(&url)
        .set("Content-Type", "application/json")
        .set("X-Agy-Gateway-Token", token)
        .send_string(json_body);

    match resp {
        Ok(r) => {
            if r.status() != 200 {
                return Ok(false);
            }
            let text = r.into_string().unwrap_or_default();
            if text.is_empty() {
                return Ok(false);
            }
            let parsed: serde_json::Value = match serde_json::from_str(&text) {
                Ok(v) => v,
                Err(_) => return Ok(false),
            };
            Ok(parsed.get("ok").and_then(|v| v.as_bool()).unwrap_or(false))
        }
        Err(ureq::Error::Status(_, _)) => Ok(false),
        Err(ureq::Error::Transport(t)) => Err(t.to_string()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::net::TcpListener;
    use std::thread;

    struct TempDirGuard(PathBuf);
    impl TempDirGuard {
        fn new(prefix: &str) -> Self {
            let mut p = env::temp_dir();
            let uniq = format!(
                "{}_{}_{:?}",
                prefix,
                std::process::id(),
                thread::current().id()
            );
            p.push(uniq.replace(|c: char| !c.is_ascii_alphanumeric() && c != '_', "_"));
            let _ = fs::remove_dir_all(&p);
            fs::create_dir_all(&p).unwrap();
            Self(p)
        }
    }
    impl Drop for TempDirGuard {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    #[test]
    fn port_resolution_precedence() {
        let tmp = TempDirGuard::new("agy_gw_port");
        let home = tmp.0.join("user0").join("com.agy.dev").join("files");
        let logs = home.join("logs");
        fs::create_dir_all(&logs).unwrap();

        // 4. Default dev port from home dir app_id
        assert_eq!(resolve_gateway_port_from(&home, None, None), 45161);
        assert_eq!(
            resolve_gateway_port_from(&home, None, Some("com.agy")),
            45160
        );

        // 3. core.state.json
        fs::write(
            logs.join("core.state.json"),
            r#"{"gateway_port": 45999}"#,
        )
        .unwrap();
        assert_eq!(resolve_gateway_port_from(&home, None, None), 45999);

        // 2. gateway.port overrides core.state.json
        fs::write(logs.join("gateway.port"), "45888\n").unwrap();
        assert_eq!(resolve_gateway_port_from(&home, None, None), 45888);

        // 1. AGY_GATEWAY_PORT overrides all
        assert_eq!(resolve_gateway_port_from(&home, Some("45777"), None), 45777);
        // Invalid env falls back to file
        assert_eq!(resolve_gateway_port_from(&home, Some("abc"), None), 45888);
    }

    #[test]
    fn token_resolution_precedence() {
        let tmp = TempDirGuard::new("agy_gw_tok");
        let home = tmp.0.join("files");
        let logs = home.join("logs");
        fs::create_dir_all(&logs).unwrap();

        assert_eq!(read_gateway_token_from(&home, None, None), None);
        fs::write(logs.join("gateway.token"), "  file_tok_123 \n").unwrap();
        assert_eq!(
            read_gateway_token_from(&home, None, None).as_deref(),
            Some("file_tok_123")
        );

        let custom_file = tmp.0.join("custom.tok");
        fs::write(&custom_file, "custom_tok\n").unwrap();
        assert_eq!(
            read_gateway_token_from(&home, None, Some(&custom_file)).as_deref(),
            Some("custom_tok")
        );
        assert_eq!(
            read_gateway_token_from(&home, Some(" env_tok "), Some(&custom_file)).as_deref(),
            Some("env_tok")
        );
    }

    #[test]
    fn constant_time_equality() {
        assert!(constant_time_eq(b"abc123", b"abc123"));
        assert!(!constant_time_eq(b"abc123", b"abc124"));
        assert!(!constant_time_eq(b"abc", b"abcd"));
    }

    #[test]
    fn post_gateway_json_mock_server() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let handle = thread::spawn(move || {
            for _ in 0..2 {
                let (mut stream, _) = listener.accept().unwrap();
                let mut buf = [0u8; 4096];
                let n = stream.read(&mut buf).unwrap();
                let req = String::from_utf8_lossy(&buf[..n]).to_string();
                let lower = req.to_ascii_lowercase();
                if lower.contains("x-agy-gateway-token: good-tok") {
                    let body = r#"{"ok": true}"#;
                    let resp = format!(
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                        body.len(),
                        body
                    );
                    stream.write_all(resp.as_bytes()).unwrap();
                } else {
                    let body = r#"{"ok": false, "error": "unauthorized"}"#;
                    let resp = format!(
                        "HTTP/1.1 401 Unauthorized\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                        body.len(),
                        body
                    );
                    stream.write_all(resp.as_bytes()).unwrap();
                }
            }
        });

        assert_eq!(
            post_gateway_json(port, "good-tok", r#"{"command":"notify"}"#, 3).unwrap(),
            true
        );
        assert_eq!(
            post_gateway_json(port, "bad-tok", r#"{"command":"notify"}"#, 3).unwrap(),
            false
        );
        handle.join().unwrap();
    }
}
