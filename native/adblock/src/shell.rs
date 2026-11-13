//! Thread-safe wrapper around [`adblock::Engine`].

use crate::error::ShellError;
use crate::resources::parse_resources;
use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use adblock::Engine;
use std::collections::HashSet;

/// Outcome of a network-request check.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct CheckResult {
    /// The request should be blocked (or replaced by `redirect` when present).
    pub matched: bool,
    /// An `$important` rule matched; exceptions do not apply.
    pub important: bool,
    /// Replacement resource as a `data:` URL, when a `$redirect` rule matched.
    pub redirect: Option<String>,
    /// Text of the matched exception (`@@`) rule, if any.
    pub exception: Option<String>,
}

/// An adblock engine plus its (separately loaded) scriptlet/redirect resources.
///
/// `adblock::Engine` is `Send + Sync` (the crate's `single-thread` feature is disabled) and every
/// query takes `&self`, so a `Shell` can be shared freely between threads, e.g. in an `Arc`.
pub struct Shell {
    engine: Engine,
}

const _: fn() = || {
    fn assert_send_sync<T: Send + Sync>() {}
    assert_send_sync::<Shell>();
};

impl Shell {
    /// Loads an engine produced by [`Shell::serialize`] / `build-engine`.
    ///
    /// Resources are NOT part of the serialized engine, so they are supplied here.
    pub fn from_serialized(bytes: &[u8], resources_json: Option<&str>) -> Result<Self, ShellError> {
        let mut engine = Engine::default();
        engine
            .deserialize(bytes)
            .map_err(|e| ShellError::Deserialize(format!("{e:?}")))?;
        Self::with_resources(engine, resources_json)
    }

    /// Builds an engine from newline-separated ABP/uBO rules (standard format).
    pub fn from_rules(rules: &str, resources_json: Option<&str>) -> Result<Self, ShellError> {
        let mut set = FilterSet::new(false);
        set.add_filter_list(rules.to_owned(), ParseOptions::default());
        Self::with_resources(Engine::new_with_filter_set(set), resources_json)
    }

    /// Wraps an already built engine (used by the CLI after parsing several lists).
    pub fn from_engine(engine: Engine, resources_json: Option<&str>) -> Result<Self, ShellError> {
        Self::with_resources(engine, resources_json)
    }

    fn with_resources(mut engine: Engine, resources_json: Option<&str>) -> Result<Self, ShellError> {
        if let Some(json) = resources_json {
            engine.use_resources(parse_resources(json)?);
        }
        Ok(Self { engine })
    }

    /// Serializes the rules (not the resources).
    pub fn serialize(&self) -> Vec<u8> {
        self.engine.serialize()
    }

    /// Checks one request. Unparsable URLs are never matched.
    pub fn check(&self, url: &str, source_url: &str, request_type: &str) -> CheckResult {
        let Ok(request) = Request::new(url, source_url, request_type, "get") else {
            return CheckResult::default();
        };
        let result = self.engine.check_network_request(&request);
        CheckResult {
            matched: result.should_block(),
            important: result.important,
            redirect: result.redirect,
            exception: result.exception.map(|e| e.raw_line.unwrap_or_default()),
        }
    }

    /// Cosmetic resources for a page URL as JSON:
    /// `{"hide_selectors":[],"procedural_actions":[],"exceptions":[],"injected_script":"","generichide":false}`.
    pub fn url_cosmetic_resources(&self, url: &str) -> String {
        let res = self.engine.url_cosmetic_resources(url);
        serde_json::to_string(&res).unwrap_or_else(|_| EMPTY_COSMETIC.to_owned())
    }

    /// Generic hide selectors for classes/ids seen on a page, as a JSON array of strings.
    /// `exceptions` comes from [`Shell::url_cosmetic_resources`].
    pub fn hidden_class_id_selectors(
        &self,
        classes: &[String],
        ids: &[String],
        exceptions: &[String],
    ) -> String {
        let exceptions: HashSet<String> = exceptions.iter().cloned().collect();
        let selectors = self.engine.hidden_class_id_selectors(classes, ids, &exceptions);
        serde_json::to_string(&selectors).unwrap_or_else(|_| "[]".to_owned())
    }
}

const EMPTY_COSMETIC: &str =
    r#"{"hide_selectors":[],"procedural_actions":[],"exceptions":[],"injected_script":"","generichide":false}"#;
