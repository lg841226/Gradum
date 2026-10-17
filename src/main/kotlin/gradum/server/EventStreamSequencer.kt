package gradum.server

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Assigns a monotonic `seq` to every event streamed back to one client, and
 * tracks how many events the transport had to drop.
 *
 * The sequence is the client's only way to notice it missed part of a stream:
 * a number is claimed even for dropped events, so a discontinuity plus the
 * `gap` reported on the next delivered event tell the client exactly how many
 * events it lost. Without this a stalled client silently rebuilds an
 * incomplete conversation and never knows it drifted.
 *
 * One instance belongs to one `/events` request. The producer is expected to
 * emit from a single logical stream, so atomic counters are enough.
 */
class EventStreamSequencer {
  private val nextSequence: AtomicLong = AtomicLong(0)
  private val droppedEvents: AtomicInteger = AtomicInteger(0)

  /** Reserves the sequence number for one outgoing event. */
  fun claimSequence(): Long = nextSequence.incrementAndGet()

  /** Events dropped since the last successful delivery; reported as `gap`. */
  fun pendingGap(): Int = droppedEvents.get()

  /** Records one event the transport refused (channel full). */
  fun recordDrop() {
    droppedEvents.incrementAndGet()
  }

  /** Clears the drop counter once an event reached the transport. */
  fun markDelivered() {
    droppedEvents.set(0)
  }

  /** Drops recorded but never reported, because the stream ended first. */
  fun unreportedDropCount(): Int = droppedEvents.get()
}
