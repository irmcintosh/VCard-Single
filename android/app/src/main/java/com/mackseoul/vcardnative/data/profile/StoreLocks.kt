package com.mackseoul.vcardnative.data.profile

import java.util.concurrent.ConcurrentHashMap

/** Process-local only. The application does not support a second process mutating its store. */
internal object StoreLocks {
    private val locks = ConcurrentHashMap<String, Any>()
    private val uncertainKeys = ConcurrentHashMap.newKeySet<Any>()
    private val unsyncedRemovals = ConcurrentHashMap.newKeySet<Any>()
    fun forCanonicalRoot(path: String): Any = locks.computeIfAbsent(path) { Any() }
    fun markUncertain(key: Any) { uncertainKeys.add(key) }
    fun isUncertain(key: Any): Boolean = key in uncertainKeys
    /** Only after the exact replacement directory has been verified. */
    fun clearUncertain(key: Any) { uncertainKeys.remove(key) }
    /** An operation record was unlinked but its directory sync has not been confirmed yet. */
    fun markRemovalUnsynced(key: Any) { unsyncedRemovals.add(key) }
    fun isRemovalUnsynced(key: Any): Boolean = key in unsyncedRemovals
    fun clearRemovalUnsynced(key: Any) { unsyncedRemovals.remove(key) }
}
