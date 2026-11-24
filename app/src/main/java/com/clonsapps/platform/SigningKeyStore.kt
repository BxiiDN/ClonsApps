package com.clonsapps.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.clonsapps.engine.sign.SigningIdentity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Kunci penandatanganan per clone (per package name), dibuat di perangkat.
 * Kunci privat disimpan terenkripsi AES-256-GCM dengan kunci induk di Android Keystore
 * (tidak bisa diekspor dari perangkat). Clone yang sama selalu ditandatangani kunci yang sama,
 * sehingga bisa diperbarui tanpa kehilangan data. Cadangan hanya lewat PKCS#12 berkata sandi.
 */
class SigningKeyStore(context: Context) {
    private val dir = File(context.filesDir, "keys").apply { mkdirs() }

    private fun fileFor(pkg: String) = File(dir, "$pkg.key")

    fun has(pkg: String): Boolean = fileFor(pkg).isFile

    fun packages(): List<String> = dir.listFiles()?.filter { it.name.endsWith(".key") }?.map { it.name.removeSuffix(".key") }?.sorted() ?: emptyList()

    @Synchronized
    fun getOrCreate(pkg: String): SigningIdentity {
        load(pkg)?.let { return it }
        val id = SigningIdentity.generate("ClonApps $pkg")
        save(pkg, id)
        return id
    }

    fun load(pkg: String): SigningIdentity? {
        val f = fileFor(pkg)
        if (!f.isFile) return null
        val blob = f.readBytes()
        val iv = blob.copyOfRange(0, 12)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, iv))
        val plain = cipher.doFinal(blob, 12, blob.size - 12)
        val din = DataInputStream(ByteArrayInputStream(plain))
        val keyBytes = ByteArray(din.readInt()).also { din.readFully(it) }
        val certBytes = ByteArray(din.readInt()).also { din.readFully(it) }
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
        return SigningIdentity(key, cert)
    }

    fun certSha256(pkg: String): String? = runCatching { load(pkg)?.certificateSha256() }.getOrNull()

    private fun save(pkg: String, id: SigningIdentity) {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { d ->
            val k = id.privateKey().encoded
            val c = id.certificate().encoded
            d.writeInt(k.size); d.write(k)
            d.writeInt(c.size); d.write(c)
        }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val iv = cipher.iv
        val enc = cipher.doFinal(bos.toByteArray())
        val tmp = File(dir, "$pkg.key.tmp")
        tmp.writeBytes(iv + enc)
        if (!tmp.renameTo(fileFor(pkg))) {
            fileFor(pkg).delete()
            tmp.renameTo(fileFor(pkg))
        }
    }

    fun delete(pkg: String) {
        fileFor(pkg).delete()
    }

    /** Ekspor semua kunci ke PKCS#12 terlindungi kata sandi. */
    fun exportBackup(out: OutputStream, password: CharArray): Int {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        var n = 0
        for (pkg in packages()) {
            val id = load(pkg) ?: continue
            ks.setKeyEntry(pkg, id.privateKey(), password, arrayOf(id.certificate()))
            n++
        }
        ks.store(out, password)
        return n
    }

    data class ImportResult(val imported: Int, val skippedDifferent: List<String>, val alreadySame: Int)

    /** Impor cadangan. Kunci yang sudah ada dengan sertifikat berbeda TIDAK ditimpa. */
    fun importBackup(input: InputStream, password: CharArray): ImportResult {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(input, password)
        var imported = 0
        var same = 0
        val skipped = ArrayList<String>()
        for (alias in ks.aliases().toList()) {
            val key = ks.getKey(alias, password) as? PrivateKey ?: continue
            val cert = ks.getCertificate(alias) as? X509Certificate ?: continue
            val incoming = SigningIdentity(key, cert)
            val existing = runCatching { load(alias) }.getOrNull()
            when {
                existing == null -> { save(alias, incoming); imported++ }
                existing.certificateSha256() == incoming.certificateSha256() -> same++
                else -> skipped.add(alias)
            }
        }
        return ImportResult(imported, skipped, same)
    }

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(MASTER_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(MASTER_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    companion object {
        private const val MASTER_ALIAS = "clonapps_master_v1"
        private const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
