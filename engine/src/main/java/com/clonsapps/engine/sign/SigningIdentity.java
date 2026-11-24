package com.clonsapps.engine.sign;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Date;

/** Kunci privat + sertifikat untuk menandatangani satu clone. Clone TIDAK memakai tanda tangan pengembang asli. */
public final class SigningIdentity {
    private final PrivateKey privateKey;
    private final X509Certificate certificate;

    public SigningIdentity(PrivateKey privateKey, X509Certificate certificate) {
        this.privateKey = privateKey;
        this.certificate = certificate;
    }

    public PrivateKey privateKey() {
        return privateKey;
    }

    public X509Certificate certificate() {
        return certificate;
    }

    /** RSA 2048 baru dengan sertifikat berlaku sampai 2075 (Android tidak menegakkan masa berlaku sertifikat APK). */
    public static SigningIdentity generate(String commonName) throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        Date notBefore = new Date(System.currentTimeMillis() - 24L * 3600 * 1000);
        Date notAfter = new Date(3345062400000L); // 2076-01-01
        X509Certificate cert = CertificateBuilder.selfSigned(kp, commonName, notBefore, notAfter);
        return new SigningIdentity(kp.getPrivate(), cert);
    }

    public String certificateSha256() {
        try {
            return sha256Hex(certificate.getEncoded());
        } catch (CertificateEncodingException e) {
            return "?";
        }
    }

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(64);
            for (byte x : d) {
                sb.append(String.format("%02x", x & 0xFF));
            }
            return sb.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
