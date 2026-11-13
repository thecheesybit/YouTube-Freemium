//! Movy patch: on windows-gnu napi-build normally links libnode.dll, which only exists in custom
//! node builds. N-API symbols are instead resolved at runtime from the host process
//! (napi-sys "dyn-symbols"), so nothing has to be linked here.
pub fn setup_gnu() {}
