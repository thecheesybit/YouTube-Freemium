package app.movy.shell;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.format.Formatter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.movy.adblock.BlockStats;

/** Builds the Settings sections: Source, Protection, Sources, Storage, About. Add a section = add a method. */
final class SettingsModel {
    /** Things the rows can ask the sheet to do. */
    interface Actions {
        void rebuild();

        void checkSources();

        void confirmClearCache();

        void confirmSignOut();

        void setAdblock(boolean on);

        void openDefaultSource();
    }

    /** Live inputs; read each time the list is rebuilt. */
    static final class Inputs {
        final SourceRepository repo;
        final Prefs prefs;
        final BlockStats stats;
        final HealthChecker health;
        final boolean adblockConfigured;
        final boolean adblockOn;
        final String appName;
        final boolean checking;
        /** Null until measured. */
        final Long storageBytes;
        /** Size of the TV asset cache; null when the cache is off (phone). */
        final Long assetCacheBytes;
        final Drawable icon;

        Inputs(SourceRepository repo, Prefs prefs, BlockStats stats, HealthChecker health, boolean adblockConfigured,
               boolean adblockOn, String appName, boolean checking, Long storageBytes, Long assetCacheBytes, Drawable icon) {
            this.repo = repo;
            this.prefs = prefs;
            this.stats = stats;
            this.health = health;
            this.adblockConfigured = adblockConfigured;
            this.adblockOn = adblockOn;
            this.appName = appName;
            this.checking = checking;
            this.storageBytes = storageBytes;
            this.assetCacheBytes = assetCacheBytes;
            this.icon = icon;
        }
    }

    private SettingsModel() {}

    static List<SettingsSection> build(Context ctx, Inputs in, Actions act) {
        List<SettingsSection> out = new ArrayList<>();
        LiveConfig cfg = in.repo.config();
        out.add(sourceSection(ctx, in, act, cfg));
        if (in.adblockConfigured) out.add(protectionSection(ctx, in, act));
        out.add(playbackSection(ctx, in));
        out.add(sourcesSection(ctx, in, act, cfg));
        out.add(storageSection(ctx, in, act));
        out.add(aboutSection(ctx, in, cfg));
        return Collections.unmodifiableList(out);
    }

    private static SettingsSection playbackSection(Context ctx, Inputs in) {
        List<SettingsRow> rows = new ArrayList<>();
        rows.add(SettingsRow.toggle(ctx.getString(R.string.movy_pip_toggle), in.prefs.pipEnabled(),
                UiTokens.BADGE_BLUE, R.drawable.ic_play, on -> in.prefs.setPipEnabled(on)));
        rows.add(SettingsRow.toggle(ctx.getString(R.string.movy_background_play_toggle), in.prefs.backgroundPlayEnabled(),
                UiTokens.BADGE_GREEN, R.drawable.ic_replay, on -> in.prefs.setBackgroundPlayEnabled(on)));
        return SettingsSection.of(ctx.getString(R.string.movy_sec_playback), ctx.getString(R.string.movy_playback_footer), rows);
    }

    /** Id of the source that opens on launch: the saved default, else the recommended one. */
    static Source defaultSource(Inputs in) {
        LiveConfig cfg = in.repo.config();
        String def = in.prefs.defaultSourceId();
        Source s = def == null ? null : cfg.find(def);
        return s != null && s.enabled ? s : in.repo.preselected();
    }

    private static SettingsSection sourceSection(Context ctx, Inputs in, Actions act, LiveConfig cfg) {
        List<SettingsRow> rows = new ArrayList<>();
        rows.add(SettingsRow.nav(ctx.getString(R.string.movy_default_source), defaultSource(in).name,
                UiTokens.BADGE_BLUE, R.drawable.ic_play, act::openDefaultSource));
        rows.add(SettingsRow.toggle(ctx.getString(R.string.movy_ask_every_launch), in.prefs.askEveryLaunch(),
                UiTokens.BADGE_ORANGE, R.drawable.ic_replay, on -> in.prefs.setAskEveryLaunch(on)));
        return SettingsSection.of(ctx.getString(R.string.movy_sec_source), ctx.getString(R.string.movy_source_footer), rows);
    }

    /** Sub-page of "Default source": a checkmark list of the enabled sources. */
    static List<SettingsSection> defaultSourcePage(Context ctx, Inputs in, Actions act) {
        List<SettingsRow> rows = new ArrayList<>();
        String current = defaultSource(in).id;
        for (Source s : in.repo.config().enabledSources()) {
            final String id = s.id;
            rows.add(SettingsRow.check(s.name, id.equals(current), s.accent, () -> {
                in.prefs.setDefaultSourceId(id);
                act.rebuild();
            }));
        }
        return Collections.singletonList(SettingsSection.of(null, ctx.getString(R.string.movy_default_source_footer), rows));
    }

    private static SettingsSection protectionSection(Context ctx, Inputs in, Actions act) {
        List<SettingsRow> rows = new ArrayList<>();
        rows.add(SettingsRow.toggle(ctx.getString(R.string.movy_adblock_toggle), in.adblockOn, UiTokens.BADGE_GREEN,
                R.drawable.ic_shield, act::setAdblock));
        rows.add(SettingsRow.value(ctx.getString(R.string.movy_ads_blocked), String.valueOf(in.stats.adsBlocked()),
                UiTokens.BADGE_RED, R.drawable.ic_block));
        rows.add(SettingsRow.value(ctx.getString(R.string.movy_redirects_killed), String.valueOf(in.stats.redirectsKilled()),
                UiTokens.BADGE_PURPLE, R.drawable.ic_replay));
        return SettingsSection.of(ctx.getString(R.string.movy_sec_adblock), null, rows);
    }

    private static SettingsSection sourcesSection(Context ctx, Inputs in, Actions act, LiveConfig cfg) {
        List<SettingsRow> rows = new ArrayList<>();
        for (Source s : cfg.enabledSources()) {
            HealthChecker.Status st = in.health.status(s.id);
            String text = in.checking ? ctx.getString(R.string.movy_status_checking) : statusText(ctx, st);
            rows.add(SettingsRow.status(s.name, text, IslandPainter.healthColor(st)));
        }
        return new SettingsSection(ctx.getString(R.string.movy_sec_status), null, ctx.getString(R.string.movy_check_now),
                act::checkSources, rows);
    }

    static String statusText(Context ctx, HealthChecker.Status st) {
        switch (st) {
            case UP: return ctx.getString(R.string.movy_status_up);
            case SLOW: return ctx.getString(R.string.movy_status_slow);
            case DOWN: return ctx.getString(R.string.movy_status_down);
            default: return ctx.getString(R.string.movy_status_unknown);
        }
    }

    private static SettingsSection storageSection(Context ctx, Inputs in, Actions act) {
        List<SettingsRow> rows = new ArrayList<>();
        String used = in.storageBytes == null ? "…" : Formatter.formatShortFileSize(ctx, in.storageBytes);
        rows.add(SettingsRow.value(ctx.getString(R.string.movy_storage_used), used, UiTokens.BADGE_GREY, R.drawable.ic_storage));
        if (in.assetCacheBytes != null) {
            rows.add(SettingsRow.value(ctx.getString(R.string.movy_asset_cache), Formatter.formatShortFileSize(ctx, in.assetCacheBytes), UiTokens.BADGE_GREY, R.drawable.ic_storage));
        }
        rows.add(SettingsRow.action(ctx.getString(R.string.movy_clear_cache), UiTokens.BLUE, act::confirmClearCache));
        rows.add(SettingsRow.action(ctx.getString(R.string.movy_sign_out), UiTokens.RED, act::confirmSignOut));
        return SettingsSection.of(ctx.getString(R.string.movy_sec_storage), null, rows);
    }

    private static SettingsSection aboutSection(Context ctx, Inputs in, LiveConfig cfg) {
        String ver = ctx.getString(R.string.movy_version, BuildConfig.VERSION_NAME, BuildConfig.MOVY_BUILD);
        if (BuildConfig.MOVY_REMOTE && cfg.contentVersion > 0) ver += ctx.getString(R.string.movy_cfg_suffix, cfg.contentVersion);
        List<SettingsRow> rows = new ArrayList<>();
        rows.add(SettingsRow.about(in.icon, in.appName, cfg.tagline, ctx.getString(R.string.movy_by_credit, cfg.credit), ver));
        return SettingsSection.of(ctx.getString(R.string.movy_sec_about), null, rows);
    }
}
