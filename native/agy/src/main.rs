fn main() {
    let args: Vec<String> = std::env::args().collect();
    let code = agy::dispatch(args);
    std::process::exit(code);
}
