package com.sitemanager.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generates Ed25519 SSH key pairs in OpenSSH wire format using only the JDK.
 *
 * <p>Output format:
 * <ul>
 *   <li>{@link GeneratedKey#privateKeyPem} — PEM-wrapped OpenSSH v1 private key
 *       ({@code -----BEGIN OPENSSH PRIVATE KEY-----}), compatible with the
 *       {@code GIT_SSH_COMMAND -i ...} flow.</li>
 *   <li>{@link GeneratedKey#publicKeyAuthorized} — single-line authorized_keys
 *       form ({@code ssh-ed25519 AAAA... comment}) ready to paste into a git
 *       host's deploy keys page.</li>
 * </ul>
 *
 * <p>The OpenSSH key format is documented in
 * {@code PROTOCOL.key} from openssh-portable. Keys are emitted unencrypted
 * (cipher "none") so they can be loaded headlessly.
 */
final class SshKeyGenerator {

    private static final String MAGIC = "openssh-key-v1\0";
    private static final String KEY_TYPE = "ssh-ed25519";
    private static final int NONE_CIPHER_BLOCK_SIZE = 8;
    private static final int PEM_LINE_WIDTH = 70;

    private SshKeyGenerator() {}

    static final class GeneratedKey {
        final String privateKeyPem;
        final String publicKeyAuthorized;

        GeneratedKey(String privateKeyPem, String publicKeyAuthorized) {
            this.privateKeyPem = privateKeyPem;
            this.publicKeyAuthorized = publicKeyAuthorized;
        }
    }

    static GeneratedKey generate(String comment) throws GeneralSecurityException, IOException {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
        KeyPair pair = gen.generateKeyPair();
        // X.509 SubjectPublicKeyInfo for Ed25519 ends with the 32-byte raw public key.
        byte[] pubBytes = tail(pair.getPublic().getEncoded(), 32);
        // PKCS#8 PrivateKeyInfo for Ed25519 ends with the 32-byte raw seed.
        byte[] seed = tail(pair.getPrivate().getEncoded(), 32);

        String safeComment = comment == null ? "" : comment;
        return new GeneratedKey(
                formatPrivateKey(pubBytes, seed, safeComment),
                formatAuthorizedKey(pubBytes, safeComment));
    }

    private static String formatAuthorizedKey(byte[] pubBytes, String comment) throws IOException {
        ByteArrayOutputStream blob = new ByteArrayOutputStream();
        writeString(blob, KEY_TYPE.getBytes(StandardCharsets.UTF_8));
        writeString(blob, pubBytes);
        String b64 = Base64.getEncoder().encodeToString(blob.toByteArray());
        StringBuilder sb = new StringBuilder(KEY_TYPE).append(' ').append(b64);
        if (!comment.isBlank()) {
            sb.append(' ').append(comment);
        }
        return sb.toString();
    }

    private static String formatPrivateKey(byte[] pubBytes, byte[] seed, String comment) throws IOException {
        ByteArrayOutputStream outer = new ByteArrayOutputStream();
        outer.write(MAGIC.getBytes(StandardCharsets.UTF_8));
        writeString(outer, "none".getBytes(StandardCharsets.UTF_8));
        writeString(outer, "none".getBytes(StandardCharsets.UTF_8));
        writeString(outer, new byte[0]);
        writeUint32(outer, 1);

        ByteArrayOutputStream pubBlob = new ByteArrayOutputStream();
        writeString(pubBlob, KEY_TYPE.getBytes(StandardCharsets.UTF_8));
        writeString(pubBlob, pubBytes);
        writeString(outer, pubBlob.toByteArray());

        ByteArrayOutputStream privBlob = new ByteArrayOutputStream();
        int check = new SecureRandom().nextInt();
        writeUint32(privBlob, check);
        writeUint32(privBlob, check);
        writeString(privBlob, KEY_TYPE.getBytes(StandardCharsets.UTF_8));
        writeString(privBlob, pubBytes);
        byte[] privSecret = new byte[64];
        System.arraycopy(seed, 0, privSecret, 0, 32);
        System.arraycopy(pubBytes, 0, privSecret, 32, 32);
        writeString(privBlob, privSecret);
        writeString(privBlob, comment.getBytes(StandardCharsets.UTF_8));
        // PROTOCOL.key: pad with 01, 02, 03, ... up to cipher block size (8 for "none").
        int padIdx = 0;
        while ((privBlob.size() % NONE_CIPHER_BLOCK_SIZE) != 0) {
            privBlob.write(++padIdx);
        }
        writeString(outer, privBlob.toByteArray());

        String b64 = Base64.getEncoder().encodeToString(outer.toByteArray());
        StringBuilder sb = new StringBuilder("-----BEGIN OPENSSH PRIVATE KEY-----\n");
        for (int i = 0; i < b64.length(); i += PEM_LINE_WIDTH) {
            sb.append(b64, i, Math.min(i + PEM_LINE_WIDTH, b64.length())).append('\n');
        }
        sb.append("-----END OPENSSH PRIVATE KEY-----\n");
        return sb.toString();
    }

    private static byte[] tail(byte[] arr, int n) {
        byte[] out = new byte[n];
        System.arraycopy(arr, arr.length - n, out, 0, n);
        return out;
    }

    private static void writeString(ByteArrayOutputStream out, byte[] data) throws IOException {
        writeUint32(out, data.length);
        out.write(data);
    }

    private static void writeUint32(ByteArrayOutputStream out, int v) {
        out.write((v >>> 24) & 0xff);
        out.write((v >>> 16) & 0xff);
        out.write((v >>> 8) & 0xff);
        out.write(v & 0xff);
    }
}
