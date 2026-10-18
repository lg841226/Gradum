package gradum.server

import kotlin.test.Test
import kotlin.test.assertEquals

class EventStreamSequencerTest {

  @Test
  fun `sequence starts at one and increases monotonically`() {
    val sequencer = EventStreamSequencer()

    assertEquals(1L, sequencer.claimSequence())
    assertEquals(2L, sequencer.claimSequence())
    assertEquals(3L, sequencer.claimSequence())
  }

  @Test
  fun `gap stays zero while every event is delivered`() {
    val sequencer = EventStreamSequencer()

    sequencer.claimSequence()
    sequencer.markDelivered()

    assertEquals(0, sequencer.pendingGap())
  }

  @Test
  fun `a dropped event is reported as a gap on the next delivery`() {
    val sequencer = EventStreamSequencer()

    sequencer.claimSequence()
    sequencer.recordDrop()

    assertEquals(1, sequencer.pendingGap())

    sequencer.claimSequence()
    sequencer.markDelivered()

    assertEquals(0, sequencer.pendingGap())
  }

  @Test
  fun `consecutive drops accumulate into a single gap`() {
    val sequencer = EventStreamSequencer()

    repeat(times = 3) {
      sequencer.claimSequence()
      sequencer.recordDrop()
    }

    assertEquals(3, sequencer.pendingGap())
  }

  @Test
  fun `a delivered event after two drops jumps by the drop count plus one`() {
    val sequencer = EventStreamSequencer()

    val firstSequence: Long = sequencer.claimSequence()

    sequencer.claimSequence()
    sequencer.recordDrop()
    sequencer.claimSequence()
    sequencer.recordDrop()

    val secondSequence: Long = sequencer.claimSequence()

    assertEquals(3L, secondSequence - firstSequence)
    assertEquals(2, sequencer.pendingGap())
  }

  @Test
  fun `drops never reported before the stream ends stay pending`() {
    val sequencer = EventStreamSequencer()

    sequencer.claimSequence()
    sequencer.recordDrop()

    assertEquals(1, sequencer.pendingGap())
  }
}
