package com.clonsapps.engine.sign;

import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import com.clonsapps.engine.CloneException;
import com.clonsapps.engine.ErrorCode;

import java.io.File;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Penandatanganan dan verifikasi memakai apksig (com.android.tools.build:apksig, Apache-2.0),
 * pustaka Java murni yang juga dipakai apksigner resmi dan berjalan di Android.
 * Alignment sudah dilakukan sebelum tahap ini; apksig diminta mempertahankannya.
 */
public final class ApkSigning {
    private ApkSigning() {
    }

    public static void sign(File unsignedApk, File outApk, SigningIdentity id, int minSdk) throws CloneException {
        try {
            ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder(
                    "CLONAPPS", id.privateKey(), Collections.singletonList(id.certificate())).build();
            new ApkSigner.Builder(Collections.singletonList(signer))
                    .setInputApk(unsignedApk)
                    .setOutputApk(outApk)
                    .setMinSdkVersion(Math.max(1, minSdk))
                    .setV1SigningEnabled(minSdk < 24)
                    .setV2SigningEnabled(true)
                    .setV3SigningEnabled(true)
                    .setAlignmentPreserved(true)
                    .build()
                    .sign();
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            if (msg.contains("ENOSPC") || msg.toLowerCase().contains("no space left")) {
                throw new CloneException(ErrorCode.STORAGE_FULL, "saat menandatangani " + outApk.getName(), e);
            }
            throw new CloneException(ErrorCode.SIGNING_FAILED, outApk.getName() + ": " + e.getClass().getSimpleName() + ": " + msg, e);
        }
    }

    public static final class Verification {
        public boolean verified;
        public boolean v1;
        public boolean v2;
        public boolean v3;
        public final List<String> errors = new ArrayList<>();
        public final List<X509Certificate> signers = new ArrayList<>();
    }

    public static Verification verify(File apk) {
        Verification v = new Verification();
        try {
            ApkVerifier.Result r = new ApkVerifier.Builder(apk).build().verify();
            v.verified = r.isVerified();
            v.v1 = r.isVerifiedUsingV1Scheme();
            v.v2 = r.isVerifiedUsingV2Scheme();
            v.v3 = r.isVerifiedUsingV3Scheme();
            for (ApkVerifier.IssueWithParams i : r.getErrors()) {
                v.errors.add(i.toString());
            }
            for (ApkVerifier.Result.V2SchemeSignerInfo s : r.getV2SchemeSigners()) {
                for (ApkVerifier.IssueWithParams i : s.getErrors()) {
                    v.errors.add("v2: " + i);
                }
            }
            v.signers.addAll(r.getSignerCertificates());
        } catch (Exception e) {
            v.verified = false;
            v.errors.add(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return v;
    }
}
