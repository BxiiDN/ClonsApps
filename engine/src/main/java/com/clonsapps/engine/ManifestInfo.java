package com.clonsapps.engine;

import com.clonsapps.engine.res.AndroidAttrs;
import com.clonsapps.engine.res.Axml;

import java.util.ArrayList;
import java.util.List;

/** Informasi yang dibaca dari AndroidManifest.xml biner (base maupun split). */
public final class ManifestInfo {
    public String packageName;
    public long versionCode;
    public String versionName;
    public int minSdk = 1;
    public int targetSdk;
    public String split;
    public boolean featureSplit;
    public String configForSplit;
    public boolean splitRequired;
    public int labelRes;
    public String labelText;
    public int iconRes;
    public int roundIconRes;
    public String sharedUserId;
    public Boolean extractNativeLibs;
    public boolean debuggable;
    public boolean testOnly;
    public boolean hasCode = true;
    public final List<String> authorities = new ArrayList<>();
    public final List<String> declaredPermissions = new ArrayList<>();
    public final List<String> usedPermissions = new ArrayList<>();
    public final List<String> relativeComponents = new ArrayList<>();
    public final List<String> launcherActivities = new ArrayList<>();
    public final List<Integer> launcherIconRes = new ArrayList<>();
    public final List<String> metaDataNames = new ArrayList<>();
    /** ID resource XML yang dirujuk meta-data (shortcuts, sync adapter, searchable, dll.). */
    public final List<Integer> metaDataXmlRes = new ArrayList<>();
    public int components;

    public boolean isBase() {
        return split == null || split.isEmpty();
    }

    static boolean underApplication(Axml.Element e) {
        return e.parent != null && "application".equals(e.parent.name)
                && e.parent.parent != null && e.parent.parent.parent == null;
    }

    static String str(Axml.Attr a) {
        return a == null ? null : a.stringValue();
    }

    static boolean bool(Axml.Attr a, boolean def) {
        if (a == null) {
            return def;
        }
        if (a.type == Axml.TYPE_BOOLEAN || a.isInt()) {
            return a.data != 0;
        }
        if (a.type == Axml.TYPE_STRING) {
            return "true".equalsIgnoreCase(a.str);
        }
        return def;
    }

    static int intVal(Axml.Attr a, int def) {
        if (a == null) {
            return def;
        }
        if (a.isInt()) {
            return a.data;
        }
        String s = a.stringValue();
        if (s != null) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return 10000; // codename pratinjau
            }
        }
        return def;
    }

    public static ManifestInfo read(Axml doc) throws CloneException {
        Axml.Element root = doc.root();
        if (root == null || !"manifest".equals(root.name)) {
            throw new CloneException(ErrorCode.BAD_MANIFEST, "elemen akar bukan <manifest>");
        }
        ManifestInfo m = new ManifestInfo();
        m.packageName = str(root.plain("package"));
        if (m.packageName == null || m.packageName.isEmpty()) {
            throw new CloneException(ErrorCode.BAD_MANIFEST, "atribut package tidak ada");
        }
        Axml.Attr vc = root.android(AndroidAttrs.VERSION_CODE, "versionCode");
        m.versionCode = vc == null ? 0 : (vc.isInt() ? (vc.data & 0xFFFFFFFFL) : intVal(vc, 0));
        m.versionName = str(root.android(AndroidAttrs.VERSION_NAME, "versionName"));
        m.split = str(root.plain("split"));
        m.configForSplit = str(root.plain("configForSplit"));
        m.featureSplit = bool(root.android(AndroidAttrs.IS_FEATURE_SPLIT, "isFeatureSplit"), false);
        m.splitRequired = bool(root.android(AndroidAttrs.IS_SPLIT_REQUIRED, "isSplitRequired"), false);
        m.sharedUserId = str(root.android(AndroidAttrs.SHARED_USER_ID, "sharedUserId"));
        String pkg = m.packageName;
        for (Axml.Element e : doc.elements()) {
            String n = e.name;
            if ("uses-sdk".equals(n) && e.parent == root) {
                m.minSdk = intVal(e.android(AndroidAttrs.MIN_SDK_VERSION, "minSdkVersion"), 1);
                m.targetSdk = intVal(e.android(AndroidAttrs.TARGET_SDK_VERSION, "targetSdkVersion"), m.minSdk);
            } else if ("application".equals(n) && e.parent == root) {
                Axml.Attr label = e.android(AndroidAttrs.LABEL, "label");
                if (label != null) {
                    if (label.isReference()) {
                        m.labelRes = label.data;
                    } else {
                        m.labelText = label.stringValue();
                    }
                }
                Axml.Attr icon = e.android(AndroidAttrs.ICON, "icon");
                if (icon != null && icon.isReference()) {
                    m.iconRes = icon.data;
                }
                Axml.Attr round = e.android(AndroidAttrs.ROUND_ICON, "roundIcon");
                if (round != null && round.isReference()) {
                    m.roundIconRes = round.data;
                }
                m.extractNativeLibs = e.android(AndroidAttrs.EXTRACT_NATIVE_LIBS, "extractNativeLibs") == null
                        ? null : bool(e.android(AndroidAttrs.EXTRACT_NATIVE_LIBS, "extractNativeLibs"), true);
                m.debuggable = bool(e.android(AndroidAttrs.DEBUGGABLE, "debuggable"), false);
                m.testOnly = bool(e.android(AndroidAttrs.TEST_ONLY, "testOnly"), false);
                m.hasCode = bool(e.android(AndroidAttrs.HAS_CODE, "hasCode"), true);
                String appName = str(e.android(AndroidAttrs.NAME, "name"));
                if (IdentityMapper.isRelativeClass(appName)) {
                    m.relativeComponents.add(appName);
                }
            } else if (("permission".equals(n) || "permission-group".equals(n) || "permission-tree".equals(n)) && e.parent == root) {
                String pn = str(e.android(AndroidAttrs.NAME, "name"));
                if (pn != null && "permission".equals(n)) {
                    m.declaredPermissions.add(pn);
                }
            } else if (n.startsWith("uses-permission") && e.parent == root) {
                String pn = str(e.android(AndroidAttrs.NAME, "name"));
                if (pn != null) {
                    m.usedPermissions.add(pn);
                }
            } else if (underApplication(e) && isComponent(n)) {
                m.components++;
                String cn = str(e.android(AndroidAttrs.NAME, "name"));
                if (IdentityMapper.isRelativeClass(cn)) {
                    m.relativeComponents.add(cn);
                }
                if ("provider".equals(n)) {
                    String auth = str(e.android(AndroidAttrs.AUTHORITIES, "authorities"));
                    if (auth != null) {
                        for (String a : auth.split(";")) {
                            if (!a.trim().isEmpty()) {
                                m.authorities.add(a.trim());
                            }
                        }
                    }
                }
                if (("activity".equals(n) || "activity-alias".equals(n)) && isLauncher(e)) {
                    m.launcherActivities.add(cn == null ? "?" : IdentityMapper.absolutize(pkg, cn));
                    Axml.Attr icon = e.android(AndroidAttrs.ICON, "icon");
                    if (icon != null && icon.isReference() && icon.data != 0) {
                        m.launcherIconRes.add(icon.data);
                    }
                    Axml.Attr round = e.android(AndroidAttrs.ROUND_ICON, "roundIcon");
                    if (round != null && round.isReference() && round.data != 0) {
                        m.launcherIconRes.add(round.data);
                    }
                }
            } else if ("meta-data".equals(n) && e.parent != null && (underApplication(e.parent) || "application".equals(e.parent.name))) {
                String mn = str(e.android(AndroidAttrs.NAME, "name"));
                if (mn != null) {
                    m.metaDataNames.add(mn);
                }
                Axml.Attr res = e.android(AndroidAttrs.RESOURCE, "resource");
                if (res != null && res.isReference() && res.data != 0 && !m.metaDataXmlRes.contains(res.data)) {
                    m.metaDataXmlRes.add(res.data);
                }
            }
        }
        return m;
    }

    static boolean isComponent(String n) {
        return "activity".equals(n) || "activity-alias".equals(n) || "service".equals(n)
                || "receiver".equals(n) || "provider".equals(n);
    }

    static boolean isLauncher(Axml.Element activity) {
        for (Axml.Element f : activity.children) {
            if (!"intent-filter".equals(f.name)) {
                continue;
            }
            boolean main = false;
            boolean launcher = false;
            for (Axml.Element c : f.children) {
                String v = str(c.android(AndroidAttrs.NAME, "name"));
                if ("action".equals(c.name) && "android.intent.action.MAIN".equals(v)) {
                    main = true;
                }
                if ("category".equals(c.name) && ("android.intent.category.LAUNCHER".equals(v)
                        || "android.intent.category.LEANBACK_LAUNCHER".equals(v))) {
                    launcher = true;
                }
            }
            if (main && launcher) {
                return true;
            }
        }
        return false;
    }
}
