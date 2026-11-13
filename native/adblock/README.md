# movy-adblock

Thin wrapper around Brave's [`adblock`](https://crates.io/crates/adblock) crate (**0.13.5**) used by the
Android shells (via JNI) and the build tooling (`build-engine`).

## Layout
- `src/shell.rs` – `Shell` (engine + resources) and `CheckResult`; `src/resources.rs` – resources.json parsing
- `src/jni.rs` – JNI exports for `app.movy.adblock.NativeBridge` (feature `jni`)
- `src/bin/build-engine.rs` – host CLI
- `android/NativeBridge.java` – Java side of the JNI bridge (copy into the Android project)
- `build-android.ps1` – `cargo ndk` build of `libmovy_adblock.so` (arm64-v8a, armeabi-v7a, x86_64, API 24)

## Design decisions
- **Features**: `adblock` is used with `default-features = false` plus `embedded-domain-resolver` and
  `full-regex-handling`. The default `single-thread` feature is disabled on purpose: with it the engine is
  not `Send`/`Sync`. Without it `Engine: Send + Sync` (asserted in the crate) and all queries take `&self`,
  so `Shell` needs **no lock** and can be shared across WebView IO threads (`Arc<Shell>` / a raw handle).
- **Resources are NOT serialized.** `Engine::serialize()` only stores the compiled rules; resources
  (redirect bodies for `$redirect`, scriptlets for `##+js()`) live in a separate in-memory store. They are
  Brave's `resources.json` (`Vec<adblock::resources::Resource>` via serde: `name`, `aliases`, `kind`,
  base64 `content`, ...) and must be supplied at every load:
  `Shell::from_serialized(bytes, Some(resources_json))` / `nativeCreate(dat, resourcesJson)`.
  Without them redirect rules still block (no replacement body) and scriptlets are not injected.
  `build-engine --resources` only validates the JSON; ship `resources.json` next to `engine.dat`.
- **engine.dat is version-coupled**: build it with the same `adblock` crate version as the runtime
  (host CLI and `.so` share `Cargo.lock`). Mismatched versions fail with `ShellError::Deserialize`
  (a handle of 0 over JNI) – rebuild the dat.
- `from_rules` / `from_serialized` return `Result` (invalid resources JSON / corrupt data) instead of the
  infallible `from_rules` in PLAN.md.
- `check()` never panics on bad URLs: unparsable URL -> `CheckResult::default()` (not matched).
  `redirect` is a `data:<mime>;base64,...` URL. `exception` is the matched `@@` rule text, or `""` when the
  engine was built without debug info (the usual case) – use `Some(_)` as "an exception applied".
- `matched` = `BlockerResult::should_block()` (important, or block rule without exception).
- Request types: `document, subdocument, script, stylesheet, image, media, font, xmlhttprequest, fetch,
  ping, beacon, websocket, object, other` (unknown strings are treated as `other`).
- JNI uses `jni` 0.21.1 (the 0.22 API is a different, less documented design). Every entry point is wrapped
  in `catch_unwind`; null strings and handle 0 return null/0. Do not use a handle concurrently with or
  after `nativeDestroy` (the Kotlin singleton should simply never destroy it while in use).

## Public API
```rust
pub struct Shell; pub struct CheckResult { matched: bool, important: bool, redirect: Option<String>, exception: Option<String> }
Shell::from_serialized(&[u8], Option<&str>) -> Result<Shell, ShellError>
Shell::from_rules(&str, Option<&str>) -> Result<Shell, ShellError>
Shell::from_engine(adblock::Engine, Option<&str>) -> Result<Shell, ShellError>
Shell::serialize(&self) -> Vec<u8>
Shell::check(&self, url, source_url, request_type: &str) -> CheckResult
Shell::url_cosmetic_resources(&self, url) -> String   // {"hide_selectors":[],"procedural_actions":[],"exceptions":[],"injected_script":"","generichide":false}
Shell::hidden_class_id_selectors(&self, &[String], &[String], &[String]) -> String // JSON array
```

## CLI
```
build-engine --out engine.dat [--resources resources.json] [--hosts hosts.txt]... easylist.txt easyprivacy.txt
build-engine --dat engine.dat [--resources resources.json] --check <url> --source <url> --type script
```

## Build / test
```
cargo test
cargo build --release --bin build-engine
./build-android.ps1 [-OutDir <jniLibs dir>]    # needs cargo-ndk, Android NDK, android rust targets
```
