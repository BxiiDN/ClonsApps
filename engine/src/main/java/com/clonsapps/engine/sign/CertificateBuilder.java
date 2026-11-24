package com.clonsapps.engine.sign;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Membuat sertifikat X.509 v3 self-signed (SHA256withRSA) dengan encoder DER minimal,
 * tanpa pustaka tambahan. Dipakai untuk kunci penandatanganan clone yang dibuat di perangkat.
 */
public final class CertificateBuilder {
    private CertificateBuilder() {
    }

    private static final byte[] OID_SHA256_RSA = {0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B};
    private static final byte[] OID_CN = {0x55, 0x04, 0x03};
    private static final byte[] OID_O = {0x55, 0x04, 0x0A};

    public static X509Certificate selfSigned(KeyPair kp, String commonName, Date notBefore, Date notAfter)
            throws GeneralSecurityException {
        byte[] algId = seq(cat(tlv(0x06, OID_SHA256_RSA), new byte[]{0x05, 0x00}));
        byte[] name = seq(cat(
                set(seq(cat(tlv(0x06, OID_O), tlv(0x0C, "ClonApps".getBytes(StandardCharsets.UTF_8))))),
                set(seq(cat(tlv(0x06, OID_CN), tlv(0x0C, commonName.getBytes(StandardCharsets.UTF_8)))))));
        byte[] serial = new BigInteger(63, new SecureRandom()).add(BigInteger.ONE).toByteArray();
        byte[] tbs = seq(cat(
                tlv(0xA0, tlv(0x02, new byte[]{0x02})),
                tlv(0x02, serial),
                algId,
                name,
                seq(cat(time(notBefore), time(notAfter))),
                name,
                kp.getPublic().getEncoded()));
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bitString = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bitString, 1, sig.length);
        byte[] cert = seq(cat(tbs, algId, tlv(0x03, bitString)));
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(cert));
    }

    private static byte[] time(Date d) {
        SimpleDateFormat utc = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        utc.setTimeZone(TimeZone.getTimeZone("UTC"));
        SimpleDateFormat gen = new SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US);
        gen.setTimeZone(TimeZone.getTimeZone("UTC"));
        SimpleDateFormat year = new SimpleDateFormat("yyyy", Locale.US);
        year.setTimeZone(TimeZone.getTimeZone("UTC"));
        int y = Integer.parseInt(year.format(d));
        if (y >= 1950 && y < 2050) {
            return tlv(0x17, utc.format(d).getBytes(StandardCharsets.US_ASCII));
        }
        return tlv(0x18, gen.format(d).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] seq(byte[] content) {
        return tlv(0x30, content);
    }

    private static byte[] set(byte[] content) {
        return tlv(0x31, content);
    }

    static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(content.length + 6);
        o.write(tag);
        int len = content.length;
        if (len < 0x80) {
            o.write(len);
        } else if (len < 0x100) {
            o.write(0x81);
            o.write(len);
        } else if (len < 0x10000) {
            o.write(0x82);
            o.write(len >>> 8);
            o.write(len);
        } else {
            o.write(0x83);
            o.write(len >>> 16);
            o.write(len >>> 8);
            o.write(len);
        }
        o.write(content, 0, content.length);
        return o.toByteArray();
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            o.write(p, 0, p.length);
        }
        return o.toByteArray();
    }
}
