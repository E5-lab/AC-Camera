package com.e5lab.accamera.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * One sound-pressure-level reading at a grid point.
 *
 * The natural key is (projectId, col, row) — a grid point holds a single current reading,
 * plus optional remediation tracking (what was done + the post-treatment re-measurement).
 */
@Entity(
    tableName = "measurements",
    primaryKeys = ["projectId", "col", "row"],
    foreignKeys = [
        ForeignKey(
            entity = Project::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class Measurement(
    val projectId: Long,
    val col: Int,
    val row: Int,

    /** Measured sound pressure level in dB. */
    val spl: Double,

    val measuredAt: Long = System.currentTimeMillis(),
    val note: String = "",

    /** Remediation / treatment note recorded for this point. */
    val remediationNote: String? = null,

    /** Re-measured SPL after treatment (null until remediated). */
    val afterSpl: Double? = null,
    val afterMeasuredAt: Long? = null,

    /** Flagged as a detected noise source by the analysis step. */
    val isNoiseSource: Boolean = false,
) {
    val hasRemediation: Boolean get() = afterSpl != null
    val reductionDb: Double? get() = if (afterSpl != null) spl - afterSpl!! else null
}
