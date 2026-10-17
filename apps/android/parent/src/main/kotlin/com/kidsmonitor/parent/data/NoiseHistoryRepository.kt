package com.kidsmonitor.parent.data

import com.kidsmonitor.shared.net.HubApi
import com.kidsmonitor.shared.protocol.NoiseSample

const val NOISE_DAY_MS = 86_400_000L

data class NoiseHistory(val samples: List<NoiseSample> = emptyList(), val events: List<EventEntity> = emptyList())

class NoiseHistoryRepository(private val db: ParentDatabase, private val api: HubApi) {
    suspend fun cached(id: String, nowMs: Long): NoiseHistory = NoiseHistory(
        db.noise().since(id, nowMs - NOISE_DAY_MS).map { it.sample() },
        (db.events().inWindow(id, nowMs - NOISE_DAY_MS) + db.noise().alertsSince(id, nowMs - NOISE_DAY_MS).map { it.event() })
            .distinctBy { it.id }.sortedBy { it.tsMs },
    )

    /** Fetch only new levels (with one interval of overlap); markers cover the complete day. */
    suspend fun refresh(id: String, nowMs: Long): Pair<NoiseHistory, Boolean> {
        val since = db.noise().latestEnd(id)?.let { maxOf(nowMs - NOISE_DAY_MS, it - 15_000) }
        val response = api.noiseHistory(id, since)
        db.noise().upsert(response.samples.map { NoiseEntity(id, it.startMs, it.endMs, it.levelDb, it.peakDb, it.thresholdDb) })
        db.noise().upsertAlerts(response.events.map { it.toNoiseAlert() })
        db.noise().prune(nowMs - 2 * NOISE_DAY_MS)
        db.noise().pruneAlerts(nowMs - 2 * NOISE_DAY_MS)
        return cached(id, nowMs) to response.eventsTruncated
    }
}
