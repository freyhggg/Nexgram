package org.telegram.ui;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.wifi.p2p.WifiP2pDevice;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.messenger.TelegramQRCodeWriter;
import org.telegram.messenger.UserConfig;
import org.telegram.nexgram.nearby.NearbyBle;
import org.telegram.nexgram.nearby.NearbyLink;
import org.telegram.nexgram.nearby.NearbyStore;
import org.telegram.nexgram.nearby.NearbyWifiDirect;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * NEXGRAM Nearby — передача файлів між пристроями поблизу.
 * Wi-Fi Direct + BLE-маячок, ECDH P-256 → AES-256-GCM, явна згода отримувача,
 * перевірка цілісності SHA-256, журнал передач. Без автододавання контактів.
 */
public class NearbyActivity extends BaseFragment {

    private static final int REQ_NEARBY_PERMS = 4181;
    private static final int REQ_PICK_FILE = 4182;

    private static final int BG = Color.rgb(9, 8, 18);
    private static final int SURFACE = Color.rgb(21, 16, 32);
    private static final int TEXT = Color.rgb(244, 238, 255);
    private static final int MUTED = Color.rgb(169, 157, 187);

    private NearbyWifiDirect wifiDirect;
    private NearbyBle ble;

    private TextView statusText;
    private LinearLayout devicesBox;
    private LinearLayout journalBox;
    private View sendButton;
    private View disconnectButton;

    private boolean discovering;
    private boolean pendingDiscovery;
    private boolean connected;
    private String sendHost;
    private String bleStatus = "";
    private String pendingConnectName;

    private final Map<String, WifiP2pDevice> peersByAddress = new HashMap<>();
    private final Map<String, NearbyBle.BleDevice> bleByName = new HashMap<>();

    private AlertDialog progressDialog;
    private ProgressBar progressBar;
    private TextView progressText;

    @Override
    public boolean onFragmentCreate() {
        if (!super.onFragmentCreate()) {
            return false;
        }
        wifiDirect = new NearbyWifiDirect(ApplicationLoader.applicationContext, wifiCallbacks);
        ble = new NearbyBle(bleCallbacks);
        NearbyLink link = NearbyLink.get();
        link.setListener(linkListener);
        link.startServer(label());
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        if (wifiDirect != null) {
            wifiDirect.unregister();
        }
        if (ble != null) {
            ble.stopAll();
        }
        NearbyLink link = NearbyLink.get();
        link.stopServer();
        link.setListener(null);
        discovering = false;
    }

    @Override
    public View createView(Context context) {
        NEXGRAMTheme.apply(context);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("NEXGRAM Nearby");
        actionBar.setAllowOverlayTitle(false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(20), AndroidUtilities.dp(18), AndroidUtilities.dp(28));

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));

        content.addView(text(context, "ПРИСТРОЇ ПОБЛИЗУ", 12, MUTED, true), margin(0, 0, 0, 6));
        content.addView(text(context, "Передача файлів без інтернету", 24, TEXT, true), margin(0, 0, 0, 8));

        LinearLayout statusCard = card(context);
        content.addView(statusCard, margin(0, 0, 0, 12));
        statusText = text(context, "Натисни «Пошук пристроїв», щоб знайти NEXGRAM поруч.", 14, TEXT, false);
        statusCard.addView(statusText, margin(0, 0, 0, 0));

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        content.addView(actions, margin(0, 0, 0, 12));
        LinearLayout.LayoutParams ap1 = new LinearLayout.LayoutParams(0, -2, 1f);
        ap1.rightMargin = AndroidUtilities.dp(8);
        TextView searchBtn = button(context, "Пошук пристроїв");
        actions.addView(searchBtn, ap1);
        searchBtn.setOnClickListener(v -> startDiscoveryPressed());
        LinearLayout.LayoutParams ap2 = new LinearLayout.LayoutParams(0, -2, 1f);
        ap2.leftMargin = AndroidUtilities.dp(8);
        TextView qrBtn = button(context, "Мій QR-контакт");
        actions.addView(qrBtn, ap2);
        qrBtn.setOnClickListener(v -> showMyQr());

        LinearLayout actions2 = new LinearLayout(context);
        actions2.setOrientation(LinearLayout.HORIZONTAL);
        content.addView(actions2, margin(0, 0, 0, 12));
        LinearLayout.LayoutParams ap3 = new LinearLayout.LayoutParams(0, -2, 1f);
        ap3.rightMargin = AndroidUtilities.dp(8);
        TextView scanBtn = button(context, "Сканувати контакт");
        actions2.addView(scanBtn, ap3);
        scanBtn.setOnClickListener(v -> scanContact());
        LinearLayout.LayoutParams ap4 = new LinearLayout.LayoutParams(0, -2, 1f);
        ap4.leftMargin = AndroidUtilities.dp(8);
        sendButton = button(context, "Надіслати файл");
        actions2.addView(sendButton, ap4);
        sendButton.setOnClickListener(v -> pickFile());
        sendButton.setVisibility(View.GONE);

        disconnectButton = button(context, "Розірвати з'єднання");
        disconnectButton.setVisibility(View.GONE);
        content.addView(disconnectButton, margin(0, 0, 0, 12));
        disconnectButton.setOnClickListener(v -> {
            if (wifiDirect != null) {
                wifiDirect.removeGroup();
            }
            connected = false;
            sendHost = null;
            updateStatus();
        });

        content.addView(text(context, "ПРИСТРОЇ", 11, MUTED, true), margin(2, 6, 0, 10));
        devicesBox = new LinearLayout(context);
        devicesBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(devicesBox, margin(0, 0, 0, 12));

        content.addView(text(context, "ЖУРНАЛ ПЕРЕДАЧ", 11, MUTED, true), margin(2, 6, 0, 10));
        journalBox = new LinearLayout(context);
        journalBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(journalBox, margin(0, 0, 0, 12));

        LinearLayout info = card(context);
        content.addView(info, margin(0, 0, 0, 0));
        info.addView(text(context, "Як це працює", 15, TEXT, true), margin(0, 0, 0, 6));
        info.addView(text(context, "Wi-Fi Direct створює прямий канал між телефонами, BLE-маячок допомагає його знайти. Передача шифрується (ECDH P-256 → AES-256-GCM), отримувач завжди підтверджує прийом, цілісність перевіряє SHA-256. Контакти не додаються автоматично. Обидва телефони тримають цей екран відкритим. Файли зберігаються у Downloads.", 12, MUTED, false), margin(0, 0, 0, 0));

        rebuildDevices();
        rebuildJournal();
        updateStatus();

        fragmentView = root;
        return fragmentView;
    }

    // ================================ дії користувача ================================

    private void startDiscoveryPressed() {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            for (String p : new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT}) {
                if (activity.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                    missing.add(p);
                }
            }
        } else {
            if (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }
        if (!missing.isEmpty()) {
            pendingDiscovery = true;
            activity.requestPermissions(missing.toArray(new String[0]), REQ_NEARBY_PERMS);
            return;
        }
        startDiscovery();
    }

    private void startDiscovery() {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        discovering = true;
        String label = label();
        NearbyLink.get().startServer(label);
        wifiDirect.register();
        wifiDirect.discoverPeers();
        BluetoothManager mgr = (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
        if (mgr != null && mgr.getAdapter() != null) {
            try {
                mgr.getAdapter().setName("NEXGRAM " + label);
            } catch (Exception ignore) {
            }
        }
        String mac = wifiDirect.getThisDeviceAddress();
        ble.startAdvertising(activity, mac != null ? macBytes(mac) : null);
        ble.startScan(activity);
        // MAC-адреса власного P2P-інтерфейсу може прийти трохи пізніше — повторюємо маячок
        AndroidUtilities.runOnUIThread(() -> {
            if (discovering) {
                String mac2 = wifiDirect.getThisDeviceAddress();
                if (mac2 != null) {
                    ble.startAdvertising(activity, macBytes(mac2));
                }
            }
        }, 3000);
        updateStatus();
    }

    private void pickFile() {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        if (!connected || TextUtils.isEmpty(sendHost)) {
            toast("Спершу з'єднайся з пристроєм");
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            activity.startActivityForResult(intent, REQ_PICK_FILE);
        } catch (Exception e) {
            toast("Не вдалося відкрити вибір файлу");
        }
    }

    private void sendChosen(Uri uri) {
        if (!connected || TextUtils.isEmpty(sendHost)) {
            toast("З'єднання втрачено — з'єднайся знову");
            return;
        }
        showProgress(false);
        NearbyLink.get().sendFile(uri, sendHost);
    }

    private void onDeviceTap(String name, WifiP2pDevice p2p) {
        if (p2p != null && p2p.deviceAddress != null) {
            wifiDirect.connect(p2p.deviceAddress);
            toast("З'єднуюся з " + name + "…");
            return;
        }
        pendingConnectName = name;
        wifiDirect.register();
        wifiDirect.discoverPeers();
        toast("Шукаю " + name + " через Wi-Fi Direct…");
        AndroidUtilities.runOnUIThread(() -> {
            if (pendingConnectName != null) {
                pendingConnectName = null;
                toast("Не знайдено через Wi-Fi Direct. Переконайся, що на іншому телефоні відкритий екран Nearby і натиснуто «Пошук», потім спробуй ще раз.");
            }
        }, 25000);
    }

    private void showMyQr() {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 0);
            Bitmap bmp = new TelegramQRCodeWriter().encode(contactPayload(label()), AndroidUtilities.dp(340), AndroidUtilities.dp(340), hints, null, 0f, 0xFFFFFFFF, 0xFF090812);
            ImageView iv = new ImageView(activity);
            iv.setImageBitmap(bmp);
            int pad = AndroidUtilities.dp(24);
            iv.setPadding(pad, pad, pad, pad);
            iv.setBackgroundColor(0xFFFFFFFF);
            AlertDialog.Builder b = new AlertDialog.Builder(activity);
            b.setTitle("Мій контакт Nearby");
            b.setView(iv);
            b.setPositiveButton("Готово", null);
            b.show();
        } catch (Exception e) {
            toast("Не вдалося створити QR: " + e.getMessage());
        }
    }

    private void scanContact() {
        CameraScanActivity.showAsSheet(this, false, CameraScanActivity.TYPE_QR, new CameraScanActivity.CameraScanActivityDelegate() {
            @Override
            public void didFindQr(String text) {
                handleScannedContact(text);
            }
        });
    }

    private void handleScannedContact(String payload) {
        if (payload == null || !payload.startsWith("nexgram:contact")) {
            toast("Це не контакт Nearby");
            return;
        }
        try {
            Uri u = Uri.parse(payload);
            String n = u.getQueryParameter("n");
            String un = u.getQueryParameter("u");
            if (TextUtils.isEmpty(n)) {
                n = "NEXGRAM-пристрій";
            }
            NearbyBle.BleDevice d = new NearbyBle.BleDevice();
            d.name = n;
            bleByName.put(n, d);
            rebuildDevices();
            toast("Контакт додано: " + n + (TextUtils.isEmpty(un) ? "" : " (@" + un + ")") + ". Натисни на нього для з'єднання.");
        } catch (Exception e) {
            toast("Не вдалося прочитати контакт");
        }
    }

    // ================================ колбеки модулів ================================

    private final NearbyWifiDirect.Callbacks wifiCallbacks = new NearbyWifiDirect.Callbacks() {
        @Override
        public void onP2pState(boolean enabled) {
            updateStatus();
            if (!enabled) {
                toast("Wi-Fi Direct вимкнено — увімкни Wi-Fi");
            }
        }

        @Override
        public void onPeers(List<WifiP2pDevice> peers) {
            peersByAddress.clear();
            for (WifiP2pDevice d : peers) {
                if (d != null && d.deviceAddress != null) {
                    peersByAddress.put(d.deviceAddress, d);
                }
            }
            matchPending();
            rebuildDevices();
        }

        @Override
        public void onConnection(boolean groupFormed, boolean isOwner, String host) {
            if (!groupFormed) {
                connected = false;
                sendHost = null;
                rebuildDevices();
                updateStatus();
                return;
            }
            if (isOwner) {
                // адресу клієнта дізнаємося з control-пакета (onPeerIpKnown)
                updateStatus();
            } else {
                sendHost = host;
                connected = host != null;
                if (host != null) {
                    NearbyLink.get().announceControl(host);
                }
                rebuildDevices();
                updateStatus();
            }
        }

        @Override
        public void onP2pError(int reason, String stage) {
            if ("discover".equals(stage) || "connect".equals(stage)) {
                toast("Wi-Fi Direct: помилка " + stage + " (код " + reason + ")");
            }
        }
    };

    private final NearbyBle.Callbacks bleCallbacks = new NearbyBle.Callbacks() {
        @Override
        public void onBleDevices(List<NearbyBle.BleDevice> devices) {
            bleByName.clear();
            for (NearbyBle.BleDevice d : devices) {
                if (d.name != null) {
                    bleByName.put(d.name, d);
                }
            }
            matchPending();
            rebuildDevices();
        }

        @Override
        public void onBleStatus(String message) {
            bleStatus = message == null ? "" : message;
            updateStatus();
        }
    };

    private final NearbyLink.Listener linkListener = new NearbyLink.Listener() {
        @Override
        public void onIncomingRequest(String peerLabel, String fileName, long size, NearbyLink.Decision decision) {
            Activity activity = getParentActivity();
            if (activity == null || activity.isFinishing()) {
                decision.decide(false);
                return;
            }
            AlertDialog.Builder b = new AlertDialog.Builder(activity);
            b.setTitle("Передача Nearby");
            b.setMessage(AndroidUtilities.formatFileSize(size) + " • " + fileName + "\nВід: " + peerLabel + "\n\nПрийняти файл?");
            b.setPositiveButton("Прийняти", (dialog, which) -> {
                dialog.dismiss();
                decision.decide(true);
            });
            b.setNegativeButton("Відмовити", (dialog, which) -> {
                dialog.dismiss();
                decision.decide(false);
            });
            AlertDialog dialog = b.show();
            dialog.setOnCancelListener(d -> decision.decide(false));
        }

        @Override
        public void onProgress(boolean incoming, long transferred, long total) {
            if (progressDialog == null || !progressDialog.isShowing()) {
                showProgress(incoming);
            }
            if (progressBar != null && total > 0) {
                progressBar.setProgress((int) Math.min(10000L, transferred * 10000L / total));
            }
            if (progressText != null) {
                progressText.setText(AndroidUtilities.formatFileSize(transferred) + " з " + AndroidUtilities.formatFileSize(total));
            }
        }

        @Override
        public void onFinished(boolean incoming, String fileName, String savedPath, boolean shaOk, String error) {
            dismissProgress();
            JSONObject entry = new JSONObject();
            try {
                entry.put("time", System.currentTimeMillis());
                entry.put("dir", incoming ? "in" : "out");
                entry.put("file", fileName == null ? "?" : fileName);
                entry.put("ok", shaOk && error == null);
                entry.put("path", savedPath == null ? "" : savedPath);
                entry.put("error", error == null ? "" : error);
                NearbyStore.addJournal(ApplicationLoader.applicationContext, entry);
            } catch (Exception ignore) {
            }
            rebuildJournal();
            if (error != null) {
                toast("Nearby: " + error);
            } else {
                toast("Nearby: готово" + (savedPath == null || savedPath.isEmpty() ? "" : " — " + savedPath));
            }
        }

        @Override
        public void onPeerIpKnown(String peerHost) {
            sendHost = peerHost;
            connected = true;
            updateStatus();
        }
    };

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_PICK_FILE && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            sendChosen(data.getData());
        }
    }

    @Override
    public void onRequestPermissionsResultFragment(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_NEARBY_PERMS && pendingDiscovery) {
            pendingDiscovery = false;
            startDiscovery();
        }
    }

    // ================================ помічники ================================

    private void matchPending() {
        if (pendingConnectName == null) {
            return;
        }
        // 1) надійний шлях: MAC-підказка з BLE-маячка збігається з Wi-Fi Direct піром
        for (NearbyBle.BleDevice b : bleByName.values()) {
            String mac = macToString(b.p2pMacHint);
            WifiP2pDevice peer = mac == null ? null : peersByAddress.get(mac);
            if (peer != null) {
                pendingConnectName = null;
                wifiDirect.connect(peer.deviceAddress);
                toast("З'єднуюся з " + (peer.deviceName != null && !peer.deviceName.trim().isEmpty() ? peer.deviceName.trim() : mac) + "…");
                return;
            }
        }
        // 2) запасний шлях: збіг за ім'ям
        for (WifiP2pDevice d : peersByAddress.values()) {
            String n = d.deviceName == null ? "" : d.deviceName.trim();
            if (n.equals(pendingConnectName)) {
                pendingConnectName = null;
                wifiDirect.connect(d.deviceAddress);
                toast("З'єднуюся з " + n + "…");
                return;
            }
        }
    }

    private void rebuildDevices() {
        if (devicesBox == null) {
            return;
        }
        Context context = getParentActivity() != null ? getParentActivity() : ApplicationLoader.applicationContext;
        devicesBox.removeAllViews();
        Map<String, WifiP2pDevice> used = new HashMap<>();
        int added = 0;
        String myAddress = wifiDirect != null ? wifiDirect.getThisDeviceAddress() : null;
        for (WifiP2pDevice d : peersByAddress.values()) {
            if (d.deviceAddress == null || d.deviceAddress.equals(myAddress)) {
                continue;
            }
            String name = d.deviceName == null || d.deviceName.trim().isEmpty() ? "Пристрій" : d.deviceName.trim();
            used.put(name, d);
            devicesBox.addView(deviceRow(context, name, describePeer(d), () -> onDeviceTap(name, d)), margin(0, 0, 0, 8));
            added++;
        }
        for (NearbyBle.BleDevice b : bleByName.values()) {
            if (b.name == null || used.containsKey(b.name.trim())) {
                continue;
            }
            final String name = b.name;
            devicesBox.addView(deviceRow(context, name, "BLE • " + b.rssi + " dBm", () -> onDeviceTap(name, null)), margin(0, 0, 0, 8));
            added++;
        }
        if (added == 0) {
            devicesBox.addView(text(context, "Поки нічого. На обох телефонах має бути відкритий екран Nearby і натиснутий «Пошук».", 13, MUTED, false), margin(0, 2, 0, 0));
        }
    }

    private String describePeer(WifiP2pDevice d) {
        String state;
        switch (d.status) {
            case WifiP2pDevice.CONNECTED:
                state = "з'єднаний";
                break;
            case WifiP2pDevice.INVITED:
                state = "запрошений";
                break;
            case WifiP2pDevice.AVAILABLE:
                state = "доступний";
                break;
            default:
                state = "стан " + d.status;
        }
        return "Wi-Fi Direct • " + state;
    }

    private void rebuildJournal() {
        if (journalBox == null) {
            return;
        }
        Context context = getParentActivity() != null ? getParentActivity() : ApplicationLoader.applicationContext;
        journalBox.removeAllViews();
        JSONArray journal = NearbyStore.getJournal(ApplicationLoader.applicationContext);
        if (journal.length() == 0) {
            journalBox.addView(text(context, "Передач ще не було.", 13, MUTED, false), margin(0, 2, 0, 0));
            return;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("dd.MM HH:mm", Locale.getDefault());
        for (int i = 0; i < journal.length(); i++) {
            JSONObject item = journal.optJSONObject(i);
            if (item == null) {
                continue;
            }
            boolean in = "in".equals(item.optString("dir"));
            boolean ok = item.optBoolean("ok");
            String title = (in ? "⬇ " : "⬆ ") + item.optString("file", "?");
            String sub = fmt.format(new Date(item.optLong("time"))) + (in ? " • отримано" : " • надіслано") + (ok ? " • цілісність ✓" : " • " + (item.optString("error").isEmpty() ? "не завершено" : item.optString("error")));
            LinearLayout row = card(context);
            row.setOrientation(LinearLayout.VERTICAL);
            row.addView(text(context, title, 14, TEXT, true), margin(0, 0, 0, 4));
            row.addView(text(context, sub, 12, MUTED, false), margin(0, 0, 0, 0));
            journalBox.addView(row, margin(0, 0, 0, 8));
        }
    }

    private void updateStatus() {
        if (statusText == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (connected && !TextUtils.isEmpty(sendHost)) {
            sb.append("З'єднано. Натисни «Надіслати файл».");
        } else if (connected) {
            sb.append("Wi-Fi Direct групу створено — узгоджуємо адресу…");
        } else if (discovering) {
            sb.append("Пошук пристроїв поблизу (Wi-Fi Direct + BLE)…");
        } else {
            sb.append("Натисни «Пошук пристроїв», щоб знайти NEXGRAM поруч.");
        }
        if (bleStatus != null && !bleStatus.isEmpty()) {
            sb.append("\nBLE: ").append(bleStatus);
        }
        statusText.setText(sb.toString());
        if (sendButton != null) {
            sendButton.setVisibility(connected && !TextUtils.isEmpty(sendHost) ? View.VISIBLE : View.GONE);
        }
        if (disconnectButton != null) {
            disconnectButton.setVisibility(connected ? View.VISIBLE : View.GONE);
        }
    }

    private void showProgress(boolean incoming) {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        dismissProgress();
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = AndroidUtilities.dp(20);
        box.setPadding(pad, pad, pad, 0);
        progressBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(10000);
        box.addView(progressBar, new LinearLayout.LayoutParams(-1, -2));
        progressText = text(activity, "…", 13, MUTED, false);
        box.addView(progressText, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog.Builder b = new AlertDialog.Builder(activity);
        b.setTitle(incoming ? "Отримання файлу" : "Передача файлу");
        b.setView(box);
        b.setNegativeButton("Скасувати", (dialog, which) -> NearbyLink.get().cancelCurrent());
        progressDialog = b.show();
    }

    private void dismissProgress() {
        if (progressDialog != null) {
            try {
                if (progressDialog.isShowing()) {
                    progressDialog.dismiss();
                }
            } catch (Exception ignore) {
            }
            progressDialog = null;
            progressBar = null;
            progressText = null;
        }
    }

    private String label() {
        String l = NearbyStore.getLabel(ApplicationLoader.applicationContext);
        if (l == null) {
            l = "NX-" + String.format(Locale.US, "%04X", (int) (Math.random() * 0x10000));
            NearbyStore.setLabel(ApplicationLoader.applicationContext, l);
        }
        return l;
    }

    private String contactPayload(String label) throws Exception {
        String username = "";
        try {
            TLRPC.User me = UserConfig.getInstance(UserConfig.selectedAccount).getCurrentUser();
            if (me != null && !TextUtils.isEmpty(me.username)) {
                username = me.username;
            }
        } catch (Exception ignore) {
        }
        return "nexgram:contact?n=" + URLEncoder.encode(label, "UTF-8") + "&u=" + URLEncoder.encode(username, "UTF-8");
    }

    private static byte[] macBytes(String mac) {
        try {
            String[] parts = mac.split(":");
            byte[] b = new byte[6];
            for (int i = 0; i < 6 && i < parts.length; i++) {
                b[i] = (byte) Integer.parseInt(parts[i], 16);
            }
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    private static String macToString(byte[] bytes) {
        if (bytes == null || bytes.length != 6) {
            return null;
        }
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < 6; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(String.format(Locale.US, "%02x", bytes[i] & 0xFF));
        }
        return sb.toString();
    }

    private void toast(String text) {
        Context ctx = getParentActivity() != null ? getParentActivity() : ApplicationLoader.applicationContext;
        Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show();
    }

    private LinearLayout deviceRow(Context context, String title, String subtitle, Runnable onClick) {
        LinearLayout row = card(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setOnClickListener(v -> onClick.run());
        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
        labels.addView(text(context, title, 15, TEXT, true));
        labels.addView(text(context, subtitle, 12, MUTED, false), margin(0, 4, 10, 0));
        TextView arrow = text(context, "›", 22, MUTED, true);
        row.addView(arrow, new LinearLayout.LayoutParams(-2, -2));
        return row;
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

    private TextView button(Context context, String label) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setTextSize(14);
        view.setTextColor(TEXT);
        view.setGravity(Gravity.CENTER);
        GradientDrawable background = new GradientDrawable();
        background.setColor(SURFACE);
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
