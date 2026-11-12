package org.telegram.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.nexgram.NexgramIsland;
import org.telegram.nexgram.NexgramPrefs;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

/** NEXGRAM Control Center — all preferences are real and locally persisted. */
public class NEXGRAMSettingsActivity extends BaseFragment {
    private static final int BG = Color.rgb(9, 8, 18);
    private static final int SURFACE = Color.rgb(21, 16, 32);
    private static final int TEXT = Color.rgb(244, 238, 255);
    private static final int MUTED = Color.rgb(169, 157, 187);

    private static final int[] ACCENTS = new int[] {
            Color.rgb(139, 44, 255), Color.rgb(176, 76, 255),
            Color.rgb(77, 111, 255), Color.rgb(0, 190, 170),
            Color.rgb(255, 92, 141), Color.rgb(255, 194, 75)
    };

    @Override
    public View createView(Context context) {
        NEXGRAMTheme.apply(context);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("NEXGRAM");
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

        TextView brand = text(context, "NEXGRAM CONTROL CENTER", 12, MUTED, true);
        brand.setLetterSpacing(0.12f);
        content.addView(brand, margin(0, 0, 0, 8));
        content.addView(text(context, "Твій Telegram. Твій стиль.", 24, TEXT, true), margin(0, 0, 0, 8));
        content.addView(text(context, "Усі налаштування зберігаються на пристрої та не передаються стороннім сервісам.", 14, MUTED, false), margin(0, 0, 0, 22));

        // --- Appearance ---
        addSection(content, context, "ОФОРМЛЕННЯ");
        LinearLayout accentCard = card(context);
        content.addView(accentCard, margin(0, 0, 0, 12));
        accentCard.addView(text(context, "Колір акценту", 16, TEXT, true), margin(0, 0, 0, 12));
        LinearLayout swatches = new LinearLayout(context);
        swatches.setOrientation(LinearLayout.HORIZONTAL);
        accentCard.addView(swatches, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(44)));
        int selected = NexgramPrefs.getAccent(context);
        for (int i = 0; i < ACCENTS.length; i++) {
            final int color = ACCENTS[i];
            View swatch = new View(context);
            swatch.setBackground(swatchShape(context, color, color == selected));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(AndroidUtilities.dp(44), AndroidUtilities.dp(44));
            lp.rightMargin = AndroidUtilities.dp(10);
            swatches.addView(swatch, lp);
            swatch.setContentDescription("Вибрати колір акценту");
            swatch.setOnClickListener(v -> {
                NexgramPrefs.setAccent(context, color);
                NEXGRAMTheme.apply(context);
                for (int j = 0; j < swatches.getChildCount(); j++) {
                    swatches.getChildAt(j).setBackground(swatchShape(context, ACCENTS[j], ACCENTS[j] == color));
                }
            });
        }

        // --- NEXGRAM Island ---
        addSection(content, context, "СПОВІЩЕННЯ");
        addSwitch(content, context, "Острівець повідомлень",
                "Показує банер у застосунку, коли телефон відкритий. Системні сповіщення Telegram не дублюються й не вимикаються.",
                NexgramPrefs.KEY_ISLAND_ENABLED, true, (checked) -> {
                    NexgramPrefs.setIslandEnabled(context, checked);
                    if (getParentActivity() instanceof org.telegram.ui.LaunchActivity) {
                        NexgramIsland.refresh((org.telegram.ui.LaunchActivity) getParentActivity());
                    }
                });
        addSwitch(content, context, "Анімації острівця",
                "Плавне появляння/зникнення банера. Вимикається разом із режимом зменшення анімацій.",
                NexgramPrefs.KEY_ISLAND_ANIMATIONS, true, (checked) ->
                        NexgramPrefs.setIslandAnimations(context, checked));

        // --- Motion ---
        addSection(content, context, "ЗРУЧНІСТЬ");
        addSwitch(content, context, "Зменшити анімації NEXGRAM",
                "Впливає на власні анімації NEXGRAM (острівець). Анімації Telegram залишаються недоторканими.",
                NexgramPrefs.KEY_REDUCE_MOTION, false, (checked) ->
                        NexgramPrefs.setReduceMotion(context, checked));

        // --- Security ---
        addSection(content, context, "БЕЗПЕКА");
        LinearLayout guardCard = entryCard(context, "Account Guard",
                "Активні сесії, двоетапна перевірка, журнал нових сесій. Офіційні дані Telegram.",
                (v) -> presentFragment(new AccountGuardActivity()));
        content.addView(guardCard, margin(0, 0, 0, 12));

        // --- Intelligence ---
        addSection(content, context, "РОЗУМНИЙ ПОШУК");
        LinearLayout intelCard = entryCard(context, "Identity Finder",
                "Публічні профілі, username, бізнес-сторінки. Кожен результат — з джерелом і рівнем достовірності.",
                (v) -> presentFragment(new IdentityFinderActivity()));
        content.addView(intelCard, margin(0, 0, 0, 12));

        // --- Nearby ---
        addSection(content, context, "ПРИСТРОЇ ПОБЛИЗУ");
        LinearLayout nearbyCard = entryCard(context, "Nearby",
                "Передача файлів між телефонами поруч: Wi-Fi Direct + BLE-маячок, шифрування ECDH/AES-GCM, згода отримувача обов'язкова.",
                (v) -> presentFragment(new NearbyActivity()));
        content.addView(nearbyCard, margin(0, 0, 0, 12));

        // --- About ---
        addSection(content, context, "ПРО NEXGRAM");
        LinearLayout about = card(context);
        content.addView(about, margin(0, 0, 0, 12));
        about.addView(text(context, "NEXGRAM 2.1.0", 16, TEXT, true), margin(0, 0, 0, 6));
        about.addView(text(context, "Модифікація Telegram for Android (GPL-2.0). Мережевий стек, авторизація, чати, медіа та дзвінки — оригінальні компоненти Telegram.", 12, MUTED, false), margin(0, 0, 0, 6));
        about.addView(text(context, "Собствені API-ключі: BuildVars.java (локально).", 12, MUTED, false), margin(0, 0, 0, 6));
        TextView policy = button(context, "Політика модуля Identity Finder", SURFACE);
        about.addView(policy, margin(0, 8, 0, 0));
        policy.setOnClickListener(v -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setTitle("Правила Identity Finder");
            builder.setMessage("Дозволено: публічні вебсторінки, публічні профілі, публічні username, бізнес-контакти, згадки, посилання.\n\nЗаборонено: приховані номери, приватні адреси, геолокація, приватні переписки, злиті бази, обходи захисту, профілювання людей за неперевіреними збігами.");
            builder.setPositiveButton("Зрозуміло", null);
            builder.show();
        });

        content.addView(text(context, "NEXGRAM 2.1.0 • на основі Telegram for Android", 12, MUTED, false), margin(0, 18, 0, 0));

        fragmentView = root;
        return fragmentView;
    }

    private GradientDrawable swatchShape(Context context, int color, boolean selected) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(AndroidUtilities.dp(14));
        if (selected) {
            shape.setStroke(AndroidUtilities.dp(3), TEXT);
        }
        return shape;
    }

    private interface SwitchListener {
        void onChecked(boolean checked);
    }

    private void addSwitch(LinearLayout parent, Context context, String title, String subtitle, String key, boolean defaultValue, SwitchListener listener) {
        LinearLayout row = card(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(row, margin(0, 0, 0, 10));
        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
        labels.addView(text(context, title, 15, TEXT, true));
        labels.addView(text(context, subtitle, 12, MUTED, false), margin(0, 4, 10, 0));
        Switch toggle = new Switch(context);
        toggle.setChecked(NexgramPrefs.get(context).getBoolean(key, defaultValue));
        row.addView(toggle, new LinearLayout.LayoutParams(-2, -2));
        toggle.setOnCheckedChangeListener((CompoundButton button, boolean checked) -> listener.onChecked(checked));
    }

    private LinearLayout entryCard(Context context, String title, String subtitle, View.OnClickListener listener) {
        LinearLayout card = card(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setClickable(true);
        card.setOnClickListener(listener);
        card.setContentDescription(title);
        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        card.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
        labels.addView(text(context, title, 16, TEXT, true));
        labels.addView(text(context, subtitle, 12, MUTED, false), margin(0, 4, 10, 0));
        TextView arrow = text(context, "›", 22, MUTED, true);
        card.addView(arrow, new LinearLayout.LayoutParams(-2, -2));
        return card;
    }

    private void addSection(LinearLayout parent, Context context, String title) {
        parent.addView(text(context, title, 11, MUTED, true), margin(2, 12, 0, 10));
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
}
