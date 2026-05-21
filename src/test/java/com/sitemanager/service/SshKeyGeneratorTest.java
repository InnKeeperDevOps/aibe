package com.sitemanager.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class SshKeyGeneratorTest {

    @Test
    void generate_returnsAuthorizedKeyFormatAndPrivatePem() throws Exception {
        SshKeyGenerator.GeneratedKey key = SshKeyGenerator.generate("test@aibe");

        assertNotNull(key.publicKeyAuthorized);
        assertNotNull(key.privateKeyPem);

        assertTrue(key.publicKeyAuthorized.startsWith("ssh-ed25519 "),
                "public key should start with ssh-ed25519");
        assertTrue(key.publicKeyAuthorized.endsWith(" test@aibe"),
                "public key should end with comment");

        assertTrue(key.privateKeyPem.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----\n"),
                "private key PEM should have OpenSSH header");
        assertTrue(key.privateKeyPem.trim().endsWith("-----END OPENSSH PRIVATE KEY-----"),
                "private key PEM should have OpenSSH footer");
    }

    @Test
    void generate_publicKeyEmbedsSameKeyAsPrivateBlob() throws Exception {
        SshKeyGenerator.GeneratedKey key = SshKeyGenerator.generate("test@aibe");

        // Authorized-keys form: "ssh-ed25519 <base64-blob> <comment>"
        String[] parts = key.publicKeyAuthorized.split(" ");
        assertEquals(3, parts.length);
        byte[] pubBlob = Base64.getDecoder().decode(parts[1]);
        byte[] pubBytes = extractEd25519PublicKeyFromAuthorizedBlob(pubBlob);
        assertEquals(32, pubBytes.length);

        // OpenSSH v1 private key body
        String pem = key.privateKeyPem;
        String body = pem
                .replace("-----BEGIN OPENSSH PRIVATE KEY-----", "")
                .replace("-----END OPENSSH PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] outer = Base64.getDecoder().decode(body);

        byte[] magic = "openssh-key-v1\0".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < magic.length; i++) {
            assertEquals(magic[i], outer[i], "magic mismatch at offset " + i);
        }

        // Confirm the same 32-byte ed25519 public key appears inside the private blob.
        // The blob is structured, but searching for the exact 32-byte sequence is enough
        // to assert the public key matches what we exposed in authorized-keys form.
        assertTrue(containsSubsequence(outer, pubBytes),
                "private key blob should embed the exposed public key bytes");
    }

    @Test
    void generate_emitsDifferentKeysOnEachCall() throws Exception {
        SshKeyGenerator.GeneratedKey k1 = SshKeyGenerator.generate("test@aibe");
        SshKeyGenerator.GeneratedKey k2 = SshKeyGenerator.generate("test@aibe");

        assertNotEquals(k1.publicKeyAuthorized, k2.publicKeyAuthorized);
        assertNotEquals(k1.privateKeyPem, k2.privateKeyPem);
    }

    @Test
    void generate_paddingAlignsPrivateBlobToCipherBlockSize() throws Exception {
        // PROTOCOL.key requires the unencrypted private-key list to be padded to the
        // cipher block size (8 bytes for the "none" cipher) with 01, 02, 03, ...
        SshKeyGenerator.GeneratedKey key = SshKeyGenerator.generate("test@aibe");
        byte[] outer = Base64.getDecoder().decode(key.privateKeyPem
                .replace("-----BEGIN OPENSSH PRIVATE KEY-----", "")
                .replace("-----END OPENSSH PRIVATE KEY-----", "")
                .replaceAll("\\s+", ""));

        ByteArrayInputStream in = new ByteArrayInputStream(outer);
        // skip magic
        in.skip("openssh-key-v1\0".length());
        readString(in); // ciphername
        readString(in); // kdfname
        readString(in); // kdfoptions
        readUint32(in); // numkeys
        readString(in); // pub blob
        byte[] privBlob = readString(in);

        assertEquals(0, privBlob.length % 8,
                "private blob length must be a multiple of 8 (cipher block size for 'none')");
    }

    private static byte[] extractEd25519PublicKeyFromAuthorizedBlob(byte[] blob) {
        ByteArrayInputStream in = new ByteArrayInputStream(blob);
        byte[] typeBytes = readString(in);
        assertEquals("ssh-ed25519", new String(typeBytes, StandardCharsets.UTF_8));
        return readString(in);
    }

    private static byte[] readString(ByteArrayInputStream in) {
        int len = readUint32(in);
        byte[] data = new byte[len];
        int read = in.read(data, 0, len);
        assertEquals(len, read);
        return data;
    }

    private static int readUint32(ByteArrayInputStream in) {
        int b0 = in.read();
        int b1 = in.read();
        int b2 = in.read();
        int b3 = in.read();
        return (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
    }

    private static boolean containsSubsequence(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
