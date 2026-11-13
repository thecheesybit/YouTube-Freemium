package app.movy.shell;

import android.graphics.drawable.Drawable;

import java.util.function.Consumer;

/** One entry of the Settings model. Immutable; rows are grouped into {@link SettingsSection}s. */
final class SettingsRow {
    enum Type { NAV, SWITCH, VALUE, STATUS, ACTION, CHECK, ABOUT }

    final Type type;
    final String title;
    /** Value text (right side), status text or about tagline. */
    final String value;
    final boolean checked;
    /** Badge colour (iOS system colour) or status dot colour. */
    final int color;
    /** Vector drawable resource of the white badge glyph, 0 for none. */
    final int glyph;
    /** Action tint: 0 = default label colour. */
    final int tint;
    final boolean bold;
    final Runnable onClick;
    final Consumer<Boolean> onChange;
    final Drawable icon;
    /** Extra about lines. */
    final String[] lines;

    private SettingsRow(Type type, String title, String value, boolean checked, int color, int glyph, int tint,
                        boolean bold, Runnable onClick, Consumer<Boolean> onChange, Drawable icon, String[] lines) {
        this.type = type;
        this.title = title;
        this.value = value;
        this.checked = checked;
        this.color = color;
        this.glyph = glyph;
        this.tint = tint;
        this.bold = bold;
        this.onClick = onClick;
        this.onChange = onChange;
        this.icon = icon;
        this.lines = lines;
    }

    /** Row that opens a sub-page: badge, title, current value and a chevron. */
    static SettingsRow nav(String title, String value, int badge, int glyph, Runnable onClick) {
        return new SettingsRow(Type.NAV, title, value, false, badge, glyph, 0, false, onClick, null, null, null);
    }

    static SettingsRow toggle(String title, boolean checked, int badge, int glyph, Consumer<Boolean> onChange) {
        return new SettingsRow(Type.SWITCH, title, "", checked, badge, glyph, 0, false, null, onChange, null, null);
    }

    static SettingsRow value(String title, String value, int badge, int glyph) {
        return new SettingsRow(Type.VALUE, title, value, false, badge, glyph, 0, false, null, null, null, null);
    }

    static SettingsRow status(String title, String value, int dotColor) {
        return new SettingsRow(Type.STATUS, title, value, false, dotColor, 0, 0, false, null, null, null, null);
    }

    /** Plain text action in a tint colour (blue or red). */
    static SettingsRow action(String title, int tint, Runnable onClick) {
        return new SettingsRow(Type.ACTION, title, "", false, 0, 0, tint, false, onClick, null, null, null);
    }

    /** Sub-page list entry with a trailing checkmark when selected. */
    static SettingsRow check(String title, boolean checked, int accent, Runnable onClick) {
        return new SettingsRow(Type.CHECK, title, "", checked, accent, 0, 0, checked, onClick, null, null, null);
    }

    static SettingsRow about(Drawable icon, String name, String tagline, String... lines) {
        return new SettingsRow(Type.ABOUT, name, tagline, false, 0, 0, 0, false, null, null, icon, lines);
    }
}
