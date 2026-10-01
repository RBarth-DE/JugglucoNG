package tk.glucodata.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import tk.glucodata.GlucosePoint
import tk.glucodata.ui.WearGlucoseStore
import tk.glucodata.ui.components.TrendArrowCanvas

internal data class WearReadingPeer(
    val series: WearGlucoseStore.PeerSeries,
    val point: GlucosePoint,
    val velocity: Float,
)

/** The phone's row rule: latest actual reading in the same minute, never carry forward. */
internal fun readingPeers(
    rows: List<GlucosePoint>,
    peers: List<WearGlucoseStore.PeerSeries>,
    isMmol: Boolean,
): Map<Long, List<WearReadingPeer>> {
    val result = rows.associate { it.timestamp to mutableListOf<WearReadingPeer>() }
    peers.forEach { peer ->
        val byMinute = peer.points.associateBy { it.timestamp / 60_000L }
        val matches = rows.mapNotNull { byMinute[it.timestamp / 60_000L] }
        val velocities = rowVelocities(peer.points, matches, peer.isRawMode, isMmol)
        rows.forEach { row ->
            byMinute[row.timestamp / 60_000L]?.let { point ->
                result.getValue(row.timestamp).add(WearReadingPeer(peer, point, velocities[point.timestamp] ?: 0f))
            }
        }
    }
    return result
}

/** Stack sensors so dual-lane values still fit a round watch without shrinking the time. */
@Composable
internal fun ReadingValues(
    point: GlucosePoint,
    viewMode: Int,
    isMmol: Boolean,
    velocity: Float,
    peers: List<WearReadingPeer>,
    primaryColorArgb: Int? = null,
) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SensorValue(point, viewMode, isMmol, velocity, primaryColorArgb)
        peers.forEach { peer ->
            SensorValue(peer.point, peer.series.viewMode, isMmol, peer.velocity, peer.series.colorArgb,
                Modifier.semantics { contentDescription = peer.series.sensorId })
        }
    }
}

@Composable
private fun SensorValue(
    point: GlucosePoint,
    viewMode: Int,
    isMmol: Boolean,
    velocity: Float,
    identityArgb: Int?,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        identityArgb?.let {
            Box(Modifier.padding(end = 4.dp).size(4.dp).background(Color(it), CircleShape))
        }
        WearGlucoseValue(
            point = point,
            isMmol = isMmol,
            viewMode = viewMode,
            style = readingValueStyle(viewMode),
            primaryColor = tk.glucodata.ui.WearGlucoseColors.valueColor(
                primaryLaneValue(point, viewMode), isMmol, MaterialTheme.colorScheme.onSurface,
            ),
        )
        TrendArrowCanvas(
            velocity = velocity,
            pulseKey = null,
            modifier = Modifier.padding(start = 4.dp).size(12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
