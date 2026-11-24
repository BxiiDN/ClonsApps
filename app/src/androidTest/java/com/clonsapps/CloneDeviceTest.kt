package com.clonsapps

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clonsapps.data.BuildState
import com.clonsapps.data.CloneJob
import com.clonsapps.data.InstallState
import com.clonsapps.data.JobCodec
import com.clonsapps.data.JobRepository
import com.clonsapps.data.SourceKind
import com.clonsapps.engine.CloneEngine
import com.clonsapps.engine.CloneSpec
import com.clonsapps.engine.InstallErrors
import com.clonsapps.engine.sign.SigningIdentity
import com.clonsapps.platform.Conflicts
import com.clonsapps.platform.PackageStatus
import com.clonsapps.platform.Platform
import com.clonsapps.work.CloneProcessor
import com.clonsapps.work.Installer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.io.RandomAccessFile

/**
 * Pengujian perangkat (emulator CI) memakai aplikasi contoh milik proyek yang sudah dipasang
 * oleh skrip CI: simple, multidex, nativelib, provider (APK tunggal) dan split (base + split via bundletool).
 * Membuktikan: asli dan clone terpasang bersamaan, nama berbeda, data terpisah, update dengan kunci sama.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class CloneDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val app = ClonApp.get(ctx)
    private val pm = ctx.packageManager
    private val uia = inst.uiAutomation

    @Before
    fun adopt() {
        uia.adoptShellPermissionIdentity(Manifest.permission.INSTALL_PACKAGES, Manifest.permission.DELETE_PACKAGES)
    }

    @After
    fun drop() {
        uia.dropShellPermissionIdentity()
    }

    private fun report(msg: String) = Log.i(TAG, msg)

    private fun shell(cmd: String): String {
        val pfd = uia.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    private fun installed(pkg: String) = Platform.packageInfo(pm, pkg) != null

    private fun uninstall(pkg: String) {
        if (installed(pkg)) shell("pm uninstall $pkg")
    }

    private fun requireSample(pkg: String) {
        assertTrue("Sampel $pkg belum dipasang oleh skrip CI", installed(pkg))
    }

    private fun newJob(src: String, newPkg: String, label: String?, iconPng: ByteArray? = null, kind: SourceKind = SourceKind.INSTALLED, inputs: List<String> = emptyList()): String {
        val id = "t" + System.nanoTime().toString(36)
        val iconPath = iconPng?.let { File(ctx.cacheDir, "$id.png").apply { writeBytes(it) }.absolutePath }
        val pi = Platform.packageInfo(pm, src)!!
        app.jobs.add(
            CloneJob(
                id = id, createdAt = System.currentTimeMillis(), sourceKind = kind, sourcePackage = src,
                sourceLabel = pi.applicationInfo!!.loadLabel(pm).toString(), sourceVersionName = pi.versionName,
                sourceVersionCode = Platform.versionCode(pi), inputPaths = inputs, newPackage = newPkg, newLabel = label,
                iconPath = iconPath, outputName = "test",
            )
        )
        return id
    }

    /** Output ke external files agar shell dapat membaca bila perlu jalur cadangan. */
    private fun build(id: String): CloneJob {
        val out = File(ctx.getExternalFilesDir(null), "out-$id")
        val job = CloneProcessor(ctx).process(id, out)!!
        assertEquals("${job.errorCode}: ${job.errorMessage}", BuildState.BUILT, job.build)
        report("BUILT ${job.newPackage}: ${job.outputs.size} APK, cert ${job.certSha256}, skema ${job.schemes}")
        return job
    }

    private var lastVia = "?"

    /** Memasang lewat PackageInstaller milik ClonApps. Jalur shell hanya cadangan dan dicatat. */
    private fun install(job: CloneJob): Pair<Int, String?> {
        val files = job.outputs.map { File(it.path) }
        val outcome = runBlocking { withTimeout(180_000) { Installer.commitAndAwait(ctx, job.id, files, job.newPackage) } }
        if (outcome.status != PackageInstaller.STATUS_PENDING_USER_ACTION) {
            lastVia = "PackageInstaller"
            report("INSTALL via PackageInstaller ${job.newPackage}: status=${outcome.status} ${outcome.message}")
            return outcome.status to outcome.message
        }
        lastVia = "shell"
        val sid = Regex("\\[(\\d+)]").find(shell("pm install-create -r"))!!.groupValues[1]
        files.forEachIndexed { i, f -> shell("pm install-write -S ${f.length()} $sid part$i ${f.absolutePath}") }
        val res = shell("pm install-commit $sid").trim()
        report("INSTALL via shell (konfirmasi pengguna diperlukan) ${job.newPackage}: $res")
        return (if (res.startsWith("Success")) PackageInstaller.STATUS_SUCCESS else PackageInstaller.STATUS_FAILURE) to res
    }

    private fun installOk(job: CloneJob) {
        val (status, msg) = install(job)
        assertEquals("pemasangan gagal: $msg", PackageInstaller.STATUS_SUCCESS, status)
        assertTrue(installed(job.newPackage))
        val signers = Platform.signerSha256(ctx, job.newPackage)!!
        assertTrue("sertifikat clone harus kunci ClonApps", signers.contains(job.certSha256))
    }

    private fun readMarker(pkg: String): Map<String, String> =
        shell("run-as $pkg cat files/marker.properties").lines().mapNotNull { l ->
            val i = l.indexOf('=')
            if (i > 0) l.substring(0, i) to l.substring(i + 1) else null
        }.toMap()

    private fun launch(pkg: String): Map<String, String> {
        shell("am force-stop $pkg")
        val before = readMarker(pkg)["launches"]
        val comp = pm.getLaunchIntentForPackage(pkg)!!.component!!.flattenToShortString()
        shell("am start -W -n $comp")
        repeat(60) {
            val m = readMarker(pkg)
            if (m["launches"] != null && m["launches"] != before) {
                report("MARKER $pkg: $m")
                return m
            }
            Thread.sleep(250)
        }
        fail("Aplikasi $pkg tidak menulis marker setelah dibuka (tidak berjalan?)")
        return emptyMap()
    }

    private fun redPng(): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.rgb(230, 20, 20))
        val bos = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos)
        return bos.toByteArray()
    }

    private fun launcherLabel(pkg: String): String {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
        return pm.queryIntentActivities(i, 0).first().loadLabel(pm).toString()
    }

    // ------------------------------------------------------------------------------------------

    @Test
    fun t01_simple_sideBySide_label_icon_separateData() {
        val src = "com.clonsapps.sample.simple"
        val dst = "$src.clone2"
        requireSample(src)
        uninstall(dst)
        val origBefore = Platform.packageInfo(pm, src)!!
        val origSigners = Platform.signerSha256(ctx, src)

        val job = build(newJob(src, dst, "Simple Kedua", redPng()))
        installOk(job)
        report("t01 jalur pemasangan: $lastVia")

        // aplikasi asli tidak tersentuh
        val origAfter = Platform.packageInfo(pm, src)!!
        assertEquals(origBefore.lastUpdateTime, origAfter.lastUpdateTime)
        assertEquals(origSigners, Platform.signerSha256(ctx, src))

        // nama berbeda
        assertEquals("Simple Kedua", pm.getApplicationLabel(pm.getApplicationInfo(dst, 0)).toString())
        assertEquals("Sample Simple", pm.getApplicationLabel(pm.getApplicationInfo(src, 0)).toString())
        assertEquals("Simple Kedua", launcherLabel(dst))

        // ikon kustom diterapkan
        val icon = Platform.drawableToBitmap(pm.getApplicationIcon(dst), 64)
        val px = icon.getPixel(32, 32)
        report("ikon clone piksel tengah: #" + Integer.toHexString(px))
        assertTrue("ikon clone harus merah", android.graphics.Color.red(px) > 180 && android.graphics.Color.green(px) < 80)

        // data terpisah
        val o = launch(src)
        val c = launch(dst)
        assertEquals(src, o["package"])
        assertEquals(dst, c["package"])
        assertNotEquals("UID harus berbeda", o["uid"], c["uid"])
        assertNotEquals("direktori data harus berbeda", o["dataDir"], c["dataDir"])
        assertTrue(c["dataDir"]!!.endsWith("/$dst"))
        assertEquals("clone mulai dengan data kosong", "1", c["launches"])
        val c2 = launch(dst)
        assertEquals("2", c2["launches"])
        assertEquals("data asli tidak berubah oleh clone", o["launches"], readMarker(src)["launches"])
        if (lastVia == "PackageInstaller") assertEquals(InstallState.INSTALLED, app.jobs.get(job.id)!!.install)
    }

    @Test
    fun t02_update_sameKey_keepsData_otherKeyRejected() {
        val src = "com.clonsapps.sample.simple"
        val dst = "$src.clone3"
        requireSample(src)
        uninstall(dst)
        val first = build(newJob(src, dst, "Simple Tiga"))
        installOk(first)
        assertEquals("1", launch(dst)["launches"])

        val second = build(newJob(src, dst, "Simple Tiga v2"))
        assertEquals("kunci yang sama dipakai ulang", first.certSha256, second.certSha256)
        installOk(second)
        assertEquals("Simple Tiga v2", pm.getApplicationLabel(pm.getApplicationInfo(dst, 0)).toString())
        assertEquals("data dipertahankan setelah update", "2", launch(dst)["launches"])

        // Kunci lain → Android menolak pembaruan (dependensi tanda tangan).
        val other = SigningIdentity.generate("lain")
        val out = File(ctx.getExternalFilesDir(null), "other-${System.nanoTime()}")
        val r = CloneEngine(null, null, null).run(Platform.installedApkFiles(ctx, src)!!, CloneSpec(dst, "X", null, "x"), other, Platform.device(), out)
        val id = newJob(src, dst, "X")
        val fake = app.jobs.get(id)!!.copy(outputs = r.outputs.map { com.clonsapps.data.OutputFile(it.file.absolutePath, it.splitName, it.sha256, it.size) }, certSha256 = r.certSha256)
        val (status, msg) = install(fake)
        report("t02 pembaruan dengan kunci lain: status=$status $msg")
        assertNotEquals(PackageInstaller.STATUS_SUCCESS, status)
        assertEquals("SIGNATURE_MISMATCH", InstallErrors.explain(status, msg).code)
        assertEquals("data tetap ada setelah penolakan", "3", launch(dst)["launches"])
    }

    @Test
    fun t03_multidex() {
        val src = "com.clonsapps.sample.multidex"
        val dst = "$src.clone2"
        requireSample(src)
        uninstall(dst)
        installOk(build(newJob(src, dst, "Multidex Kedua")))
        val m = launch(dst)
        assertEquals("ok", m["multidex"])
        assertTrue("clone harus multidex: ${m["dexFiles"]}", m["dexFiles"]!!.toInt() >= 2)
    }

    @Test
    fun t04_nativeLibrary_fromStorageFile() {
        val src = "com.clonsapps.sample.nativelib"
        val dst = "$src.clone2"
        requireSample(src)
        uninstall(dst)
        // Sumber "file dari penyimpanan": salinan APK terpasang diproses lewat jalur FILE.
        val id0 = "f" + System.nanoTime().toString(36)
        val inDir = app.jobs.inputDir(id0).apply { mkdirs() }
        val copy = File(inDir, "in_0.apk")
        File(Platform.installedApkFiles(ctx, src)!!.first().path).copyTo(copy, overwrite = true)
        val id = newJob(src, dst, "Native Kedua", kind = SourceKind.FILE, inputs = listOf(copy.absolutePath))
        installOk(build(id))
        val m = launch(dst)
        assertTrue("library native harus termuat: ${m["native"]}", m["native"]!!.startsWith("ok:"))
    }

    @Test
    fun t05_provider_authorityPermission_separateData() {
        val src = "com.clonsapps.sample.provider"
        val dst = "$src.clone2"
        requireSample(src)
        uninstall(dst)
        installOk(build(newJob(src, dst, "Provider Kedua")))
        val cr = ctx.contentResolver
        fun query(auth: String): Triple<String, String, Int> =
            cr.query(Uri.parse("content://$auth/rows"), null, null, null, null)!!.use { c ->
                assertTrue(c.moveToFirst())
                Triple(c.getString(0), c.getString(1), c.getInt(2))
            }
        val before = query("$src.notes")
        cr.insert(Uri.parse("content://$dst.notes/rows"), ContentValues())
        cr.insert(Uri.parse("content://$dst.notes/rows"), ContentValues())
        val clone = query("$dst.notes")
        val orig = query("$src.notes")
        report("provider asli=$orig clone=$clone")
        assertEquals(dst, clone.first)
        assertEquals("$dst.notes", clone.second)
        assertEquals(2, clone.third)
        assertEquals("data provider asli tidak berubah", before.third, orig.third)
        assertEquals(src, orig.first)
        assertEquals(dst, pm.getPermissionInfo("$dst.permission.NOTES", 0).packageName)
        assertEquals(src, pm.getPermissionInfo("$src.permission.NOTES", 0).packageName)
        val m = launch(dst)
        assertTrue(m["provider"]!!.startsWith("$dst|$dst.notes|"))
    }

    @Test
    fun t06_splitApk_allSplitsCloned() {
        val src = "com.clonsapps.sample.split"
        val dst = "$src.clone2"
        requireSample(src)
        uninstall(dst)
        val srcSplits = pm.getApplicationInfo(src, 0).splitNames?.sorted() ?: emptyList()
        report("split sumber: $srcSplits")
        assertTrue("sampel split harus terpasang sebagai split APK", srcSplits.isNotEmpty())
        val job = build(newJob(src, dst, "Split Kedua", redPng()))
        assertEquals(1 + srcSplits.size, job.outputs.size)
        installOk(job)
        val dstSplits = pm.getApplicationInfo(dst, 0).splitNames?.sorted() ?: emptyList()
        assertEquals(srcSplits, dstSplits)
        val m = launch(dst)
        assertTrue("library native dari split ABI: ${m["native"]}", m["native"]!!.startsWith("ok:"))
        assertEquals("Split Kedua", launcherLabel(dst))
    }

    @Test
    fun t07_multipleClonesCoexist() {
        val src = "com.clonsapps.sample.simple"
        requireSample(src)
        val pkgs = listOf("$src.clone4", "$src.clone5")
        pkgs.forEach { uninstall(it) }
        pkgs.forEachIndexed { i, p -> installOk(build(newJob(src, p, "Simple ${i + 4}"))) }
        val uids = (listOf(src) + pkgs).map { pm.getApplicationInfo(it, 0).uid }.toSet()
        assertEquals("asli + 2 clone = 3 UID berbeda", 3, uids.size)
        assertNotEquals(app.keys.certSha256(pkgs[0]), app.keys.certSha256(pkgs[1]))
    }

    @Test
    fun t08_conflictsAreBlocked() {
        val st = Conflicts.check(ctx, "com.clonsapps.sample.provider", "com.clonsapps.sample.simple", app.keys, false)
        assertTrue("$st", st is PackageStatus.Taken)
        assertTrue(Conflicts.check(ctx, "com.clonsapps.sample.simple", "com.clonsapps.sample.simple", app.keys, false) is PackageStatus.SameAsOriginal)
        val before = Platform.packageInfo(pm, "com.clonsapps.sample.provider")!!.lastUpdateTime
        val id = newJob("com.clonsapps.sample.simple", "com.clonsapps.sample.provider", "Bentrok")
        val job = CloneProcessor(ctx).process(id)!!
        assertEquals(BuildState.FAILED, job.build)
        assertEquals("PACKAGE_IN_USE", job.errorCode)
        assertEquals(before, Platform.packageInfo(pm, "com.clonsapps.sample.provider")!!.lastUpdateTime)
    }

    @Test
    fun t09_cancelAndCorruptInput() {
        val src = "com.clonsapps.sample.simple"
        val id = newJob(src, "$src.clone9", "Batal")
        CloneProcessor.requestCancel(id)
        val cancelled = CloneProcessor(ctx).process(id)!!
        assertEquals(BuildState.CANCELLED, cancelled.build)
        assertTrue(app.jobs.outDir(id).listFiles()?.none { it.name.endsWith(".apk") } ?: true)

        val id2 = "c" + System.nanoTime().toString(36)
        val inDir = app.jobs.inputDir(id2).apply { mkdirs() }
        val bad = File(inDir, "in_0.apk")
        File(Platform.installedApkFiles(ctx, src)!!.first().path).copyTo(bad, overwrite = true)
        RandomAccessFile(bad, "rw").use { raf ->
            raf.seek(bad.length() / 3)
            raf.write(ByteArray(64) { 0x5A })
        }
        val jid = newJob(src, "$src.clone10", "Rusak", kind = SourceKind.FILE, inputs = listOf(bad.absolutePath))
        val failed = CloneProcessor(ctx).process(jid)!!
        report("APK rusak: ${failed.errorCode} ${failed.errorMessage}")
        assertEquals(BuildState.FAILED, failed.build)
        assertTrue(failed.errorCode, failed.errorCode == "CRC_MISMATCH" || failed.errorCode == "CORRUPT_ZIP" || failed.errorCode == "BAD_MANIFEST")
        assertFalse(installed("$src.clone10"))
    }

    @Test
    fun t10_processRecovery() {
        val dir = File(ctx.cacheDir, "recovery-${System.nanoTime()}").apply { mkdirs() }
        val running = CloneJob(
            id = "r1", createdAt = 1, sourceKind = SourceKind.INSTALLED, sourcePackage = "a.b", sourceLabel = "AB",
            sourceVersionName = null, sourceVersionCode = 1, inputPaths = emptyList(), newPackage = "a.b.c", newLabel = null,
            iconPath = null, outputName = "x", build = BuildState.RUNNING, progress = 0.5f,
        )
        File(dir, "jobs.json").writeText(JobCodec.encode(listOf(running, running.copy(id = "q1", build = BuildState.QUEUED))))
        val partial = File(dir, "jobs/r1/out/base.apk").apply { parentFile!!.mkdirs(); writeText("partial") }
        val repo = JobRepository(ctx, dir)
        assertEquals(BuildState.INTERRUPTED, repo.get("r1")!!.build)
        assertEquals(BuildState.QUEUED, repo.get("q1")!!.build)
        assertFalse("file parsial dibersihkan", partial.exists())
        assertNotNull(JobRepository(ctx, dir).get("r1"))
    }

    companion object {
        const val TAG = "ClonAppsDeviceTest"
    }
}
