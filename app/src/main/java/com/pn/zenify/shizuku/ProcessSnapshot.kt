package com.pn.zenify.shizuku

/**
 * Live process table as reported by the privileged [UserService], keyed three
 * ways because the different sources know different things:
 *
 *  - [byPackage]: package → importance, from ActivityManager (knows pkgList).
 *  - [byUid]: uid → importance, from `dumpsys`/`ps` (the app maps uid → packages
 *    through PackageManager).
 *  - [byProcess]: process-name prefix (before any ':') → importance; Android
 *    process names default to the package name.
 *
 * [available] is false when Shizuku isn't ready or no source produced data; the
 * maps are then empty and callers must fall back to usage heuristics.
 */
data class ProcessSnapshot(
    val available: Boolean,
    val byPackage: Map<String, Int>,
    val byUid: Map<Int, Int>,
    val byProcess: Map<String, Int>,
) {
    /** Strongest (lowest) importance known for [pkg] / its [uid], or null if no process. */
    fun importanceFor(pkg: String, uid: Int): Int? {
        var best: Int? = null
        fun consider(v: Int?) {
            if (v != null && (best == null || v < best!!)) best = v
        }
        consider(byPackage[pkg])
        consider(byUid[uid])
        consider(byProcess[pkg])
        return best
    }

    companion object {
        val UNAVAILABLE = ProcessSnapshot(false, emptyMap(), emptyMap(), emptyMap())

        /**
         * Parse the newline-separated dump from [IUserService.dumpProcesses]:
         * `pkg=imp`, `uid:1234=imp` or `proc:name=imp` lines. Blank or
         * `error…` output means no source worked.
         */
        fun parse(dump: String): ProcessSnapshot {
            if (dump.isBlank() || dump.startsWith("error", ignoreCase = true)) return UNAVAILABLE
            val byPackage = HashMap<String, Int>()
            val byUid = HashMap<Int, Int>()
            val byProcess = HashMap<String, Int>()
            for (line in dump.lineSequence()) {
                val idx = line.lastIndexOf('=')
                if (idx <= 0) continue
                val key = line.substring(0, idx).trim()
                val imp = line.substring(idx + 1).trim().toIntOrNull() ?: continue
                when {
                    key.startsWith("uid:") ->
                        key.substring(4).toIntOrNull()?.let { byUid.merge(it, imp) { a, b -> minOf(a, b) } }
                    key.startsWith("proc:") ->
                        byProcess.merge(key.substring(5).substringBefore(':'), imp) { a, b -> minOf(a, b) }
                    else -> byPackage.merge(key, imp) { a, b -> minOf(a, b) }
                }
            }
            val available = byPackage.isNotEmpty() || byUid.isNotEmpty() || byProcess.isNotEmpty()
            return ProcessSnapshot(available, byPackage, byUid, byProcess)
        }
    }
}
