package org.telegram.nexgram.intelligence;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks whether a public Telegram preview page exists for a username
 * (t.me/<username>). Reads only the public web page that Telegram itself
 * serves to anyone, no Telegram API and no hidden data is used.
 */
public final class TelegramPageAdapter implements IntelModule {

    @Override
    public String id() {
        return "telegram_public_page";
    }

    @Override
    public String describe() {
        return "Публічна сторінка t.me (відкритий веб-перегляд)";
    }

    @Override
    public boolean canHandle(String query) {
        String q = query == null ? "" : query.trim();
        if (q.length() < 5 || q.contains(" ")) {
            return false;
        }
        return q.matches("[A-Za-z0-9_]{4,32}");
    }

    @Override
    public void search(String query, Callback callback) {
        List<IntelResult> results = new ArrayList<>();
        String username = query.trim();
        try {
            String body = GitHubAdapter.httpGet("https://t.me/" + username, false);
            if (body == null) {
                callback.onDone(results, "t.me: мережева помилка");
                return;
            }
            String title = extract(body, "<div class=\"tgme_page_title\">", "</div>");
            String extra = extract(body, "<div class=\"tgme_page_extra\">", "</div>");
            String description = extract(body, "<div class=\"tgme_page_description\">", "</div>");
            if (title == null) {
                // No public preview page (user may exist in-app but page is private/off)
                callback.onDone(results, null);
                return;
            }
            results.add(new IntelResult(
                    "t.me: " + clean(title),
                    clean(description) + (extra != null ? " • " + clean(extra) : ""),
                    "https://t.me/" + username,
                    "Публічна сторінка t.me",
                    "-",
                    IntelResult.CONFIRMED));
            callback.onDone(results, null);
        } catch (Exception e) {
            callback.onDone(results, "t.me: " + e.getClass().getSimpleName());
        }
    }

    private static String extract(String html, String start, String end) {
        int i = html.indexOf(start);
        if (i < 0) {
            return null;
        }
        int from = i + start.length();
        int to = html.indexOf(end, from);
        if (to < 0) {
            return null;
        }
        return html.substring(from, to);
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("<br/>", " ").replace("<br>", " ")
                .replaceAll("<[^>]+>", "").trim();
    }
}
