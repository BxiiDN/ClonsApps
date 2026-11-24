package com.clonsapps.engine;

/** Temuan pemeriksaan: penyebab spesifik + tindakan. ERROR menghentikan proses. */
public final class Issue {
    public enum Severity { ERROR, WARNING, INFO }

    public final Severity severity;
    public final String code;
    public final String title;
    public final String detail;
    public final String action;

    public Issue(Severity severity, String code, String title, String detail, String action) {
        this.severity = severity;
        this.code = code;
        this.title = title;
        this.detail = detail;
        this.action = action;
    }

    public static Issue error(ErrorCode c, String detail) {
        return new Issue(Severity.ERROR, c.name(), c.message, detail, c.action);
    }

    public static Issue warn(String code, String title, String detail, String action) {
        return new Issue(Severity.WARNING, code, title, detail, action);
    }

    public static Issue info(String code, String title, String detail) {
        return new Issue(Severity.INFO, code, title, detail, null);
    }

    @Override
    public String toString() {
        return severity + " " + code + ": " + title + (detail == null ? "" : " — " + detail);
    }
}
