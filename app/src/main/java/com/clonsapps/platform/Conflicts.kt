package com.clonsapps.platform

import android.content.Context
import com.clonsapps.engine.ErrorCode
import com.clonsapps.engine.PackageNames

/** Status package name tujuan di perangkat ini. */
sealed class PackageStatus {
    object Free : PackageStatus()
    data class Invalid(val code: ErrorCode) : PackageStatus()
    object SameAsOriginal : PackageStatus()
    /** Clone buatan ClonApps dengan kunci yang sama: akan diperbarui, data dipertahankan. */
    data class OurClone(val label: String) : PackageStatus()
    /** Terpasang dengan tanda tangan lain: ClonApps tidak akan menimpanya. */
    data class Taken(val label: String) : PackageStatus()
    object QueuedElsewhere : PackageStatus()

    val blocking: Boolean get() = this is Invalid || this is SameAsOriginal || this is Taken || this is QueuedElsewhere

    fun message(pkg: String): String = when (this) {
        Free -> "Tersedia. Clone akan dipasang sebagai aplikasi baru dengan data kosong."
        is Invalid -> code.message + " " + code.action
        SameAsOriginal -> ErrorCode.PACKAGE_SAME_AS_ORIGINAL.message + " " + ErrorCode.PACKAGE_SAME_AS_ORIGINAL.action
        is OurClone -> "Sudah terpasang sebagai clone ClonApps (\"$label\") dengan kunci yang sama: akan diperbarui dan datanya dipertahankan."
        is Taken -> "$pkg sudah dipakai \"$label\" dengan tanda tangan lain. ClonApps tidak menimpa aplikasi tersebut; pilih package name lain."
        QueuedElsewhere -> "Package ini sudah dipakai job lain di antrean."
    }
}

object Conflicts {
    fun check(context: Context, pkg: String, sourcePackage: String, keys: SigningKeyStore, queued: Boolean): PackageStatus {
        PackageNames.check(pkg)?.let { return PackageStatus.Invalid(it) }
        if (pkg == sourcePackage) return PackageStatus.SameAsOriginal
        if (queued) return PackageStatus.QueuedElsewhere
        val pm = context.packageManager
        val pi = Platform.packageInfo(pm, pkg) ?: return PackageStatus.Free
        val label = pi.applicationInfo?.loadLabel(pm)?.toString() ?: pkg
        val ours = keys.certSha256(pkg)
        val installed = Platform.signerSha256(context, pkg) ?: emptyList()
        return if (ours != null && installed.contains(ours)) PackageStatus.OurClone(label) else PackageStatus.Taken(label)
    }

    /** Saran package clone berikutnya yang belum dipakai: paket.clone2, paket.clone3, ... */
    fun suggest(context: Context, sourcePackage: String, takenInQueue: (String) -> Boolean): String {
        val pm = context.packageManager
        for (n in 2..99) {
            val p = PackageNames.suggest(sourcePackage, n)
            if (Platform.packageInfo(pm, p) == null && !takenInQueue(p)) return p
        }
        return PackageNames.suggest(sourcePackage, 100)
    }
}
