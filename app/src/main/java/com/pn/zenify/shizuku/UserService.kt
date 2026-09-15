package com.pn.zenify.shizuku

import android.app.ActivityManager
import android.content.Context
import android.os.IBinder
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.system.exitProcess

/**
 * Runs inside the privileged process spawned by Shizuku (shell uid 2000, or
 * root uid 0 if Shizuku was started as root). From here `am force-stop` works
 * without the caller holding any special system permission — exactly how a
 * root-free hibernation engine is supposed to behave.
 *
 * Shizuku instantiates this class reflectively, so the no-arg constructor and
 * the [destroy]/[exit] contract below must stay intact.
 */
class UserService : IUserService.Stub {

    @Suppress("unused")
    constructor() : super()

    /** Shizuku also looks for a (Context) constructor; keep it tolerant. */
    @Suppress("unused")
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : super()

    private companion object {
        /**
         * One entry of the ActivityManager LRU dump. The columns between the
         * entry number and the `pid:process/uid` token changed across releases:
         *   Android 8-14: `#12: cch+ 3 T/ /CAC  trm: 0 4566:com.foo/u0a137 (cch-empty)`
         *   Android 15+:  `#94: cch  + 5 SVC  --------- 4320:com.foo/u0a651 act:recents`
         * so group 1 captures that whole middle and the proc-state token is
         * picked out of it by name. Group 2 = pid, 3 = process name, 4 = uid.
         */
        val LRU_LINE = Regex("""#\s*\d+:\s+(.+?)\s+(\d+):([^\s/]+)/(\S+)""")

        /** ProcessList.makeProcStateString tokens (see [importanceForProcState]). */
        val PROC_STATES = setOf(
            "PER", "PERU", "TOP", "BTOP", "FGS", "BFGS", "TBND", "IMPF", "IMPB", "TRNB",
            "TPSL", "BKUP", "SVC", "RCVR", "HVY", "HOME", "LAST", "CAC", "CACC", "CACS",
            "CRE", "CEM", "NONE",
        )

        /** Uid tokens as printed by UserHandle.formatUid: `u0a137`, `u0s1000`, `u0i12`, or plain `1000`. */
        val UID_TOKEN = Regex("""u(\d+)([ais])(\d+)""")

        const val FIRST_APPLICATION_UID = 10000
        const val PER_USER_RANGE = 100000
    }

    override fun destroy() {
        exitProcess(0)
    }

    override fun exit() {
        destroy()
    }

    override fun execute(command: Array<out String>): String {
        return runCommand(command.toList())
    }

    override fun forceStop(packageName: String): String {
        // `am force-stop` is the most reliable hibernation primitive available
        // to the shell uid across OEM ROMs.
        val out = runCommand(listOf("am", "force-stop", packageName))
        return out.trim()
    }

    /**
     * Reads the live process table for the whole system. Running as the shell
     * uid we hold REAL_GET_TASKS and DUMP, so unlike a normal app we see every
     * process, not just our own. Three sources, best first, so one broken
     * hidden-API path on some ROM never blanks the whole feature:
     *
     *  1. `ActivityManager.getRunningAppProcesses()` — real importance + pkgList
     *     (`pkg=imp` lines).
     *  2. `dumpsys activity lru` (or `processes`) — the OOM-adjuster's own list
     *     with each process's proc-state, mapped to importance
     *     (`proc:name=imp` + `uid:N=imp` lines).
     *  3. `ps -A` — bare process existence, reported as "working"
     *     (`proc:name=300` + `uid:N=300` lines).
     *
     * Returns an `error: …` string only if every source failed.
     */
    override fun dumpProcesses(): String {
        val errors = StringBuilder()
        viaActivityManager(errors)?.let { return it }
        viaDumpsysLru(errors)?.let { return it }
        viaPs(errors)?.let { return it }
        return "error: no process source available ($errors)"
    }

    // ---- Source 1: ActivityManager -----------------------------------------

    private fun viaActivityManager(errors: StringBuilder): String? {
        val procs = runCatching { processesViaSystemContext() }
            .recoverCatching { t ->
                errors.append("am-ctx: ").append(t).append("; ")
                processesViaBinder()
            }
            .getOrElse { t ->
                errors.append("am-binder: ").append(t).append("; ")
                return null
            }
        // Only ourselves back means REAL_GET_TASKS wasn't honoured — useless.
        if (procs.size <= 1) {
            errors.append("am: only ${procs.size} process(es) visible; ")
            return null
        }
        val best = HashMap<String, Int>()
        for (proc in procs) {
            val pkgs = proc.pkgList?.takeIf { it.isNotEmpty() }
                ?: arrayOf(proc.processName.substringBefore(':'))
            for (pkg in pkgs) {
                // Lower importance value == more important; keep the strongest.
                best.merge(pkg, proc.importance) { a, b -> minOf(a, b) }
            }
        }
        val sb = StringBuilder()
        best.forEach { (pkg, imp) -> sb.append(pkg).append('=').append(imp).append('\n') }
        return sb.toString()
    }

    private fun processesViaSystemContext(): List<ActivityManager.RunningAppProcessInfo> {
        val activityThread = Class.forName("android.app.ActivityThread")
        val systemMain = activityThread.getMethod("systemMain").invoke(null)
        val context = activityThread.getMethod("getSystemContext").invoke(systemMain) as Context
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.runningAppProcesses ?: emptyList()
    }

    @Suppress("UNCHECKED_CAST")
    private fun processesViaBinder(): List<ActivityManager.RunningAppProcessInfo> {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val binder = serviceManager.getMethod("getService", String::class.java)
            .invoke(null, "activity") as IBinder
        val stub = Class.forName("android.app.IActivityManager\$Stub")
        val am = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        val list = Class.forName("android.app.IActivityManager")
            .getMethod("getRunningAppProcesses")
            .invoke(am) as? List<ActivityManager.RunningAppProcessInfo>
        return list ?: emptyList()
    }

    // ---- Source 2: dumpsys activity lru -------------------------------------

    private fun viaDumpsysLru(errors: StringBuilder): String? {
        var out = runCommand(listOf("dumpsys", "activity", "lru"))
        if (!out.contains("trm:")) out = runCommand(listOf("dumpsys", "activity", "processes"))
        if (!out.contains("trm:")) {
            errors.append("dumpsys: no LRU entries (${out.trim().take(80)}); ")
            return null
        }
        val sb = StringBuilder()
        var parsed = 0
        for (line in out.lineSequence()) {
            val m = LRU_LINE.find(line) ?: continue
            val stateToken = procStateToken(m.groupValues[1])
            val processName = m.groupValues[3]
            val uid = parseUid(m.groupValues[4])
            val imp = importanceForProcState(stateToken)
            sb.append("proc:").append(processName).append('=').append(imp).append('\n')
            // Only app uids: a system-uid line would wrongly light up every
            // package sharing uid 1000 (Settings, framework…).
            if (uid != null && uid % PER_USER_RANGE >= FIRST_APPLICATION_UID) {
                sb.append("uid:").append(uid).append('=').append(imp).append('\n')
            }
            parsed++
        }
        if (parsed == 0) {
            errors.append("dumpsys: LRU lines did not parse; ")
            return null
        }
        return sb.toString()
    }

    /**
     * The proc-state column out of the LRU entry's middle columns: the last
     * token that is a known state name (older dumps glue it to the scheduling
     * columns as `T/A/TOP`, so '/' counts as a separator).
     */
    private fun procStateToken(middle: String): String {
        val tokens = middle.replace('/', ' ').trim().split(Regex("\\s+"))
        return tokens.lastOrNull { it.uppercase() in PROC_STATES }?.uppercase()
            ?: tokens.lastOrNull()?.uppercase().orEmpty()
    }

    /** `u0a137` → 10137, `u0s1000` → 1000, `1000` → 1000, isolated (`u0i…`) → null. */
    private fun parseUid(token: String): Int? {
        token.toIntOrNull()?.let { return it }
        val m = UID_TOKEN.matchEntire(token) ?: return null
        val user = m.groupValues[1].toIntOrNull() ?: return null
        val n = m.groupValues[3].toIntOrNull() ?: return null
        return when (m.groupValues[2]) {
            "a" -> user * PER_USER_RANGE + FIRST_APPLICATION_UID + n
            "s" -> user * PER_USER_RANGE + n
            else -> null
        }
    }

    /**
     * ProcessList.makeProcStateString tokens → RunningAppProcessInfo importance
     * (mirrors RunningAppProcessInfo.procStateToImportance).
     */
    private fun importanceForProcState(state: String): Int = when (state) {
        "PER", "PERU" -> 50                       // persistent system processes
        "TOP", "BTOP" -> 100                      // foreground
        "FGS", "BFGS", "TBND" -> 125              // foreground service / bound to top
        "IMPF" -> 200                             // visible
        "IMPB", "TRNB" -> 230                     // perceptible
        "TPSL", "BKUP", "SVC", "RCVR", "HVY" -> 300 // service / receiver / heavy-weight
        "HOME", "LAST", "CAC", "CACC", "CACS", "CRE", "CEM" -> 400 // cached
        "NONE" -> 1000
        else -> 300                               // unknown but alive → treat as working
    }

    // ---- Source 3: ps ---------------------------------------------------------

    private fun viaPs(errors: StringBuilder): String? {
        val out = runCommand(listOf("ps", "-A", "-o", "UID,NAME"))
        val sb = StringBuilder()
        var parsed = 0
        for (line in out.lineSequence()) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 2) continue
            val uid = parts[0].toIntOrNull()
                ?: parseUid(parts[0].replace("_", ""))   // some toybox builds print u0_a137
                ?: continue
            val name = parts[1]
            // App processes are named after their package (plus an optional :suffix).
            if (uid % PER_USER_RANGE < FIRST_APPLICATION_UID || !name.contains('.')) continue
            sb.append("proc:").append(name).append("=300\n")
            sb.append("uid:").append(uid).append("=300\n")
            parsed++
        }
        if (parsed == 0) {
            errors.append("ps: no app processes (${out.trim().take(80)}); ")
            return null
        }
        return sb.toString()
    }

    // ---- Shell ----------------------------------------------------------------

    private fun runCommand(argv: List<String>): String {
        val direct = exec(argv)
        if (direct != null) return direct
        // PATH can be minimal in the app_process host; retry with the absolute binary.
        val abs = "/system/bin/" + argv.first()
        if (File(abs).exists()) exec(listOf(abs) + argv.drop(1))?.let { return it }
        return "error: cannot run ${argv.first()}"
    }

    private fun exec(argv: List<String>): String? {
        return try {
            val process = ProcessBuilder(argv)
                .redirectErrorStream(true)
                .start()
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            process.waitFor()
            output
        } catch (t: Throwable) {
            null
        }
    }

    @Suppress("unused")
    fun asBinderInternal(): IBinder = this
}
