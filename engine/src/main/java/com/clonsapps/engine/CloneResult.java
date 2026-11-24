package com.clonsapps.engine;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Hasil clone yang sudah ditandatangani DAN lolos verifikasi. */
public final class CloneResult {
    public static final class Output {
        public final File file;
        public final String splitName;
        public final String sha256;
        public final long size;

        public Output(File file, String splitName, String sha256, long size) {
            this.file = file;
            this.splitName = splitName;
            this.sha256 = sha256;
            this.size = size;
        }
    }

    public String oldPackage;
    public String newPackage;
    public String label;
    public long versionCode;
    public String versionName;
    public String certSha256;
    public String signatureSchemes;
    public final List<Output> outputs = new ArrayList<>();
    public final List<String> changes = new ArrayList<>();
    public final List<Issue> warnings = new ArrayList<>();

    public List<File> files() {
        List<File> l = new ArrayList<>();
        for (Output o : outputs) {
            l.add(o.file);
        }
        return l;
    }
}
