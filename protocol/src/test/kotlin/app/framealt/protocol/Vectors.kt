package app.framealt.protocol

import app.framealt.protocol.util.Hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Loader for the JSON vector files in `src/test/resources/vectors`.
 *
 * Every file declares its own `count`, and [load] asserts the parsed list matches it.
 * Without that check a loader bug would return an empty list and every vector test would
 * pass vacuously — the exact failure mode these tests exist to prevent.
 */
internal object Vectors {

    private val json = Json { ignoreUnknownKeys = true }

    fun load(fileName: String): List<Vector> {
        val stream = Vectors::class.java.getResourceAsStream("/vectors/$fileName")
            ?: fail("missing vector file: vectors/$fileName")
        val root = stream.use { json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject }

        val declared = root.getValue("count").jsonPrimitive.content.toInt()
        val vectors = root.getValue("vectors").jsonArray.map { Vector(fileName, it.jsonObject) }

        assertEquals(declared, vectors.size, "$fileName declares $declared vectors but parsed ${vectors.size}")
        check(vectors.isNotEmpty()) { "$fileName contains no vectors" }
        return vectors
    }
}

internal class Vector(private val fileName: String, private val fields: JsonObject) {

    val note: String get() = fields["note"]?.jsonPrimitive?.content ?: "(no note)"

    fun bytes(key: String): ByteArray = Hex.decode(text(key))

    fun text(key: String): String =
        fields[key]?.jsonPrimitive?.content ?: fail("$fileName vector '$note' has no field '$key'")

    fun flag(key: String): Boolean =
        fields[key]?.jsonPrimitive?.boolean ?: fail("$fileName vector '$note' has no field '$key'")

    /** Used in assertion messages so a failure names the exact vector. */
    override fun toString(): String = "$fileName[$note]"
}
