package org.telegram.nexgram.intelligence;

import org.telegram.nexgram.NexgramPrefs;
import org.telegram.nexgram.SecureKeyStore;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Optional external web-search adapter. Requires a user-provided API key
 * (stored encrypted via Android Keystore). If no key is set, this module
 * stays disabled — it never fakes results.
 */
public final class ExternalSearchAdapter implements IntelModule {

    private final Context context;

    public ExternalSearchAdapter(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public String id() {
        return "external_web";
    }

    @Override
    public String describe() {
        return "Зовнішній пошуковий API (за ключем користувача)";
    }

    public boolean hasKey() {
        return getKey() != null;
    }

    private String getKey() {
        String cipher = NexgramPrefs.intelKeyCipher(context);
        if (cipher == null) {
            return null;
        }
        return SecureKeyStore.decrypt(cipher);
    }

    @Override
    public boolean canHandle(String query) {
        return hasKey() && query != null && !query.trim().isEmpty();
    }

    @Override
    public void search(String query, Callback callback) {
        List<IntelResult> results = new ArrayList<>();
        String key = getKey();
        if (key == null) {
            callback.onDone(results, "Ключ не налаштовано");
            return;
        }
        try {
            String url = "https://serpapi.com/search.json?q="
                    + java.net.URLEncoder.encode(query.trim(), "UTF-8")
                    + "&num=5&api_key=" + java.net.URLEncoder.encode(key, "UTF-8");
            String body = GitHubAdapter.httpGet(url, false);
            if (body == null) {
                callback.onDone(results, "Зовнішній пошук: мережева помилка або помилка запиту");
                return;
            }
            JSONObject root = new JSONObject(body);
            if (root.has("error")) {
                callback.onDone(results, "Зовнішній пошук: " + root.optString("error"));
                return;
            }
            JSONArray organic = root.optJSONArray("organic_results");
            if (organic == null) {
                callback.onDone(results, null);
                return;
            }
            for (int i = 0; i < organic.length() && i < 5; i++) {
                JSONObject item = organic.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String link = item.optString("link", "");
                if (link.isEmpty()) {
                    continue;
                }
                results.add(new IntelResult(
                        item.optString("title", link),
                        item.optString("snippet", ""),
                        link,
                        "Зовнішній пошуковий API (SerpAPI-сумісний)",
                        item.optString("date", "-"),
                        IntelResult.UNVERIFIED));
            }
            callback.onDone(results, null);
        } catch (Exception e) {
            callback.onDone(results, "Зовнішній пошук: " + e.getClass().getSimpleName());
        }
    }
}
