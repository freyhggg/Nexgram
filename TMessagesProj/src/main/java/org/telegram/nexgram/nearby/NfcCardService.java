package org.telegram.nexgram.nearby;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import android.text.TextUtils;

import org.telegram.messenger.UserConfig;
import org.telegram.nexgram.NexgramPrefs;
import org.telegram.tgnet.TLRPC;

import java.nio.charset.StandardCharsets;

/**
 * NEXGRAM NFC contact card — Host Card Emulation service.
 *
 * While another NEXGRAM device reads this phone over NFC (reader mode),
 * this service answers ISO-DEP APDU commands with the owner's PUBLIC
 * contact details, but only if the owner explicitly enabled sharing in
 * NEXGRAM → NFC-картка. Sharing is fully user-controlled:
 *  - master toggle (off by default),
 *  - username toggle (on by default),
 *  - phone number toggle (off by default).
 * Nothing is sent without the owner's prior consent; the READER side also
 * shows a confirmation dialog before anything is saved locally.
 *
 * Wire format (plain, no sensitive data travels beyond what the user chose):
 *   "NXNFC1|<name>|<username>|<phone>"
 */
public class NfcCardService extends HostApduService {

    private static final byte[] STATUS_OK = {(byte) 0x90, (byte) 0x00};
    private static final byte[] STATUS_UNKNOWN = {(byte) 0x6D, (byte) 0x00};

    // ISO 7816 constants
    private static final byte INS_SELECT = (byte) 0xA4;
    private static final byte INS_GET_CARD = (byte) 0x11;
    private static final byte CLA_NEXGRAM = (byte) 0x80;

    private static final int MAX_FIELDS = 60;
    private static final int MAX_PAYLOAD = 240;

    @Override
    public byte[] processCommandApdu(byte[] apdu, Bundle extras) {
        if (apdu == null || apdu.length < 5) {
            return STATUS_UNKNOWN;
        }
        byte ins = apdu[1];
        if (ins == INS_SELECT) {
            // SELECT (AID routed by the NFC stack) — acknowledge, reader asks for data next.
            return STATUS_OK;
        }
        if (ins == INS_GET_CARD && apdu[0] == CLA_NEXGRAM) {
            byte[] payload = buildPayload();
            if (payload == null || payload.length == 0) {
                // Card present but sharing disabled — reader shows a polite "disabled" state.
                return STATUS_OK;
            }
            byte[] response = new byte[payload.length + 2];
            System.arraycopy(payload, 0, response, 0, payload.length);
            response[payload.length] = STATUS_OK[0];
            response[payload.length + 1] = STATUS_OK[1];
            return response;
        }
        return STATUS_UNKNOWN;
    }

    @Override
    public void onDeactivated(int reason) {
    }

    /**
     * Builds the payload from the current Telegram profile and the user's
     * sharing choices. Returns null when sharing is disabled or the
     * profile is not available.
     */
    private byte[] buildPayload() {
        if (!NexgramPrefs.nfcShareEnabled(this)) {
            return null;
        }
        TLRPC.User user = UserConfig.getInstance(UserConfig.selectedAccount).getCurrentUser();
        if (user == null) {
            return null;
        }
        String name = ((user.first_name != null ? user.first_name : "")
                + (user.last_name != null && !user.last_name.isEmpty() ? " " + user.last_name : "")).trim();
        String username = "";
        if (NexgramPrefs.nfcShareUsername(this) && !TextUtils.isEmpty(user.username)) {
            username = user.username;
        }
        String phone = "";
        if (NexgramPrefs.nfcSharePhone(this) && !TextUtils.isEmpty(user.phone)) {
            phone = user.phone;
        }
        if (TextUtils.isEmpty(name) && TextUtils.isEmpty(username) && TextUtils.isEmpty(phone)) {
            return null;
        }
        String payload = "NXNFC1|" + clip(name) + "|" + clip(username) + "|" + clip(phone);
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_PAYLOAD) {
            // Keep the response within a single ISO-DEP frame; drop the phone field gracefully.
            bytes = ("NXNFC1|" + clip(name) + "|" + clip(username) + "|")
                    .getBytes(StandardCharsets.UTF_8);
        }
        return bytes;
    }

    private static String clip(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > MAX_FIELDS ? value.substring(0, MAX_FIELDS) : value;
    }
}
