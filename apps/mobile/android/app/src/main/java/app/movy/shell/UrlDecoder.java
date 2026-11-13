package app.movy.shell;

/** Turns an obfuscated start URL from movy-defaults.json back into a plain URL. */
public interface UrlDecoder {
    String decode(String encoded);

    /** For configs whose URLs are plain (remote config, tests). */
    UrlDecoder IDENTITY = s -> s;
}
