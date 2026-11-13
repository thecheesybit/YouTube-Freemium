//! Movy patch of `windows-link` for the windows-gnu toolchain without binutils.
//! Original uses `kind = "raw-dylib"`, which makes rustc call dlltool.exe + as.exe.
//! Here the functions are plain `extern` declarations resolved against kernel32,
//! which Rust's std already links on every Windows target.
#![no_std]

#[macro_export]
macro_rules! link {
    ($library:literal $abi:literal $($link_name:literal)? fn $($function:tt)*) => (
        extern $abi {
            $(#[link_name=$link_name])?
            pub fn $($function)*;
        }
    )
}
