package com.clonsapps.engine;

/** Pengaturan clone dari pengguna. Field null berarti "pertahankan dari aplikasi asli". */
public final class CloneSpec {
    public final String newPackage;
    public final String newLabel;
    public final byte[] iconPng;
    public final String outputName;

    public CloneSpec(String newPackage, String newLabel, byte[] iconPng, String outputName) {
        this.newPackage = newPackage;
        this.newLabel = newLabel;
        this.iconPng = iconPng;
        this.outputName = outputName;
    }
}
