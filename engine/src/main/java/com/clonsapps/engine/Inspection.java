package com.clonsapps.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Hasil tahap Periksa: info aplikasi, temuan, dan rencana perubahan identitas. */
public final class Inspection {
    public ManifestInfo base;
    public final List<ManifestInfo> splits = new ArrayList<>();
    public final List<Issue> issues = new ArrayList<>();
    public final Set<String> nativeAbis = new TreeSet<>();
    public int dexCount;
    public long totalSize;
    public String label;
    public final Map<String, String> authorityMap = new LinkedHashMap<>();
    public final Map<String, String> permissionMap = new LinkedHashMap<>();
    public final List<Integer> iconResIds = new ArrayList<>();

    public boolean hasErrors() {
        for (Issue i : issues) {
            if (i.severity == Issue.Severity.ERROR) {
                return true;
            }
        }
        return false;
    }

    public Issue firstError() {
        for (Issue i : issues) {
            if (i.severity == Issue.Severity.ERROR) {
                return i;
            }
        }
        return null;
    }

    public List<String> splitNames() {
        List<String> l = new ArrayList<>();
        for (ManifestInfo s : splits) {
            l.add(s.split);
        }
        return l;
    }
}
