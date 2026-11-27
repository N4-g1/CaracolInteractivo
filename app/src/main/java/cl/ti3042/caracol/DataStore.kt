package cl.ti3042.caracol

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

class DataStore(context: Context) : SQLiteOpenHelper(context, "caracol.db", null, 1) {
    private data class Account(val iv: ByteArray, val data: ByteArray)
    private data class Attempts(val count: Int = 0, val until: Long = 0)

    private val attempts = mutableMapOf<Role, Attempts>()
    private val random = SecureRandom()
    private val vaultKey: SecretKey by lazy(::loadVaultKey)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE accounts (role TEXT PRIMARY KEY, iv BLOB NOT NULL, verifier BLOB NOT NULL)")
        db.execSQL("CREATE TABLE readings (id INTEGER PRIMARY KEY, seq INTEGER NOT NULL, raw INTEGER NOT NULL, hidden INTEGER NOT NULL, alarm INTEGER NOT NULL, armed INTEGER NOT NULL, demo INTEGER NOT NULL, captured INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("No existe una migración para la versión indicada.")
    }

    @Synchronized fun hasAccounts(): Boolean = readableDatabase
        .rawQuery("SELECT count(*) FROM accounts", null)
        .use { it.moveToFirst() && it.getInt(0) == Role.entries.size }

    @Synchronized fun createAccounts(first: String, firstPassword: String, second: String, secondPassword: String) {
        val educator = normalize(first)
        val director = normalize(second)
        require(USER.matches(educator) && USER.matches(director) && educator != director) {
            "Usa dos nombres distintos de 3 a 32 caracteres (letras, números, ., _ o -)."
        }
        require(firstPassword.length in 10..128 && secondPassword.length in 10..128 &&
            firstPassword != secondPassword) {
            "Usa contraseñas distintas de 10 a 128 caracteres."
        }
        check(!hasAccounts()) { "Las cuentas ya están configuradas." }
        val records = listOf(
            createRecord(Role.EDUCATOR, educator, firstPassword),
            createRecord(Role.DIRECTOR, director, secondPassword)
        )
        val db = writableDatabase
        db.beginTransaction()
        try {
            records.forEach { db.insertOrThrow("accounts", null, it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized fun authenticate(role: Role, username: String, password: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        val previous = attempts[role] ?: Attempts()
        if (now < previous.until) return false
        val account = readableDatabase.query("accounts", arrayOf("iv", "verifier"),
            "role = ?", arrayOf(role.name), null, null, null).use { cursor ->
            if (cursor.moveToFirst()) Account(cursor.getBlob(0), cursor.getBlob(1))
            else null
        }
        val matched = if (account != null && password.length in 1..128) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, vaultKey, GCMParameterSpec(128, account.iv))
            cipher.updateAAD(role.name.toByteArray())
            val stored = cipher.doFinal(account.data)
            val salt = stored.copyOfRange(0, SALT_BYTES)
            val expected = stored.copyOfRange(SALT_BYTES, SALT_BYTES + VERIFIER_BYTES)
            val name = stored.copyOfRange(SALT_BYTES + VERIFIER_BYTES, stored.size)
            val candidate = derive(password, salt)
            try {
                MessageDigest.isEqual(expected, candidate) &&
                    MessageDigest.isEqual(name, normalize(username).toByteArray(Charsets.UTF_8))
            } finally {
                stored.fill(0)
                expected.fill(0)
                name.fill(0)
                candidate.fill(0)
            }
        } else false
        if (matched) {
            attempts.remove(role)
        } else {
            val count = if (previous.until > 0) 1 else previous.count + 1
            attempts[role] = if (count >= 5) Attempts(until = now + 60_000) else Attempts(count)
        }
        return matched
    }

    fun record(reading: Reading) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertOrThrow("readings", null, ContentValues().apply {
                put("seq", reading.sequence)
                put("raw", reading.raw)
                put("hidden", reading.hidden)
                put("alarm", reading.alarm)
                put("armed", reading.armed)
                put("demo", reading.simulated)
                put("captured", reading.capturedAt)
            })
            db.execSQL("DELETE FROM readings WHERE id NOT IN (SELECT id FROM readings ORDER BY id DESC LIMIT 300)")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun recent(): List<Reading> = readableDatabase.rawQuery(
        "SELECT seq, raw, hidden, alarm, armed, demo, captured FROM readings ORDER BY id DESC LIMIT 30", null
    ).use { rows ->
        buildList {
            while (rows.moveToNext()) {
                add(Reading(rows.getLong(0), rows.getInt(1), rows.getInt(2) == 1, rows.getInt(3) == 1,
                    rows.getInt(4) == 1, rows.getInt(5) == 1, rows.getLong(6)))
            }
        }
    }

    private fun createRecord(role: Role, username: String, password: String): ContentValues {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val verifier = derive(password, salt)
        val packed = salt + verifier + username.toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, vaultKey)
        cipher.updateAAD(role.name.toByteArray())
        try {
            return ContentValues().apply {
                put("role", role.name)
                put("iv", cipher.iv)
                put("verifier", cipher.doFinal(packed))
            }
        } finally {
            verifier.fill(0)
            packed.fill(0)
        }
    }

    private fun derive(password: String, salt: ByteArray): ByteArray {
        val chars = password.toCharArray()
        val spec = PBEKeySpec(chars, salt, 600_000, VERIFIER_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }

    private fun loadVaultKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(VAULT_ALIAS, null) as? SecretKey)?.let { return it }
        val purposes = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(VAULT_ALIAS, purposes)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private fun normalize(name: String) = name.trim().lowercase(Locale.ROOT)

    companion object {
        private const val SALT_BYTES = 16
        private const val VERIFIER_BYTES = 32
        private const val VAULT_ALIAS = "caracol-account-v1"
        private val USER = Regex("[a-z0-9_.-]{3,32}")
    }
}
