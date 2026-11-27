package cl.ti3042.caracol

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

const val NOISE_THRESHOLD = 512
const val STALE_MS = 3_000L
const val SESSION_MS = 60 * 60_000L

enum class Role(val label: String) { EDUCATOR("Educadora"), DIRECTOR("Directora") }
enum class Action(val wire: String, val label: String) {
    HIDE("E", "Esconder"), SHOW("M", "Mostrar")
}

data class Reading(
    val sequence: Long,
    val raw: Int,
    val hidden: Boolean,
    val alarm: Boolean,
    val armed: Boolean,
    val simulated: Boolean,
    val capturedAt: Long = System.currentTimeMillis()
) {
    val percent: Int get() = (raw * 100 + 511) / 1023

    fun json() = JSONObject()
        .put("seq", sequence)
        .put("raw", raw)
        .put("hidden", hidden)
        .put("alarm", alarm)
        .put("armed", armed)
        .put("demo", simulated)
        .put("at", capturedAt)

    companion object {
        fun fromJson(json: JSONObject): Reading {
            val raw = json.getInt("raw")
            val sequence = json.getLong("seq")
            require(raw in 0..1023 && sequence in 0..0xffff_ffffL)
            return Reading(sequence, raw, json.getBoolean("hidden"), json.getBoolean("alarm"),
                json.getBoolean("armed"), json.getBoolean("demo"), json.getLong("at"))
        }

        fun listFromJson(array: JSONArray) = List(array.length()) { index ->
            fromJson(array.getJSONObject(index))
        }
    }
}

data class Snapshot(val reading: Reading?, val online: Boolean, val ageMs: Long?) {
    val fresh: Boolean get() = online && ageMs != null && ageMs < STALE_MS

    fun json() = JSONObject()
        .put("reading", reading?.json() ?: JSONObject.NULL)
        .put("online", online)
        .put("age_ms", ageMs ?: JSONObject.NULL)

    companion object {
        fun fromJson(json: JSONObject) = Snapshot(
            json.optJSONObject("reading")?.let(Reading::fromJson),
            json.getBoolean("online"),
            if (json.isNull("age_ms")) null else json.getLong("age_ms").coerceAtLeast(0)
        )
    }
}

class LiveFeed {
    private data class State(val reading: Reading?, val online: Boolean, val receivedAt: Long)
    @Volatile private var state = State(null, false, 0)

    fun publish(reading: Reading) {
        state = State(reading, true, SystemClock.elapsedRealtime())
    }

    fun disconnect() {
        state = state.copy(online = false)
    }

    fun reset() {
        state = State(null, false, 0)
    }

    fun snapshot(): Snapshot {
        val current = state
        val age = current.reading?.let { SystemClock.elapsedRealtime() - current.receivedAt }
        return Snapshot(current.reading, current.online, age)
    }
}

sealed interface SerialMessage {
    data class Telemetry(val reading: Reading) : SerialMessage
    data class Ack(val id: Int, val result: String) : SerialMessage
    data object Ready : SerialMessage
    data object Boot : SerialMessage
}

fun parseSerial(line: String): SerialMessage? {
    if (line == "LISTO") return SerialMessage.Ready
    if (line == "BOOT") return SerialMessage.Boot
    val parts = line.split(',')
    if (parts.size == 3 && parts[0] == "A") {
        val id = parts[1].toIntOrNull() ?: return null
        return if (id in 1..60_000 && parts[2] in listOf("OK", "RUIDO", "SEGURO")) {
            SerialMessage.Ack(id, parts[2])
        } else null
    }
    if (parts.size != 6 || parts[0] != "T") return null
    val sequence = parts[1].toLongOrNull() ?: return null
    val raw = parts[2].toIntOrNull() ?: return null
    if (sequence !in 0..0xffff_ffffL || raw !in 0..1023 ||
        (3..5).any { parts[it] != "0" && parts[it] != "1" }) return null
    return SerialMessage.Telemetry(
        Reading(sequence, raw, parts[3] == "1", parts[4] == "1", parts[5] == "1", false)
    )
}
