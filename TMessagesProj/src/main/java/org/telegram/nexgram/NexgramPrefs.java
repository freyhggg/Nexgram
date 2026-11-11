package org.telegram.nexgram;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Central local persistence for all NEXGRAM preferences.
 * Nothing here leaves the device: no Telegram session tokens, no passwords,
 * only UI/feature choices and Account Guard incident metadata.
 */
public final class NexgramPrefs {
    public static final String FILE = "nexgram_settings";

    // Keys (kept compatible with NEXGRAM 1.x values)
    public static final String KEY_ACCENT = "accent_color";
    public static final String KEY_REDUCE_MOTION = "reduce_motion";
    public static final String KEY_ISLAND_ENABLED = "island_enabled";
    public static final String KEY_ISLAND_ANIMATIONS = "island_animations";
    public static final String KEY_GUARD_KNOWN_HASHES = "guard_known_hashes";
    public static final String KEY_GUARD_INCIDENTS = "guard_incident_log";
    public static final String KEY_INTEL_KEY_CIPHER = "intel_search_key_cipher";

    public static final int DEFAULT_ACCENT = 0xFF8B2CFF;

    private NexgramPrefs() {
    }

    public static SharedPreferences get(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // --- Appearance ---

    public static int getAccent(Context context) {
        return get(context).getInt(KEY_ACCENT, DEFAULT_ACCENT);
    }

    public static void setAccent(Context context, int color) {
        get(context).edit().putInt(KEY_ACCENT, color).apply();
    }

    public static boolean reduceMotion(Context context) {
        return get(context).getBoolean(KEY_REDUCE_MOTION, false);
    }

    public static void setReduceMotion(Context context, boolean value) {
        get(context).edit().putBoolean(KEY_REDUCE_MOTION, value).apply();
    }

    // --- NEXGRAM Island (in-app notification banner) ---

    public static boolean islandEnabled(Context context) {
        return get(context).getBoolean(KEY_ISLAND_ENABLED, true);
    }

    public static void setIslandEnabled(Context context, boolean value) {
        get(context).edit().putBoolean(KEY_ISLAND_ENABLED, value).apply();
    }

    public static boolean islandAnimations(Context context) {
        return get(context).getBoolean(KEY_ISLAND_ANIMATIONS, true) && !reduceMotion(context);
    }

    public static void setIslandAnimations(Context context, boolean value) {
        get(context).edit().putBoolean(KEY_ISLAND_ANIMATIONS, value).apply();
    }

    // --- Account Guard: known session registry ---

    public static Set<Long> knownSessionHashes(Context context) {
        Set<String> stored = get(context).getStringSet(KEY_GUARD_KNOWN_HASHES, null);
        Set<Long> result = new HashSet<>();
        if (stored != null) {
            for (String value : stored) {
                try {
                    result.add(Long.parseLong(value));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return result;
    }

    public static void setKnownSessionHashes(Context context, Set<Long> hashes) {
        Set<String> stored = new HashSet<>();
        for (Long hash : hashes) {
            stored.add(String.valueOf(hash));
        }
        get(context).edit().putStringSet(KEY_GUARD_KNOWN_HASHES, stored).apply();
    }

    // --- Account Guard: incident log (JSON array, newest first, capped) ---

    public static List<JSONObject> incidentLog(Context context) {
        List<JSONObject> result = new ArrayList<>();
        String raw = get(context).getString(KEY_GUARD_INCIDENTS, null);
        if (raw == null) {
            return result;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                result.add(array.optJSONObject(i));
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    /** Adds an incident about a previously unseen session. Capped at 40 entries. */
    public static void appendIncident(Context context, JSONObject incident) {
        try {
            List<JSONObject> current = incidentLog(context);
            current.add(0, incident);
            while (current.size() > 40) {
                current.remove(current.size() - 1);
            }
            JSONArray array = new JSONArray();
            for (JSONObject entry : current) {
                array.put(entry);
            }
            get(context).edit().putString(KEY_GUARD_INCIDENTS, array.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public static void clearIncidents(Context context) {
        get(context).edit().remove(KEY_GUARD_INCIDENTS).apply();
    }

    // --- Intelligence: optional external search key (cipher text only) ---

    public static String intelKeyCipher(Context context) {
        return get(context).getString(KEY_INTEL_KEY_CIPHER, null);
    }

    public static void setIntelKeyCipher(Context context, String cipher) {
        if (cipher == null) {
            get(context).edit().remove(KEY_INTEL_KEY_CIPHER).apply();
        } else {
            get(context).edit().putString(KEY_INTEL_KEY_CIPHER, cipher).apply();
        }
    }
}
