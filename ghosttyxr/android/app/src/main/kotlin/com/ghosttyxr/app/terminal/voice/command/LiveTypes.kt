package com.ghosttyxr.app.terminal.voice.command

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal data class FunctionCallPart(val name: String, val args: Map<String, JsonElement>, val id: String = "")
internal data class FunctionResponsePart(val name: String, val response: JsonObject, val id: String = "")

internal class Schema(val json: JsonObject) {
    companion object {
        private fun scalar(type: String, description: String) = Schema(buildJsonObject {
            put("type", type)
            put("description", description)
        })
        fun string(description: String) = scalar("STRING", description)
        fun integer(description: String) = scalar("INTEGER", description)
        fun boolean(description: String) = scalar("BOOLEAN", description)
        fun enumeration(values: List<String>, description: String) = Schema(buildJsonObject {
            put("type", "STRING")
            put("description", description)
            put("enum", buildJsonArray { values.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        })
    }
}

internal class FunctionDeclaration(
    private val name: String,
    private val description: String,
    private val parameters: Map<String, Schema>,
    private val optionalParameters: List<String> = emptyList(),
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        if (parameters.isNotEmpty()) {
            putJsonObject("parameters") {
                put("type", "OBJECT")
                putJsonObject("properties") { parameters.forEach { (name, schema) -> put(name, schema.json) } }
                val required = parameters.keys - optionalParameters.toSet()
                if (required.isNotEmpty()) put("required", buildJsonArray {
                    required.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                })
            }
        }
    }
}
