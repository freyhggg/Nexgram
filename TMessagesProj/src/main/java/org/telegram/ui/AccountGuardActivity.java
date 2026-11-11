package org.telegram.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;
import org.telegram.nexgram.AccountGuardController;
import org.telegram.nexgram.NexgramPrefs;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.List;

/**
 * Account Guard — NEXGRAM's session security screen.
 *
 * Only official Telegram API data is used:
 *  - active sessions (account.getAuthorizations)
 *  - 2FA status (account.getPassword)
 *  - session termination (account.resetAuthorization, explicit user action)
 *
 * No IP blocking, no password/session storage. Unfamiliar sessions are
 * detected by comparing locally stored session hashes only.
 */
public class AccountGuardActivity extends BaseFragment {

    private static final int BG = Color.rgb(9, 8, 18);
    private static final int SURFACE = Color.rgb(21, 16, 32);
    private static final int TEXT = Color.rgb(244, 238, 255);
    private static final int MUTED = Color.rgb(169, 157, 187);
    private static final int DANGER = 0xFFFF5C5C;

    private LinearLayout sessionsContainer;
    private LinearLayout incidentsContainer;
    private TextView passwordStatus;
    private TextView statusLine;
    private final List<TLRPC.TL_authorization> loadedSessions = new ArrayList<>();

    @Override
    public View createView(Context context) {
        NEXGRAMTheme.apply(context);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("Account Guard");
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

        content.addView(text(context, "ЗАХИСТ АКАУНТА", 11, MUTED, true), margin(2, 0, 0, 6));
        content.addView(text(context, "Сесії та двоетапна перевірка", 22, TEXT, true), margin(0, 0, 0, 8));
        content.addView(text(context, "Використовуються лише офіційні дані Telegram. NEXGRAM не блокує IP і не зберігає паролі чи сесії.", 13, MUTED, false), margin(0, 0, 0, 18));

        // 2FA card
        LinearLayout twofa = card(context);
        content.addView(twofa, margin(0, 0, 0, 12));
        twofa.addView(text(context, "Двоетапна автентифікація", 16, TEXT, true), margin(0, 0, 0, 6));
        passwordStatus = text(context, "Перевіряю…", 13, MUTED, false);
        twofa.addView(passwordStatus, margin(0, 0, 0, 10));
        TextView open2fa = button(context, "Керування 2FA в Telegram", 0xFF8B2CFF);
        twofa.addView(open2fa, margin(0, 0, 0, 0));
        open2fa.setOnClickListener(v -> {
            int account = UserConfig.selectedAccount;
            if (UserConfig.getInstance(account).isClientActivated()) {
                presentFragment(new TwoStepVerificationActivity(account));
            }
        });

        // Sessions card
        content.addView(text(context, "АКТИВНІ СЕСІЇ", 11, MUTED, true), margin(2, 14, 0, 10));
        LinearLayout sessionsCard = card(context);
        content.addView(sessionsCard, margin(0, 0, 0, 12));
        statusLine = text(context, "Завантаження…", 13, MUTED, false);
        sessionsCard.addView(statusLine, margin(0, 0, 0, 10));
        sessionsContainer = new LinearLayout(context);
        sessionsContainer.setOrientation(LinearLayout.VERTICAL);
        sessionsCard.addView(sessionsContainer, new LinearLayout.LayoutParams(-1, -2));
        TextView markKnown = button(context, "Позначити всі поточні сесії як відомі", SURFACE);
        sessionsCard.addView(markKnown, margin(0, 12, 0, 0));
        markKnown.setOnClickListener(v -> {
            AccountGuardController.markAllKnown(getContext(), loadedSessions);
            renderSessions(getContext());
        });

        // Incidents card
        content.addView(text(context, "ЖУРНАЛ ПОДІЙ", 11, MUTED, true), margin(2, 14, 0, 10));
        LinearLayout incidentsCard = card(context);
        content.addView(incidentsCard, margin(0, 0, 0, 12));
        incidentsContainer = new LinearLayout(context);
        incidentsContainer.setOrientation(LinearLayout.VERTICAL);
        incidentsCard.addView(incidentsContainer, new LinearLayout.LayoutParams(-1, -2));
        TextView clearLog = button(context, "Очистити журнал", SURFACE);
        incidentsCard.addView(clearLog, margin(0, 12, 0, 0));
        clearLog.setOnClickListener(v -> {
            AccountGuardController.clearIncidentLog(getContext());
            renderIncidents(context);
        });

        content.addView(text(context, "NEXGRAM Account Guard • офіційний API Telegram", 12, MUTED, false), margin(0, 18, 0, 0));

        fragmentView = root;
        loadSessions();
        renderIncidents(context);
        loadPasswordState();
        return fragmentView;
    }

    // --- Data ---

    private void loadSessions() {
        int account = UserConfig.selectedAccount;
        AccountGuardController.fetchSessions(account, (result, error) -> {
            if (getContext() == null) {
                return;
            }
            loadedSessions.clear();
            if (result != null) {
                loadedSessions.addAll(result.authorizations);
                // record previously unseen sessions in the local incident log
                for (TLRPC.TL_authorization auth : result.authorizations) {
                    if (!AccountGuardController.isKnownSession(getContext(), auth.hash)) {
                        AccountGuardController.reportUnfamiliar(getContext(), auth);
                    }
                }
                renderIncidents(getContext());
            }
            renderSessions(getContext());
            if (error != null) {
                statusLine.setText("Помилка Telegram API: " + error.text);
            }
        });
    }

    private void loadPasswordState() {
        int account = UserConfig.selectedAccount;
        AccountGuardController.fetchPasswordState(account, (result, error) -> {
            if (getContext() == null) {
                return;
            }
            if (result != null) {
                if (result.has_password) {
                    passwordStatus.setText("Увімкнена — акаунт захищено паролем.");
                    passwordStatus.setTextColor(0xFF42D392);
                } else {
                    passwordStatus.setText("Не налаштована. Рекомендуємо увімкнути для захисту від перехоплення акаунта.");
                    passwordStatus.setTextColor(DANGER);
                }
            } else if (error != null) {
                passwordStatus.setText("Не вдалося перевірити: " + error.text);
                passwordStatus.setTextColor(MUTED);
            }
        });
    }

    private void renderSessions(Context context) {
        sessionsContainer.removeAllViews();
        if (loadedSessions.isEmpty()) {
            statusLine.setText("Сесії не завантажені або список порожній.");
            return;
        }
        statusLine.setText("Знайдено сесій: " + loadedSessions.size());
        for (TLRPC.TL_authorization auth : loadedSessions) {
            sessionsContainer.addView(sessionCard(context, auth), margin(0, 0, 0, 10));
        }
    }

    private View sessionCard(Context context, TLRPC.TL_authorization auth) {
        LinearLayout card = card(context);
        boolean current = auth.current;
        boolean known = AccountGuardController.isKnownSession(context, auth.hash);

        LinearLayout top = new LinearLayout(context);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(top, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        top.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));

        labels.addView(text(context, safe(auth.device_model, "Невідомий пристрій"), 15, TEXT, true));
        labels.addView(text(context, safe(auth.app_name, "App") + " " + safe(auth.app_version, "") +
                " • " + safe(auth.platform, "?") + " " + safe(auth.system_version, ""), 12, MUTED, false), margin(0, 3, 8, 0));
        labels.addView(text(context, "IP: " + safe(auth.ip, "—") + " • " + safe(auth.country, "—") +
                (TextUtilsCompat(safe(auth.region, "")) ? "" : " • " + safe(auth.region, "")), 12, MUTED, false), margin(0, 3, 8, 0));
        labels.addView(text(context, "Остання активність: " + AccountGuardController.formatTs(auth.date_active), 12, MUTED, false), margin(0, 3, 8, 0));

        String badge = current ? "ПОТОЧНА" : (known ? "" : "НОВА");
        if (!badge.isEmpty()) {
            TextView badgeView = text(context, badge, 10, current ? 0xFF42D392 : DANGER, true);
            badgeView.setLetterSpacing(0.08f);
            top.addView(badgeView, new LinearLayout.LayoutParams(-2, -2));
        }

        if (!current) {
            TextView terminate = button(context, "Завершити сесію", SURFACE);
            terminate.setTextColor(DANGER);
            card.addView(terminate, margin(0, 10, 0, 0));
            terminate.setOnClickListener(v -> confirmTerminate(context, auth));
        }
        return card;
    }

    private void confirmTerminate(Context context, TLRPC.TL_authorization auth) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Завершити сесію?");
        builder.setMessage("Пристрій «" + safe(auth.device_model, "?") + "» буде від'єднано від акаунта. Дію не можна скасувати.");
        builder.setPositiveButton("Завершити", (dialog, which) -> {
            int account = UserConfig.selectedAccount;
            AccountGuardController.terminateSession(account, auth.hash, (result, error) -> {
                if (getContext() == null) {
                    return;
                }
                if (result != null && result) {
                    loadSessions();
                } else {
                    statusLine.setText("Не вдалося завершити сесію: " + (error != null ? error.text : "невідома помилка"));
                }
            });
        });
        builder.setNegativeButton("Скасувати", null);
        builder.show();
    }

    private void renderIncidents(Context context) {
        incidentsContainer.removeAllViews();
        List<JSONObject> incidents = NexgramPrefs.incidentLog(context);
        if (incidents.isEmpty()) {
            incidentsContainer.addView(text(context, "Журнал порожній. Тут з'являтимуться записи про нові сесії, яких раніше не було.", 12, MUTED, false), margin(0, 0, 0, 0));
            return;
        }
        for (JSONObject entry : incidents) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.VERTICAL);
            incidentsContainer.addView(row, margin(0, 0, 0, 10));
            row.addView(text(context, entry.optString("device", "?"), 14, TEXT, true));
            row.addView(text(context, entry.optString("app", "") + " • " + entry.optString("ip", "—") + " • " + entry.optString("country", "—"), 12, MUTED, false), margin(0, 2, 0, 0));
            try {
                row.addView(text(context, AccountGuardController.formatWallTime(entry.optLong("time", 0)), 11, MUTED, false), margin(0, 2, 0, 0));
            } catch (Exception ignored) {
            }
        }
    }

    // --- UI helpers (shared NEXGRAM style) ---

    private static boolean TextUtilsCompat(String value) {
        return value == null || value.isEmpty();
    }

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

    private static String safe(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }
}
