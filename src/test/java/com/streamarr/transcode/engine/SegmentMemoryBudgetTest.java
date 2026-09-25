package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Segment Memory Budget Tests")
class SegmentMemoryBudgetTest {

  private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);
  private static final long MIB = 1024L * 1024;
  private static final long ONE_SLOT = Producer.BUDGET_BYTES;

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  @DisplayName("Should refuse a budget when the worker has no slot")
  void shouldRefuseABudgetWhenTheWorkerHasNoSlot(int slots) {
    assertThatIllegalArgumentException().isThrownBy(() -> SegmentMemoryBudget.forSlots(slots));
  }

  @Test
  @DisplayName("Should refuse bytes that fit at once when an earlier reader waits for memory")
  void shouldRefuseBytesThatFitAtOnceWhenAnEarlierReaderWaitsForMemory() {
    var budget = SegmentMemoryBudget.forSlots(1);
    assertThat(budget.tryReserveAtOnce(ONE_SLOT - MIB)).isTrue();
    var earlier = reserveOnAnotherThread(budget, 2 * MIB, () -> false);

    assertThat(budget.tryReserveAtOnce(MIB)).isFalse();

    budget.release(ONE_SLOT - MIB);
    assertThat(earlier).succeedsWithin(WAIT_LIMIT).isEqualTo(true);
    assertThat(budget.tryReserveAtOnce(MIB)).isTrue();
  }

  @Test
  @DisplayName(
      "Should reserve for the next waiting reader once the reader ahead of it withdraws when the"
          + " next one's bytes fit")
  void
      shouldReserveForTheNextWaitingReaderOnceTheReaderAheadOfItWithdrawsWhenTheNextOnesBytesFit() {
    var budget = SegmentMemoryBudget.forSlots(1);
    assertThat(budget.tryReserveAtOnce(ONE_SLOT - MIB)).isTrue();
    var withdrawn = new AtomicBoolean();
    var ahead = reserveOnAnotherThread(budget, 2 * MIB, withdrawn::get);
    var next = reserveOnAnotherThread(budget, MIB, () -> false);

    withdrawn.set(true);
    budget.release(0);

    assertThat(ahead).succeedsWithin(WAIT_LIMIT).isEqualTo(false);
    assertThat(next).succeedsWithin(WAIT_LIMIT).isEqualTo(true);
  }

  // Returns once the reader waits for memory.
  private static CompletableFuture<Boolean> reserveOnAnotherThread(
      SegmentMemoryBudget budget, long bytes, BooleanSupplier withdrawn) {
    var reserved = new CompletableFuture<Boolean>();
    var reader =
        Thread.ofVirtual().start(() -> reserved.complete(budget.tryReserve(bytes, withdrawn)));
    await().atMost(WAIT_LIMIT).until(() -> reader.getState() == Thread.State.WAITING);
    return reserved;
  }
}
