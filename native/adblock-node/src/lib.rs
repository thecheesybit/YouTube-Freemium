//! Node-API binding over `movy-adblock::Shell`. Loaded by the Electron main process only.
//! Uses the same `adblock` crate version and `engine.dat` format as the Android JNI layer.

use movy_adblock::Shell;
use napi::bindgen_prelude::Buffer;
use napi::{Error, Result};
use napi_derive::napi;

/// Result of checking one network request.
#[napi(object)]
pub struct CheckResult {
    pub matched: bool,
    pub important: bool,
    /// `data:` URL of the replacement resource for `$redirect` rules.
    pub redirect: Option<String>,
    /// True when an exception (`@@`) rule applied.
    pub exception: bool,
}

#[napi]
pub struct Engine {
    shell: Shell,
}

fn to_err(e: impl std::fmt::Display) -> Error {
    Error::from_reason(e.to_string())
}

#[napi]
impl Engine {
    /// Loads a serialized engine (`engine.dat`) plus the optional `resources.json` text.
    #[napi(factory)]
    pub fn from_serialized(dat: Buffer, resources_json: Option<String>) -> Result<Self> {
        let shell = Shell::from_serialized(&dat, resources_json.as_deref()).map_err(to_err)?;
        Ok(Self { shell })
    }

    /// Builds an engine from newline separated ABP/uBO rules (used by tests and custom setups).
    #[napi(factory)]
    pub fn from_rules(rules: String, resources_json: Option<String>) -> Result<Self> {
        let shell = Shell::from_rules(&rules, resources_json.as_deref()).map_err(to_err)?;
        Ok(Self { shell })
    }

    /// Serializes the rules (resources are not included), e.g. for round-trip tests.
    #[napi]
    pub fn serialize(&self) -> Buffer {
        Buffer::from(self.shell.serialize())
    }

    #[napi]
    pub fn check(&self, url: String, source_url: String, request_type: String) -> CheckResult {
        let r = self.shell.check(&url, &source_url, &request_type);
        CheckResult {
            matched: r.matched,
            important: r.important,
            redirect: r.redirect,
            exception: r.exception.is_some(),
        }
    }

    /// JSON text: `{"hide_selectors":[],"procedural_actions":[],"exceptions":[],"injected_script":"","generichide":false}`.
    #[napi]
    pub fn url_cosmetic_resources(&self, url: String) -> String {
        self.shell.url_cosmetic_resources(&url)
    }

    /// JSON array text of generic hide selectors for the given classes/ids.
    #[napi]
    pub fn hidden_class_id_selectors(&self, classes: Vec<String>, ids: Vec<String>, exceptions: Vec<String>) -> String {
        self.shell.hidden_class_id_selectors(&classes, &ids, &exceptions)
    }
}
