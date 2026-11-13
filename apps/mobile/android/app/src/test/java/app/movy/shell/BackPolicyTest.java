package app.movy.shell;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class BackPolicyTest {

    private static List<String> list(String... urls) {
        return Arrays.asList(urls);
    }

    // home → A → B: current=2 (B), walk back finds home at 0 → steps = 0-2 = -2
    @Test
    public void homeToAToB_givesMinusTwo() {
        List<String> urls = list(
                "https://www.youtube.com/",
                "https://www.youtube.com/watch?v=aaa",
                "https://www.youtube.com/watch?v=bbb"
        );
        assertEquals(-2, BackPolicy.decide(urls, 2));
    }

    // search → A: current=1 (A), walk back finds search at 0 → steps = 0-1 = -1
    @Test
    public void searchToA_givesMinusOne() {
        List<String> urls = list(
                "https://www.youtube.com/results?search_query=cats",
                "https://www.youtube.com/watch?v=aaa"
        );
        assertEquals(-1, BackPolicy.decide(urls, 1));
    }

    // Deep link straight to a video (no list page): → HOME
    @Test
    public void deepLinkVideo_givesHome() {
        List<String> urls = list("https://www.youtube.com/watch?v=xyz");
        assertEquals(BackPolicy.HOME, BackPolicy.decide(urls, 0));
    }

    // home → shorts → shorts → shorts: 3 shorts, current=3 → walk back to home at 0 → steps = -3
    @Test
    public void homeShortsShortShorts_givesMinusThree() {
        List<String> urls = list(
                "https://www.youtube.com/",
                "https://www.youtube.com/shorts/aaa",
                "https://www.youtube.com/shorts/bbb",
                "https://www.youtube.com/shorts/ccc"
        );
        assertEquals(-3, BackPolicy.decide(urls, 3));
    }

    // A non-video page: → DEFAULT
    @Test
    public void nonVideoPage_givesDefault() {
        List<String> urls = list(
                "https://www.youtube.com/",
                "https://www.youtube.com/channel/UCxxx"
        );
        assertEquals(BackPolicy.DEFAULT, BackPolicy.decide(urls, 1));
    }

    // music.youtube.com watch URL: → DEFAULT (player is an overlay, not a page)
    @Test
    public void musicWatchUrl_givesDefault() {
        List<String> urls = list(
                "https://music.youtube.com/",
                "https://music.youtube.com/watch?v=aaa"
        );
        // isPlayerPage returns false for music host → DEFAULT
        assertEquals(BackPolicy.DEFAULT, BackPolicy.decide(urls, 1));
    }

    // isPlayerPage: /watch and /shorts/ return true; everything else false
    @Test
    public void isPlayerPage_watchReturnsTrue() {
        assertTrue(BackPolicy.isPlayerPage("https://www.youtube.com/watch?v=abc"));
    }

    @Test
    public void isPlayerPage_shortsReturnsTrue() {
        assertTrue(BackPolicy.isPlayerPage("https://www.youtube.com/shorts/abc"));
    }

    @Test
    public void isPlayerPage_channelReturnsFalse() {
        assertFalse(BackPolicy.isPlayerPage("https://www.youtube.com/channel/UC123"));
    }

    @Test
    public void isPlayerPage_musicWatchReturnsFalse() {
        assertFalse(BackPolicy.isPlayerPage("https://music.youtube.com/watch?v=abc"));
    }

    @Test
    public void isPlayerPage_nullReturnsFalse() {
        assertFalse(BackPolicy.isPlayerPage(null));
    }

    // isWatch: specifically /watch on YouTube hosts
    @Test
    public void isWatch_tests() {
        assertTrue(BackPolicy.isWatch("https://www.youtube.com/watch?v=abc"));
        assertTrue(BackPolicy.isWatch("https://m.youtube.com/watch?v=abc"));
        assertTrue(BackPolicy.isWatch("https://youtube.com/watch?v=abc"));
        assertFalse(BackPolicy.isWatch("https://www.youtube.com/shorts/abc"));
        assertFalse(BackPolicy.isWatch("https://music.youtube.com/watch?v=abc"));
        assertFalse(BackPolicy.isWatch("https://www.youtube.com/"));
        assertFalse(BackPolicy.isWatch(null));
    }

    // action tests (fullscreen, PlayerHost.State, canGoBack)
    @Test
    public void action_fullscreen_givesExitFullscreen() {
        assertEquals(BackPolicy.Action.EXIT_FULLSCREEN,
                BackPolicy.action(true, PlayerHost.State.FULL, true));
        assertEquals(BackPolicy.Action.EXIT_FULLSCREEN,
                BackPolicy.action(true, PlayerHost.State.MINI, false));
    }

    @Test
    public void action_playerFull_givesMinimizePlayer() {
        assertEquals(BackPolicy.Action.MINIMIZE_PLAYER,
                BackPolicy.action(false, PlayerHost.State.FULL, true));
        assertEquals(BackPolicy.Action.MINIMIZE_PLAYER,
                BackPolicy.action(false, PlayerHost.State.FULL, false));
    }

    @Test
    public void action_browseCanGoBack_givesBrowseBack() {
        assertEquals(BackPolicy.Action.BROWSE_BACK,
                BackPolicy.action(false, PlayerHost.State.MINI, true));
        assertEquals(BackPolicy.Action.BROWSE_BACK,
                BackPolicy.action(false, PlayerHost.State.HIDDEN, true));
    }

    @Test
    public void action_browseCannotGoBack_playerMini_givesMoveTaskToBack() {
        assertEquals(BackPolicy.Action.MOVE_TASK_TO_BACK,
                BackPolicy.action(false, PlayerHost.State.MINI, false));
    }

    @Test
    public void action_browseCannotGoBack_playerHidden_givesFinish() {
        assertEquals(BackPolicy.Action.FINISH,
                BackPolicy.action(false, PlayerHost.State.HIDDEN, false));
    }

    // Edge: empty list → DEFAULT
    @Test
    public void emptyList_givesDefault() {
        assertEquals(BackPolicy.DEFAULT, BackPolicy.decide(Collections.emptyList(), 0));
    }
}
