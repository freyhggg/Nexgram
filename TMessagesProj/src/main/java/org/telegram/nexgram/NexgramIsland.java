package org.telegram.nexgram;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * NEXGRAM Island — an in-app, animated banner for incoming messages.
 *
 * This is NOT a system notification and never replaces or duplicates
 * Telegram's official Android notifications:
 *  - it is drawn inside NEXGRAM's own window hierarchy only,
 *  - it is shown only while the app window is focused,
 *  - it fully respects the chat's mute settings (official
 *    NotificationsController.isGlobalNotificationsEnabled check),
 *  - tapping it simply opens the chat.
 */
public final class NexgramIsland implements NotificationCenter.NotificationCenterDelegate {

    private static final long AUTO_HIDE_MS = 4200;

    private static NexgramIsland instance;

    private final LaunchActivity activity;
    private final List<Integer> attachedAccounts = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private IslandView view;
    private ValueAnimator animator;
    private Runnable hideRunnable;
    private boolean visible;

    private NexgramIsland(LaunchActivity activity) {
        this.activity = activity;
    }

    // --- Lifecycle ---

    public static void attach(LaunchActivity activity) {
        if (activity == null) {
            return;
        }
        detach();
        if (!NexgramPrefs.islandEnabled(activity)) {
            return;
        }
        instance = new NexgramIsland(activity);
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (UserConfig.getInstance(account).isClientActivated()) {
                NotificationCenter.getInstance(account).addObserver(instance, NotificationCenter.didReceiveNewMessages);
                instance.attachedAccounts.add(account);
            }
        }
    }

    public static void detach() {
        if (instance == null) {
            return;
        }
        for (Integer account : instance.attachedAccounts) {
            NotificationCenter.getInstance(account).removeObserver(instance, NotificationCenter.didReceiveNewMessages);
        }
        instance.attachedAccounts.clear();
        instance.cancelHide();
        instance.cancelAnimation();
        if (instance.view != null && instance.view.getParent() instanceof ViewGroup) {
            ((ViewGroup) instance.view.getParent()).removeView(instance.view);
        }
        instance.view = null;
        instance = null;
    }

    /** Re-evaluates the enabled preference (called from NEXGRAM settings). */
    public static void refresh(LaunchActivity activity) {
        attach(activity);
    }

    // --- NotificationCenterDelegate ---

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id != NotificationCenter.didReceiveNewMessages || args == null || args.length < 2) {
            return;
        }
        long dialogId = args[0] instanceof Long ? (Long) args[0] : 0;
        if (!(args[1] instanceof ArrayList)) {
            return;
        }
        ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[1];
        boolean scheduled = args.length > 2 && args[2] instanceof Boolean && (Boolean) args[2];

        MessageObject show = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            MessageObject messageObject = messages.get(i);
            if (messageObject == null || messageObject.isOut() || scheduled) {
                continue;
            }
            show = messageObject;
            break;
        }
        if (show == null) {
            return;
        }
        // Respect the chat's official notification (mute) settings.
        if (!NotificationsController.getInstance(account).isGlobalNotificationsEnabled(dialogId, false, false)) {
            return;
        }
        show(account, dialogId, show);
    }

    // --- Presentation ---

    private void show(int account, long dialogId, MessageObject messageObject) {
        Window window = activity.getWindow();
        if (window == null || !window.getDecorView().hasWindowFocus() || activity.isFinishing()) {
            // App not visibly focused -> the official system notification handles it.
            return;
        }
        if (view == null) {
            view = new IslandView(activity);
            FrameLayout decorView = (FrameLayout) window.getDecorView();
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, AndroidUtilities.dp(52));
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.topMargin = AndroidUtilities.dp(40);
            decorView.addView(view, lp);
            view.setAlpha(0f);
            view.setOnClickListener(v -> openChat(account, dialogId));
        }
        view.bind(account, messageObject);

        cancelHide();
        cancelAnimation();
        if (!visible) {
            visible = true;
            if (NexgramPrefs.islandAnimations(activity)) {
                // NEXGRAM entrance: spring overshoot + scale — noticeably juicier
                // than a plain fade and unique to NEXGRAM (original Telegram
                // has no in-app banner like this).
                view.setTranslationY(-AndroidUtilities.dp(72));
                view.setScaleX(0.88f);
                view.setScaleY(0.88f);
                animator = ValueAnimator.ofFloat(0f, 1f);
                animator.addUpdateListener(a -> {
                    float fraction = a.getAnimatedFraction();
                    view.setAlpha(Math.min(1f, fraction * 1.6f));
                    view.setTranslationY(-AndroidUtilities.dp(72) * (1f - fraction));
                });
                animator.setDuration(380);
                animator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        view.animate().scaleX(1f).scaleY(1f).setDuration(240)
                                .setInterpolator(new android.view.animation.OvershootInterpolator(1.7f))
                                .setStartDelay(0)
                                .start();
                    }
                });
                animator.start();
            } else {
                view.setAlpha(1f);
                view.setTranslationY(0f);
            }
        }
        hideRunnable = () -> hide();
        handler.postDelayed(hideRunnable, AUTO_HIDE_MS);
    }

    private void hide() {
        cancelHide();
        if (view == null) {
            visible = false;
            return;
        }
        cancelAnimation();
        if (NexgramPrefs.islandAnimations(activity)) {
            animator = ValueAnimator.ofFloat(1f, 0f);
            animator.addUpdateListener(a -> view.setAlpha(1f - a.getAnimatedFraction()));
            animator.setDuration(200);
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    removeView();
                }
            });
            animator.start();
        } else {
            removeView();
        }
    }

    private void removeView() {
        visible = false;
        if (view != null && view.getParent() instanceof ViewGroup) {
            ((ViewGroup) view.getParent()).removeView(view);
            view = null;
        }
    }

    private void cancelHide() {
        if (hideRunnable != null) {
            handler.removeCallbacks(hideRunnable);
            hideRunnable = null;
        }
    }

    private void cancelAnimation() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    private void openChat(int account, long dialogId) {
        cancelHide();
        hide();
        if (activity.isFinishing()) {
            return;
        }
        android.os.Bundle args = new android.os.Bundle();
        if (dialogId > 0) {
            args.putLong("user_id", dialogId);
        } else {
            args.putLong("chat_id", -dialogId);
        }
        activity.presentFragment(new ChatActivity(args));
    }

    // --- View ---

    private static final class IslandView extends FrameLayout {
        private final TextView avatarText;
        private final TextView nameText;
        private final TextView previewText;
        private final int accent = NexgramPrefs.getAccent(getContext());

        IslandView(android.content.Context context) {
            super(context);
            setBackground(rounded(0xEE151020));
            setClickable(true);

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(6), AndroidUtilities.dp(14), AndroidUtilities.dp(6));
            addView(row, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

            FrameLayout avatar = new FrameLayout(context);
            GradientDrawable avatarBg = new GradientDrawable();
            avatarBg.setColor(accent);
            avatarBg.setCornerRadius(AndroidUtilities.dp(18));
            avatar.setBackground(avatarBg);
            avatarText = new TextView(context);
            avatarText.setTextColor(Color.WHITE);
            avatarText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            avatarText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            avatarText.setGravity(Gravity.CENTER);
            avatarText.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            avatar.addView(avatarText, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
            row.addView(avatar, new LinearLayout.LayoutParams(AndroidUtilities.dp(36), AndroidUtilities.dp(36)));

            LinearLayout labels = new LinearLayout(context);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(AndroidUtilities.dp(10), 0, 0, 0);
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));

            nameText = new TextView(context);
            nameText.setTextColor(0xFFF4EEFF);
            nameText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            nameText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            nameText.setSingleLine(true);
            nameText.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(nameText, new LinearLayout.LayoutParams(-1, -2));

            previewText = new TextView(context);
            previewText.setTextColor(0xFFA99DBB);
            previewText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            previewText.setSingleLine(true);
            previewText.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(previewText, new LinearLayout.LayoutParams(-1, -2));
        }

        void bind(int account, MessageObject messageObject) {
            long senderId = messageObject.getSenderId();
            String name;
            if (senderId > 0) {
                TLRPC.User user = MessagesController.getInstance(account).getUser(senderId);
                name = user != null ? UserObject.getFirstName(user) : "Нове повідомлення";
            } else if (senderId < 0) {
                TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-senderId);
                name = chat != null && !TextUtils.isEmpty(chat.title) ? chat.title : "Нове повідомлення";
            } else {
                name = "Нове повідомлення";
            }
            nameText.setText(name);
            if (!TextUtils.isEmpty(name)) {
                avatarText.setText(name.substring(0, 1).toUpperCase());
            }
            previewText.setText(previewFor(messageObject));
            setContentDescription("Повідомлення від " + name + ": " + previewFor(messageObject));
        }

        private static String previewFor(MessageObject messageObject) {
            String text = messageObject.messageText != null ? messageObject.messageText.toString() : "";
            if (!TextUtils.isEmpty(text)) {
                return text.replace("\n", " ");
            }
            if (messageObject.isPhoto() || messageObject.isGif()) return "📷 Фото";
            if (messageObject.isVoice()) return "🎤 Голосове повідомлення";
            if (messageObject.isRoundVideo()) return "🎥 Відеоповідомлення";
            if (messageObject.isVideo()) return "🎞 Відео";
            if (messageObject.isMusic()) return "🎵 Аудіо";
            if (messageObject.isDocument()) return "📎 Файл";
            if (messageObject.isLocation()) return "📍 Геолокація";
            if (messageObject.isSticker()) return "🎨 Стікер";
            return "Нове повідомлення";
        }

        private static GradientDrawable rounded(int color) {
            GradientDrawable drawable = new GradientDrawable();
            drawable.setColor(color);
            drawable.setCornerRadius(AndroidUtilities.dp(26));
            return drawable;
        }
    }
}
