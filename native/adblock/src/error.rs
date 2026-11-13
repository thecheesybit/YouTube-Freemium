//! Error type shared by the library, JNI layer and CLI.

use std::fmt;

#[derive(Debug)]
pub enum ShellError {
    /// The serialized engine could not be decoded (corrupt, truncated or built by another crate version).
    Deserialize(String),
    /// `resources.json` was not valid Brave resource JSON.
    Resources(String),
}

impl fmt::Display for ShellError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ShellError::Deserialize(m) => write!(f, "cannot load engine data: {m}"),
            ShellError::Resources(m) => write!(f, "invalid resources json: {m}"),
        }
    }
}

impl std::error::Error for ShellError {}
