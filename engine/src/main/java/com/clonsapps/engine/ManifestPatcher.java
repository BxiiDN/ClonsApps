package com.clonsapps.engine;

import com.clonsapps.engine.res.AndroidAttrs;
import com.clonsapps.engine.res.Axml;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Perubahan manifest yang terarah untuk identitas paket baru. Setiap perubahan dicatat
 * ke daftar {@code changes} agar terlihat di tahap Periksa dan di riwayat.
 */
public final class ManifestPatcher {
    private ManifestPatcher() {
    }

    /** Atribut berisi nama kelas yang diselesaikan Android relatif terhadap paket manifest. */
    private static final int[] CLASS_ATTRS = {
            AndroidAttrs.NAME, AndroidAttrs.TARGET_ACTIVITY, AndroidAttrs.BACKUP_AGENT,
            AndroidAttrs.MANAGE_SPACE_ACTIVITY, AndroidAttrs.PARENT_ACTIVITY_NAME, AndroidAttrs.APP_COMPONENT_FACTORY
    };
    private static final String[] CLASS_ATTR_NAMES = {
            "name", "targetActivity", "backupAgent", "manageSpaceActivity", "parentActivityName", "appComponentFactory"
    };
    private static final Set<String> COMPONENT_TAGS = new HashSet<>(Arrays.asList(
            "activity", "activity-alias", "service", "receiver", "provider"));
    private static final int[] PERMISSION_ATTRS = {
            AndroidAttrs.PERMISSION, AndroidAttrs.READ_PERMISSION, AndroidAttrs.WRITE_PERMISSION
    };
    private static final String[] PERMISSION_ATTR_NAMES = {"permission", "readPermission", "writePermission"};

    public static void patch(Axml doc, IdentityMapper map, Set<String> declaredPermissions, String newLabel,
                             List<String> changes) throws CloneException {
        Axml.Element root = doc.root();
        Axml.Attr pkg = root.plain("package");
        if (pkg == null) {
            throw new CloneException(ErrorCode.BAD_MANIFEST, "atribut package tidak ada");
        }
        String old = pkg.stringValue();
        if (!map.oldPackage.equals(old)) {
            throw new CloneException(ErrorCode.SPLIT_PACKAGE_MISMATCH, old + " ≠ " + map.oldPackage);
        }
        pkg.setString(map.newPackage);
        String splitName = ManifestInfo.str(root.plain("split"));
        String where = splitName == null ? "base" : "split " + splitName;
        changes.add("[" + where + "] package: " + old + " → " + map.newPackage);

        Axml.Attr shared = root.android(AndroidAttrs.SHARED_USER_ID, "sharedUserId");
        if (shared != null && shared.stringValue() != null) {
            String nv = map.mapOwned(shared.stringValue());
            changes.add("[" + where + "] sharedUserId: " + shared.stringValue() + " → " + nv);
            shared.setString(nv);
        }

        for (Axml.Element e : doc.elements()) {
            String n = e.name;
            boolean isApp = "application".equals(n) && e.parent == root;
            boolean isComp = ManifestInfo.underApplication(e) && COMPONENT_TAGS.contains(n);
            boolean isInstr = "instrumentation".equals(n) && e.parent == root;

            if (isApp || isComp || isInstr) {
                // Nama alias juga dijadikan absolut terhadap paket lama, persis seperti Android menyelesaikannya
                // pada aplikasi asli, sehingga targetActivity tetap menunjuk aktivitas yang sama.
                for (int i = 0; i < CLASS_ATTRS.length; i++) {
                    Axml.Attr a = e.android(CLASS_ATTRS[i], CLASS_ATTR_NAMES[i]);
                    if (a != null && a.type == Axml.TYPE_STRING && IdentityMapper.isRelativeClass(a.str)) {
                        String abs = map.absolutizeClass(a.str);
                        changes.add("[" + where + "] <" + n + "> " + CLASS_ATTR_NAMES[i] + ": " + a.str + " → " + abs);
                        a.setString(abs);
                    }
                }
            }
            if (isComp || isApp) {
                Axml.Attr aff = e.android(AndroidAttrs.TASK_AFFINITY, "taskAffinity");
                if (aff != null && aff.type == Axml.TYPE_STRING) {
                    String nv = map.mapAffinity(aff.str);
                    if (!nv.equals(aff.str)) {
                        changes.add("[" + where + "] <" + n + "> taskAffinity: " + aff.str + " → " + nv);
                        aff.setString(nv);
                    }
                }
            }
            if (isComp && "provider".equals(n)) {
                Axml.Attr auth = e.android(AndroidAttrs.AUTHORITIES, "authorities");
                if (auth != null && auth.stringValue() != null) {
                    String nv = map.mapAuthorities(auth.stringValue());
                    changes.add("[" + where + "] authority: " + auth.stringValue() + " → " + nv);
                    auth.setString(nv);
                }
            }
            if (isInstr) {
                Axml.Attr tp = e.android(AndroidAttrs.TARGET_PACKAGE, "targetPackage");
                if (tp != null && map.oldPackage.equals(tp.stringValue())) {
                    tp.setString(map.newPackage);
                    changes.add("[" + where + "] instrumentation targetPackage → " + map.newPackage);
                }
            }
            // Permission yang DIDEKLARASIKAN aplikasi wajib unik per penanda tangan.
            if (e.parent == root && ("permission".equals(n) || "permission-group".equals(n) || "permission-tree".equals(n))) {
                Axml.Attr name = e.android(AndroidAttrs.NAME, "name");
                if (name != null && name.stringValue() != null) {
                    String nv = map.mapOwned(name.stringValue());
                    changes.add("[" + where + "] <" + n + "> " + name.stringValue() + " → " + nv);
                    name.setString(nv);
                }
                Axml.Attr group = e.android(AndroidAttrs.PERMISSION_GROUP, "permissionGroup");
                if (group != null && group.stringValue() != null && group.stringValue().startsWith(map.oldPackage + ".")) {
                    group.setString(map.mapOwned(group.stringValue()));
                }
                continue;
            }
            if (e.parent == root && n.startsWith("uses-permission")) {
                Axml.Attr name = e.android(AndroidAttrs.NAME, "name");
                if (name != null && declaredPermissions.contains(name.stringValue())) {
                    String nv = map.mapOwned(name.stringValue());
                    changes.add("[" + where + "] <" + n + "> " + name.stringValue() + " → " + nv);
                    name.setString(nv);
                }
                continue;
            }
            // Atribut permission pada application/komponen/path-permission yang merujuk permission milik aplikasi.
            if (isInsideApplication(e, root)) {
                for (int i = 0; i < PERMISSION_ATTRS.length; i++) {
                    Axml.Attr a = e.android(PERMISSION_ATTRS[i], PERMISSION_ATTR_NAMES[i]);
                    if (a != null && a.stringValue() != null && declaredPermissions.contains(a.stringValue())) {
                        String nv = map.mapOwned(a.stringValue());
                        changes.add("[" + where + "] <" + n + "> " + PERMISSION_ATTR_NAMES[i] + ": " + a.stringValue() + " → " + nv);
                        a.setString(nv);
                    }
                }
            }
        }

        if (newLabel != null) {
            for (Axml.Element e : doc.elements()) {
                boolean isApp = "application".equals(e.name) && e.parent == root;
                boolean isLauncher = ManifestInfo.underApplication(e)
                        && ("activity".equals(e.name) || "activity-alias".equals(e.name))
                        && ManifestInfo.isLauncher(e);
                if (!isApp && !isLauncher) {
                    continue;
                }
                Axml.Attr label = e.android(AndroidAttrs.LABEL, "label");
                if (label == null && isApp) {
                    label = e.addAndroid("label", AndroidAttrs.LABEL);
                }
                if (label != null) {
                    label.setString(newLabel);
                    changes.add("[" + where + "] <" + e.name + "> label → \"" + newLabel + "\"");
                }
            }
        }
    }

    private static boolean isInsideApplication(Axml.Element e, Axml.Element root) {
        for (Axml.Element p = e; p != null; p = p.parent) {
            if ("application".equals(p.name) && p.parent == root) {
                return true;
            }
            if ("queries".equals(p.name)) {
                return false;
            }
        }
        return false;
    }

    /**
     * File XML resource yang dirujuk meta-data (shortcut statis, sync adapter, searchable):
     * hanya targetPackage paket lama dan authority yang sudah dipetakan yang diubah.
     */
    public static int patchXmlResource(Axml doc, IdentityMapper map, Set<String> oldAuthorities) {
        int changed = 0;
        for (Axml.Element e : doc.elements()) {
            for (Axml.Attr a : e.attrs) {
                if (a.type != Axml.TYPE_STRING || a.str == null) {
                    continue;
                }
                boolean androidNs = Axml.ANDROID_NS.equals(a.ns);
                if (!androidNs) {
                    continue;
                }
                boolean targetPkg = a.resId == AndroidAttrs.TARGET_PACKAGE || "targetPackage".equals(a.name);
                if (targetPkg && map.oldPackage.equals(a.str)) {
                    a.setString(map.newPackage);
                    changed++;
                    continue;
                }
                boolean authorityAttr = "contentAuthority".equals(a.name) || "searchSuggestAuthority".equals(a.name)
                        || "authorities".equals(a.name);
                if (authorityAttr && oldAuthorities.contains(a.str)) {
                    a.setString(map.mapOwned(a.str));
                    changed++;
                }
            }
        }
        return changed;
    }
}
