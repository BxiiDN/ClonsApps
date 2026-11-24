package com.clonsapps.engine;

/**
 * Aturan pemetaan identitas paket yang terarah. Tidak ada penggantian string secara buta:
 * hanya nilai identitas tertentu (authority, permission yang dideklarasikan aplikasi,
 * sharedUserId, taskAffinity) yang dipetakan, dan nama kelas relatif dijadikan absolut
 * terhadap paket ASLI karena kelasnya tidak dipindahkan.
 */
public final class IdentityMapper {
    public final String oldPackage;
    public final String newPackage;

    public IdentityMapper(String oldPackage, String newPackage) {
        this.oldPackage = oldPackage;
        this.newPackage = newPackage;
    }

    /** Nama kelas relatif menurut aturan Android: diawali titik, atau tanpa titik sama sekali. */
    public static boolean isRelativeClass(String name) {
        return name != null && !name.isEmpty() && (name.charAt(0) == '.' || name.indexOf('.') < 0);
    }

    public static String absolutize(String pkg, String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        if (name.charAt(0) == '.') {
            return pkg + name;
        }
        if (name.indexOf('.') < 0) {
            return pkg + "." + name;
        }
        return name;
    }

    public String absolutizeClass(String name) {
        return absolutize(oldPackage, name);
    }

    /**
     * Identitas global milik aplikasi (authority, permission, sharedUserId) yang wajib unik
     * di perangkat: awalan paket lama diganti paket baru; selain itu diberi awalan paket baru.
     */
    public String mapOwned(String value) {
        if (value == null) {
            return null;
        }
        if (value.equals(oldPackage)) {
            return newPackage;
        }
        if (value.startsWith(oldPackage + ".") || value.startsWith(oldPackage + ":")) {
            return newPackage + value.substring(oldPackage.length());
        }
        return newPackage + "." + value;
    }

    /** taskAffinity hanya dipetakan bila memang merujuk paket lama. */
    public String mapAffinity(String value) {
        if (value == null) {
            return null;
        }
        if (value.equals(oldPackage) || value.startsWith(oldPackage + ".") || value.startsWith(oldPackage + ":")) {
            return newPackage + value.substring(oldPackage.length());
        }
        return value;
    }

    public String mapAuthorities(String list) {
        if (list == null) {
            return null;
        }
        String[] parts = list.split(";");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            String t = p.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(mapOwned(t));
        }
        return sb.toString();
    }
}
