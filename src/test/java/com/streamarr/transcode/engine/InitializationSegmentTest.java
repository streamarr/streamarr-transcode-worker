package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
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

    assertThat(first).isNotEqualTo(new InitializationSegment(new byte[] {1, 2, 4}));
  }

  @Test
  @DisplayName("Should differ from a fragment when the fragment carries the same bytes")
  void shouldDifferFromAFragmentWhenTheFragmentCarriesTheSameBytes() {
    Mp4Unit fragment = new Fragment(List.of(new byte[] {1, 2, 3}), Optional.empty());

    assertThat(new InitializationSegment(new byte[] {1, 2, 3})).isNotEqualTo(fragment);
  }

  @Test
  @DisplayName("Should describe its length without its bytes when printed")
  void shouldDescribeItsLengthWithoutItsBytesWhenPrinted() {
    assertThat(new InitializationSegment(new byte[] {7, 7}))
        .hasToString("InitializationSegment[2 bytes]");
  }
}
