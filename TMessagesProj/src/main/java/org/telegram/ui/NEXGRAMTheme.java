package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.ui.ActionBar.Theme;

/** Applies NEXGRAM's locally selected accent to Telegram's existing theme engine. */
public final class NEXGRAMTheme {
    private static final String PREFS = "nexgram_settings";
    private static final String KEY_ACCENT = "accent_color";

    private NEXGRAMTheme() {
    }

    public static int getAccent(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_ACCENT, 0xFF8B2CFF);
    }

    public static void apply(Context context) {
        int accent = getAccent(context);
        // NEXGRAM dark palette: apply through Telegram's existing theme engine.
        // This keeps chat rendering, adapters and message cells on their native code paths.
        Theme.setColor(Theme.key_windowBackgroundWhite, 0xFF090812, false);
        Theme.setColor(Theme.key_windowBackgroundGray, 0xFF090812, false);
        Theme.setColor(Theme.key_windowBackgroundWhiteBlackText, 0xFFF4EEFF, false);
        Theme.setColor(Theme.key_windowBackgroundWhiteGrayText, 0xFFA99DBB, false);
        Theme.setColor(Theme.key_windowBackgroundWhiteGrayText2, 0xFFA99DBB, false);
        Theme.setColor(Theme.key_actionBarDefault, 0xFF090812, false);
        Theme.setColor(Theme.key_actionBarDefaultIcon, accent, false);
        Theme.setColor(Theme.key_actionBarDefaultTitle, 0xFFF4EEFF, false);
        Theme.setColor(Theme.key_chats_name, 0xFFF4EEFF, false);
        Theme.setColor(Theme.key_chats_nameArchived, 0xFFF4EEFF, false);
        Theme.setColor(Theme.key_chats_message, 0xFFA99DBB, false);
        Theme.setColor(Theme.key_chats_messageArchived, 0xFFA99DBB, false);
        Theme.setColor(Theme.key_chats_date, 0xFFA99DBB, false);
        Theme.setColor(Theme.key_chats_date_bold, accent, false);
        Theme.setColor(Theme.key_chats_onlineCircle, 0xFF42D392, false);
        Theme.setColor(Theme.key_chats_unreadCounter, accent, false);
        Theme.setColor(Theme.key_chats_unreadCounterText, 0xFFFFFFFF, false);
        Theme.setColor(Theme.key_chats_verifiedBackground, accent, false);
        // Chat surfaces and message typography. These are Telegram Theme keys,
        // so the original message cells, media bubbles and input panel remain in use.
        Theme.setColor(Theme.key_chat_inBubble, 0xFF1B1628, false);
        Theme.setColor(Theme.key_chat_messageTextIn, 0xFFF4EEFF, false);
        Theme.setColor(Theme.key_chat_messageTextOut, 0xFFFFFFFF, false);
        Theme.setColor(Theme.key_chat_messagePanelBackground, 0xFF151020, false);
        Theme.setColor(Theme.key_chat_messagePanelText, 0xFFF4EEFF, false);
        Theme.setColor(Theme.key_chat_outBubble, 0xFF4A168A, false);
        Theme.setColor(Theme.key_chat_outBubbleGradient1, accent, false);
        Theme.setColor(Theme.key_chat_outBubbleSelected, blend(0xFF4A168A, 0xFFFFFFFF, 0.16f), false);
    }

    public static void saveAccent(Context context, int accent) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_ACCENT, accent).apply();
        apply(context);
    }

    private static int blend(int from, int to, float amount) {
        int a = Math.round(((from >>> 24) & 0xFF) * (1f - amount) + ((to >>> 24) & 0xFF) * amount);
        int r = Math.round(((from >>> 16) & 0xFF) * (1f - amount) + ((to >>> 16) & 0xFF) * amount);
        int g = Math.round(((from >>> 8) & 0xFF) * (1f - amount) + ((to >>> 8) & 0xFF) * amount);
        int b = Math.round((from & 0xFF) * (1f - amount) + (to & 0xFF) * amount);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
