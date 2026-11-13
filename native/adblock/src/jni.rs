//! JNI exports for `app.movy.adblock.NativeBridge` (feature `jni`).
//!
//! Handles are `Box<Shell>` pointers passed as `jlong`; 0 means "no engine".
//! Panics are caught at every entry point and mapped to a null/0 result.
//! The caller must not use a handle concurrently with or after `nativeDestroy`.

use crate::Shell;
use jni::objects::{JByteArray, JClass, JObjectArray, JString};
use jni::sys::{jlong, jstring};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::ptr;

const NULL_STRING: jstring = ptr::null_mut();

fn read_string(env: &mut JNIEnv, s: &JString) -> Option<String> {
    if s.is_null() {
        return None;
    }
    env.get_string(s).ok().map(String::from)
}

fn read_string_array(env: &mut JNIEnv, arr: &JObjectArray) -> Vec<String> {
    if arr.is_null() {
        return Vec::new();
    }
    let len = env.get_array_length(arr).unwrap_or(0);
    let mut out = Vec::with_capacity(len.max(0) as usize);
    for i in 0..len {
        let Ok(obj) = env.get_object_array_element(arr, i) else { continue };
        let s = JString::from(obj);
        if let Some(v) = read_string(env, &s) {
            out.push(v);
        }
        let _ = env.delete_local_ref(s);
    }
    out
}

fn to_jstring(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s).map(|j| j.into_raw()).unwrap_or(NULL_STRING)
}

fn boxed_handle(shell: Shell) -> jlong {
    Box::into_raw(Box::new(shell)) as jlong
}

/// Borrows the shell behind a handle. The pointer must come from `boxed_handle` and still be live.
fn shell<'a>(handle: jlong) -> Option<&'a Shell> {
    // SAFETY: handles are produced by `boxed_handle` and only invalidated by `nativeDestroy`,
    // which the Java side calls once, after all users are done.
    unsafe { (handle as *const Shell).as_ref() }
}

fn guard<T>(default: T, f: impl FnOnce() -> T) -> T {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or(default)
}

#[no_mangle]
pub extern "system" fn Java_app_movy_adblock_NativeBridge_nativeCreate(
    mut env: JNIEnv,
    _class: JClass,
    engine_dat: JByteArray,
    resources_json: JString,
) -> jlong {
    guard(0, || {
        if engine_dat.is_null() {
            return 0;
        }
        let Ok(bytes) = env.convert_byte_array(&engine_dat) else { return 0 };
        let resources = read_string(&mut env, &resources_json);
        Shell::from_serialized(&bytes, resources.as_deref()).map(boxed_handle).unwrap_or(0)
    })
}

#[no_mangle]
pub extern "system" fn Java_app_movy_adblock_NativeBridge_nativeCreateFromRules(
    mut env: JNIEnv,
    _class: JClass,
    rules: JString,
    resources_json: JString,
) -> jlong {
    guard(0, || {
        let Some(rules) = read_string(&mut env, &rules) else { return 0 };
        let resources = read_string(&mut env, &resources_json);
        Shell::from_rules(&rules, resources.as_deref()).map(boxed_handle).unwrap_or(0)
    })
}

#[no_mangle]
pub extern "system" fn Java_app_movy_adblock_NativeBridge_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    guard((), || {
        if handle != 0 {
            // SAFETY: see `shell`; ownership returns to Rust exactly once.
            drop(unsafe { Box::from_raw(handle as *mut Shell) });
        }
    })
}

/// Returns null (allow), "B" (block) or "R:<data url>" (redirect).
#[no_mangle]
pub extern "system" fn Java_app_movy_adblock_NativeBridge_nativeCheck(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    url: JString,
    source_url: JString,
    request_type: JString,
) -> jstring {
    guard(NULL_STRING, || {
        let Some(shell) = shell(handle) else { return NULL_STRING };
        let Some(url) = read_string(&mut env, &url) else { return NULL_STRING };
        let source = read_string(&mut env, &source_url).unwrap_or_default();
        let kind = read_string(&mut env, &request_type).unwrap_or_else(|| "other".to_owned());
        let result = shell.check(&url, &source, &kind);
        match (result.matched, result.redirect) {
            (true, Some(body)) => to_jstring(&mut env, &format!("R:{body}")),
            (true, None) => to_jstring(&mut env, "B"),
            (false, _) => NULL_STRING,
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_app_movy_adblock_NativeBridge_nativeCosmetic(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    url: JString,
) -> jstring {
    guard(NULL_STRING, || {
        let Some(shell) = shell(handle) else { return NULL_STRING };
        let Some(url) = read_string(&mut env, &url) else { return NULL_STRING };
        to_jstring(&mut env, &shell.url_cosmetic_resources(&url))
    })
}

#[no_mangle]
pub extern "system" fn Java_app_movy_adblock_NativeBridge_nativeHiddenSelectors(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    classes: JObjectArray,
    ids: JObjectArray,
    exceptions: JObjectArray,
) -> jstring {
    guard(NULL_STRING, || {
        let Some(shell) = shell(handle) else { return NULL_STRING };
        let classes = read_string_array(&mut env, &classes);
        let ids = read_string_array(&mut env, &ids);
        let exceptions = read_string_array(&mut env, &exceptions);
        to_jstring(&mut env, &shell.hidden_class_id_selectors(&classes, &ids, &exceptions))
    })
}

