package org.telegram.nexgram.nearby;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/**
 * Обгортка Wi-Fi Direct: реєструє приймач системних подій, керує пошуком
 * та з'єднанням. Не вимагає Firebase/Google Play — чистий Android API.
 */
public final class NearbyWifiDirect {

    public interface Callbacks {
        void onP2pState(boolean enabled);

        void onPeers(List<WifiP2pDevice> peers);

        void onConnection(boolean groupFormed, boolean isOwner, String peerHost);

        void onP2pError(int reason, String stage);
    }

    private final Context context;
    private final Callbacks callbacks;
    private final WifiP2pManager.PeerListListener peersListener;
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private volatile boolean registered;
    private volatile Boolean p2pEnabled = null;
    private volatile String thisDeviceAddress;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            String action = intent.getAction();
            if (action == null || manager == null || channel == null) {
                return;
            }
            if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                boolean enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, WifiP2pManager.WIFI_P2P_STATE_DISABLED) == WifiP2pManager.WIFI_P2P_STATE_ENABLED;
                p2pEnabled = enabled;
                callbacks.onP2pState(enabled);
            } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                manager.requestPeers(channel, peersListener);
            } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                manager.requestConnectionInfo(channel, (WifiP2pInfo info) -> {
                    if (info == null || !info.groupFormed) {
                        callbacks.onConnection(false, false, null);
                        return;
                    }
                    if (info.isGroupOwner) {
                        // адресу клієнта дізнаємося, коли він надішле control-пакет (NearbyLink)
                        callbacks.onConnection(true, true, null);
                    } else {
                        callbacks.onConnection(true, false, info.groupOwnerAddress != null ? info.groupOwnerAddress.getHostAddress() : null);
                    }
                });
            } else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                WifiP2pDevice device = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
                if (device != null) {
                    thisDeviceAddress = device.deviceAddress;
                }
            }
        }
    };

    public NearbyWifiDirect(Context context, Callbacks callbacks) {
        this.context = context.getApplicationContext();
        this.callbacks = callbacks;
        this.peersListener = (WifiP2pDeviceList list) -> {
            List<WifiP2pDevice> peers = new ArrayList<>(list.getDeviceList());
            callbacks.onPeers(peers);
        };
    }

    public void register() {
        if (registered) {
            return;
        }
        manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        if (manager == null) {
            callbacks.onP2pError(-1, "init");
            return;
        }
        channel = manager.initialize(context, context.getMainLooper(), null);
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
        registered = true;
    }

    public void unregister() {
        if (!registered) {
            return;
        }
        try {
            context.unregisterReceiver(receiver);
        } catch (Exception ignore) {
        }
        registered = false;
    }

    public Boolean isP2pEnabled() {
        return p2pEnabled;
    }

    public String getThisDeviceAddress() {
        return thisDeviceAddress;
    }

    public void discoverPeers() {
        if (manager == null || channel == null) {
            callbacks.onP2pError(-1, "discover");
            return;
        }
        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
            }

            @Override
            public void onFailure(int reason) {
                callbacks.onP2pError(reason, "discover");
            }
        });
    }

    public void connect(String deviceAddress) {
        if (manager == null || channel == null || deviceAddress == null) {
            callbacks.onP2pError(-1, "connect");
            return;
        }
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = deviceAddress;
        manager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
            }

            @Override
            public void onFailure(int reason) {
                callbacks.onP2pError(reason, "connect");
            }
        });
    }

    public void removeGroup() {
        if (manager == null || channel == null) {
            return;
        }
        manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
            }

            @Override
            public void onFailure(int reason) {
            }
        });
    }
}
