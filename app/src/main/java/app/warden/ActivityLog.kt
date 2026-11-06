package app.warden

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

// History only: a corrupt file just starts the log over rather than failing every read.
private val Context.activityDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "activity_log",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** One protected session: a target app came to the front, then left. */
data class SessionRecord(
    val packageName: String,
    val appLabel: String,
    val startedAt: Long,
    /** Null while the session is still open. */
    val endedAt: Long?,
    val disabled: Set<Protection>,
    val failedToDisable: Set<Protection>,
    val failedToRestore: Set<Protection>,
    /** True when this session was closed by the fallback recovery path, not a normal restore. */
    val recovered: Boolean,
)

/**
 * Small, bounded history of recent sessions (newest first), stored as one JSON string in its own
 * DataStore file. Written only at transitions (enter, leave, recovery), never per poll. Callers
 * wrap recording in runCatching: logging must never break or delay a transition.
 */
class ActivityLog(private val context: Context) {

    /** Recent sessions, newest first. */
    val records: Flow<List<SessionRecord>> =
        context.activityDataStore.data.map { decode(it[KEY]) }

    suspend fun clear() {
        context.activityDataStore.edit { it.remove(KEY) }
    }

    /**
     * Puts back records removed by [clear] (Undo). Merged with anything recorded since, newest
     * first, so an undo can never drop a newer session.
     */
    suspend fun restore(previous: List<SessionRecord>) = update { current ->
        (current + previous)
            .distinctBy { it.packageName to it.startedAt }
            .sortedByDescending { it.startedAt }
    }

    /** Opens a record for a session that just started. Any stale open record is closed first. */
    suspend fun recordEnter(
        packageName: String,
        appLabel: String,
        startedAt: Long,
        disabled: Set<Protection>,
        failedToDisable: Set<Protection>,
    ) = update { list ->
        val closed = list.map { if (it.endedAt == null) it.copy(endedAt = startedAt, recovered = true) else it }
        listOf(
            SessionRecord(
                packageName = packageName,
                appLabel = appLabel,
                startedAt = startedAt,
                endedAt = null,
                disabled = disabled,
                failedToDisable = failedToDisable,
                failedToRestore = emptySet(),
                recovered = false,
            ),
        ) + closed
    }

    /** Closes every open record. [recovered] marks sessions closed by a recovery path. */
    suspend fun recordLeave(
        failedToRestore: Set<Protection>,
        recovered: Boolean,
        endedAt: Long = System.currentTimeMillis(),
    ) = update { list ->
        list.map {
            if (it.endedAt == null) {
                it.copy(endedAt = endedAt, failedToRestore = failedToRestore, recovered = recovered)
            } else {
                it
            }
        }
    }

    private suspend fun update(transform: (List<SessionRecord>) -> List<SessionRecord>) {
        context.activityDataStore.edit { prefs ->
            prefs[KEY] = encode(transform(decode(prefs[KEY])).take(MAX_RECORDS))
        }
    }

    private companion object {
        val KEY = stringPreferencesKey("records")
        const val MAX_RECORDS = 20

        fun encode(list: List<SessionRecord>): String {
            val array = JSONArray()
            for (r in list) {
                val o = JSONObject()
                    .put("pkg", r.packageName)
                    .put("label", r.appLabel)
                    .put("start", r.startedAt)
                    .put("disabled", names(r.disabled))
                    .put("failDisable", names(r.failedToDisable))
                    .put("failRestore", names(r.failedToRestore))
                    .put("recovered", r.recovered)
                r.endedAt?.let { o.put("end", it) }
                array.put(o)
            }
            return array.toString()
        }

        fun decode(raw: String?): List<SessionRecord> {
            if (raw.isNullOrEmpty()) return emptyList()
            return try {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { i ->
                    runCatching {
                        val o = array.getJSONObject(i)
                        SessionRecord(
                            packageName = o.getString("pkg"),
                            appLabel = o.getString("label"),
                            startedAt = o.getLong("start"),
                            endedAt = if (o.has("end")) o.getLong("end") else null,
                            disabled = protections(o.optJSONArray("disabled")),
                            failedToDisable = protections(o.optJSONArray("failDisable")),
                            failedToRestore = protections(o.optJSONArray("failRestore")),
                            recovered = o.optBoolean("recovered", false),
                        )
                    }.getOrNull()
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

        fun names(set: Set<Protection>): JSONArray = JSONArray().also { a -> set.forEach { a.put(it.name) } }

        fun protections(array: JSONArray?): Set<Protection> {
            if (array == null) return emptySet()
            val out = linkedSetOf<Protection>()
            for (i in 0 until array.length()) {
                runCatching { out += Protection.valueOf(array.getString(i)) }
            }
            return out
        }
    }
}
