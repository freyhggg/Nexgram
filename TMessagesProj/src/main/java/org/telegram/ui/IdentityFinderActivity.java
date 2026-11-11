package org.telegram.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.nexgram.NexgramPrefs;
import org.telegram.nexgram.SecureKeyStore;
import org.telegram.nexgram.intelligence.ExternalSearchAdapter;
import org.telegram.nexgram.intelligence.GitHubAdapter;
import org.telegram.nexgram.intelligence.IntelModule;
import org.telegram.nexgram.intelligence.IntelResult;
import org.telegram.nexgram.intelligence.IntelligenceCoordinator;
import org.telegram.nexgram.intelligence.TelegramPageAdapter;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

/**
 * Identity Finder — search of PUBLIC information only.
 *
 * Allowed: public web pages, public profiles, public usernames,
 * public business contacts, public mentions and links.
 *
 * Forbidden (and not implemented): hidden phone numbers, private
 * addresses, geolocation tracking, private conversations, leaked
 * databases, account protection bypass, or profiling of people
 * based on unverified matches.
 */
public class IdentityFinderActivity extends BaseFragment {

    private static final int BG = Color.rgb(9, 8, 18);
    private static final int SURFACE = Color.rgb(21, 16, 32);
    private static final int TEXT = Color.rgb(244, 238, 255);
    private static final int MUTED = Color.rgb(169, 157, 187);
    private static final int GOOD = 0xFF42D392;
    private static final int WARN = 0xFFFFC24B;

    private LinearLayout resultsContainer;
    private TextView statusLine;
    private TextView keyStatus;
    private EditText queryField;
    private IntelligenceCoordinator coordinator;
    private ExternalSearchAdapter externalAdapter;

    @Override
    public View createView(Context context) {
        NEXGRAMTheme.apply(context);
        coordinator = new IntelligenceCoordinator();
        coordinator.register(new TelegramPageAdapter());
        coordinator.register(new GitHubAdapter());
        externalAdapter = new ExternalSearchAdapter(context);
        coordinator.register(externalAdapter);

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("Identity Finder");
        actionBar.setAllowOverlayTitle(false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(BG);
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(20), AndroidUtilities.dp(18), AndroidUtilities.dp(28));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        content.addView(text(context, "ПУБЛІЧНИЙ ПОШУК", 11, MUTED, true), margin(2, 0, 0, 6));
        content.addView(text(context, "Identity Finder", 22, TEXT, true), margin(0, 0, 0, 8));
        content.addView(text(context, "Шукає лише публічні дані: відкриті профілі, username, бізнес-сторінки, згадки. Кожен результат містить джерело, посилання, дату та рівень достовірності.", 13, MUTED, false), margin(0, 0, 0, 12));

        LinearLayout limits = card(context);
        content.addView(limits, margin(0, 0, 0, 14));
        limits.addView(text(context, "Обмеження (непорушні)", 14, TEXT, true), margin(0, 0, 0, 6));
        limits.addView(text(context, "Не шукає приховані номери • не визначає приватні адреси • не відстежує геолокацію • не читає приватні переписки • не використовує злиті бази • не обходити захист акаунтів.", 12, MUTED, false));

        // Query input
        LinearLayout queryCard = card(context);
        content.addView(queryCard, margin(0, 0, 0, 12));
        queryCard.addView(text(context, "Запит", 14, TEXT, true), margin(0, 0, 0, 8));
        queryField = new EditText(context);
        queryField.setHint("username або фраза для публічного пошуку");
        queryField.setHintTextColor(MUTED);
        queryField.setTextColor(TEXT);
        queryField.setTextSize(15);
        queryField.setSingleLine(true);
        GradientDrawable field = new GradientDrawable();
        field.setColor(0xFF090812);
        field.setCornerRadius(AndroidUtilities.dp(12));
        queryField.setBackground(field);
        queryField.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(12), AndroidUtilities.dp(14), AndroidUtilities.dp(12));
        queryCard.addView(queryField, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(46)));
        TextView run = button(context, "Шукати в публічних джерелах", 0xFF8B2CFF);
        queryCard.addView(run, margin(0, 10, 0, 0));
        run.setOnClickListener(v -> runSearch(context));

        // External key management
        LinearLayout keyCard = card(context);
        content.addView(keyCard, margin(0, 0, 0, 14));
        keyCard.addView(text(context, "Зовнішній пошуковий API (необов'язково)", 14, TEXT, true), margin(0, 0, 0, 6));
        keyStatus = text(context, keyStatusText(context), 12, MUTED, false);
        keyCard.addView(keyStatus, margin(0, 0, 0, 8));
        LinearLayout keyRow = new LinearLayout(context);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        keyRow.setGravity(Gravity.CENTER_VERTICAL);
        keyCard.addView(keyRow, new LinearLayout.LayoutParams(-1, -2));
        TextView setKey = button(context, "Ключ…", SURFACE);
        setKey.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        keyRow.addView(setKey);
        setKey.setOnClickListener(v -> askForKey(context));
        TextView clearKey = button(context, "Очистити", SURFACE);
        clearKey.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        ((LinearLayout.LayoutParams) clearKey.getLayoutParams()).leftMargin = AndroidUtilities.dp(10);
        keyRow.addView(clearKey);
        clearKey.setOnClickListener(v -> {
            NexgramPrefs.setIntelKeyCipher(context, null);
            keyStatus.setText(keyStatusText(context));
        });

        // Results
        content.addView(text(context, "РЕЗУЛЬТАТИ", 11, MUTED, true), margin(2, 6, 0, 10));
        resultsContainer = new LinearLayout(context);
        resultsContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(resultsContainer, new LinearLayout.LayoutParams(-1, -2));

        content.addView(text(context, "Достовірність означає рівень доступу до джерела: «Підтверджено» — жива публічна сторінка перевірена запитом; «Ймовірно» — збіг у публічному індексі; «Непідтверджено» — згадка без прямої сторінки.", 11, MUTED, false), margin(0, 12, 0, 0));
        content.addView(text(context, "NEXGRAM Identity Finder • тільки публічні джерела", 12, MUTED, false), margin(0, 18, 0, 0));

        fragmentView = root;
        return fragmentView;
    }

    private String keyStatusText(Context context) {
        return externalAdapter != null && externalAdapter.hasKey()
                ? "Ключ налаштовано і зашифровано (Android Keystore)."
                : "Ключ не налаштовано. Модуль вимкнено і він не дає результатів.";
    }

    private void askForKey(Context context) {
        final EditText input = new EditText(context);
        input.setHint("API-ключ (SerpAPI-сумісний)");
        input.setHintTextColor(MUTED);
        input.setTextColor(TEXT);
        input.setSingleLine(true);
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Ключ зовнішнього API");
        builder.setMessage("Ключ зберігається тільки на пристрої, зашифрований через Android Keystore. Не надсилайте його в чати.");
        builder.setView(input, AndroidUtilities.dp(64));
        builder.setPositiveButton("Зберегти", (dialog, which) -> {
            String value = input.getText().toString().trim();
            if (!value.isEmpty()) {
                String cipher = SecureKeyStore.encrypt(value);
                if (cipher != null) {
                    NexgramPrefs.setIntelKeyCipher(context, cipher);
                }
                keyStatus.setText(keyStatusText(context));
            }
        });
        builder.setNegativeButton("Скасувати", null);
        builder.show();
    }

    private void runSearch(Context context) {
        final String query = queryField.getText() != null ? queryField.getText().toString().trim() : "";
        if (query.isEmpty()) {
            statusCard(context, "Введіть запит.");
            return;
        }
        resultsContainer.removeAllViews();
        statusLine = text(context, "Аналіз запиту та запуск адаптерів…", 13, MUTED, false);
        resultsContainer.addView(statusLine, margin(0, 0, 0, 8));

        coordinator.run(query, report -> AndroidUtilities.runOnUIThread(() -> {
            if (getContext() == null) {
                return;
            }
            resultsContainer.removeAllViews();
            Context ctx = getContext();
            if (report.errors != null) {
                for (String error : report.errors) {
                    resultsContainer.addView(statusCard(ctx, "⚠ " + error), margin(0, 0, 0, 8));
                }
            }
            if (report.results.isEmpty()) {
                resultsContainer.addView(statusCard(ctx, "Публічних результатів не знайдено."), margin(0, 0, 0, 8));
                return;
            }
            for (IntelResult result : report.results) {
                resultsContainer.addView(resultCard(ctx, result), margin(0, 0, 0, 10));
            }
        }));
    }

    private View resultCard(Context context, IntelResult result) {
        LinearLayout card = card(context);
        int badgeColor = result.confidence == IntelResult.CONFIRMED ? GOOD
                : result.confidence == IntelResult.PROBABLE ? WARN : MUTED;
        TextView badge = text(context, IntelResult.confidenceLabel(result.confidence).toUpperCase(), 10, badgeColor, true);
        badge.setLetterSpacing(0.1f);
        card.addView(badge, margin(0, 0, 0, 6));
        card.addView(text(context, result.title, 15, TEXT, true));
        if (!TextUtils.isEmpty(result.summary)) {
            card.addView(text(context, result.summary, 12, MUTED, false), margin(0, 3, 0, 0));
        }
        card.addView(text(context, "Джерело: " + result.source + " • Дата: " + result.dateText, 11, MUTED, false), margin(0, 5, 0, 0));
        if (!TextUtils.isEmpty(result.url)) {
            TextView link = text(context, result.url, 12, 0xFFB04CFF, true);
            card.addView(link, margin(0, 5, 0, 0));
            link.setOnClickListener(v -> Browser.openUrl(context, result.url));
        }
        return card;
    }

    private View statusCard(Context context, String message) {
        LinearLayout card = card(context);
        card.addView(text(context, message, 13, MUTED, false));
        return card;
    }

    // --- UI helpers ---

    private LinearLayout card(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(15), AndroidUtilities.dp(16), AndroidUtilities.dp(15));
        GradientDrawable background = new GradientDrawable();
        background.setColor(SURFACE);
        background.setCornerRadius(AndroidUtilities.dp(18));
        layout.setBackground(background);
        return layout;
    }

    private TextView button(Context context, String label, int color) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setTextSize(14);
        view.setTextColor(TEXT);
        view.setGravity(Gravity.CENTER);
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(AndroidUtilities.dp(14));
        view.setBackground(background);
        view.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(12));
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private TextView text(Context context, String value, int size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return view;
    }

    private LinearLayout.LayoutParams margin(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(AndroidUtilities.dp(left), AndroidUtilities.dp(top), AndroidUtilities.dp(right), AndroidUtilities.dp(bottom));
        return lp;
    }
}
