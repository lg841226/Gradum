package gradum.server

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Assigns a monotonic `seq` to every event streamed to one client, and counts
 * events the transport had to drop.
 *
 * The seq is the client's only way to detect a missed part of the stream: a
 * dropped event still claims a number, so a discontinuity plus the `gap` on the
 * next delivered event tells the client exactly how many events it lost.
 * Without this, a stalled client silently rebuilds an incomplete conversation
 * and never knows it drifted.
 *
 * One instance belongs to one `/events` request. The producer emits from a
 * single logical stream, so atomic counters suffice. Per event:
 * [claimSequence], then [recordDrop] if the transport refuses it or
 * [markDelivered] once it is out, so [pendingGap] counts exactly the drops
 * since the last delivery.
 */
class EventStreamSequencer {
  private val nextSequence: AtomicLong = AtomicLong(0)

  private val droppedEvents: AtomicInteger = AtomicInteger(0)

  fun claimSequence(): Long = nextSequence.incrementAndGet()

  fun pendingGap(): Int = droppedEvents.get()

  fun recordDrop() {
    droppedEvents.incrementAndGet()
  }

  fun markDelivered() {
    droppedEvents.set(0)
  }
}
