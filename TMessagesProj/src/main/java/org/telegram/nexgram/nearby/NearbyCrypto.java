package org.telegram.nexgram.nearby;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyAgreement;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Криптографія сесії NEXGRAM Nearby: ECDH P-256 → AES-256-GCM.
 * Сесійний ключ = SHA-256(спільний секрет ECDH || nonce клієнта || nonce сервера).
 * Кожен кадр шифрується з власним nonce [ролі(1)][тип(1)][послідовність(4)][нулі(6)],
 * тому повторне використання nonce в межах сесії неможливе, а перестановка кадрів
 * ламає GCM-автентифікацію (AAD = "NXNB").
 */
public final class NearbyCrypto {

    public static final int CHUNK_SIZE = 65536;
    /** Роль відправника кадру в nonce: TCP-клієнт або TCP-сервер сесії. */
    public static final byte ROLE_CLIENT = 1;
    public static final byte ROLE_SERVER = 2;

    private NearbyCrypto() {
    }

    public static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    public static byte[] encodePublic(KeyPair pair) {
        return pair.getPublic().getEncoded();
    }

    public static PublicKey decodePublic(byte[] encoded) throws Exception {
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(encoded));
    }

    public static byte[] randomBytes(int count) {
        byte[] bytes = new byte[count];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    public static SecretKey deriveKey(KeyPair own, byte[] peerPublic, byte[] clientNonce, byte[] serverNonce) throws Exception {
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(own.getPrivate());
        agreement.doPhase(decodePublic(peerPublic), true);
        byte[] secret = agreement.generateSecret();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(secret);
        digest.update(clientNonce);
        digest.update(serverNonce);
        return new SecretKeySpec(digest.digest(), "AES");
    }

    private static byte[] nonceFor(byte role, byte type, int sequence) {
        byte[] nonce = new byte[12];
        nonce[0] = role;
        nonce[1] = type;
        nonce[2] = (byte) (sequence >>> 24);
        nonce[3] = (byte) (sequence >>> 16);
        nonce[4] = (byte) (sequence >>> 8);
        nonce[5] = (byte) sequence;
        return nonce;
    }

    private static byte[] gcm(SecretKey key, byte role, byte type, int sequence, byte[] data, int mode) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonceFor(role, type, sequence)));
        cipher.updateAAD("NXNB".getBytes(StandardCharsets.US_ASCII));
        return cipher.doFinal(data);
    }

    public static byte[] encrypt(byte role, byte type, int sequence, SecretKey key, byte[] plain) throws Exception {
        return gcm(key, role, type, sequence, plain, Cipher.ENCRYPT_MODE);
    }

    public static byte[] decrypt(byte role, byte type, int sequence, SecretKey key, byte[] encrypted) throws Exception {
        return gcm(key, role, type, sequence, encrypted, Cipher.DECRYPT_MODE);
    }

    public static byte[] sha256(InputStream in) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) > 0) {
            digest.update(buffer, 0, n);
        }
        return digest.digest();
    }

    public static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append("0123456789abcdef".charAt((b >> 4) & 0xF));
            sb.append("0123456789abcdef".charAt(b & 0xF));
        }
        return sb.toString();
    }
}
