package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Segment Memory Budget Tests")
class SegmentMemoryBudgetTest {

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  @DisplayName("Should refuse a budget when the worker has no slot")
  void shouldRefuseABudgetWhenTheWorkerHasNoSlot(int slots) {
    assertThatIllegalArgumentException().isThrownBy(() -> SegmentMemoryBudget.forSlots(slots));
  }
}
