package app.movy.shell;

import android.content.Context;
import android.util.Log;

import com.google.firebase.remoteconfig.ConfigUpdate;
import com.google.firebase.remoteconfig.ConfigUpdateListener;
import com.google.firebase.remoteconfig.FirebaseRemoteConfig;
import com.google.firebase.remoteconfig.FirebaseRemoteConfigException;
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Firebase Remote Config client (no Analytics). Order: defaults and cached last-good are applied by the caller
 * before this starts; here: fetchAndActivate in the background, then the real-time update listener. A REST fallback
 * covers networks where the SDK cannot reach Google (see {@link RestRemoteFallback}).
 */
public final class RemoteBackends {
    private static final String TAG = "MovyRemote";
    private static final long RELEASE_INTERVAL_S = 3600;

    private RemoteBackends() {}

    public static boolean available() {
        return true;
    }

    public static void start(Context ctx, RemoteApplier applier, String parameter, boolean tv, boolean debug,
                             String instanceId) {
        final AtomicBoolean sdkOk = new AtomicBoolean();
        new RestRemoteFallback(applier, parameter, tv, instanceId, sdkOk).start();
        final FirebaseRemoteConfig rc;
        try {
            rc = FirebaseRemoteConfig.getInstance();
        } catch (RuntimeException e) {
            Log.w(TAG, "remote config unavailable: " + e.getClass().getSimpleName());
            return;
        }
        rc.setConfigSettingsAsync(new FirebaseRemoteConfigSettings.Builder()
                .setMinimumFetchIntervalInSeconds(debug ? 0 : RELEASE_INTERVAL_S).build());
        rc.fetchAndActivate().addOnCompleteListener(t -> {
            if (t.isSuccessful()) {
                sdkOk.set(true);
                Log.i(TAG, "sdk fetch ok");
            } else {
                Log.i(TAG, "sdk fetch failed, keeping last good config");
            }
            applyFrom(rc, applier, parameter, tv);
        });
        rc.addOnConfigUpdateListener(new ConfigUpdateListener() {
            @Override
            public void onUpdate(ConfigUpdate update) {
                Log.i(TAG, "realtime update received");
                rc.activate().addOnCompleteListener(t -> applyFrom(rc, applier, parameter, tv));
            }

            @Override
            public void onError(FirebaseRemoteConfigException error) {
                Log.i(TAG, "realtime listener error: " + error.getCode());
            }
        });
    }

    private static void applyFrom(FirebaseRemoteConfig rc, RemoteApplier applier, String parameter, boolean tv) {
        String json = tv ? rc.getString(parameter + "_tv") : "";
        if (json == null || json.isEmpty()) json = rc.getString(parameter);
        applier.apply(json);
    }
}
