package app.movy.shell;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Loads the build-time defaults (assets/movy-defaults.json; start URLs obfuscated with BuildConfig.MOVY_KEY). */
final class Defaults {
    private Defaults() {}

    static LiveConfig load(Context ctx) {
        try (InputStream in = ctx.getAssets().open("movy-defaults.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return LiveConfig.parseDefaults(out.toString(StandardCharsets.UTF_8.name()), new Obfuscator(BuildConfig.MOVY_KEY));
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot read assets/movy-defaults.json: " + e.getMessage(), e);
        }
    }
}
