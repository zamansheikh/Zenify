package com.pn.zenify.core

import java.util.concurrent.ConcurrentHashMap

/**
 * Force-stopping an app does NOT update its `UsageStats.lastTimeUsed`, so the
 * usage-based run-state heuristic would keep showing a just-hibernated app as
 * "running". This tracker remembers when we hibernated each package and treats
 * it as stopped until the app is actually used again (lastUsed moves past the
 * hibernation time).
 */
object HibernationTracker {

    private val hibernatedAt = ConcurrentHashMap<String, Long>()

    fun mark(pkg: String, now: Long = System.currentTimeMillis()) {
        hibernatedAt[pkg] = now
    }

    fun markAll(pkgs: Collection<String>, now: Long = System.currentTimeMillis()) {
        pkgs.forEach { hibernatedAt[it] = now }
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
            return false
        }
        return true
    }
}
