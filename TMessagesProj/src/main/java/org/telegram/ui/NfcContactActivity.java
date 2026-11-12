package org.telegram.ui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.nfc.NfcAdapter;
import android.nfc.NfcManager;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.provider.ContactsContract;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.browser.Browser;
import org.telegram.nexgram.NexgramAnim;
import org.telegram.nexgram.NexgramPrefs;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

import java.nio.charset.StandardCharsets;

/**
 * NEXGRAM NFC contact exchange.
 *
 * SENDER side: an official Android HostApduService (NfcCardService) emulates
 * a contact card; sharing is off by default and fully field-controlled.
 *
 * READER side: while this screen is open, the phone listens in NFC reader
 * mode. A read ALWAYS ends in an explicit user dialog — nothing is saved
 * without confirmation. Available actions: open the Telegram profile by
 * username (official t.me link), copy, or save to system contacts through
 * the system Contacts editor (no direct contact writes).
 */
public class NfcContactActivity extends BaseFragment implements NfcAdapter.ReaderCallback {

    private static final int BG = Color.rgb(9, 8, 18);
    private static final int SURFACE = Color.rgb(21, 16, 32);
    private static final int TEXT = Color.rgb(244, 238, 255);
    private static final int MUTED = Color.rgb(169, 157, 187);
    private static final int GOOD = 0xFF42D392;
    private static final int WARN = 0xFFFFC24B;

    private TextView statusLine;
    private View readyDot;
    private FrameLayout pulseField;
    private ValueAnimator idleAnimator;

    private NfcAdapter nfcAdapter;

    @Override
    public View createView(Context context) {
        NEXGRAMTheme.apply(context);
        NfcManager manager = (NfcManager) context.getSystemService(Context.NFC_SERVICE);
        nfcAdapter = manager != null ? manager.getDefaultAdapter() : null;

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("NFC-картка");
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

        content.addView(text(context, "ОБМІН КОНТАКТАМИ", 11, MUTED, true), margin(2, 0, 0, 6));
        content.addView(text(context, "NFC-картка", 22, TEXT, true), margin(0, 0, 0, 8));
        content.addView(text(context,
                "Піднеси два телефони задніми панелями: один поширює контакт, інший читає. Дані — тільки публічні поля, які ти сам увімкнув. Читання потребує підтвердження на обох боках.",
                13, MUTED, false), margin(0, 0, 0, 12));

        // --- Status card ---
        LinearLayout statusCard = card(context);
        content.addView(statusCard, margin(0, 0, 0, 12));
        LinearLayout statusRow = new LinearLayout(context);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusCard.addView(statusRow, margin(0, 0, 0, 6));
        readyDot = new View(context);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setColor(nfcReady() ? GOOD : MUTED);
        dotBg.setCornerRadius(AndroidUtilities.dp(6));
        readyDot.setBackground(dotBg);
        statusRow.addView(readyDot, new LinearLayout.LayoutParams(AndroidUtilities.dp(12), AndroidUtilities.dp(12)));
        TextView dotSpacer = new TextView(context);
        dotSpacer.setMinWidth(AndroidUtilities.dp(8));
        statusRow.addView(dotSpacer);
        statusLine = text(context, statusText(context), 14, nfcReady() ? GOOD : TEXT, true);
        statusRow.addView(statusLine);
        statusCard.addView(text(context,
                nfcAdapter == null
                        ? "На цьому пристрої немає NFC-модуля. Обмін через NFC недоступний — використовуй Nearby або QR."
                        : "Читач активний, поки відкритий цей екран. Картка емітується постійно, якщо поширення увімкнено.",
                12, MUTED, false), margin(0, 4, 0, 0));

        // --- Pulse field (custom animation: expanding rings on a successful read) ---
        pulseField = new FrameLayout(context);
        content.addView(pulseField, margin(0, 0, 0, 8));
        pulseField.getLayoutParams().height = AndroidUtilities.dp(96);
        for (int i = 0; i < 3; i++) {
            View ring = new View(context);
            GradientDrawable ringBg = new GradientDrawable();
            ringBg.setColor(Color.TRANSPARENT);
            ringBg.setCornerRadius(AndroidUtilities.dp(999));
            ringBg.setStroke(AndroidUtilities.dp(2), NexgramPrefs.getAccent(context));
            ring.setBackground(ringBg);
            ring.setAlpha(0f);
            pulseField.addView(ring, new FrameLayout.LayoutParams(AndroidUtilities.dp(72), AndroidUtilities.dp(72), Gravity.CENTER));
        }
        View core = new View(context);
        GradientDrawable coreBg = new GradientDrawable();
        coreBg.setColor(NexgramPrefs.getAccent(context));
        coreBg.setCornerRadius(AndroidUtilities.dp(999));
        core.setBackground(coreBg);
        pulseField.addView(core, new FrameLayout.LayoutParams(AndroidUtilities.dp(26), AndroidUtilities.dp(26), Gravity.CENTER));

        // --- Sharing card ---
        LinearLayout shareCard = card(context);
        content.addView(shareCard, margin(0, 0, 0, 12));
        shareCard.addView(text(context, "Поширення мого контакту", 16, TEXT, true), margin(0, 0, 0, 4));
        shareCard.addView(text(context, "Ім'я поширюється завжди (це і є контакт). Телефон — вимкнений за замовчуванням.", 12, MUTED, false), margin(0, 0, 0, 10));
        addSwitch(shareCard, context, "Поширювати NFC-картку",
                "Головний вимикач. Вимкнено = картка порожня, пристрій нічого не віддає.",
                NexgramPrefs.KEY_NFC_SHARE, false, value -> {
                    NexgramPrefs.setNfcShareEnabled(context, value);
                    refreshStatus(context);
                });
        addSwitch(shareCard, context, "Додати username",
                "Публічний @username Telegram — дозволяє одразу відкрити профіль.",
                NexgramPrefs.KEY_NFC_SHARE_USERNAME, true, value -> NexgramPrefs.setNfcShareUsername(context, value));
        addSwitch(shareCard, context, "Додати номер телефону",
                "Номер у форматі Telegram (+38…). Увімкни, лише якщо це публічний робочий номер.",
                NexgramPrefs.KEY_NFC_SHARE_PHONE, false, value -> NexgramPrefs.setNfcSharePhone(context, value));
        LinearLayout previewCard = new LinearLayout(context);
        previewCard.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable previewBg = new GradientDrawable();
        previewBg.setColor(0xFF090812);
        previewBg.setCornerRadius(AndroidUtilities.dp(12));
        previewCard.setBackground(previewBg);
        previewCard.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(10));
        shareCard.addView(previewCard, margin(0, 10, 0, 0));
        previewCard.addView(text(context, "Що отримає інший пристрій:", 11, MUTED, true), margin(0, 0, 0, 4));
        previewCard.addView(text(context, myCardPreview(context), 13, TEXT, false));

        // --- Receiving card ---
        LinearLayout receiveCard = card(context);
        content.addView(receiveCard, margin(0, 0, 0, 14));
        receiveCard.addView(text(context, "Прочитати чужий контакт", 16, TEXT, true), margin(0, 0, 0, 6));
        receiveCard.addView(text(context,
                "Цей екран уже слухає NFC. Просто піднеси телефон до іншого NEXGRAM (навіть до свого — так можна перевірити). Перед збереженням побачиш діалог із даними.",
                12, MUTED, false), margin(0, 0, 0, 8));
        TextView openNearby = button(context, "Бездротовий обмін файлів (Nearby)", SURFACE);
        receiveCard.addView(openNearby, margin(0, 2, 0, 0));
        openNearby.setOnClickListener(v -> presentFragment(new NearbyActivity()));

        content.addView(text(context, "NEXGRAM NFC • Android Host Card Emulation + Reader Mode", 12, MUTED, false), margin(0, 18, 0, 0));

        NexgramAnim.cascade(content);
        fragmentView = root;
        return fragmentView;
    }

    // --- Reader mode lifecycle ---

    @Override
    public void onResume() {
        super.onResume();
        Activity activity = getParentActivity();
        if (nfcAdapter != null && activity != null) {
            nfcAdapter.enableReaderMode(activity, this,
                    NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null);
        }
        startIdlePulse();
        Context context = getContext();
        if (context != null) {
            refreshStatus(context);
        }
    }

    @Override
    public void onPause() {
        Activity activity = getParentActivity();
        if (nfcAdapter != null && activity != null) {
            nfcAdapter.disableReaderMode(activity);
        }
        stopIdlePulse();
        super.onPause();
    }

    @Override
    public void onFragmentDestroy() {
        stopIdlePulse();
        super.onFragmentDestroy();
    }

    // --- Tag processing (runs on a worker thread; NFC forbids main-thread transceive) ---

    @Override
    public void onTagDiscovered(Tag tag) {
        IsoDep iso = tag != null ? IsoDep.get(tag) : null;
        if (iso == null) {
            showReadResult(null, null, null, false, false, "Пристрій не підтримує NEXGRAM-картку (не ISO-DEP).");
            return;
        }
        new Thread(() -> {
            try {
                iso.connect();
                iso.setTimeout(1500);
                byte[] select = {0x00, (byte) 0xA4, 0x04, 0x00, 0x07,
                        (byte) 0xF0, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x00};
                byte[] selectResponse = iso.transceive(select);
                if (!isOk(selectResponse)) {
                    showReadResult(null, null, null, false, false, "Картка NEXGRAM не відповіла (SELECT відхилено).");
                    return;
                }
                byte[] get = {(byte) 0x80, 0x11, 0x00, 0x00};
                byte[] response = iso.transceive(get);
                if (!isOk(response)) {
                    showReadResult(null, null, null, false, false, "Картка не віддала дані.");
                    return;
                }
                int length = response.length - 2;
                if (length <= 0) {
                    showReadResult(null, null, null, false, false,
                            "Пристрій знайдено, але поширення контакту вимкнено на ньому.");
                    return;
                }
                String payload = new String(response, 0, length, StandardCharsets.UTF_8);
                String[] parts = payload.split("\\|", -1);
                if (parts.length < 4 || !"NXNFC1".equals(parts[0])) {
                    showReadResult(null, null, null, false, false, "Невідомий формат NFC-картки.");
                    return;
                }
                showReadResult(parts[1], parts[2], parts[3], true, true, null);
            } catch (Exception e) {
                showReadResult(null, null, null, false, false, "NFC-обмін не вдався: " + e.getMessage());
            } finally {
                try {
                    iso.close();
                } catch (Exception ignored) {
                }
            }
        }, "NexgramNfcReader").start();
    }

    private static boolean isOk(byte[] response) {
        return response != null && response.length >= 2
                && response[response.length - 2] == (byte) 0x90 && response[response.length - 1] == (byte) 0x00;
    }

    private void showReadResult(String name, String username, String phone,
                                boolean valid, boolean animate, String error) {
        AndroidUtilities.runOnUIThread(() -> {
            Context context = getContext();
            if (context == null || getParentActivity() == null) {
                return;
            }
            if (animate) {
                pulse();
            }
            if (!valid) {
                AndroidUtilities.runOnUIThread(() -> android.widget.Toast
                        .makeText(context, error, android.widget.Toast.LENGTH_LONG).show());
                return;
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
            builder.setTitle("Прочитано NFC-картку");
            StringBuilder info = new StringBuilder();
            if (!TextUtils.isEmpty(name)) {
                info.append("Ім'я: ").append(name).append("\n");
            }
            if (!TextUtils.isEmpty(username)) {
                info.append("Telegram: @").append(username).append("\n");
            }
            if (!TextUtils.isEmpty(phone)) {
                info.append("Телефон: ").append(phone).append("\n");
            }
            if (TextUtils.isEmpty(name) && TextUtils.isEmpty(username) && TextUtils.isEmpty(phone)) {
                info.append("Картка порожня (власник нічого не поширює).");
            }
            builder.setMessage(info.toString().trim());
            builder.setNegativeButton("Закрити", null);
            if (!TextUtils.isEmpty(username)) {
                builder.setPositiveButton("Профіль у Telegram", (dialog, which) ->
                        Browser.openUrl(getParentActivity(), "https://t.me/" + username));
                builder.setNeutralButton("Копіювати", (dialog, which) -> {
                    AndroidUtilities.addToClipboard("@" + username);
                    android.widget.Toast.makeText(context, "Скопійовано", android.widget.Toast.LENGTH_SHORT).show();
                });
            } else if (!TextUtils.isEmpty(phone)) {
                builder.setNeutralButton("Копіювати", (dialog, which) -> {
                    AndroidUtilities.addToClipboard(phone);
                    android.widget.Toast.makeText(context, "Скопійовано", android.widget.Toast.LENGTH_SHORT).show();
                });
            }
            if (!TextUtils.isEmpty(phone) || !TextUtils.isEmpty(name)) {
                // Save through the SYSTEM Contacts editor — NEXGRAM never writes contacts directly.
                final String saveName = TextUtils.isEmpty(name) ? (TextUtils.isEmpty(username) ? "NEXGRAM" : username) : name;
                final String savePhone = TextUtils.isEmpty(phone) ? "" : phone;
                final String note = TextUtils.isEmpty(username) ? "Отримано через NEXGRAM NFC"
                        : "Telegram: @" + username;
                builder.setPositiveButton("Зберегти в контакти", (dialog, which) -> {
                    Intent intent = new Intent(ContactsContract.Intents.Insert.ACTION);
                    intent.setType(ContactsContract.RawContacts.CONTENT_TYPE);
                    intent.putExtra(ContactsContract.Intents.Insert.NAME, saveName);
                    if (!TextUtils.isEmpty(savePhone)) {
                        intent.putExtra(ContactsContract.Intents.Insert.PHONE, savePhone);
                        intent.putExtra(ContactsContract.Intents.Insert.PHONE_TYPE,
                                ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE);
                    }
                    intent.putExtra(ContactsContract.Intents.Insert.NOTES, note);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        getParentActivity().startActivity(intent);
                    } catch (Exception e) {
                        android.widget.Toast.makeText(context, "Редактор контактів недоступний", android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
            }
            builder.show();
        });
    }

    // --- Idle pulse + success burst (animations original Telegram doesn't have) ---

    private void startIdlePulse() {
        if (pulseField == null || pulseField.getChildCount() == 0) {
            return;
        }
        if (!NexgramPrefs.animExperiments(getContext())) {
            return;
        }
        stopIdlePulse();
        View core = pulseField.getChildAt(pulseField.getChildCount() - 1);
        idleAnimator = ValueAnimator.ofFloat(1f, 1.18f, 1f);
        idleAnimator.setDuration(1800);
        idleAnimator.setRepeatCount(ValueAnimator.INFINITE);
        idleAnimator.addUpdateListener(a -> {
            float f = (Float) a.getAnimatedValue();
            core.setScaleX(f);
            core.setScaleY(f);
        });
        idleAnimator.start();
    }

    private void stopIdlePulse() {
        if (idleAnimator != null) {
            idleAnimator.cancel();
            idleAnimator = null;
        }
        if (pulseField != null && pulseField.getChildCount() > 0) {
            View core = pulseField.getChildAt(pulseField.getChildCount() - 1);
            core.setScaleX(1f);
            core.setScaleY(1f);
        }
    }

    private void pulse() {
        if (pulseField == null || !NexgramPrefs.animExperiments(getContext())) {
            return;
        }
        for (int i = 0; i < pulseField.getChildCount() - 1; i++) {
            View ring = pulseField.getChildAt(i);
            ring.animate().cancel();
            ring.setScaleX(0.4f);
            ring.setScaleY(0.4f);
            ring.setAlpha(1f);
            ring.animate()
                    .scaleX(2.6f).scaleY(2.6f).alpha(0f)
                    .setDuration(780).setStartDelay(i * 160L)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }
    }

    // --- Status helpers ---

    private boolean nfcReady() {
        return nfcAdapter != null && nfcAdapter.isEnabled();
    }

    private String statusText(Context context) {
        if (nfcAdapter == null) {
            return "NFC недоступний";
        }
        if (!nfcAdapter.isEnabled()) {
            return "NFC вимкнено в налаштуваннях Android";
        }
        return NexgramPrefs.nfcShareEnabled(context) ? "Картка активна • читач активний" : "Картка вимкнена • читач активний";
    }

    private void refreshStatus(Context context) {
        if (statusLine != null) {
            statusLine.setText(statusText(context));
            statusLine.setTextColor(nfcReady() ? GOOD : TEXT);
        }
        if (readyDot != null) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(nfcReady() ? GOOD : MUTED);
            bg.setCornerRadius(AndroidUtilities.dp(6));
            readyDot.setBackground(bg);
        }
        if (getContext() != null) {
            NexgramAnim.pop(statusLine);
        }
    }

    private String myCardPreview(Context context) {
        TLRPC.User user = UserConfig.getInstance(UserConfig.selectedAccount).getCurrentUser();
        String name = user != null ? UserObject.getFirstName(user) : "—";
        StringBuilder preview = new StringBuilder(name);
        if (NexgramPrefs.nfcShareUsername(context) && user != null && !TextUtils.isEmpty(user.username)) {
            preview.append(" • @").append(user.username);
        }
        if (NexgramPrefs.nfcSharePhone(context) && user != null && !TextUtils.isEmpty(user.phone)) {
            preview.append(" • ").append(user.phone);
        }
        if (!NexgramPrefs.nfcShareEnabled(context)) {
            preview.append("  (поширення вимкнено)");
        }
        return preview.toString();
    }

    // --- Switch helper (mirrors settings screen pattern) ---

    private interface SwitchListener {
        void onChecked(boolean value);
    }

    private void addSwitch(LinearLayout parent, Context context, String title, String subtitle,
                           String key, boolean defaultValue, SwitchListener listener) {
        LinearLayout row = card(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(row, margin(0, 6, 0, 10));
        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
        labels.addView(text(context, title, 15, TEXT, true));
        labels.addView(text(context, subtitle, 12, MUTED, false), margin(0, 4, 10, 0));
        android.widget.Switch toggle = new android.widget.Switch(context);
        toggle.setChecked(NexgramPrefs.get(context).getBoolean(key, defaultValue));
        row.addView(toggle, new LinearLayout.LayoutParams(-2, -2));
        toggle.setOnCheckedChangeListener((button, checked) -> listener.onChecked(checked));
        NexgramAnim.pressScale(row);
    }

    // --- UI helpers (same style as NEXGRAM settings) ---

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
        NexgramAnim.pressScale(view);
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
