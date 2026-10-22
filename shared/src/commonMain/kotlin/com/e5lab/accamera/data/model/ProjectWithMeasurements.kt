package com.e5lab.accamera.data.model

import kotlinx.serialization.Serializable

/**
 * Exchange envelope for a single project together with all of its grid measurements.
 *
 * This is the canonical interchange format shared by the Android (Room) and Desktop (JSON) clients.
 * Both sides serialize/deserialize the exact same `List<ProjectWithMeasurements>` via [JsonExchange],
 * so a project authored on the phone and one authored on the PC are byte-for-byte compatible and can
 * be handed over through the JSON export/import buttons.
 */
@Serializable
data class ProjectWithMeasurements(
    val project: Project,
    val measurements: List<Measurement>,
)
