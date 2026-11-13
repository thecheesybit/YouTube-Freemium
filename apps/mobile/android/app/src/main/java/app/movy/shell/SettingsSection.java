package app.movy.shell;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** An inset-grouped block: optional header, rounded card of rows, optional footer text and footer action. */
final class SettingsSection {
    final String header;
    final String footer;
    final String footerAction;
    final Runnable onFooterAction;
    final List<SettingsRow> rows;

    SettingsSection(String header, String footer, String footerAction, Runnable onFooterAction, List<SettingsRow> rows) {
        this.header = header;
        this.footer = footer;
        this.footerAction = footerAction;
        this.onFooterAction = onFooterAction;
        this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
    }

    static SettingsSection of(String header, String footer, List<SettingsRow> rows) {
        return new SettingsSection(header, footer, null, null, rows);
    }
}
