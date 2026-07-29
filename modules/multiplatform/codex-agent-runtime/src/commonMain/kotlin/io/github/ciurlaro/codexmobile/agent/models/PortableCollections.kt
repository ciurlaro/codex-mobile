@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.ciurlaro.codexmobile.agent

import kotlin.concurrent.atomics.AtomicBoolean

internal class PortableLock {
    private val locked = AtomicBoolean(false)

    inline fun <T> withLock(block: () -> T): T {
        // ponytail: critical sections are tiny; replace with a platform mutex if contention is measured.
        while (!locked.compareAndSet(false, true)) Unit
        return try {
            block()
        } finally {
            locked.store(false)
        }
    }
}

internal class PortableMutableMap<K, V> : MutableMap<K, V> {
    private val lock = PortableLock()
    private val valuesByKey = mutableMapOf<K, V>()

    override val size: Int get() = lock.withLock { valuesByKey.size }
    override fun isEmpty(): Boolean = lock.withLock(valuesByKey::isEmpty)
    override fun containsKey(key: K): Boolean = lock.withLock { valuesByKey.containsKey(key) }
    override fun containsValue(value: V): Boolean = lock.withLock { valuesByKey.containsValue(value) }
    override fun get(key: K): V? = lock.withLock { valuesByKey[key] }
    override fun put(key: K, value: V): V? = lock.withLock { valuesByKey.put(key, value) }
    override fun remove(key: K): V? = lock.withLock { valuesByKey.remove(key) }
    override fun putAll(from: Map<out K, V>) = lock.withLock { valuesByKey.putAll(from) }
    override fun clear() = lock.withLock(valuesByKey::clear)
    override val keys: MutableSet<K> get() = lock.withLock { valuesByKey.keys.toMutableSet() }
    override val values: MutableCollection<V> get() = lock.withLock { valuesByKey.values.toMutableList() }
    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = lock.withLock { valuesByKey.toMutableMap().entries }

    fun putIfAbsent(key: K, value: V): V? = lock.withLock {
        valuesByKey[key]?.also { return@withLock it }
        valuesByKey[key] = value
        null
    }

    fun remove(key: K, value: V): Boolean = lock.withLock {
        if (valuesByKey[key] != value) false else valuesByKey.remove(key).let { true }
    }

    fun removeWhere(predicate: (Map.Entry<K, V>) -> Boolean): Boolean = lock.withLock {
        val keys = valuesByKey.filter(predicate).keys
        keys.forEach(valuesByKey::remove)
        keys.isNotEmpty()
    }
}

internal class PortableMutableSet<E> : MutableSet<E> {
    private val lock = PortableLock()
    private val values = mutableSetOf<E>()

    override val size: Int get() = lock.withLock { values.size }
    override fun isEmpty(): Boolean = lock.withLock(values::isEmpty)
    override fun contains(element: E): Boolean = lock.withLock { element in values }
    override fun containsAll(elements: Collection<E>): Boolean = lock.withLock { values.containsAll(elements) }
    override fun add(element: E): Boolean = lock.withLock { values.add(element) }
    override fun addAll(elements: Collection<E>): Boolean = lock.withLock { values.addAll(elements) }
    override fun clear() = lock.withLock(values::clear)
    override fun iterator(): MutableIterator<E> = lock.withLock { values.toMutableSet().iterator() }
    override fun remove(element: E): Boolean = lock.withLock { values.remove(element) }
    override fun removeAll(elements: Collection<E>): Boolean = lock.withLock { values.removeAll(elements.toSet()) }
    override fun retainAll(elements: Collection<E>): Boolean = lock.withLock { values.retainAll(elements.toSet()) }
}
