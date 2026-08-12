package me.rerere.rikkahub.data.source.github

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * GitHub credentials are deliberately kept outside Settings/DataStore and cloud backup.
 *
 * The access token is only read by [GitHubRepositoryClient]. It is never part of a Tool schema,
 * model message, tool result, or log line. The state shape already has room for the short-lived
 * access + refresh token pair used by a future GitHub App web-flow implementation; a manually
 * entered fine-grained PAT uses only [GitHubCredentialState.accessToken].
 */
internal class GitHubCredentialStore(context: Context) {
    private val file = File(context.noBackupFilesDir, FILE_NAME)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Synchronized
    fun read(): GitHubCredentialState {
        if (!file.exists()) return GitHubCredentialState()
        return runCatching {
            val bytes = file.readBytes()
            require(bytes.size > IV_SIZE)
            val iv = bytes.copyOfRange(0, IV_SIZE)
            val encrypted = bytes.copyOfRange(IV_SIZE, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH, iv))
            json.decodeFromString<GitHubCredentialState>(cipher.doFinal(encrypted).decodeToString())
        }.getOrElse { GitHubCredentialState() }
    }

    @Synchronized
    fun write(state: GitHubCredentialState) {
        if (state.isEmpty) {
            clear()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(json.encodeToString(state).encodeToByteArray())
        val temporary = File(file.parentFile, "$FILE_NAME.tmp")
        temporary.writeBytes(cipher.iv + encrypted)
        temporary.copyTo(file, overwrite = true)
        temporary.delete()
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val FILE_NAME = "github_credentials.enc"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "moataz_github_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_LENGTH = 128
    }
}

@Serializable
internal data class GitHubCredentialState(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresAtMs: Long? = null,
    val kind: GitHubCredentialKind = GitHubCredentialKind.FINE_GRAINED_PAT,
) {
    val isEmpty: Boolean
        get() = accessToken.isNullOrBlank() && refreshToken.isNullOrBlank()
}

@Serializable
internal enum class GitHubCredentialKind {
    FINE_GRAINED_PAT,
    GITHUB_APP_USER_TOKEN,
}

/** Allows the client to later swap PAT storage for GitHub App token refresh without API churn. */
internal fun interface GitHubCredentialProvider {
    fun tokenFor(repository: GitHubRepositoryRef): String?
}
