package org.telegram.nexgram.nearby;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.ParcelUuid;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BLE-маячок Nearby: пристрій рекламує службовий UUID, у service data —
 * підказка MAC-адреси Wi-Fi Direct. Сканування йде лише поки користувач
 * відкрив екран Nearby і натиснув «Пошук» — прихованого сканування немає.
 */
public final class NearbyBle {

    public static final class BleDevice {
        public String name;
        public byte[] p2pMacHint;
        public int rssi;
    }

    public interface Callbacks {
        void onBleDevices(List<BleDevice> devices);

        void onBleStatus(String message);
    }

    public static final UUID SERVICE_UUID = UUID.nameUUIDFromBytes("org.telegram.nexgram.nearby".getBytes(StandardCharsets.US_ASCII));

    private final Callbacks callbacks;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;
    private final Map<String, BleDevice> devices = new HashMap<>();
    private long lastNotify;
    private boolean advertising;
    private boolean scanning;

    public NearbyBle(Callbacks callbacks) {
        this.callbacks = callbacks;
    }

    private static BluetoothAdapter adapter(Context context) {
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        return manager != null ? manager.getAdapter() : null;
    }

    public void startAdvertising(Context context, byte[] p2pMacHint) {
        BluetoothAdapter a = adapter(context);
        if (a == null || !a.isEnabled()) {
            callbacks.onBleStatus("Bluetooth вимкнено");
            return;
        }
        advertiser = a.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            callbacks.onBleStatus("BLE-передавач недоступний на цьому пристрої");
            return;
        }
        byte[] data = p2pMacHint != null ? p2pMacHint : new byte[6];
        AdvertiseData advertise = new AdvertiseData.Builder()
                .addServiceUuid(new ParcelUuid(SERVICE_UUID))
                .addServiceData(new ParcelUuid(SERVICE_UUID), data)
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .build();
        advertising = true;
        if (Build.VERSION.SDK_INT >= 26) {
            AdvertiseData scanResponse = new AdvertiseData.Builder()
                    .setIncludeDeviceName(true)
                    .build();
            advertiser.startAdvertising(advertise, scanResponse, advertiseCallback);
        } else {
            advertiser.startAdvertising(advertise, advertiseCallback);
        }
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            callbacks.onBleStatus("маячок активний");
        }

        @Override
        public void onStartFailure(int errorCode) {
            advertising = false;
            callbacks.onBleStatus("маячок не запустився (код " + errorCode + ")");
        }
    };

    public void stopAdvertising() {
        if (advertiser != null && advertising) {
            try {
                advertiser.stopAdvertising(advertiseCallback);
            } catch (Exception ignore) {
            }
        }
        advertising = false;
    }

    public void startScan(Context context) {
        BluetoothAdapter a = adapter(context);
        if (a == null || !a.isEnabled()) {
            callbacks.onBleStatus("Bluetooth вимкнено");
            return;
        }
        BluetoothLeScanner s = a.getBluetoothLeScanner();
        if (s == null) {
            callbacks.onBleStatus("BLE-сканер недоступний");
            return;
        }
        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE_UUID)).build());
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        scanner = s;
        scanning = true;
        s.startScan(filters, settings, scanCallback);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            ScanRecord record = result.getScanRecord();
            if (record == null) {
                return;
            }
            byte[] hint = record.getServiceData(new ParcelUuid(SERVICE_UUID));
            String name = record.getDeviceName();
            if ((name == null || name.length() == 0) && result.getDevice() != null) {
                name = result.getDevice().getName();
            }
            if (name == null || name.length() == 0) {
                name = "NEXGRAM-пристрій";
            }
            synchronized (devices) {
                BleDevice device = devices.get(name);
                if (device == null) {
                    device = new BleDevice();
                    device.name = name;
                    devices.put(name, device);
                }
                device.rssi = result.getRssi();
                if (hint != null && hint.length == 6) {
                    device.p2pMacHint = hint;
                }
            }
            notifyAggregated();
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            callbacks.onBleStatus("скан не запустився (код " + errorCode + ")");
        }
    };

    private void notifyAggregated() {
        long now = System.currentTimeMillis();
        if (now - lastNotify < 1200) {
            return;
        }
        lastNotify = now;
        List<BleDevice> snapshot;
        synchronized (devices) {
            snapshot = new ArrayList<>(devices.values());
        }
        callbacks.onBleDevices(snapshot);
    }

    public void stopAll() {
        stopAdvertising();
        if (scanner != null && scanning) {
            try {
                scanner.stopScan(scanCallback);
            } catch (Exception ignore) {
            }
        }
        scanning = false;
        synchronized (devices) {
            devices.clear();
        }
    }
}
