//! Host CLI: compile filter lists into a serialized engine, or run a debug check against one.
//!
//! build-engine --out engine.dat [--resources resources.json] [--hosts hosts.txt]... <list files...>
//! build-engine --dat engine.dat [--resources resources.json] --check <url> --source <url> --type <t>

use adblock::lists::{FilterFormat, FilterSet, ParseOptions};
use adblock::Engine;
use movy_adblock::Shell;
use std::path::PathBuf;
use std::process::ExitCode;
use std::time::Instant;

const USAGE: &str = "usage:\n  build-engine --out engine.dat [--resources resources.json] [--hosts <file>]... <list files...>\n  build-engine --dat engine.dat [--resources resources.json] --check <url> [--source <url>] [--type <t>]";

#[derive(Default)]
struct Args {
    out: Option<PathBuf>,
    dat: Option<PathBuf>,
    resources: Option<PathBuf>,
    check: Option<String>,
    source: Option<String>,
    request_type: Option<String>,
    hosts: Vec<PathBuf>,
    lists: Vec<PathBuf>,
}

fn parse_args(mut it: impl Iterator<Item = String>) -> Result<Args, String> {
    let mut a = Args::default();
    while let Some(arg) = it.next() {
        let mut value = |name: &str| it.next().ok_or_else(|| format!("{name} needs a value"));
        match arg.as_str() {
            "--out" => a.out = Some(value("--out")?.into()),
            "--dat" => a.dat = Some(value("--dat")?.into()),
            "--resources" => a.resources = Some(value("--resources")?.into()),
            "--check" => a.check = Some(value("--check")?),
            "--source" => a.source = Some(value("--source")?),
            "--type" => a.request_type = Some(value("--type")?),
            "--hosts" => a.hosts.push(value("--hosts")?.into()),
            "-h" | "--help" => return Err(String::new()),
            s if s.starts_with("--") => return Err(format!("unknown option {s}")),
            _ => a.lists.push(arg.into()),
        }
    }
    Ok(a)
}

fn read(path: &PathBuf) -> Result<String, String> {
    std::fs::read_to_string(path).map_err(|e| format!("cannot read {}: {e}", path.display()))
}

/// (network, cosmetic) rule line counts; comments and headers are skipped.
fn count_rules(text: &str) -> (usize, usize) {
    let (mut net, mut cosm) = (0, 0);
    for line in text.lines().map(str::trim) {
        if line.is_empty() || line.starts_with('!') || line.starts_with('[') {
            continue;
        }
        if line.contains("##") || line.contains("#@#") || line.contains("#?#") || line.contains("#$#") {
            cosm += 1;
        } else {
            net += 1;
        }
    }
    (net, cosm)
}

fn build(args: &Args, out: &PathBuf) -> Result<(), String> {
    if args.lists.is_empty() && args.hosts.is_empty() {
        return Err("no filter lists given".into());
    }
    let mut set = FilterSet::new(false);
    let (mut total_net, mut total_cosm) = (0, 0);
    let sources = args
        .lists
        .iter()
        .map(|p| (p, FilterFormat::Standard))
        .chain(args.hosts.iter().map(|p| (p, FilterFormat::Hosts)));
    for (path, format) in sources {
        let text = read(path)?;
        let (net, cosm) = count_rules(&text);
        println!("{}: {net} network, {cosm} cosmetic", path.display());
        total_net += net;
        total_cosm += cosm;
        set.add_filter_list(text, ParseOptions { format, ..ParseOptions::default() });
    }
    println!("total: {total_net} network, {total_cosm} cosmetic");

    let started = Instant::now();
    let engine = Engine::new_with_filter_set(set);
    let resources = args.resources.as_ref().map(read).transpose()?;
    // Validates resources.json early; resources themselves are not part of engine.dat.
    let shell = Shell::from_engine(engine, resources.as_deref()).map_err(|e| e.to_string())?;
    let bytes = shell.serialize();
    println!("compiled in {:?}", started.elapsed());
    std::fs::write(out, &bytes).map_err(|e| format!("cannot write {}: {e}", out.display()))?;
    println!("wrote {} ({} bytes)", out.display(), bytes.len());
    Ok(())
}

fn check(args: &Args, url: &str) -> Result<(), String> {
    let dat = args.dat.as_ref().or(args.out.as_ref()).ok_or("--check needs --dat <engine.dat>")?;
    let bytes = std::fs::read(dat).map_err(|e| format!("cannot read {}: {e}", dat.display()))?;
    let resources = args.resources.as_ref().map(read).transpose()?;
    let started = Instant::now();
    let shell = Shell::from_serialized(&bytes, resources.as_deref()).map_err(|e| e.to_string())?;
    println!("loaded engine in {:?}", started.elapsed());
    let source = args.source.as_deref().unwrap_or("");
    let kind = args.request_type.as_deref().unwrap_or("other");
    let t = Instant::now();
    let r = shell.check(url, source, kind);
    println!("{r:?} ({:?})", t.elapsed());
    println!("verdict: {}", if r.matched { "BLOCKED" } else { "allowed" });
    Ok(())
}

fn run() -> Result<(), String> {
    let args = parse_args(std::env::args().skip(1))?;
    match (&args.check, &args.out) {
        (Some(url), _) => check(&args, url),
        (None, Some(out)) => build(&args, out),
        (None, None) => Err("need --out (build) or --check (debug)".into()),
    }
}

fn main() -> ExitCode {
    match run() {
        Ok(()) => ExitCode::SUCCESS,
        Err(msg) => {
            if !msg.is_empty() {
                eprintln!("error: {msg}");
            }
            eprintln!("{USAGE}");
            if msg.is_empty() { ExitCode::SUCCESS } else { ExitCode::FAILURE }
        }
    }
}
