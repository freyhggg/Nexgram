package org.telegram.nexgram.nearby;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Рушій передачі NEXGRAM Nearby. Чистий Android: ServerSocket поверх
 * Wi-Fi Direct групи. Протокол NXNB v1:
 *   кадр = "NXNB"(4) | версія(1) | тип(1) | довжина(4) | корисне навантаження
 *   HELLO — відкритий (обмін ECDH-ключами + nonce), далі все шифрується AES-GCM.
 *   ROLE (0=control, 1=відправляю, 2=приймаю) → META (ім'я, розмір, SHA-256)
 *   → CONSENT (згода отримувача обов'язкова) → DATA (зашифровані частки) → кінець.
 * Обидва пристрої тримають сервер на порт 59995, поки екран Nearby відкритий.
 */
public final class NearbyLink {

    public static final int PORT = 59995;
    private static final byte VERSION = 1;
    private static final byte F_HELLO = 1;
    private static final byte F_ROLE = 2;
    private static final byte F_META = 3;
    private static final byte F_CONSENT = 4;
    private static final byte F_DATA = 5;
    private static final byte F_CANCEL = 6;
    private static final byte F_ACK = 7;
    private static final byte ROLE_CONTROL = 0;
    private static final byte ROLE_SEND = 1;
    private static final byte ROLE_CLIENT = NearbyCrypto.ROLE_CLIENT;
    private static final byte ROLE_SERVER = NearbyCrypto.ROLE_SERVER;

    public interface Listener {
        /** Викликається на UI-потоці: показати діалог згоди на прийом. */
        void onIncomingRequest(String peerLabel, String fileName, long size, Decision decision);

        void onProgress(boolean incoming, long transferred, long total);

        void onFinished(boolean incoming, String fileName, String savedPath, boolean shaOk, String error);

        /** Власник групи дізнався адресу клієнта (control-пакет). */
        void onPeerIpKnown(String peerHost);
    }

    public interface Decision {
        void decide(boolean accept);
    }

    private static final class Hello {
        byte[] nonce;
        byte[] pub;
        String label;
    }

    private static final class SavedTarget {
        OutputStream out;
        String displayPath;
        Uri storeUri;
        File file;
    }

    private static volatile NearbyLink instance;

    public static NearbyLink get() {
        if (instance == null) {
            synchronized (NearbyLink.class) {
                if (instance == null) {
                    instance = new NearbyLink();
                }
            }
        }
        return instance;
    }

    private volatile ServerSocket serverSocket;
    private volatile Listener listener;
    private volatile String deviceLabel = "NEXGRAM";
    private volatile String lastClientHost;
    private volatile boolean cancelled;
    private final AtomicBoolean transferActive = new AtomicBoolean(false);

    private NearbyLink() {
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void startServer(String label) {
        deviceLabel = label;
        cancelled = false;
        if (serverSocket != null && !serverSocket.isClosed()) {
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(PORT));
                serverSocket = ss;
                while (!ss.isClosed()) {
                    final Socket socket = ss.accept();
                    new Thread(() -> handleSession(socket), "nexgram-nearby-session").start();
                }
            } catch (Exception e) {
                // сокет закрито під час зупинки — штатно
            }
        }, "nexgram-nearby-server");
        thread.setDaemon(true);
        thread.start();
    }

    public void stopServer() {
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception ignore) {
        }
        serverSocket = null;
    }

    public String getLastClientHost() {
        return lastClientHost;
    }

    public void cancelCurrent() {
        cancelled = true;
    }

    /** Клієнт групи сповіщає власника про свою IP-адресу (control-сесія). */
    public void announceControl(String ownerHost) {
        Thread thread = new Thread(() -> {
            try {
                KeyPair myKeys = NearbyCrypto.generateKeyPair();
                byte[] clientNonce = NearbyCrypto.randomBytes(16);
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(ownerHost, PORT), 8000);
                    socket.setSoTimeout(15000);
                    InputStream in = new BufferedInputStream(socket.getInputStream());
                    OutputStream out = new BufferedOutputStream(socket.getOutputStream());
                    writeHello(out, clientNonce, myKeys, deviceLabel);
                    Hello serverHello = readHello(in);
                    SecretKeyAlias key = new SecretKeyAlias(NearbyCrypto.deriveKey(myKeys, serverHello.pub, clientNonce, serverHello.nonce));
                    writeEncryptedFrame(out, key.key, ROLE_CLIENT, F_ROLE, 0, new byte[]{ROLE_CONTROL});
                    byte[] head = readHeader(in, F_ACK);
                    readFully(in, headerLen(head));
                }
            } catch (Exception ignore) {
            }
        }, "nexgram-nearby-announce");
        thread.setDaemon(true);
        thread.start();
    }

    /** Надсилання файлу на peerHost (адреса з'єднаного пристрою). */
    public void sendFile(Uri uri, String peerHost) {
        final Listener l = listener;
        if (peerHost == null || peerHost.isEmpty()) {
            if (l != null) {
                AndroidUtilities.runOnUIThread(() -> l.onFinished(false, null, null, false, "немає адреси пристрою — спершу з'єднайся"));
            }
            return;
        }
        if (!transferActive.compareAndSet(false, true)) {
            if (l != null) {
                AndroidUtilities.runOnUIThread(() -> l.onFinished(false, null, null, false, "інша передача вже активна"));
            }
            return;
        }
        cancelled = false;
        Thread thread = new Thread(() -> {
            boolean ok = false;
            String error = null;
            String name = null;
            try {
                ContentResolver cr = ApplicationLoader.applicationContext.getContentResolver();
                name = queryDisplayName(cr, uri);
                if (name == null || name.trim().isEmpty()) {
                    name = "file";
                }
                long size;
                try (AssetFileDescriptor afd = cr.openAssetFileDescriptor(uri, "r")) {
                    size = afd != null ? afd.getLength() : -1;
                }
                if (size <= 0) {
                    throw new IOException("невідомий розмір файлу — обери локальний файл");
                }
                byte[] sha;
                try (InputStream fin = cr.openInputStream(uri)) {
                    if (fin == null) {
                        throw new IOException("файл недоступний");
                    }
                    sha = NearbyCrypto.sha256(fin);
                }
                KeyPair myKeys = NearbyCrypto.generateKeyPair();
                byte[] clientNonce = NearbyCrypto.randomBytes(16);
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(peerHost, PORT), 8000);
                    socket.setSoTimeout(180000);
                    InputStream in = new BufferedInputStream(socket.getInputStream());
                    OutputStream out = new BufferedOutputStream(socket.getOutputStream());
                    writeHello(out, clientNonce, myKeys, deviceLabel);
                    Hello serverHello = readHello(in);
                    javax.crypto.SecretKey key = NearbyCrypto.deriveKey(myKeys, serverHello.pub, clientNonce, serverHello.nonce);
                    writeEncryptedFrame(out, key, ROLE_CLIENT, F_ROLE, 0, new byte[]{ROLE_SEND});
                    byte[] nameB = name.getBytes(StandardCharsets.UTF_8);
                    ByteBuffer mb = ByteBuffer.allocate(2 + nameB.length + 8 + 32);
                    mb.putShort((short) nameB.length);
                    mb.put(nameB);
                    mb.putLong(size);
                    mb.put(sha);
                    writeEncryptedFrame(out, key, ROLE_CLIENT, F_META, 0, mb.array());
                    byte[] consent = readEncryptedFrame(in, key, ROLE_SERVER, F_CONSENT, 0);
                    if (consent.length == 0 || consent[0] == 0) {
                        throw new IOException("отримувач відмовив у прийомі");
                    }
                    long sent = 0;
                    int seq = 0;
                    try (InputStream fin = cr.openInputStream(uri)) {
                        byte[] buf = new byte[NearbyCrypto.CHUNK_SIZE];
                        int n;
                        while ((n = fin.read(buf)) > 0) {
                            if (cancelled) {
                                try {
                                    writeEncryptedFrame(out, key, ROLE_CLIENT, F_CANCEL, 0, new byte[]{1});
                                } catch (Exception ignore) {
                                }
                                throw new IOException("передачу скасовано");
                            }
                            byte[] cipher = NearbyCrypto.encrypt(NearbyCrypto.ROLE_CLIENT, F_DATA, seq, key, Arrays.copyOf(buf, n));
                            byte[] payload = new byte[4 + cipher.length];
                            payload[0] = (byte) (seq >>> 24);
                            payload[1] = (byte) (seq >>> 16);
                            payload[2] = (byte) (seq >>> 8);
                            payload[3] = (byte) seq;
                            System.arraycopy(cipher, 0, payload, 4, cipher.length);
                            writeFrame(out, F_DATA, payload);
                            sent += n;
                            seq++;
                            if (seq % 8 == 0 && l != null) {
                                final long s = sent;
                                AndroidUtilities.runOnUIThread(() -> l.onProgress(false, s, size));
                            }
                        }
                    }
                    out.flush();
                    ok = true;
                }
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            transferActive.set(false);
            final boolean fOk = ok;
            final String fErr = error;
            final String fName = name;
            if (l != null) {
                AndroidUtilities.runOnUIThread(() -> l.onFinished(false, fName, null, fOk, fErr));
            }
        }, "nexgram-nearby-send");
        thread.setDaemon(true);
        thread.start();
    }

    // ================================ серверна сесія ================================

    private void handleSession(Socket socket) {
        javax.crypto.SecretKey key = null;
        try {
            socket.setSoTimeout(180000);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            Hello clientHello = readHello(in);
            KeyPair myKeys = NearbyCrypto.generateKeyPair();
            byte[] serverNonce = NearbyCrypto.randomBytes(16);
            writeHello(out, serverNonce, myKeys, deviceLabel);
            key = NearbyCrypto.deriveKey(myKeys, clientHello.pub, clientHello.nonce, serverNonce);
            byte[] rolePayload = readEncryptedFrame(in, key, NearbyCrypto.ROLE_CLIENT, F_ROLE, 0);
            byte role = rolePayload.length > 0 ? rolePayload[0] : -1;
            if (role == ROLE_CONTROL) {
                lastClientHost = socket.getInetAddress().getHostAddress();
                final String host = lastClientHost;
                final Listener l = listener;
                if (l != null) {
                    AndroidUtilities.runOnUIThread(() -> l.onPeerIpKnown(host));
                }
                writeFrame(out, F_ACK, new byte[]{1});
                return;
            }
            if (role != ROLE_SEND) {
                return;
            }
            // прийом файлу від клієнта
            byte[] meta = readEncryptedFrame(in, key, NearbyCrypto.ROLE_CLIENT, F_META, 0);
            ByteBuffer mb = ByteBuffer.wrap(meta);
            int nameLen = mb.getShort() & 0xFFFF;
            byte[] nameB = new byte[nameLen];
            mb.get(nameB);
            String fileName = new String(nameB, StandardCharsets.UTF_8);
            long size = mb.getLong();
            byte[] sha = new byte[32];
            mb.get(sha);
            if (!transferActive.compareAndSet(false, true)) {
                writeEncryptedFrame(out, key, NearbyCrypto.ROLE_SERVER, F_CONSENT, 0, new byte[]{0});
                return;
            }
            final String peerLabel = clientHello.label == null || clientHello.label.isEmpty() ? "пристрій поблизу" : clientHello.label;
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicBoolean accepted = new AtomicBoolean(false);
            final Listener l = listener;
            if (l != null) {
                AndroidUtilities.runOnUIThread(() -> l.onIncomingRequest(peerLabel, fileName, size, ok -> {
                    accepted.set(ok);
                    latch.countDown();
                }));
            } else {
                latch.countDown();
            }
            boolean accept = false;
            try {
                accept = latch.await(90, TimeUnit.SECONDS) && accepted.get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            writeEncryptedFrame(out, key, NearbyCrypto.ROLE_SERVER, F_CONSENT, 0, new byte[]{(byte) (accept ? 1 : 0)});
            if (!accept) {
                transferActive.set(false);
                return;
            }
            receiveFile(in, key, fileName, size, sha);
        } catch (Exception e) {
            if (key != null && transferActive.compareAndSet(true, false)) {
                final String msg = e instanceof EOFException ? "передачу перервано" : (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                final Listener l = listener;
                if (l != null) {
                    AndroidUtilities.runOnUIThread(() -> l.onFinished(true, null, null, false, msg));
                }
            }
        } finally {
            try {
                socket.close();
            } catch (Exception ignore) {
            }
        }
    }

    private void receiveFile(InputStream in, javax.crypto.SecretKey key, String fileName, long size, byte[] sha) {
        Context ctx = ApplicationLoader.applicationContext;
        SavedTarget target = null;
        long got = 0;
        boolean success = false;
        String savedPath = null;
        String error = null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            target = openSaveTarget(ctx, fileName);
            int seq = 0;
            while (got < size) {
                byte[] head = readHeaderAny(in);
                byte type = head[5];
                int len = headerLen(head);
                if (type == F_CANCEL) {
                    readFully(in, len);
                    throw new EOFException("передачу скасовано відправником");
                }
                if (type != F_DATA) {
                    throw new IOException("несподіваний кадр " + type);
                }
                byte[] payload = readFully(in, len);
                byte[] cipher = Arrays.copyOfRange(payload, 4, payload.length);
                byte[] plain = NearbyCrypto.decrypt(NearbyCrypto.ROLE_CLIENT, F_DATA, seq++, key, cipher);
                md.update(plain);
                target.out.write(plain);
                got += plain.length;
                if (seq % 8 == 0) {
                    final long g = got;
                    final Listener l = listener;
                    if (l != null) {
                        AndroidUtilities.runOnUIThread(() -> l.onProgress(true, g, size));
                    }
                }
            }
            target.out.flush();
            target.out.close();
            if (MessageDigest.isEqual(md.digest(), sha)) {
                success = true;
                savedPath = target.displayPath;
            } else {
                error = "хеш не збігається — файл пошкоджено, прийом скасовано";
            }
        } catch (Exception e) {
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        if (!success && target != null) {
            try {
                target.out.close();
            } catch (Exception ignore) {
            }
            cleanupTarget(ctx, target);
        }
        transferActive.set(false);
        final boolean fOk = success;
        final String fPath = savedPath;
        final String fErr = error;
        final String fName = fileName;
        final Listener l = listener;
        if (l != null) {
            AndroidUtilities.runOnUIThread(() -> l.onFinished(true, fName, fPath, fOk, fErr));
        }
    }

    // ================================ кадри ================================

    private static void writeHello(OutputStream out, byte[] nonce, KeyPair pair, String label) throws IOException {
        byte[] pub = NearbyCrypto.encodePublic(pair);
        byte[] nameB = label.getBytes(StandardCharsets.UTF_8);
        ByteBuffer bb = ByteBuffer.allocate(16 + 2 + pub.length + 2 + nameB.length);
        bb.put(nonce);
        bb.putShort((short) pub.length);
        bb.put(pub);
        bb.putShort((short) nameB.length);
        bb.put(nameB);
        writeFrame(out, F_HELLO, bb.array());
    }

    private static Hello readHello(InputStream in) throws IOException {
        byte[] head = readHeader(in, F_HELLO);
        byte[] payload = readFully(in, headerLen(head));
        DataInputStream d = new DataInputStream(new ByteArrayInputStream(payload));
        Hello h = new Hello();
        h.nonce = new byte[16];
        d.readFully(h.nonce);
        int pubLen = d.readUnsignedShort();
        h.pub = new byte[pubLen];
        d.readFully(h.pub);
        int nameLen = d.readUnsignedShort();
        byte[] nameB = new byte[nameLen];
        d.readFully(nameB);
        h.label = new String(nameB, StandardCharsets.UTF_8);
        return h;
    }

    private static void writeEncryptedFrame(OutputStream out, javax.crypto.SecretKey key, byte senderRole, byte type, int seq, byte[] plainPayload) throws Exception {
        byte[] cipher = NearbyCrypto.encrypt(senderRole, type, seq, key, plainPayload);
        writeFrame(out, type, cipher);
    }

    private static byte[] readEncryptedFrame(InputStream in, javax.crypto.SecretKey key, byte senderRole, byte expectedType, int seq) throws Exception {
        byte[] head = readHeader(in, expectedType);
        byte[] cipher = readFully(in, headerLen(head));
        return NearbyCrypto.decrypt(senderRole, expectedType, seq, key, cipher);
    }

    private static void writeFrame(OutputStream out, byte type, byte[] payload) throws IOException {
        byte[] head = new byte[10];
        head[0] = 'N';
        head[1] = 'X';
        head[2] = 'N';
        head[3] = 'B';
        head[4] = VERSION;
        head[5] = type;
        head[6] = (byte) (payload.length >>> 24);
        head[7] = (byte) (payload.length >>> 16);
        head[8] = (byte) (payload.length >>> 8);
        head[9] = (byte) payload.length;
        out.write(head);
        out.write(payload);
        out.flush();
    }

    private static byte[] readHeader(InputStream in, byte expectedType) throws IOException {
        byte[] head = readHeaderAny(in);
        if (head[5] != expectedType) {
            throw new IOException("несподіваний тип кадру " + head[5]);
        }
        return head;
    }

    private static byte[] readHeaderAny(InputStream in) throws IOException {
        byte[] head = readFully(in, 10);
        if (head[0] != 'N' || head[1] != 'X' || head[2] != 'N' || head[3] != 'B' || head[4] != VERSION) {
            throw new IOException("невідомий формат кадру");
        }
        return head;
    }

    private static int headerLen(byte[] head) {
        return ((head[6] & 0xFF) << 24) | ((head[7] & 0xFF) << 16) | ((head[8] & 0xFF) << 8) | (head[9] & 0xFF);
    }

    private static byte[] readFully(InputStream in, int len) throws IOException {
        byte[] b = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(b, off, len - off);
            if (n < 0) {
                throw new EOFException("з'єднання перервано");
            }
            off += n;
        }
        return b;
    }

    // ================================ збереження ================================

    private static String queryDisplayName(ContentResolver cr, Uri uri) {
        try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                return c.getString(0);
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private static SavedTarget openSaveTarget(Context ctx, String fileName) throws Exception {
        String safe = fileName == null || fileName.trim().isEmpty() ? "file" : fileName.replaceAll("[/\\\\:*?\"<>|]", "_");
        SavedTarget t = new SavedTarget();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, safe);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, guessMime(safe));
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri != null) {
                t.storeUri = uri;
                t.out = ctx.getContentResolver().openOutputStream(uri);
                if (t.out != null) {
                    t.displayPath = Environment.DIRECTORY_DOWNLOADS + "/" + safe;
                    return t;
                }
            }
        }
        File dir = new File(ctx.getExternalFilesDir(null), "Nearby");
        dir.mkdirs();
        File f = new File(dir, safe);
        int i = 1;
        while (f.exists()) {
            f = new File(dir, safe + " (" + i + ")");
            i++;
        }
        t.file = f;
        t.out = new FileOutputStream(f);
        t.displayPath = f.getAbsolutePath();
        return t;
    }

    private static void cleanupTarget(Context ctx, SavedTarget t) {
        try {
            if (t.storeUri != null) {
                ctx.getContentResolver().delete(t.storeUri, null, null);
            } else if (t.file != null) {
                t.file.delete();
            }
        } catch (Exception ignore) {
        }
    }

    private static String guessMime(String name) {
        int i = name.lastIndexOf('.');
        String ext = i >= 0 ? name.substring(i + 1).toLowerCase() : "";
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime == null ? "application/octet-stream" : mime;
    }

    /** Невеликий контейнер, щоб ключ не губився у лямбдах announceControl. */
    private static final class SecretKeyAlias {
        final javax.crypto.SecretKey key;

        SecretKeyAlias(javax.crypto.SecretKey key) {
            this.key = key;
        }
    }
}
