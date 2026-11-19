package app.orchard.tv.system

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

internal data class DeviceStats(
    val cpuPercent: Int?,
    val memoryPercent: Int?,
    val storagePercent: Int?,
)

/** Reads device totals. Each reader belongs to one visible Home session. */
internal class DeviceStatsReader(context: Context) {
    private val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val memory = ActivityManager.MemoryInfo()
    private val dataDirectory = Environment.getDataDirectory().path
    private val cpuSource by lazy {
        listOf(::procStat, ::cpuAccounting, ::uptime).firstOrNull { read -> read() != null }
    }
    private var previousCpu: CpuCounter? = null

    fun read(): DeviceStats {
        val cpu = cpuSource?.invoke()
        val percent = previousCpu?.let { before -> cpu?.percentSince(before) }
        previousCpu = cpu
        activity.getMemoryInfo(memory)
        return DeviceStats(
            cpuPercent = percent,
            memoryPercent = usedCapacityPercent(memory.totalMem, memory.availMem),
            storagePercent = runCatching {
                val storage = StatFs(dataDirectory)
                usedCapacityPercent(storage.totalBytes, storage.freeBytes)
            }.getOrNull(),
        )
    }

    private fun procStat(): CpuCounter? = firstLine("/proc/stat")?.let { CpuCounter.parseProcStat(it) }

    private fun cpuAccounting(): CpuCounter? {
        // The root CPU accounting group counts time used by tasks on the whole TV. Some
        // Android builds expose this counter even when they hide /proc/stat from apps.
        val active = firstLine("/acct/cpuacct.usage")?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
            ?: return null
        val cores = onlineCores() ?: return null
        return CpuCounter(SystemClock.uptimeMillis() * 1_000_000.0 * cores, active.toDouble(), cores)
    }

    private fun uptime(): CpuCounter? {
        val line = firstLine("/proc/uptime") ?: return null
        val cores = onlineCores() ?: return null
        return CpuCounter.parseUptime(line, cores)
    }

    private fun onlineCores(): Int? = runCatching { Os.sysconf(OsConstants._SC_NPROCESSORS_ONLN) }
        .getOrNull()?.takeIf { it > 0 }?.toInt()

    private fun firstLine(path: String): String? = try {
        File(path).bufferedReader().use { it.readLine() }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}

internal fun usedCapacityPercent(total: Long, free: Long): Int? {
    if (total <= 0 || free !in 0..total) return null
    return ((total - free) * 100.0 / total).roundToInt()
}

internal data class CpuCounter(val total: Double, val active: Double, val onlineCores: Int = 0) {
    fun percentSince(before: CpuCounter): Int? {
        val elapsed = total - before.total
        val used = active - before.active
        if (onlineCores != before.onlineCores || elapsed <= 0 || used < 0) return null
        return (100 * used / elapsed).roundToInt().coerceIn(0, 100)
    }

    companion object {
        private val spaces = Regex("\\s+")

        fun parseProcStat(line: String): CpuCounter? {
            val fields = line.trim().split(spaces)
            if (fields.firstOrNull() != "cpu" || fields.size < 5) return null
            // Guest time is already included in user and nice. Count it only once.
            val counters = fields.drop(1).take(8).map { it.toLongOrNull()?.takeIf { value -> value >= 0 } ?: return null }
            val total = counters.sum().toDouble()
            return CpuCounter(total, total - counters[3] - counters.getOrElse(4) { 0L })
        }

        fun parseUptime(line: String, cores: Int): CpuCounter? {
            if (cores <= 0) return null
            val fields = line.trim().split(spaces)
            val seconds = fields.getOrNull(0)?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: return null
            val idle = fields.getOrNull(1)?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: return null
            val total = seconds * cores
            return CpuCounter(total, total - idle, cores)
        }
    }
}
