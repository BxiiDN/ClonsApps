package com.clonsapps.engine;

/** Sinyal pembatalan yang diperiksa mesin di setiap langkah dan di dalam perulangan penyalinan data. */
public interface CancelToken {
    boolean isCancelled();

    CancelToken NONE = new CancelToken() {
        @Override
        public boolean isCancelled() {
            return false;
        }
    };

    static void check(CancelToken token) throws CloneException {
        if (token != null && token.isCancelled()) {
            throw new CloneException(ErrorCode.CANCELLED, null);
        }
    }
}
