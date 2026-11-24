package com.clonsapps.engine;

import java.util.regex.Pattern;

/** Validasi package name, nama aplikasi, dan nama file hasil. */
public final class PackageNames {
    private PackageNames() {
    }

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private static final Pattern FILE_NAME = Pattern.compile("[\\p{L}\\p{N} ._()\\-]{1,80}");
    public static final int MAX_LENGTH = 127;

    /** @return null bila valid, atau kode kegagalan. */
    public static ErrorCode check(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return ErrorCode.INVALID_PACKAGE_NAME;
        }
        if (pkg.length() > MAX_LENGTH) {
            return ErrorCode.PACKAGE_TOO_LONG;
        }
        String[] parts = pkg.split("\\.", -1);
        if (parts.length < 2) {
            return ErrorCode.INVALID_PACKAGE_NAME;
        }
        for (String p : parts) {
            if (!SEGMENT.matcher(p).matches()) {
                return ErrorCode.INVALID_PACKAGE_NAME;
            }
        }
        return null;
    }

    public static void require(String pkg) throws CloneException {
        ErrorCode c = check(pkg);
        if (c != null) {
            throw new CloneException(c, "\"" + pkg + "\"");
        }
    }

    public static boolean validLabel(String label) {
        return label != null && !label.trim().isEmpty() && label.length() <= 100;
    }

    public static boolean validFileName(String name) {
        return name != null && FILE_NAME.matcher(name).matches() && !name.startsWith(".") && !name.contains("..");
    }

    public static String sanitizeFileName(String s) {
        if (s == null) {
            return "clone";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == ' ' || c == '.' || c == '_' || c == '-' || c == '(' || c == ')') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String r = sb.toString().trim();
        while (r.startsWith(".")) {
            r = r.substring(1);
        }
        if (r.length() > 80) {
            r = r.substring(0, 80).trim();
        }
        return r.isEmpty() ? "clone" : r;
    }

    /** Saran package clone ke-n: com.contoh.app → com.contoh.app.clone2. */
    public static String suggest(String original, int n) {
        String base = original + ".clone" + n;
        return base.length() <= MAX_LENGTH ? base : "app.clone" + n + "." + Integer.toHexString(original.hashCode() & 0x7fffffff);
    }
}
