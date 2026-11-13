package app.movy.shell;

import android.app.Activity;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * Owns the Island: feeds it the live source list, decides when it is visible (home pages, error panel, fullscreen,
 * chooser) and turns its taps into actions. All calls on the main thread.
 */
final class IslandController implements SourceRepository.Listener, HealthChecker.Listener, IslandView.Listener, Failover.Listener {
    private static final String ALWAYS = "always";
    private static final long LANDING_WINDOW_MS = 3000;

    private final SourceRepository repo;
    private final Prefs prefs;
    private final HealthChecker health;
    private final LaunchController launch;
    private final Runnable openSettings;
    private final Runnable focusPage;
    private final IslandView view;
    private final boolean tv;
    private boolean homeNow;
    private boolean fullscreen;
    private boolean pip;
    private boolean idle;
    private boolean forced;
    /** Where the last start-page load of the active source landed (some sites redirect "/" to a path). */
    private String landing;
    /** Landing page learned per source this process; a resumed source (last URL is not its start page) restores it. */
    private final java.util.Map<String, String> landings = new java.util.HashMap<>();
    private boolean armLanding;
    /** Automatic redirects (also SPA route changes) within this window after the first URL still define the landing page. */
    private long landingUntil;

    IslandController(Activity activity, SourceRepository repo, Prefs prefs, HealthChecker health, LaunchController launch,
                     boolean tv, Runnable openSettings, Runnable focusPage) {
        this.repo = repo;
        this.prefs = prefs;
        this.health = health;
        this.launch = launch;
        this.openSettings = openSettings;
        this.focusPage = focusPage;
        this.tv = tv;
        view = new IslandView(activity, tv);
        view.setListener(this);
        view.setHealth(health);
        view.setPositionFraction(prefs.islandY());
        ViewGroup content = activity.findViewById(android.R.id.content);
        content.addView(view, new FrameLayout.LayoutParams(-1, -1));
        applyConfig(repo.config());
        repo.addListener(this);
        health.addListener(this);
    }

    IslandView view() {
        return view;
    }

    boolean isExpanded() {
        return view.isExpanded();
    }

    /** True when the key was consumed by the expanded Island (TV D-pad navigation). */
    boolean handleKey(android.view.KeyEvent e) {
        return tv && e.getAction() == android.view.KeyEvent.ACTION_DOWN && view.handleKey(e);
    }

    boolean isHomeNow() {
        return homeNow;
    }

    /** TV colour keys: jump straight to the Nth enabled source. */
    void pickSlot(int slot) {
        java.util.List<Source> list = repo.config().enabledSources();
        if (slot < 0 || slot >= list.size()) return;
        onSourcePicked(list.get(slot));
        if (view.isExpanded()) view.collapse();
    }

    /** TV CHANNEL +/-: switches to the next/previous enabled source (wrapping) and returns its name, or null. */
    String stepSource(int delta) {
        java.util.List<Source> list = repo.config().enabledSources();
        Source a = repo.active();
        int cur = -1;
        for (int i = 0; i < list.size(); i++) if (a != null && list.get(i).id.equals(a.id)) cur = i;
        int next = TvKeys.stepSlot(cur, list.size(), delta);
        if (next < 0) return null;
        pickSlot(next);
        return list.get(next).name;
    }

    void collapse() {
        view.collapse();
    }

    /** TV MENU key: shows and toggles the Island on any page. */
    void menuToggle() {
        if (fullscreen || launch.isChooserShowing()) return;
        if (view.isExpanded()) {
            view.collapse();
            return;
        }
        forced = true;
        view.setShown(true, true);
        view.expand();
    }

    // ---- inputs from the shell ----

    /** Every main-frame URL change, including SPA route changes. */
    void onUrl(String url) {
        Source active = repo.active();
        long now = android.os.SystemClock.elapsedRealtime();
        if (armLanding && active != null && HomePageRule.belongsTo(active, url)) {
            if (landing == null) landingUntil = now + LANDING_WINDOW_MS;
            if (now <= landingUntil) { landing = HomePageRule.key(url); landings.put(active.id, landing); } else armLanding = false;
        }
        boolean home = HomePageRule.isHome(repo.config(), url) || (landing != null && landing.equals(HomePageRule.key(url)));
        android.util.Log.i(KillLog.TAG, "island: page home=" + home + " armed=" + armLanding + " landingKnown=" + (landing != null));
        if (home == homeNow) return;
        homeNow = home;
        refreshVisibility();
    }

    /** TV idle: the capsule hides (and an open Island closes) until the next key. */
    void setIdle(boolean on) {
        idle = on;
        if (on && isExpanded()) collapse();
        refreshVisibility();
    }

    void onFullscreen(boolean on) {
        fullscreen = on;
        refreshVisibility();
    }

    void setPipMode(boolean inPip) {
        pip = inPip;
        if (inPip && isExpanded()) collapse();
        refreshVisibility();
    }

    void refreshVisibility() {
        boolean want = forced || (!pip && !fullscreen && !launch.isChooserShowing()
                && (launch.errorPanel().isShowing() || ALWAYS.equals(repo.config().islandVisibility) || homeNow));
        if (fullscreen || idle || pip) want = false;
        android.util.Log.i(KillLog.TAG, "island: shown=" + want + " err=" + launch.errorPanel().isShowing() + " fs=" + fullscreen + " pip=" + pip);
        view.setShown(want, true);
    }

    @Override
    public void onFailureShown(Source suggested) {
        refreshVisibility();
        if (suggested == null) return;
        view.expand();
        view.pulse(suggested.id);
    }

    // ---- SourceRepository.Listener ----

    @Override
    public void onConfigChanged(LiveConfig config) {
        applyConfig(config);
        refreshVisibility();
    }

    @Override
    public void onActiveSourceChanged(Source active) {
        view.setActive(active.id);
        landing = landings.get(active.id);
        landingUntil = 0;
        armLanding = landing == null && repo.lastUrl(active).equals(active.url); // only a start-page load defines the landing page
        refreshVisibility();
    }

    private void applyConfig(LiveConfig c) {
        Source a = repo.active();
        view.setSources(c.enabledSources(), a != null ? a.id : null);
        Announcement an = c.announcement;
        view.setAnnouncement(an != null && !an.id.equals(prefs.announcementSeen()) ? an : null);
    }

    @Override
    public void onHealthChanged() {
        view.invalidateBlock();
    }

    // ---- IslandView.Listener ----

    @Override
    public void onSourcePicked(Source source) {
        Source a = repo.active();
        if (a == null || !a.id.equals(source.id)) repo.switchTo(source.id);
    }

    @Override
    public void onSettingsRequested() {
        openSettings.run();
    }

    @Override
    public void onExpandedChanged(boolean expanded) {
        if (expanded) {
            health.refresh();
        } else {
            forced = false;
            refreshVisibility();
            if (tv) focusPage.run();
        }
    }

    @Override
    public void onPositionChanged(float fraction) {
        prefs.setIslandY(fraction);
    }

    @Override
    public void onAnnouncementDismissed(String id) {
        prefs.setAnnouncementSeen(id);
    }
}
