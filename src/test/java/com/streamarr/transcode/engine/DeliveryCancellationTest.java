package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Delivery Cancellation Tests")
class DeliveryCancellationTest {

  @Test
  @DisplayName("Should run the registered action once when the delivery is cancelled twice")
  void shouldRunTheRegisteredActionOnceWhenTheDeliveryIsCancelledTwice() {
    var cancellation = new DeliveryCancellation();
    var runs = new AtomicInteger();
    cancellation.onCancel(runs::incrementAndGet);

    cancellation.cancel();
    cancellation.cancel();

    assertThat(runs).hasValue(1);
  }

  @Test
  @DisplayName("Should refuse a second action when the delivery already has one")
  void shouldRefuseASecondActionWhenTheDeliveryAlreadyHasOne() {
    var cancellation = new DeliveryCancellation();
    var runs = new AtomicInteger();
    cancellation.onCancel(runs::incrementAndGet);

    assertThatIllegalStateException()
        .isThrownBy(() -> cancellation.onCancel(runs::incrementAndGet));
    cancellation.cancel();
    assertThat(runs).hasValue(1);
  }

  @Test
  @DisplayName("Should run an action at once when the sink registers it after the cancellation")
  void shouldRunAnActionAtOnceWhenTheSinkRegistersItAfterTheCancellation() {
    var cancellation = new DeliveryCancellation();
    var runs = new AtomicInteger();
    cancellation.cancel();

    cancellation.onCancel(runs::incrementAndGet);

    assertThat(runs).hasValue(1);
  }

  @Test
  @DisplayName("Should run no action when the delivery is never cancelled")
  void shouldRunNoActionWhenTheDeliveryIsNeverCancelled() {
    var cancellation = new DeliveryCancellation();
    var runs = new AtomicInteger();

    cancellation.onCancel(runs::incrementAndGet);

    assertThat(runs).hasValue(0);
  }
}
