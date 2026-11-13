//! Parsing of Brave's `resources.json` (a JSON array of `adblock::resources::Resource`).

use crate::error::ShellError;
use adblock::resources::Resource;

/// Parses a resources JSON document. Blank input yields no resources.
pub fn parse_resources(json: &str) -> Result<Vec<Resource>, ShellError> {
    if json.trim().is_empty() {
        return Ok(Vec::new());
    }
    serde_json::from_str(json).map_err(|e| ShellError::Resources(e.to_string()))
}
