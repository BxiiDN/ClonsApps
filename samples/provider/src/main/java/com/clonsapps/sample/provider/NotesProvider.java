package com.clonsapps.sample.provider;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/** Provider sederhana: data (jumlah baris) disimpan di data privat aplikasi masing-masing. */
public class NotesProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences("notes", Context.MODE_PRIVATE);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor c = new MatrixCursor(new String[]{"package", "authority", "count"});
        c.addRow(new Object[]{getContext().getPackageName(), uri.getAuthority(), prefs().getInt("rows", 0)});
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        int n = prefs().getInt("rows", 0) + 1;
        prefs().edit().putInt("rows", n).commit();
        return Uri.withAppendedPath(uri, String.valueOf(n));
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        prefs().edit().putInt("rows", 0).commit();
        return 1;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.clonsapps.notes";
    }
}
