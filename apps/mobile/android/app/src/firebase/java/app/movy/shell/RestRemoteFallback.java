package app.movy.shell;

import android.util.Log;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fallback for networks where the Firebase SDK cannot reach Google (black-holed IPv6): fetches the same Remote Config
 * parameter over the REST API with an IPv4-first client, until the SDK reports one successful fetch.
 */
final class RestRemoteFallback {
    private static final String TAG = "MovyRemote";
    private static final String HOST = "firebaseremoteconfig.googleapis.com";
    private static final long FIRST_DELAY_S = 5;
    private static final long PERIOD_S = 60;

    private final RemoteApplier applier;
    private final String parameter;
    private final boolean tv;
    private final String instanceId;
    private final AtomicBoolean sdkOk;
    private final ScheduledExecutorService pool = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "movy-remote-rest");
        t.setDaemon(true);
        return t;
    });
    private String lastJson;

    RestRemoteFallback(RemoteApplier applier, String parameter, boolean tv, String instanceId, AtomicBoolean sdkOk) {
        this.tv = tv;
        this.applier = applier;
        this.parameter = parameter;
        this.instanceId = instanceId;
        this.sdkOk = sdkOk;
    }

    void start() {
        pool.scheduleWithFixedDelay(this::tick, FIRST_DELAY_S, PERIOD_S, TimeUnit.SECONDS);
    }

    private void tick() {
        if (sdkOk.get()) {
            pool.shutdown();
            return;
        }
        try {
            FirebaseOptions o = FirebaseApp.getInstance().getOptions();
            Map<String, String> headers = new HashMap<>();
            headers.put("x-goog-api-key", o.getApiKey());
            headers.put("Content-Type", "application/json");
            String body = new JSONObject().put("appId", o.getApplicationId()).put("appInstanceId", instanceId).toString();
            String path = "/v1/projects/" + o.getGcmSenderId() + "/namespaces/firebase:fetch";
            Ipv4FirstHttps.Response r = Ipv4FirstHttps.request("POST", HOST, path, headers, body, 4000, 10000);
            if (r.status != 200) {
                Log.i(TAG, "rest fetch status " + r.status);
                return;
            }
            JSONObject entries = new JSONObject(r.body).optJSONObject("entries");
            String json = null;
            if (entries != null) {
                if (tv) json = entries.optString(parameter + "_tv", null);
                if (json == null || json.isEmpty()) json = entries.optString(parameter, null);
            }
            if (json != null && !json.equals(lastJson)) {
                lastJson = json;
                applier.apply(json);
            }
        } catch (IOException | JSONException | RuntimeException e) {
            Log.i(TAG, "rest fetch failed: " + e.getClass().getSimpleName());
        }
    }
}
