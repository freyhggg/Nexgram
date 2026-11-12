package org.telegram.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.TelegramQRCodeWriter;
import org.telegram.messenger.AuthTokensHelper;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.nexgram.NexgramAnim;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * NEXGRAM QR login — the official Telegram "login by QR code" flow
 * (same as Telegram Desktop / Web), built on the real MTProto calls
 * auth.exportLoginToken / auth.importLoginToken that already exist in
 * TLRPC of this codebase.
 *
 * Flow:
 *  1. NEXGRAM requests a login token and renders it as tg://login?token=… QR.
 *  2. The user scans it with an ALREADY AUTHORIZED official Telegram
 *     (Settings → Devices → Link Desktop Device) — no code, no SMS, no typing.
 *  3. NEXGRAM polls the token: waiting → (optional DC migration) → success,
 *     then finishes the login exactly like the official code path does.
 */
public class NexgramQrLoginActivity extends BaseFragment {

    private static final int BG = Color.rgb(9, 8, 18);
    private static final int SURFACE = Color.rgb(21, 16, 32);
    private static final int TEXT = Color.rgb(244, 238, 255);
    private static final int MUTED = Color.rgb(169, 157, 187);
    private static final int GOOD = 0xFF42D392;

    private static final long POLL_INTERVAL_MS = 700;
    private static final long ERROR_RETRY_MS = 2500;
    private static final long TOKEN_REFRESH_MS = 30_000;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private FrameLayout qrCard;
    private ImageView qrView;
    private TextView statusLine;
    private TextView statusDetail;

    private boolean running;
    private boolean importing;
    private boolean finishing;
    private byte[] currentToken;
    private long tokenExpiresAtMs;
    private int targetDc;
    private int consecutiveErrors;

    @Override
    public View createView(Context context) {
        NEXGRAMTheme.apply(context);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("Вхід по QR-коду");
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

        content.addView(text(context, "БЕЗ КОДУ І БЕЗ SMS", 11, MUTED, true), margin(2, 0, 0, 6));
        content.addView(text(context, "Вхід по QR-коду", 22, TEXT, true), margin(0, 0, 0, 8));
        content.addView(text(context,
                "Офіційний спосіб Telegram (як у Desktop/Web): офіційний застосунок сканує цей QR і підтверджує вхід. Код вводити не потрібно взагалі.",
                13, MUTED, false), margin(0, 0, 0, 12));

        // QR area
        qrCard = new FrameLayout(context);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFFFFFFFF);
        cardBg.setCornerRadius(AndroidUtilities.dp(24));
        qrCard.setBackground(cardBg);
        content.addView(qrCard, margin(0, 0, 0, 10));
        qrView = new ImageView(context);
        qrView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qrCard.addView(qrView, new FrameLayout.LayoutParams(AndroidUtilities.dp(300), AndroidUtilities.dp(300), Gravity.CENTER));
        qrCard.getLayoutParams().height = AndroidUtilities.dp(340);

        statusLine = text(context, "Генеруємо токен…", 15, TEXT, true);
        statusLine.setGravity(Gravity.CENTER);
        content.addView(statusLine, margin(0, 0, 0, 4));
        statusDetail = text(context, "", 12, MUTED, false);
        statusDetail.setGravity(Gravity.CENTER);
        content.addView(statusDetail, margin(0, 0, 0, 12));

        // Instructions
        LinearLayout howTo = card(context);
        content.addView(howTo, margin(0, 0, 0, 12));
        howTo.addView(text(context, "Як увійти", 16, TEXT, true), margin(0, 0, 0, 6));
        howTo.addView(text(context,
                "1. Відкрий офіційний Telegram, у якому ти вже залогінений.\n"
                        + "2. Налаштування → Пристрої → «Пов'язати пристрій».\n"
                        + "3. Наведи камеру на цей QR-код.\n"
                        + "4. Підтверди вхід — NEXGRAM залогінить тебе сам.",
                13, TEXT, false), margin(0, 0, 0, 8));
        howTo.addView(text(context,
                "Потрібен офіційний Telegram з активним акаунтом на цьому номері. Створити новий акаунт можна лише в офіційному застосунку (правило Telegram для всіх сторонніх клієнтів).",
                12, MUTED, false));
        TextView regen = button(context, "Оновити QR-код", SURFACE);
        content.addView(regen, margin(0, 10, 0, 0));
        regen.setOnClickListener(v -> {
            currentToken = null;
            requestToken();
        });

        NexgramAnim.cascade(content);
        NexgramAnim.pressScale(regen);

        fragmentView = root;
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        startPolling();
    }

    @Override
    public void onPause() {
        stopPolling();
        super.onPause();
    }

    @Override
    public void onFragmentDestroy() {
        stopPolling();
        super.onFragmentDestroy();
    }

    // --- Official MTProto QR-login flow ---

    private void startPolling() {
        if (running || finishing) {
            return;
        }
        running = true;
        consecutiveErrors = 0;
        setStatus("Генеруємо токен…", "");
        requestToken();
    }

    private void stopPolling() {
        running = false;
        handler.removeCallbacksAndMessages(null);
    }

    private void scheduleNext(long delayMs) {
        if (!running) {
            return;
        }
        handler.postDelayed(this::requestToken, delayMs);
    }

    private void requestToken() {
        if (!running || finishing) {
            return;
        }
        Context context = getContext();
        if (context == null) {
            stopPolling();
            return;
        }
        boolean nowImporting = importing && currentToken != null;
        if (!nowImporting && currentToken != null
                && System.currentTimeMillis() < tokenExpiresAtMs - 4000
                && tokenExpiresAtMs > 0) {
            // Token still fresh — just wait for the scan, re-check soon.
            scheduleNext(POLL_INTERVAL_MS);
            return;
        }
        if (nowImporting) {
            TLRPC.TL_auth_importLoginToken req = new TLRPC.TL_auth_importLoginToken();
            req.token = currentToken;
            sendToTargetDc(req);
        } else {
            TLRPC.TL_auth_exportLoginToken req = new TLRPC.TL_auth_exportLoginToken();
            req.api_id = BuildVars.APP_ID;
            req.api_hash = BuildVars.APP_HASH;
            getConnectionsManager().sendRequest(req, this::onTokenResponse, 0);
        }
    }

    private void sendToTargetDc(TLRPC.TL_auth_importLoginToken req) {
        getConnectionsManager().sendRequest(req, this::onTokenResponse, null, null, 0, targetDc,
                ConnectionsManager.ConnectionTypeGeneric, true);
    }

    private void onTokenResponse(TLObject response, TLRPC.TL_error error) {
        AndroidUtilities.runOnUIThread(() -> {
            if (!running || finishing) {
                return;
            }
            Context context = getContext();
            if (context == null) {
                stopPolling();
                return;
            }
            if (error != null) {
                consecutiveErrors++;
                setStatus("Помилка входу", error.text);
                if (consecutiveErrors <= 3) {
                    scheduleNext(ERROR_RETRY_MS);
                } else {
                    stopPolling();
                }
                return;
            }
            consecutiveErrors = 0;
            if (response instanceof TLRPC.TL_auth_loginToken) {
                TLRPC.TL_auth_loginToken token = (TLRPC.TL_auth_loginToken) response;
                boolean tokenChanged = currentToken == null
                        || !java.util.Arrays.equals(currentToken, token.token);
                currentToken = token.token;
                tokenExpiresAtMs = token.expires > 0 ? token.expires * 1000L
                        : System.currentTimeMillis() + TOKEN_REFRESH_MS;
                if (tokenChanged) {
                    renderQr(context);
                }
                setStatus("QR готовий — скануй офіційним Telegram",
                        importing ? "Очікуємо підтвердження на DC " + targetDc : "Налаштування → Пристрої → Пов'язати пристрій");
                scheduleNext(POLL_INTERVAL_MS);
            } else if (response instanceof TLRPC.TL_auth_loginTokenMigrateTo) {
                TLRPC.TL_auth_loginTokenMigrateTo migrate = (TLRPC.TL_auth_loginTokenMigrateTo) response;
                importing = true;
                targetDc = migrate.dc_id;
                currentToken = migrate.token;
                setStatus("Переносимо сесію…", "DC " + targetDc);
                scheduleNext(POLL_INTERVAL_MS);
            } else if (response instanceof TLRPC.TL_auth_loginTokenSuccess) {
                TLRPC.TL_auth_loginTokenSuccess success = (TLRPC.TL_auth_loginTokenSuccess) response;
                if (success.authorization instanceof TLRPC.TL_auth_authorization) {
                    completeLogin((TLRPC.TL_auth_authorization) success.authorization);
                } else {
                    setStatus("Неочікувана відповідь сервера", "");
                    stopPolling();
                }
            } else {
                setStatus("Невідома відповідь сервера", "");
                scheduleNext(ERROR_RETRY_MS);
            }
        });
    }

    private void renderQr(Context context) {
        try {
            String base64 = Base64.encodeToString(currentToken,
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            String content = "tg://login?token=" + base64;
            HashMap<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 0);
            Bitmap bmp = new TelegramQRCodeWriter().encode(content,
                    AndroidUtilities.dp(300), AndroidUtilities.dp(300), hints, null, 0f, 0xFFFFFFFF, 0xFF090812);
            qrView.setImageBitmap(bmp);
            qrView.setAlpha(0f);
            qrView.animate().alpha(1f).setDuration(240).start();
            NexgramAnim.pop(qrCard);
        } catch (Exception e) {
            setStatus("Не вдалось намалювати QR", String.valueOf(e.getMessage()));
        }
    }

    /**
     * Mirrors the official onAuthSuccess() of LoginActivity so the QR login
     * completes through the exact same state transitions as a normal login.
     */
    private void completeLogin(TLRPC.TL_auth_authorization res) {
        finishing = true;
        stopPolling();
        int account = currentAccount;
        MessagesController.getInstance(account).cleanup();
        ConnectionsManager.getInstance(account).setUserId(res.user.id);
        UserConfig.getInstance(account).clearConfig();
        MessagesController.getInstance(account).cleanup();
        UserConfig.getInstance(account).syncContacts = true;
        UserConfig.getInstance(account).setCurrentUser(res.user);
        UserConfig.getInstance(account).saveConfig(true);
        MessagesStorage.getInstance(account).cleanup(true);
        ArrayList<TLRPC.User> users = new ArrayList<>();
        users.add(res.user);
        MessagesStorage.getInstance(account).putUsersAndChats(users, null, true, true);
        MessagesController.getInstance(account).putUser(res.user, false);
        ContactsController.getInstance(account).checkAppAccount();
        MessagesController.getInstance(account).checkPromoInfo(true);
        ConnectionsManager.getInstance(account).updateDcSettings();
        MessagesController.getInstance(account).loadAppConfig();
        MessagesController.getInstance(account).loadWebBrowserConfig();
        MessagesController.getInstance(account).checkPeerColors(false);
        if (res.future_auth_token != null) {
            AuthTokensHelper.saveLogInToken(res);
        }
        MediaDataController.getInstance(account).loadStickersByEmojiOrName(AndroidUtilities.STICKERS_PLACEHOLDER_PACK_NAME, false, true);
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.mainUserInfoChanged);
        LocaleController.getInstance().loadRemoteLanguages(account);

        if (getParentActivity() instanceof LaunchActivity) {
            ((LaunchActivity) getParentActivity()).switchToAccount(account, true, obj -> {
                Bundle args = new Bundle();
                args.putBoolean("afterSignup", false);
                MainTabsActivity mainTabsActivity = new MainTabsActivity();
                mainTabsActivity.prepareDialogsActivity(args);
                return mainTabsActivity;
            });
        }
        finishFragment();
    }

    private void setStatus(String main, String detail) {
        statusLine.setText(main);
        statusLine.setTextColor(TEXT);
        statusDetail.setText(detail == null ? "" : detail);
    }

    // --- UI helpers (NEXGRAM style) ---

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
