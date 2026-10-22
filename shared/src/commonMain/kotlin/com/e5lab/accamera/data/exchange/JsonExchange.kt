package com.e5lab.accamera.data.exchange

import com.e5lab.accamera.data.model.ProjectWithMeasurements
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Centralized JSON (de)serializer for the cross-platform data interchange.
 *
 * Both the Android (Room) and Desktop (file) clients MUST go through this object so the produced
 * JSON is byte-identical on every platform. [encodeDefaults] is enabled so a file exported from one
 * client imports cleanly into the other even when fields hold their default values.
 *
 * Format: `List<ProjectWithMeasurements>` — i.e. every project plus its grid measurements.
 */
object JsonExchange {

    val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun export(projects: List<ProjectWithMeasurements>): String =
        json.encodeToString(ListSerializer(ProjectWithMeasurements.serializer()), projects)

    fun import(text: String): List<ProjectWithMeasurements> =
        json.decodeFromString(ListSerializer(ProjectWithMeasurements.serializer()), text)
}
