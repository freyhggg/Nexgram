package org.telegram.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.nexgram.NexgramAnim;
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

    private ValueAnimator breathingAnimator;
    private final java.util.List<TextView> iconBadges = new java.util.ArrayList<>();

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
        // Дихаючий фон — анімація, якої немає в оригінальному Telegram.
        breathingAnimator = NexgramAnim.breathingBackground(root, BG, Color.rgb(19, 15, 30));
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
        content.addView(text(context, "Акцент діє у темі NEXGRAM", 11, MUTED, false), margin(2, 0, 0, 0));

        // --- Theme mode: NEXGRAM / Discord ---
        LinearLayout themeCard = card(context);
        content.addView(themeCard, margin(0, 0, 0, 12));
        themeCard.addView(text(context, "Тема оформлення", 16, TEXT, true), margin(0, 0, 0, 6));
        themeCard.addView(text(context, "Discord — темно-сірий із blurple-акцентом. NEXGRAM — фіолетовий неон.", 12, MUTED, false), margin(0, 0, 0, 12));
        LinearLayout themeRow = new LinearLayout(context);
        themeRow.setOrientation(LinearLayout.HORIZONTAL);
        themeCard.addView(themeRow, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(44)));
        View nexChip = themeChip(context, "NEXGRAM", NEXGRAMTheme.getThemeMode(context) == NEXGRAMTheme.THEME_NEXGRAM);
        View dcChip = themeChip(context, "Discord", NEXGRAMTheme.getThemeMode(context) == NEXGRAMTheme.THEME_DISCORD);
        LinearLayout.LayoutParams nexParams = new LinearLayout.LayoutParams(0, AndroidUtilities.dp(44), 1f);
        nexParams.rightMargin = AndroidUtilities.dp(10);
        themeRow.addView(nexChip, nexParams);
        LinearLayout.LayoutParams dcParams = new LinearLayout.LayoutParams(0, AndroidUtilities.dp(44), 1f);
        dcParams.leftMargin = AndroidUtilities.dp(10);
        themeRow.addView(dcChip, dcParams);
        nexChip.setOnClickListener(v -> switchTheme(context, NEXGRAMTheme.THEME_NEXGRAM, nexChip, dcChip));
        dcChip.setOnClickListener(v -> switchTheme(context, NEXGRAMTheme.THEME_DISCORD, nexChip, dcChip));

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
        addSwitch(content, context, "Експериментальні анімації",
                "Каскадна поява екранів, дихаючий фон, пружне натискання на картки — рух, якого немає в оригінальному Telegram. Вимикається разом із «Зменшити анімації».",
                NexgramPrefs.KEY_ANIM_EXPERIMENTAL, true, (checked) -> {
                    NexgramPrefs.setAnimExperiments(context, checked);
                    if (getParentActivity() != null) {
                        getParentActivity().recreate();
                    }
                });

        // --- Personalization (icon = official Telegram premium icons, unlocked) ---
        addSection(content, context, "ПЕРСОНАЛІЗАЦІЯ");
        LinearLayout iconCard = card(context);
        content.addView(iconCard, margin(0, 0, 0, 12));
        iconCard.addView(text(context, "Значок застосунку", 16, TEXT, true), margin(0, 0, 0, 4));
        iconCard.addView(text(context,
                "Офіційні альтернативні значки Telegram. У NEXGRAM усі вільні — включно з тими, що в оригіналі лише для Premium. Зміна застосовується за кілька секунд.",
                12, MUTED, false), margin(0, 0, 0, 10));
        LinearLayout iconsRow = new LinearLayout(context);
        iconsRow.setOrientation(LinearLayout.HORIZONTAL);
        iconCard.addView(iconsRow, new LinearLayout.LayoutParams(-1, -2));
        for (LauncherIconController.LauncherIcon icon : LauncherIconController.LauncherIcon.values()) {
            iconsRow.addView(iconItem(context, icon));
        }
        updateIconSelection();

        // --- Security ---
        addSection(content, context, "БЕЗПЕКА");
        LinearLayout guardCard = entryCard(context, "Account Guard",
                "Активні сесії, двоетапна перевірка, журнал нових сесій. Офіційні дані Telegram.",
                (v) -> presentFragment(new AccountGuardActivity()));
        content.addView(guardCard, margin(0, 0, 0, 12));

        // --- App lock (офіційне код-блокування Telegram) ---
        LinearLayout lockCard = entryCard(context, "Код-блокування",
                SharedConfig.passcodeHash.isEmpty()
                        ? "PIN, пароль або відбиток при вході в застосунок. Офіційний механізм Telegram."
                        : "Увімкнено. Вхід у застосунок захищено; керування — після введення коду.",
                (v) -> presentFragment(PasscodeActivity.determineOpenFragment()));
        content.addView(lockCard, margin(0, 0, 0, 12));

        // --- Telegram test environment (тестовий сервер) ---
        final boolean onTestBackend = getConnectionsManager().isTestBackend();
        LinearLayout testCard = card(context);
        content.addView(testCard, margin(0, 0, 0, 12));
        LinearLayout testStatus = new LinearLayout(context);
        testStatus.setOrientation(LinearLayout.HORIZONTAL);
        testStatus.setGravity(Gravity.CENTER_VERTICAL);
        testStatus.addView(text(context, "●", 14, onTestBackend ? 0xFFFF9800 : 0xFF42D392, true), margin(2, 0, 8, 0));
        testStatus.addView(text(context, onTestBackend ? "ПІДКЛЮЧЕНО ДО ТЕСТОВОГО СЕРВЕРА" : "Підключено до основного сервера",
                14, onTestBackend ? 0xFFFF9800 : TEXT, true));
        testCard.addView(testStatus, margin(0, 0, 0, 6));
        testCard.addView(text(context,
                onTestBackend
                        ? "Тестове середовище Telegram: окрема база, окремі акаунти й чати. Основний акаунт звідси недоступний — це ізольований світ. Для повернення натисни кнопку нижче й увійди на основному сервері."
                        : "Тестовий сервер Telegram — ізольоване середовище для тестів: окрема база даних, окремі акаунти, нічого не потрапляє в основний акаунт. Основна сесія на серверах Telegram не постраждає, але локальна сесія цього середовища буде вилучена й знадобиться повторний вхід.",
                12, MUTED, false), margin(0, 0, 0, 12));
        TextView testButton = button(context, onTestBackend ? "Повернутись на основний сервер" : "Перейти на тестовий сервер", SURFACE);
        testCard.addView(testButton, margin(0, 4, 0, 0));
        testButton.setOnClickListener(v -> confirmBackendSwitch(context, !onTestBackend));

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
        LinearLayout nfcCard = entryCard(context, "NFC-картка",
                "Обмін контактами через NFC: твій телефон — картка для інших, згода з обох боків, збереження в контакти системним редактором.",
                (v) -> presentFragment(new NfcContactActivity()));
        content.addView(nfcCard, margin(0, 0, 0, 12));

        // --- About ---
        addSection(content, context, "ПРО NEXGRAM");
        LinearLayout about = card(context);
        content.addView(about, margin(0, 0, 0, 12));
        about.addView(text(context, "NEXGRAM 2.2.0", 16, TEXT, true), margin(0, 0, 0, 6));
        about.addView(text(context, "Модифікація Telegram for Android (GPL-2.0). Мережевий стек, авторизація, чати, медіа та дзвінки — оригінальні компоненти Telegram.", 12, MUTED, false), margin(0, 0, 0, 6));
        about.addView(text(context, "Власні API-ключі — зашифровані секрети CI, у коді їх немає.", 12, MUTED, false), margin(0, 0, 0, 6));
        about.addView(text(context, "P-можливості вільні: значки Premium/Turbo/Nox, експериментальні анімації, NFC-обмін, Identity Finder.", 12, MUTED, false), margin(0, 0, 0, 6));
        TextView policy = button(context, "Політика модуля Identity Finder", SURFACE);
        about.addView(policy, margin(0, 8, 0, 0));
        policy.setOnClickListener(v -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setTitle("Правила Identity Finder");
            builder.setMessage("Дозволено: публічні вебсторінки, публічні профілі, публічні username, бізнес-контакти, згадки, посилання.\n\nЗаборонено: приховані номери, приватні адреси, геолокація, приватні переписки, злиті бази, обходи захисту, профілювання людей за неперевіреними збігами.");
            builder.setPositiveButton("Зрозуміло", null);
            builder.show();
        });

        content.addView(text(context, "NEXGRAM 2.2.0 • на основі Telegram for Android", 12, MUTED, false), margin(0, 18, 0, 0));

        // NEXGRAM motion: springy press feedback + cascade entrance.
        NexgramAnim.pressScale(guardCard);
        NexgramAnim.pressScale(lockCard);
        NexgramAnim.pressScale(testButton);
        NexgramAnim.pressScale(intelCard);
        NexgramAnim.pressScale(nearbyCard);
        NexgramAnim.pressScale(nfcCard);
        NexgramAnim.pressScale(policy);
        NexgramAnim.cascade(content);

        fragmentView = root;
        return fragmentView;
    }

    @Override
    public void onFragmentDestroy() {
        if (breathingAnimator != null) {
            breathingAnimator.cancel();
            breathingAnimator = null;
        }
        super.onFragmentDestroy();
    }

    /**
     * One launcher-icon picker item: layered preview (official SA background +
     * foreground drawables), localized label and a check badge for the active icon.
     */
    private View iconItem(Context context, LauncherIconController.LauncherIcon icon) {
        LinearLayout item = new LinearLayout(context);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setClickable(true);
        item.setContentDescription(LocaleController.getString(icon.title));

        FrameLayout preview = new FrameLayout(context);
        try {
            preview.setBackground(context.getDrawable(icon.background));
        } catch (Exception ignored) {
        }
        ImageView foreground = new ImageView(context);
        try {
            foreground.setImageResource(icon.foreground);
        } catch (Exception ignored) {
        }
        preview.addView(foreground, new FrameLayout.LayoutParams(AndroidUtilities.dp(32), AndroidUtilities.dp(32), Gravity.CENTER));

        TextView badge = text(context, "✓", 12, 0xFF42D392, true);
        badge.setGravity(Gravity.CENTER);
        badge.setVisibility(View.GONE);
        preview.addView(badge, new FrameLayout.LayoutParams(AndroidUtilities.dp(18), AndroidUtilities.dp(18), Gravity.TOP | Gravity.END));
        iconBadges.add(badge);

        item.addView(preview, new LinearLayout.LayoutParams(AndroidUtilities.dp(52), AndroidUtilities.dp(52)));
        item.addView(text(context, LocaleController.getString(icon.title), 10, MUTED, false), margin(0, 4, 0, 0));
        item.setOnClickListener(v -> {
            LauncherIconController.setIcon(icon);
            updateIconSelection();
            NexgramAnim.pop(preview);
            android.widget.Toast.makeText(context, "Значок змінено: " + LocaleController.getString(icon.title), android.widget.Toast.LENGTH_SHORT).show();
        });
        NexgramAnim.pressScale(item);
        return item;
    }

    private void updateIconSelection() {
        for (int i = 0; i < iconBadges.size(); i++) {
            LauncherIconController.LauncherIcon icon = LauncherIconController.LauncherIcon.values()[i];
            iconBadges.get(i).setVisibility(LauncherIconController.isEnabled(icon) ? View.VISIBLE : View.GONE);
        }
    }

    private View themeChip(Context context, String label, boolean selected) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setTextSize(14);
        view.setTextColor(selected ? 0xFFFFFFFF : TEXT);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        view.setBackground(chipShape(selected));
        view.setContentDescription("Тема " + label);
        return view;
    }

    private GradientDrawable chipShape(boolean selected) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(selected ? 0xFF5865F2 : SURFACE);
        shape.setCornerRadius(AndroidUtilities.dp(14));
        return shape;
    }

    private void switchTheme(Context context, int mode, View nexChip, View dcChip) {
        if (NEXGRAMTheme.getThemeMode(context) == mode) {
            return;
        }
        NEXGRAMTheme.setThemeMode(context, mode);
        nexChip.setBackground(chipShape(mode == NEXGRAMTheme.THEME_NEXGRAM));
        dcChip.setBackground(chipShape(mode == NEXGRAMTheme.THEME_DISCORD));
        NexgramAnim.pop(nexChip);
        NexgramAnim.pop(dcChip);
        android.widget.Toast.makeText(context, "Тему застосовано", android.widget.Toast.LENGTH_SHORT).show();
        if (getParentActivity() != null) {
            getParentActivity().recreate();
        }
    }

    private void confirmBackendSwitch(Context context, final boolean toTest) {
        Context parent = getParentActivity() != null ? getParentActivity() : context;
        AlertDialog.Builder builder = new AlertDialog.Builder(parent);
        builder.setTitle(toTest ? "Перейти на тестовий сервер?" : "Повернутись на основний?");
        builder.setMessage(toTest
                ? "Тестовий сервер — окремий світ Telegram: інші акаунти, інші чати, інші обмеження.\n\n"
                  + "• Основний акаунт на серверах Telegram залишиться недоторканим\n"
                  + "• Локальна сесія основного сервера буде вилучена з цього пристрою\n"
                  + "• Після перемикання знадобиться повторний вхід (перемикач «Test backend» на екрані входу буде вже активний)\n\n"
                  + "Продовжити?"
                : "Застосунок повернеться на основний сервер Telegram.\n\n"
                  + "• Локальна сесія тестового сервера буде вилучена\n"
                  + "• Потрібен повторний вхід основним акаунтом\n\n"
                  + "Продовжити?");
        builder.setNegativeButton("Скасувати", null);
        builder.setPositiveButton("Далі", (dialog, which) -> {
            AlertDialog.Builder confirm = new AlertDialog.Builder(parent);
            confirm.setTitle("Підтвердження");
            confirm.setMessage(toTest
                    ? "Застосунок перезапуститься і підключиться до тестових датацентрів Telegram. Сесію цього середовища буде вилучено. Точно перемкнути?"
                    : "Застосунок перезапуститься і підключиться до основних датацентрів Telegram. Сесію тестового сервера буде вилучено. Точно перемкнути?");
            confirm.setNegativeButton("Скасувати", null);
            confirm.setPositiveButton("Так, перемкнути", (d2, w2) -> performBackendSwitch());
            confirm.show();
        });
        builder.show();
    }

    private void performBackendSwitch() {
        // Офіційний патерн Telegram (ProfileActivity → switchBackendRow):
        // скидання push-ключів, потім нативне перемикання прапорця тестового середовища.
        // Нативно: testBackend = !testBackend; datacenters.clear(); initDatacenters();
        // saveConfig(); exit(1) — процес перезапускається у новому середовищі.
        SharedConfig.pushAuthKey = null;
        SharedConfig.pushAuthKeyId = null;
        SharedConfig.saveConfig();
        getConnectionsManager().switchBackend(true);
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
