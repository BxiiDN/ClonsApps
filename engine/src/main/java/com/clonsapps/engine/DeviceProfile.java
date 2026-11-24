package com.clonsapps.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Fakta perangkat yang dibutuhkan pemeriksaan kompatibilitas (diisi dari Build.* di Android). */
public final class DeviceProfile {
    public final int sdkInt;
    public final List<String> supportedAbis;

    public DeviceProfile(int sdkInt, List<String> supportedAbis) {
        this.sdkInt = sdkInt;
        this.supportedAbis = new ArrayList<>(supportedAbis);
    }

    public static DeviceProfile of(int sdkInt, String... abis) {
        return new DeviceProfile(sdkInt, Arrays.asList(abis));
    }

    /** Nama split konfigurasi ABI memakai garis bawah: config.arm64_v8a. */
    public static String abiFromSplit(String splitName) {
        if (splitName == null) {
            return null;
        }
        String s = splitName;
        int dot = s.lastIndexOf('.');
        String last = dot >= 0 ? s.substring(dot + 1) : s;
        switch (last) {
            case "arm64_v8a":
                return "arm64-v8a";
            case "armeabi_v7a":
                return "armeabi-v7a";
            case "armeabi":
                return "armeabi";
            case "x86":
                return "x86";
            case "x86_64":
                return "x86_64";
            case "mips":
                return "mips";
            case "mips64":
                return "mips64";
            case "riscv64":
                return "riscv64";
            default:
                return null;
        }
    }
}
