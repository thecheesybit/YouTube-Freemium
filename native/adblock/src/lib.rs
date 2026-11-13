//! Movy adblock core: a thin, thread-safe wrapper over Brave's `adblock` crate.
//! See README.md for the design notes.

mod error;
mod resources;
mod shell;

#[cfg(feature = "jni")]
mod jni;

pub use adblock;
pub use error::ShellError;
pub use resources::parse_resources;
pub use shell::{CheckResult, Shell};
