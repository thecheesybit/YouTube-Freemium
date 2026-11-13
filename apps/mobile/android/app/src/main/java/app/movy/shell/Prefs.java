package app.movy.shell;

import android.content.Context;
import android.content.SharedPreferences;

/** Small persistent preferences (SharedPreferences). Never cleared automatically. */
public final class Prefs {
    private static final String FILE = "movy_prefs";
    private static final String DEFAULT_SOURCE = "defaultSourceId";
    private static final String ASK_EVERY_LAUNCH = "askEveryLaunch";
    private static final String LAST_SOURCE = "lastSourceId";
    private static final String LAST_GOOD_REMOTE = "lastGoodRemote";
    private static final String UPDATE_SHOWN_FOR = "updateShownForVersion";
    private static final String ANNOUNCEMENT_SEEN = "announcementSeen";
    private static final String ISLAND_Y = "islandY";

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        this.sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Saved "always open" source, or null when none (first launch). */
    public String defaultSourceId() {
        return sp.getString(DEFAULT_SOURCE, null);
    }

    public void setDefaultSourceId(String id) {
        SharedPreferences.Editor e = sp.edit();
        if (id == null) e.remove(DEFAULT_SOURCE); else e.putString(DEFAULT_SOURCE, id);
        e.apply();
    }

    /** True when the chooser must show on every launch ("Always open" unchecked). */
    public boolean askEveryLaunch() {
        return sp.getBoolean(ASK_EVERY_LAUNCH, false);
    }

    public void setAskEveryLaunch(boolean ask) {
        sp.edit().putBoolean(ASK_EVERY_LAUNCH, ask).apply();
    }

    public String lastSourceId() {
        return sp.getString(LAST_SOURCE, null);
    }

    public void setLastSourceId(String id) {
        sp.edit().putString(LAST_SOURCE, id).apply();
    }

    /** The last remote config JSON that validated; applied synchronously at startup. */
    public String lastGoodRemote() {
        return sp.getString(LAST_GOOD_REMOTE, null);
    }

    public void setLastGoodRemote(String json) {
        sp.edit().putString(LAST_GOOD_REMOTE, json).apply();
    }

    public int updateShownForVersion() {
        return sp.getInt(UPDATE_SHOWN_FOR, -1);
    }

    public void setUpdateShownForVersion(int contentVersion) {
        sp.edit().putInt(UPDATE_SHOWN_FOR, contentVersion).apply();
    }

    public String announcementSeen() {
        return sp.getString(ANNOUNCEMENT_SEEN, null);
    }

    public void setAnnouncementSeen(String id) {
        sp.edit().putString(ANNOUNCEMENT_SEEN, id).apply();
    }

    /** Random per-install id used by the REST remote-config fallback. */
    public String instanceId() {
        String id = sp.getString("instanceId", null);
        if (id == null) {
            id = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 22);
            sp.edit().putString("instanceId", id).apply();
        }
        return id;
    }

    /** Ad blocking switch from Settings; on by default. */
    public boolean adblockEnabled() {
        return sp.getBoolean("adblockEnabled", true);
    }

    public void setAdblockEnabled(boolean on) {
        sp.edit().putBoolean("adblockEnabled", on).apply();
    }

    /** Picture-in-picture switch from Settings; on by default. */
    public boolean pipEnabled() {
        return sp.getBoolean("pipEnabled", true);
    }

    public void setPipEnabled(boolean on) {
        sp.edit().putBoolean("pipEnabled", on).apply();
    }

    /** Background play switch from Settings; on by default. */
    public boolean backgroundPlayEnabled() {
        return sp.getBoolean("backgroundPlayEnabled", true);
    }

    public void setBackgroundPlayEnabled(boolean on) {
        sp.edit().putBoolean("backgroundPlayEnabled", on).apply();
    }

    /** Island vertical position as a 0..1 fraction (W5b). */
    public float islandY() {
        return sp.getFloat(ISLAND_Y, 0.4f);
    }

    public void setIslandY(float y) {
        sp.edit().putFloat(ISLAND_Y, y).apply();
    }

    /** TV: the one-time "Press MENU to switch source" hint has been shown. */
    /** TV: source and URL to reopen after the WebView was destroyed in the background; consumed once. */
    public String tvResumeId() {
        return sp.getString("tvResumeId", null);
    }

    public String tvResumeUrl() {
        return sp.getString("tvResumeUrl", null);
    }

    public void setTvResume(String id, String url) {
        sp.edit().putString("tvResumeId", id).putString("tvResumeUrl", url).apply();
    }

    public void clearTvResume() {
        sp.edit().remove("tvResumeId").remove("tvResumeUrl").apply();
    }

    public boolean tvHintShown() {
        return sp.getBoolean("tvHintShown", false);
    }

    public void setTvHintShown() {
        sp.edit().putBoolean("tvHintShown", true).apply();
    }
}
