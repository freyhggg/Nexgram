package org.telegram.nexgram;

import android.content.Context;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Account Guard controller.
 *
 * Uses ONLY officially available Telegram API data:
 *  - account.getAuthorizations  -> active sessions list
 *  - account.resetAuthorization -> terminate a session (explicit user action)
 *  - account.getPassword        -> whether 2FA password is enabled
 *
 * It never blocks IPs, never stores passwords or session tokens, and all
 * "unfamiliar session" logic is purely local metadata comparison.
 */
public final class AccountGuardController {

    public interface SessionsCallback {
        void onResult(TL_account.authorizations result, TLRPC.TL_error error);
    }

    public interface BoolCallback {
        void onResult(Boolean result, TLRPC.TL_error error);
    }

    public interface PasswordCallback {
        void onResult(TL_account.Password result, TLRPC.TL_error error);
    }

    private AccountGuardController() {
    }

    public static void fetchSessions(int account, SessionsCallback callback) {
        TL_account.getAuthorizations req = new TL_account.getAuthorizations();
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) ->
                AndroidUtilities.runOnUIThread(() ->
                        callback.onResult(response instanceof TL_account.authorizations
                                ? (TL_account.authorizations) response : null, error)));
    }

    public static void terminateSession(int account, long hash, BoolCallback callback) {
        TL_account.resetAuthorization req = new TL_account.resetAuthorization();
        req.hash = hash;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) ->
                AndroidUtilities.runOnUIThread(() ->
                        callback.onResult(response instanceof TLRPC.TL_boolTrue, error)));
    }

    public static void fetchPasswordState(int account, PasswordCallback callback) {
        TL_account.getPassword req = new TL_account.getPassword();
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) ->
                AndroidUtilities.runOnUIThread(() ->
                        callback.onResult(response instanceof TL_account.Password
                                ? (TL_account.Password) response : null, error)));
    }

    // --- Local unfamiliar-session registry ---

    /** True if the app has already seen this session hash before (any account use). */
    public static boolean isKnownSession(Context context, long hash) {
        return NexgramPrefs.knownSessionHashes(context).contains(hash);
    }

    /**
     * Records a local incident for a session that was not seen before.
     * Called only when Account Guard screen was opened with new sessions present.
     */
    public static void reportUnfamiliar(Context context, TLRPC.TL_authorization auth) {
        if (auth == null) {
            return;
        }
        try {
            JSONObject incident = new JSONObject();
            incident.put("time", System.currentTimeMillis());
            incident.put("device", safe(auth.device_model));
            incident.put("platform", safe(auth.platform));
            incident.put("ip", safe(auth.ip));
            incident.put("country", safe(auth.country));
            incident.put("app", safe(auth.app_name) + " " + safe(auth.app_version));
            incident.put("hash", auth.hash);
            NexgramPrefs.appendIncident(context, incident);
        } catch (Exception ignored) {
        }
    }

    /** Marks every session in the list as known (user confirmed they recognize them). */
    public static void markAllKnown(Context context, List<TLRPC.TL_authorization> sessions) {
        if (sessions == null) {
            return;
        }
        Set<Long> known = new HashSet<>(NexgramPrefs.knownSessionHashes(context));
        for (TLRPC.TL_authorization auth : sessions) {
            known.add(auth.hash);
        }
        NexgramPrefs.setKnownSessionHashes(context, known);
    }

    public static void clearKnownSessions(Context context) {
        NexgramPrefs.setKnownSessionHashes(context, new HashSet<>());
    }

    public static void clearIncidentLog(Context context) {
        NexgramPrefs.clearIncidents(context);
    }

    /** Human-friendly timestamp for incident entries and session dates. */
    public static String formatTs(long seconds) {
        try {
            SimpleDateFormat format = new SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault());
            return format.format(new Date(seconds * 1000L));
        } catch (Exception e) {
            return String.valueOf(seconds);
        }
    }

    public static String formatWallTime(long millis) {
        try {
            SimpleDateFormat format = new SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault());
            return format.format(new Date(millis));
        } catch (Exception e) {
            return String.valueOf(millis);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
