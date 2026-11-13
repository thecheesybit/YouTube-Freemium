use movy_adblock::{parse_resources, Shell, ShellError};
use std::sync::Arc;
use std::thread;

const RULES: &str = "\
||ads.example.com^$script
||tracker.test^
@@||tracker.test/allowed^
||important.test^$important
@@||important.test^
||cdn.test/ad.js$script,redirect=noopjs
example.com##.ad-banner
##.sponsored
";

// base64("// noop")
const RESOURCES: &str = r#"[{"name":"noop.js","aliases":["noopjs"],"kind":{"mime":"application/javascript"},"content":"Ly8gbm9vcA=="}]"#;

fn shell() -> Shell {
    Shell::from_rules(RULES, Some(RESOURCES)).expect("rules load")
}

#[test]
fn blocks_matching_script() {
    let r = shell().check("https://ads.example.com/x.js", "https://example.com/", "script");
    assert!(r.matched);
    assert!(r.redirect.is_none());
}

#[test]
fn rule_type_option_is_respected() {
    // ||ads.example.com^$script must not block images
    let r = shell().check("https://ads.example.com/x.png", "https://example.com/", "image");
    assert!(!r.matched);
}

#[test]
fn allows_unrelated_url() {
    let r = shell().check("https://example.com/app.js", "https://example.com/", "script");
    assert!(!r.matched);
    assert!(!r.important);
}

#[test]
fn exception_rule_overrides_block() {
    let s = shell();
    assert!(s.check("https://tracker.test/pixel", "https://example.com/", "image").matched);
    let r = s.check("https://tracker.test/allowed/pixel", "https://example.com/", "image");
    assert!(!r.matched);
    assert!(r.exception.is_some());
}

#[test]
fn important_beats_exception() {
    let r = shell().check("https://important.test/a", "https://example.com/", "script");
    assert!(r.matched && r.important);
}

#[test]
fn redirect_resource_is_data_url() {
    let r = shell().check("https://cdn.test/ad.js", "https://example.com/", "script");
    assert!(r.matched);
    let url = r.redirect.expect("redirect");
    assert!(url.starts_with("data:application/javascript;base64,"), "{url}");
}

#[test]
fn redirect_without_resources_still_blocks() {
    let s = Shell::from_rules(RULES, None).unwrap();
    let r = s.check("https://cdn.test/ad.js", "https://example.com/", "script");
    assert!(r.matched);
    assert!(r.redirect.is_none());
}

#[test]
fn cosmetic_resources_for_host() {
    let s = shell();
    let json = s.url_cosmetic_resources("https://example.com/page");
    let v: serde_json::Value = serde_json::from_str(&json).unwrap();
    let hide = v["hide_selectors"].as_array().unwrap();
    assert!(hide.iter().any(|x| x == ".ad-banner"), "{json}");
    let other: serde_json::Value =
        serde_json::from_str(&s.url_cosmetic_resources("https://other.org/")).unwrap();
    assert!(!other["hide_selectors"].as_array().unwrap().iter().any(|x| x == ".ad-banner"));
}

#[test]
fn cosmetic_invalid_url_is_empty_json() {
    let json = shell().url_cosmetic_resources("not a url");
    let v: serde_json::Value = serde_json::from_str(&json).unwrap();
    assert!(v["hide_selectors"].as_array().unwrap().is_empty());
    assert_eq!(v["generichide"], false);
}

#[test]
fn generic_hide_by_class_and_id() {
    let s = Shell::from_rules("##.sponsored\n###promo-box\n", None).unwrap();
    let json = s.hidden_class_id_selectors(
        &["sponsored".into(), "content".into()],
        &["promo-box".into()],
        &[],
    );
    let v: Vec<String> = serde_json::from_str(&json).unwrap();
    assert!(v.contains(&".sponsored".to_string()), "{json}");
    assert!(v.contains(&"#promo-box".to_string()), "{json}");
    assert_eq!(v.len(), 2);
}

#[test]
fn generic_hide_honours_exceptions() {
    let s = Shell::from_rules("##.sponsored\n", None).unwrap();
    let json = s.hidden_class_id_selectors(&["sponsored".into()], &[], &[".sponsored".into()]);
    assert_eq!(json, "[]");
}

#[test]
fn generic_hide_empty_inputs() {
    assert_eq!(shell().hidden_class_id_selectors(&[], &[], &[]), "[]");
}

#[test]
fn serialize_round_trip_keeps_verdicts() {
    let original = shell();
    let restored = Shell::from_serialized(&original.serialize(), Some(RESOURCES)).unwrap();
    let cases = [
        ("https://ads.example.com/x.js", "script"),
        ("https://example.com/app.js", "script"),
        ("https://tracker.test/pixel", "image"),
        ("https://tracker.test/allowed/pixel", "image"),
        ("https://important.test/a", "script"),
        ("https://cdn.test/ad.js", "script"),
    ];
    for (url, kind) in cases {
        let a = original.check(url, "https://example.com/", kind);
        let b = restored.check(url, "https://example.com/", kind);
        assert_eq!(a.matched, b.matched, "{url}");
        assert_eq!(a.important, b.important, "{url}");
        assert_eq!(a.redirect, b.redirect, "{url}");
    }
    assert_eq!(
        original.url_cosmetic_resources("https://example.com/"),
        restored.url_cosmetic_resources("https://example.com/")
    );
}

#[test]
fn resources_are_not_serialized() {
    let restored = Shell::from_serialized(&shell().serialize(), None).unwrap();
    let r = restored.check("https://cdn.test/ad.js", "https://example.com/", "script");
    assert!(r.matched);
    assert!(r.redirect.is_none(), "resources must be supplied at load time");
}

#[test]
fn corrupt_data_is_an_error() {
    assert!(matches!(Shell::from_serialized(b"garbage", None), Err(ShellError::Deserialize(_))));
    assert!(matches!(Shell::from_serialized(&[], None), Err(ShellError::Deserialize(_))));
    let mut bytes = shell().serialize();
    bytes.truncate(bytes.len() / 2);
    assert!(Shell::from_serialized(&bytes, None).is_err());
}

#[test]
fn invalid_resources_json_is_an_error() {
    assert!(matches!(Shell::from_rules("||a.test^", Some("{not json")), Err(ShellError::Resources(_))));
    assert!(Shell::from_rules("||a.test^", Some("")).is_ok());
    assert!(parse_resources("  ").unwrap().is_empty());
    assert_eq!(parse_resources(RESOURCES).unwrap().len(), 1);
}

#[test]
fn invalid_urls_never_match_or_panic() {
    let s = shell();
    for url in ["", "not a url", "://", "http://", "\u{0}", "javascript:alert(1)", "data:text/plain,hi"] {
        for src in ["", "garbage", "https://example.com/"] {
            let r = s.check(url, src, "script");
            assert!(!r.matched, "{url:?}");
        }
    }
    // unknown request type falls back gracefully
    let _ = s.check("https://ads.example.com/x.js", "https://example.com/", "bogus-type");
    let _ = s.url_cosmetic_resources("");
}

#[test]
fn empty_rules_allow_everything() {
    let s = Shell::from_rules("", None).unwrap();
    assert!(!s.check("https://ads.example.com/x.js", "https://example.com/", "script").matched);
}

#[test]
fn concurrent_checks_agree() {
    let s = Arc::new(shell());
    let handles: Vec<_> = (0..8)
        .map(|i| {
            let s = Arc::clone(&s);
            thread::spawn(move || {
                for n in 0..500 {
                    let blocked = s.check(&format!("https://ads.example.com/{i}/{n}.js"), "https://example.com/", "script");
                    assert!(blocked.matched);
                    let ok = s.check(&format!("https://example.com/{i}/{n}.js"), "https://example.com/", "script");
                    assert!(!ok.matched);
                    let _ = s.url_cosmetic_resources("https://example.com/");
                    let _ = s.hidden_class_id_selectors(&["sponsored".into()], &[], &[]);
                }
            })
        })
        .collect();
    for h in handles {
        h.join().expect("worker thread panicked");
    }
}
