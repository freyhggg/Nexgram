package org.telegram.nexgram.nearby;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/** Локальний журнал передач Nearby та мітка пристрою. Нічого не передається назовні. */
public final class NearbyStore {

    private static final String PREFS = "nexgram_nearby";
    private static final String KEY_LABEL = "device_label";
    private static final String KEY_JOURNAL = "journal";
    private static final int JOURNAL_MAX = 30;

    private NearbyStore() {
    }

    public static String getLabel(Context context) {
        return prefs(context).getString(KEY_LABEL, null);
    }

    public static void setLabel(Context context, String label) {
        prefs(context).edit().putString(KEY_LABEL, label).apply();
    }

    public static JSONArray getJournal(Context context) {
        try {
            return new JSONArray(prefs(context).getString(KEY_JOURNAL, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public static void addJournal(Context context, JSONObject entry) {
        JSONArray old = getJournal(context);
        JSONArray next = new JSONArray();
        next.put(entry);
        for (int i = 0; i < old.length() && i < JOURNAL_MAX - 1; i++) {
            JSONObject item = old.optJSONObject(i);
            if (item != null) {
                next.put(item);
            }
        }
        prefs(context).edit().putString(KEY_JOURNAL, next.toString()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
