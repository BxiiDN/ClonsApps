package com.clonsapps.platform

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.clonsapps.engine.DeviceProfile
import com.clonsapps.engine.sign.SigningIdentity
import java.io.ByteArrayOutputStream
import java.io.File

data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val isSystem: Boolean,
    val splitNames: List<String>,
    val minSdk: Int,
    val targetSdk: Int,
    val sizeBytes: Long,
)

object Platform {
    fun device(): DeviceProfile = DeviceProfile(Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toList())

    @Suppress("DEPRECATION")
    fun packageInfo(pm: PackageManager, pkg: String, flags: Int = 0): PackageInfo? =
        try {
            pm.getPackageInfo(pkg, flags)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

    fun versionCode(pi: PackageInfo): Long = pi.longVersionCode

    fun installedApps(context: Context, includeSystem: Boolean): List<InstalledApp> {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val apps = pm.getInstalledApplications(0)
        return apps.asSequence()
            .filter { it.packageName != context.packageName }
            .filter { includeSystem || (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 }
            .mapNotNull { ai ->
                val pi = packageInfo(pm, ai.packageName) ?: return@mapNotNull null
                val files = apkFiles(ai)
                InstalledApp(
                    packageName = ai.packageName,
                    label = ai.loadLabel(pm).toString(),
                    versionName = pi.versionName,
                    versionCode = versionCode(pi),
                    isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    splitNames = ai.splitNames?.toList() ?: emptyList(),
                    minSdk = ai.minSdkVersion,
                    targetSdk = ai.targetSdkVersion,
                    sizeBytes = files.sumOf { it.length() },
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /** Base APK + SEMUA split yang terpasang (bukan base saja). */
    fun apkFiles(ai: ApplicationInfo): List<File> {
        val list = ArrayList<File>()
        list.add(File(ai.sourceDir))
        ai.splitSourceDirs?.forEach { list.add(File(it)) }
        return list
    }

    fun installedApkFiles(context: Context, pkg: String): List<File>? {
        val pi = packageInfo(context.packageManager, pkg) ?: return null
        val ai = pi.applicationInfo ?: return null
        return apkFiles(ai)
    }

    fun drawableToBitmap(d: Drawable, size: Int): Bitmap {
        if (d is BitmapDrawable && d.bitmap != null && d.bitmap.width == size && d.bitmap.height == size) return d.bitmap
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        return bmp
    }

    fun appIcon(context: Context, pkg: String, size: Int = 96): Bitmap? = runCatching {
        drawableToBitmap(context.packageManager.getApplicationIcon(pkg), size)
    }.getOrNull()

    /** Ikon dan label dari file APK (belum terpasang). */
    fun archiveIcon(context: Context, apk: File, size: Int = 96): Bitmap? = runCatching {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val pi = pm.getPackageArchiveInfo(apk.absolutePath, 0) ?: return null
        val ai = pi.applicationInfo ?: return null
        ai.sourceDir = apk.absolutePath
        ai.publicSourceDir = apk.absolutePath
        drawableToBitmap(ai.loadIcon(pm), size)
    }.getOrNull()

    fun signerSha256(context: Context, pkg: String): List<String>? {
        val pi = packageInfo(context.packageManager, pkg, PackageManager.GET_SIGNING_CERTIFICATES) ?: return null
        val si = pi.signingInfo ?: return emptyList()
        val sigs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        return sigs?.map { SigningIdentity.sha256Hex(it.toByteArray()) } ?: emptyList()
    }

    fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val v = c.getString(0)
                    if (!v.isNullOrBlank()) return v
                }
            }
        }
        return uri.lastPathSegment ?: "file"
    }

    /** Gambar pilihan pengguna → PNG persegi 256 px (dipotong tengah). */
    fun iconPngFromUri(context: Context, uri: Uri): ByteArray? = runCatching {
        val src = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
        val side = minOf(src.width, src.height)
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        val cropped = Bitmap.createBitmap(src, x, y, side, side)
        val scaled = Bitmap.createScaledBitmap(cropped, 256, 256, true)
        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, bos)
        bos.toByteArray()
    }.getOrNull()

    fun dataDirFor(context: Context, pkg: String): String {
        val own = context.applicationInfo.dataDir ?: return "/data/user/0/$pkg"
        val parent = File(own).parent ?: return "/data/user/0/$pkg"
        return "$parent/$pkg"
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(java.util.Locale.ROOT, "%.2f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> String.format(java.util.Locale.ROOT, "%.0f KB", bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }
}
