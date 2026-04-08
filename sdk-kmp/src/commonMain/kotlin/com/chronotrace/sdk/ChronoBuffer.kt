package com.chronotrace.sdk

internal class ChronoBuffer<T>(
    private val maxEntries: Int,
    private val overflowStrategy: OverflowStrategy,
) {
    private val items = ArrayDeque<T>()

    fun offer(item: T): Int {
        if (items.size < maxEntries) {
            items.addLast(item)
            return 0
        }
        return when (overflowStrategy) {
            OverflowStrategy.DROP_OLDEST -> {
                items.removeFirst()
                items.addLast(item)
                1
            }

            OverflowStrategy.DROP_NEWEST -> 1
        }
    }

    fun prependAll(entries: List<T>): Int {
        var dropped = 0
        for (entry in entries.asReversed()) {
            if (items.size >= maxEntries) {
                when (overflowStrategy) {
                    OverflowStrategy.DROP_OLDEST -> {
                        items.removeLast()
                    }

                    OverflowStrategy.DROP_NEWEST -> {
                        dropped += 1
                        continue
                    }
                }
                dropped += 1
            }
            items.addFirst(entry)
        }
        return dropped
    }

    fun drain(): List<T> {
        if (items.isEmpty()) {
            return emptyList()
        }
        val drained = items.toList()
        items.clear()
        return drained
    }

    fun size(): Int = items.size
}
