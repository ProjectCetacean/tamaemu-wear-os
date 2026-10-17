/* SPDX-License-Identifier: GPL-3.0-or-later */
import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;

/** Local-only APK signing. Arguments contain password-file paths, never passwords. */
public final class LocalApkSigner {
    private static String fingerprint(X509Certificate certificate) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        StringBuilder text = new StringBuilder();
        for (byte value : digest) text.append(String.format("%02x", value & 0xff));
        return text.toString();
    }
    private static KeyStore load(String store, char[] password) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(Path.of(store))) { keys.load(input, password); }
        return keys;
    }
    private static void verify(String apk, String expected) throws Exception {
        ApkVerifier.Result result = new ApkVerifier.Builder(new java.io.File(apk))
            .setMinCheckedPlatformVersion(26).build().verify();
        if (!result.isVerified() || !result.isVerifiedUsingV2Scheme() || !result.isVerifiedUsingV3Scheme())
            throw new IllegalStateException("APK cryptographic verification failed: " + result.getAllErrors());
        if (result.getSignerCertificates().size() != 1 ||
            !expected.equals(fingerprint(result.getSignerCertificates().get(0))))
            throw new IllegalStateException("APK signer differs from durable local identity");
        System.out.println("Verified APK v2/v3 signatures; signer SHA-256 " + expected);
    }
    public static void main(String[] args) throws Exception {
        if (args[0].equals("verify")) { verify(args[1], args[2]); return; }
        char[] password = Files.readString(Path.of(args[2])).strip().toCharArray();
        try {
            KeyStore keys = load(args[1], password);
            X509Certificate certificate = (X509Certificate) keys.getCertificate("tamaemu");
            String identity = fingerprint(certificate);
            if (args[0].equals("identity")) { System.out.println(identity); return; }
            if (!identity.equals(args[3])) throw new IllegalStateException("Local certificate identity changed");
            PrivateKey key = (PrivateKey) keys.getKey("tamaemu", password);
            ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder("tamaemu", key,
                Collections.singletonList(certificate)).build();
            new ApkSigner.Builder(Collections.singletonList(signer))
                .setInputApk(new java.io.File(args[4])).setOutputApk(new java.io.File(args[5]))
                .setMinSdkVersion(26).setV1SigningEnabled(true).setV2SigningEnabled(true)
                .setV3SigningEnabled(true).setV4SigningEnabled(false)
                .setOtherSignersSignaturesPreserved(false).setAlignmentPreserved(true)
                .setLibraryPageAlignmentBytes(16384).setCreatedBy("Tamaemu local signing")
                .build().sign();
            verify(args[5], identity);
        } finally { Arrays.fill(password, '\0'); }
    }
}
