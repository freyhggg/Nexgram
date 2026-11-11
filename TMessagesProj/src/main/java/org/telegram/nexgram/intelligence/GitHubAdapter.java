package org.telegram.nexgram.intelligence;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * Public GitHub search. Accesses only the public GitHub REST API:
 * https://api.github.com/search/users — no keys, no private data.
 */
public final class GitHubAdapter implements IntelModule {

    @Override
    public String id() {
        return "github_public";
    }

    @Override
    public String describe() {
        return "Публічний пошук GitHub (REST API, без ключа)";
    }

    @Override
    public boolean canHandle(String query) {
        String q = query == null ? "" : query.trim();
        return q.length() >= 3 && !q.contains(" ");
    }

    @Override
    public void search(String query, Callback callback) {
        List<IntelResult> results = new ArrayList<>();
        try {
            String url = "https://api.github.com/search/users?q=" + URLEncoder.encode(query.trim(), "UTF-8") + "&per_page=5";
            String body = httpGet(url, true);
            if (body == null) {
                callback.onDone(results, "GitHub: мережева помилка або порожня відповідь");
                return;
            }
            JSONObject root = new JSONObject(body);
            JSONArray items = root.optJSONArray("items");
            if (items == null || items.length() == 0) {
                callback.onDone(results, null);
                return;
            }
            for (int i = 0; i < items.length() && i < 5; i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String login = item.optString("login", "");
                if (login.isEmpty()) {
                    continue;
                }
                int confidence = login.equalsIgnoreCase(query.trim())
                        ? IntelResult.CONFIRMED
                        : IntelResult.PROBABLE;
                results.add(new IntelResult(
                        "GitHub: @" + login,
                        item.optString("html_url", ""),
                        item.optString("html_url", ""),
                        "GitHub REST API (публічний індекс)",
                        "-", confidence));
            }
            callback.onDone(results, null);
        } catch (Exception e) {
            callback.onDone(results, "GitHub: " + e.getClass().getSimpleName());
        }
    }

    static String httpGet(String spec, boolean jsonAccept) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(spec).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "NEXGRAM-IdentityFinder (public data search)");
            if (jsonAccept) {
                connection.setRequestProperty("Accept", "application/vnd.github+json");
            }
            int code = connection.getResponseCode();
            if (code != 200) {
                return null;
            }
            InputStream stream = connection.getInputStream();
            Scanner scanner = new Scanner(stream, StandardCharsets.UTF_8.name()).useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next() : "";
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
