package br.com.neurelix.vcam.core.storage

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import br.com.neurelix.vcam.core.diagnostics.AppLog
import br.com.neurelix.vcam.core.model.AuthorizedConsumer
import br.com.neurelix.vcam.core.model.FilterParams
import br.com.neurelix.vcam.core.model.FilterProfile
import br.com.neurelix.vcam.core.model.OutputSettings
import br.com.neurelix.vcam.core.model.PendingConsumer
import br.com.neurelix.vcam.core.model.SessionRecord
import br.com.neurelix.vcam.core.model.SourceSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.store by preferencesDataStore(name = "neurelix_settings")

/** Persistência local (DataStore + JSON). Nada sai do dispositivo. */
class SettingsRepository(context: Context) {
    private val ds = context.applicationContext.store
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun <T> flow(key: String, ser: KSerializer<T>, default: T): Flow<T> =
        ds.data.map { p -> decode(p, key, ser, default) }.distinctUntilChanged()

    private fun <T> decode(p: Preferences, key: String, ser: KSerializer<T>, default: T): T {
        val raw = p[stringPreferencesKey(key)] ?: return default
        return runCatching { json.decodeFromString(ser, raw) }
            .onFailure { AppLog.w("app", "storage", "JSON inválido em '$key', usando padrão", it) }
            .getOrDefault(default)
    }

    private suspend fun <T> update(key: String, ser: KSerializer<T>, default: T, f: (T) -> T) {
        ds.edit { p ->
            val cur = decode(p, key, ser, default)
            p[stringPreferencesKey(key)] = json.encodeToString(ser, f(cur))
        }
    }

    private suspend fun <T> set(key: String, ser: KSerializer<T>, v: T) {
        ds.edit { it[stringPreferencesKey(key)] = json.encodeToString(ser, v) }
    }

    val output: Flow<OutputSettings> = flow("output", OutputSettings.serializer(), OutputSettings())
    val filters: Flow<FilterParams> = flow("filters", FilterParams.serializer(), FilterParams())
    val activeSource: Flow<SourceSpec?> = flow("active_source", SourceSpec.serializer().nullable, null)
    val recents: Flow<List<SourceSpec>> = flow("recents", ListSerializer(SourceSpec.serializer()), emptyList())
    val profiles: Flow<List<FilterProfile>> = flow("profiles", ListSerializer(FilterProfile.serializer()), emptyList())
    val authorized: Flow<List<AuthorizedConsumer>> = flow("authorized", ListSerializer(AuthorizedConsumer.serializer()), emptyList())
    val pending: Flow<List<PendingConsumer>> = flow("pending", ListSerializer(PendingConsumer.serializer()), emptyList())
    val sessions: Flow<List<SessionRecord>> = flow("sessions", ListSerializer(SessionRecord.serializer()), emptyList())
    val externalEnabled: Flow<Boolean> = flow("external_enabled", Boolean.serializer(), false)

    suspend fun setOutput(v: OutputSettings) = set("output", OutputSettings.serializer(), v)
    suspend fun setFilters(v: FilterParams) = set("filters", FilterParams.serializer(), v.sanitized())
    suspend fun setActiveSource(v: SourceSpec?) = set("active_source", SourceSpec.serializer().nullable, v)
    suspend fun setExternalEnabled(v: Boolean) = set("external_enabled", Boolean.serializer(), v)

    suspend fun addRecent(s: SourceSpec) = update("recents", ListSerializer(SourceSpec.serializer()), emptyList()) { cur ->
        (listOf(s.copy(addedAtMs = System.currentTimeMillis())) + cur.filterNot { it.id == s.id }).take(20)
    }

    suspend fun removeRecent(id: String) = update("recents", ListSerializer(SourceSpec.serializer()), emptyList()) { cur -> cur.filterNot { it.id == id } }

    suspend fun saveProfile(p: FilterProfile) = update("profiles", ListSerializer(FilterProfile.serializer()), emptyList()) { cur ->
        cur.filterNot { it.name == p.name } + p.copy(params = p.params.sanitized())
    }

    suspend fun deleteProfile(name: String) = update("profiles", ListSerializer(FilterProfile.serializer()), emptyList()) { cur -> cur.filterNot { it.name == name } }

    suspend fun addPending(p: PendingConsumer) = update("pending", ListSerializer(PendingConsumer.serializer()), emptyList()) { cur ->
        cur.filterNot { it.packageName == p.packageName } + p
    }

    suspend fun approve(c: AuthorizedConsumer) {
        update("authorized", ListSerializer(AuthorizedConsumer.serializer()), emptyList()) { cur -> cur.filterNot { it.packageName == c.packageName } + c }
        update("pending", ListSerializer(PendingConsumer.serializer()), emptyList()) { cur -> cur.filterNot { it.packageName == c.packageName } }
    }

    suspend fun revoke(pkg: String) {
        update("authorized", ListSerializer(AuthorizedConsumer.serializer()), emptyList()) { cur -> cur.filterNot { it.packageName == pkg } }
        update("pending", ListSerializer(PendingConsumer.serializer()), emptyList()) { cur -> cur.filterNot { it.packageName == pkg } }
    }

    suspend fun upsertSession(r: SessionRecord) = update("sessions", ListSerializer(SessionRecord.serializer()), emptyList()) { cur ->
        (cur.filterNot { it.id == r.id } + r).sortedByDescending { it.startedAtMs }.take(100)
    }

    suspend fun clearSessions() = set("sessions", ListSerializer(SessionRecord.serializer()), emptyList())

    suspend fun authorizedNow(): List<AuthorizedConsumer> = authorized.first()
}
