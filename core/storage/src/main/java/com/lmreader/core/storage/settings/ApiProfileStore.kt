package com.lmreader.core.storage.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lmreader.core.api.ApiProfileCodec
import com.lmreader.core.api.ApiProtocol
import com.lmreader.core.model.ApiProfile
import com.lmreader.core.model.ApiProfileRepository
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

// 单独文件便于排除凭据备份；委托全局唯一，所有仓储实例共享 DataStore。
private val Context.apiProfileDataStore: DataStore<Preferences>
    get() = PreferenceStores.get(this, "api_profiles")

/** 凭据加密边界，可在隔离测试中替换，但生产只使用 Android Keystore。 */
interface ApiSecretCipher {
    fun encrypt(value: String): String
    fun decrypt(value: String): String
}

/** AES-GCM 密钥不可导出，凭据在 DataStore 中仅以密文保存。 */
class KeystoreApiSecretCipher : ApiSecretCipher {
    private fun key(): SecretKey = synchronized(KEY_LOCK) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun encrypt(value: String): String {
        if (value.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    override fun decrypt(value: String): String {
        if (value.isEmpty()) return ""
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > 12) { "API 凭据损坏" }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
    private companion object { const val ALIAS = "lmreader.api_profiles.v1"; val KEY_LOCK = Any() }
}

/** API 配置持久化，读改写在 DataStore 事务中进行；损坏数据不静默覆盖为空。 */
class ApiProfileStore(
    context: Context,
    private val dataStore: DataStore<Preferences> = context.applicationContext.apiProfileDataStore,
    private val cipher: ApiSecretCipher = KeystoreApiSecretCipher(),
) : ApiProfileRepository {
    override val profiles: Flow<List<ApiProfile>> = dataStore.data.map { decode(it[KEY]) }.flowOn(Dispatchers.IO)

    override suspend fun save(profile: ApiProfile) = withContext(Dispatchers.IO) {
        ApiProtocol.validate(profile)
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY])
            val updated = if (current.any { it.id == profile.id }) current.map { if (it.id == profile.id) profile else it } else current + profile
            prefs[KEY] = encode(updated)
        }
        Unit
    }

    override suspend fun duplicate(id: String): ApiProfile = withContext(Dispatchers.IO) {
        var result: ApiProfile? = null
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY])
            val source = current.firstOrNull { it.id == id } ?: error("配置已经不存在")
            val copy = source.copy(id = UUID.randomUUID().toString(), name = source.displayName + "（副本）")
            prefs[KEY] = encode(current + copy)
            result = copy
        }
        checkNotNull(result)
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        dataStore.edit { prefs -> prefs[KEY] = encode(decode(prefs[KEY]).filterNot { it.id == id }) }
        Unit
    }

    suspend fun replaceAll(profiles: List<ApiProfile>) = withContext(Dispatchers.IO) {
        require(profiles.map { it.id }.distinct().size == profiles.size)
        profiles.forEach(ApiProtocol::validate)
        dataStore.edit { it[KEY] = encode(profiles) }
        Unit
    }

    private fun decode(value: String?): List<ApiProfile> = ApiProfileCodec.decode(value).map { it.copy(apiKey = cipher.decrypt(it.apiKey)) }
    private fun encode(profiles: List<ApiProfile>): String = ApiProfileCodec.encode(profiles.map { it.copy(apiKey = cipher.encrypt(it.apiKey)) })
    private companion object { val KEY = stringPreferencesKey("profiles_v1") }
}
