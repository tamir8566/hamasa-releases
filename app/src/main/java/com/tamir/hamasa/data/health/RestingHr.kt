package com.tamir.hamasa.data.health

import android.content.Context
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.RestingHeartRateRecord
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.energy.RestingHrModel

/**
 * Daily resting heart rate from the watch. Samsung Health directly first: it has no resting heart rate type, so
 * [SamsungHealth.restingHrByDay] gives a proxy (lowest 5-minute average during that morning's night). Health
 * Connect's RestingHeartRateRecord (optional permission READ_RESTING_HEART_RATE) only when Samsung gives nothing;
 * the two are never mixed in one read (different measures would skew the baseline).
 * Stored in Prefs.restingHrCsv (last ~40 days, synced to the cloud with the profile). The analysis is
 * domain/energy/RestingHrModel. Every failure is swallowed: no permission simply means no data.
 */
object RestingHr {

    val PERMISSION: String by lazy { HealthPermission.getReadPermission(RestingHeartRateRecord::class) }

    /** Called by WatchSync.sync when the watch is connected: the whole window when empty, otherwise the last 4 days. */
    suspend fun sync(context: Context) {
        val today = Days.today()
        val have = RestingHrModel.decode(AppGraph.prefs.current().restingHrCsv)
        val from = if (have.size < 5) today - RestingHrModel.KEEP_DAYS + 1 else today - 3
        // Samsung: one heart rate read per night, so the first fill is capped at 14 nights (the rest builds up daily)
        val sh = if (SamsungHealth.canReadNow(context, SamsungHealth.Kind.HEART_RATE)) {
            SamsungHealth.restingHrByDay(context, maxOf(from, today - 13), today)
        } else emptyMap()
        val fresh = if (sh.isNotEmpty()) sh else HealthConnect.restingHrByDay(context, from, today)
        if (fresh.isEmpty()) return
        AppGraph.prefs.update { p ->
            val merged = RestingHrModel.merge(RestingHrModel.decode(p.restingHrCsv), fresh, today)
            p.copy(restingHrCsv = RestingHrModel.encode(merged))
        }
    }

    /** Samsung Health directly (heart rate + sleep, for the proxy) or Health Connect's resting heart rate permission. */
    suspend fun hasPermission(context: Context): Boolean =
        runCatching {
            (SamsungHealth.canReadNow(context, SamsungHealth.Kind.HEART_RATE) && SamsungHealth.canRead(SamsungHealth.Kind.SLEEP)) ||
                PERMISSION in HealthConnect.grantedPermissions(context)
        }.getOrDefault(false)

    fun status(csv: String, today: Long = Days.today()): RestingHrModel.Status? =
        RestingHrModel.status(RestingHrModel.decode(csv), today)

    suspend fun elevatedNow(): Boolean =
        runCatching { status(AppGraph.prefs.current().restingHrCsv)?.elevated == true }.getOrDefault(false)
}
