package com.pn.zenify.core

import java.util.concurrent.ConcurrentHashMap

/**
 * Safety net beneath the OS's own force-stopped flag. Force-stopping an app
 * does NOT update its `UsageStats.lastTimeUsed`, so the usage-based run-state
 * heuristic would keep showing a just-hibernated app as "running" in the brief
 * window before the package flag is observed. This tracker remembers when we
 * hibernated each package and treats it as stopped until the app is actually
 * used again (lastUsed moves past the hibernation time) or a live process is
 * seen for it.
 */
object HibernationTracker {

    private val hibernatedAt = ConcurrentHashMap<String, Long>()

    /** Called with a fresh snapshot whenever the map changes, so it can persist. */
    @Volatile private var onChanged: ((Map<String, Long>) -> Unit)? = null

    /**
     * Seed from persisted state and register a sink that re-persists on every
     * change. Without this the map is process-local and an app update wipes it —
     * making just-hibernated apps look "running" again on next launch.
     */
    fun bind(initial: Map<String, Long>, onChanged: (Map<String, Long>) -> Unit) {
        hibernatedAt.putAll(initial)
        this.onChanged = onChanged
    }

    fun mark(pkg: String, now: Long = System.currentTimeMillis()) {
        hibernatedAt[pkg] = now
        persist()
    }

    fun markAll(pkgs: Collection<String>, now: Long = System.currentTimeMillis()) {
        if (pkgs.isEmpty()) return
        pkgs.forEach { hibernatedAt[it] = now }
        persist()
    }

    /** Drop our record for [pkg] — e.g. a live process was seen, so it is not hibernated. */
    fun forget(pkg: String) {
        if (hibernatedAt.remove(pkg) != null) persist()
    }

    /**
     * True if [pkg] was hibernated by us and hasn't been used since. Self-heals:
     * once the app is used again ([lastUsed] passes the hibernation time) the
     * entry is dropped.
     */
    fun isHibernated(pkg: String, lastUsed: Long): Boolean {
        val t = hibernatedAt[pkg] ?: return false
        if (lastUsed > t) {
            hibernatedAt.remove(pkg)
            persist()
            return false
        }
        return true
    }

    private fun persist() {
        onChanged?.invoke(HashMap(hibernatedAt))
    }
}
