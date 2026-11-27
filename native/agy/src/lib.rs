pub mod fix_paths;
pub mod gateway;
pub mod notify;

pub const VERSION: &str = env!("CARGO_PKG_VERSION");

pub fn dispatch<I, S>(args: I) -> i32
where
    I: IntoIterator<Item = S>,
    S: AsRef<str>,
{
    let collected: Vec<String> = args.into_iter().map(|s| s.as_ref().to_string()).collect();
    let argv0 = collected.first().map(String::as_str).unwrap_or("agy");
    let prog = std::path::Path::new(argv0)
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or(argv0);

    match prog {
        "agy-fix-paths" => fix_paths::run(&collected[1..]),
        "agy-notify" => notify::run(&collected[1..]),
        _ => {
            if collected.len() < 2 {
                print_usage();
                return 0;
            }
            match collected[1].as_str() {
                "version" | "--version" | "-V" => {
                    println!("agy {}", VERSION);
                    0
                }
                "fix-paths" => fix_paths::run(&collected[2..]),
                "notify" => notify::run(&collected[2..]),
                "help" | "-h" | "--help" => {
                    print_usage();
                    0
                }
                other => {
                    eprintln!("agy: unknown subcommand '{other}'");
                    print_usage();
                    1
                }
            }
        }
    }
}

fn print_usage() {
    println!(
        "agy {} - Antigravity native multicall helper\n\n\
         Usage:\n  \
           agy <subcommand> [args...]\n  \
           agy-fix-paths [PATH...]\n  \
           agy-notify [post|cancel|list] [options]\n\n\
         Subcommands:\n  \
           version     Print agy version\n  \
           fix-paths   Rewrite hardcoded Android app prefixes and /usr/bin/env shebangs\n  \
           notify      Post, cancel, or list Android notifications\n  \
           help        Show this help message",
        VERSION
    );
}
