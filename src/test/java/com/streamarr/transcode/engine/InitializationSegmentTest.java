package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
class InitializationSegmentTest {

  @Test
  @DisplayName("Should be equal to another initialization segment when their bytes are equal")
  void shouldBeEqualToAnotherInitializationSegmentWhenTheirBytesAreEqual() {
    var first = new InitializationSegment(new byte[] {1, 2, 3});
    var second = new InitializationSegment(new byte[] {1, 2, 3});

    assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
  }

  @Test
  @DisplayName("Should differ from another initialization segment when one byte differs")
  void shouldDifferFromAnotherInitializationSegmentWhenOneByteDiffers() {
    var first = new InitializationSegment(new byte[] {1, 2, 3});

    assertThat(first)
        .isNotEqualTo(new InitializationSegment(new byte[] {1, 2, 4}))
        .isNotEqualTo(new byte[] {1, 2, 3});
  }

  @Test
  @DisplayName("Should describe its length without its bytes when printed")
  void shouldDescribeItsLengthWithoutItsBytesWhenPrinted() {
    assertThat(new InitializationSegment(new byte[] {7, 7}))
        .hasToString("InitializationSegment[2 bytes]");
  }
}
