package com.clonsapps.engine;

/** Kegagalan yang diketahui penyebabnya. {@link #detail()} berisi fakta spesifik (nama entri, nilai, ukuran). */
public final class CloneException extends Exception {
    private final ErrorCode code;
    private final String detail;

    public CloneException(ErrorCode code, String detail) {
        super(code.name() + (detail == null ? "" : ": " + detail));
        this.code = code;
        this.detail = detail;
    }

    public CloneException(ErrorCode code, String detail, Throwable cause) {
        super(code.name() + (detail == null ? "" : ": " + detail), cause);
        this.code = code;
        this.detail = detail;
    }

    public ErrorCode code() {
        return code;
    }

    public String detail() {
        return detail;
    }

    public String userMessage() {
        return detail == null ? code.message : code.message + " Detail: " + detail;
    }

    public String action() {
        return code.action;
    }
}
