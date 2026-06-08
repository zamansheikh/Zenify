package com.pn.zenify.shizuku

import android.os.IBinder
import java.io.BufferedReader
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
    constructor(@Suppress("UNUSED_PARAMETER") context: android.content.Context) : super()

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
     * Reads live process importance for every app. Running as the shell uid we
     * have REAL_GET_TASKS, so [android.app.ActivityManager.getRunningAppProcesses]
     * returns the whole system, not just us. Returns "pkg=importance" lines.
     */
    override fun dumpProcesses(): String {
        return try {
            val activityThread = Class.forName("android.app.ActivityThread")
            val systemMain = activityThread.getMethod("systemMain").invoke(null)
            val context = activityThread.getMethod("getSystemContext").invoke(systemMain)
                as android.content.Context
            val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE)
                as android.app.ActivityManager

            val sb = StringBuilder()
            val best = HashMap<String, Int>()
            am.runningAppProcesses?.forEach { proc ->
                val pkgs = proc.pkgList ?: arrayOf(proc.processName)
                for (pkg in pkgs) {
                    val prev = best[pkg]
                    // Lower importance value == more important; keep the strongest.
                    if (prev == null || proc.importance < prev) best[pkg] = proc.importance
                }
            }
            best.forEach { (pkg, imp) -> sb.append(pkg).append('=').append(imp).append('\n') }
            sb.toString()
        } catch (t: Throwable) {
            "error: ${t.message}"
        }
    }

    private fun runCommand(argv: List<String>): String {
        return try {
            val process = ProcessBuilder(argv)
                .redirectErrorStream(true)
                .start()
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            process.waitFor()
            output
        } catch (t: Throwable) {
            "error: ${t.message}"
        }
    }

    @Suppress("unused")
    fun asBinderInternal(): IBinder = this
}
