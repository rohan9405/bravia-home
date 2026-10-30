package io.github.rohan9405.feed;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Map;

/** The streaming apps TMDB knows as watch providers in India, by their TV package. YouTube is not one. */
final class Providers {
    static final String REGION = "IN";

    /** package -> TMDB watch provider id (GET /3/watch/providers/tv?watch_region=IN). */
    static final Map<String, Integer> TMDB;

    static {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("com.netflix.ninja", 8);
        m.put("com.amazon.amazonvideo.livingroom", 119);
        m.put("in.startv.hotstar", 2336);
        m.put("com.apple.atve.androidtv.appletv", 350);
        m.put("com.sonyliv", 237);
        m.put("com.graymatrix.did", 232);
        m.put("com.crunchyroll.crunchyroid", 283);
        TMDB = Collections.unmodifiableMap(m);
    }

    /** Browsers publish their tabs as Continue watching; those are not shows. */
    /** TMDB's provider id -> JustWatch's short name for the same service in India (the ids are JustWatch's too). */
    private static final Map<Integer, String> JUSTWATCH = new HashMap<>();

    static {
        JUSTWATCH.put(8, "nfx");
        JUSTWATCH.put(119, "prv");
        JUSTWATCH.put(2336, "jhs");
        JUSTWATCH.put(350, "atp");
        JUSTWATCH.put(237, "snl");
        JUSTWATCH.put(232, "zee");
        JUSTWATCH.put(283, "cru");
    }

    /** JustWatch's name for a service, or null when it has none here. */
    static String justWatch(int provider) {
        return JUSTWATCH.get(provider);
    }

    /** Apple TV+ releases a few titles a month: two weeks would leave its header nearly empty. */
    static final int APPLE_TV = 350, APPLE_TV_NEW_DAYS = 30;

    /** How many days back a service's titles count as new for the header. */
    static int newDays(int provider) {
        return provider == APPLE_TV ? APPLE_TV_NEW_DAYS : Tmdb.NEW_DAYS;
    }

    static boolean browser(String pkg) {
        return pkg != null && (pkg.contains("browser") || pkg.equals("com.android.chrome"));
    }

    /** Opens the app at its TV start screen: TMDB titles carry no ID the apps accept. */
    static String launchIntent(String pkg) {
        return "intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.LEANBACK_LAUNCHER;"
                + "package=" + pkg + ";end";
    }

    private Providers() {
    }
}
