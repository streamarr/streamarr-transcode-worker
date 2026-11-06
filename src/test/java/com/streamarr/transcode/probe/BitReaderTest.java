package com.streamarr.transcode.probe;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * FFmpeg 8.1's get_ue_golomb_31(), which golomb.c defines by its ff_golomb_vlc_len and
 * ff_ue_golomb_vlc_code tables indexed by the next 9 bits.
 */
@Tag("UnitTest")
@DisplayName("Bit Reader Tests")
class BitReaderTest {

  @ParameterizedTest(name = "{0} reads as {1} over {2} bits")
  @CsvSource({
    "1, 0, 1",
    "00111, 6, 5",
    "000011111, 30, 9",
    "00000100000, 31, 11",
    "00000100011, 31, 11",
    "00000100100, 32, 11",
    "0000001000000, 32, 13",
    "00000000100000000, 32, 17",
    "0000000001000000000, 32, 19",
    "0000000000000000000, 32, 19"
  })
  @DisplayName("Should read a code as FFmpeg's 9-bit table does when it reads ue(v) up to 31")
  void shouldReadACodeAsFfmpegs9BitTableDoesWhenItReadsUpTo31(
      String code, long value, int consumedBits) {
    var reader = new BitReader(bytesOf(code + "1" + "0".repeat(40)));

    assertThat(reader.unsignedExpGolomb31()).isEqualTo(value);
    assertThat(reader.flag()).as("the bit after %d consumed bits", consumedBits).isTrue();
  }

  @ParameterizedTest(name = "{0} leading zeros read as {1} over {2} bits")
  @CsvSource({
    "0, 0, 1",
    "3, 7, 7",
    "31, 2147483647, 63",
    "32, 1073741823, 63",
    "63, 4294967295, 63"
  })
  @DisplayName(
      "Should read a code as FFmpeg's get_ue_golomb_long does, which reads at most 31 leading zeros")
  void shouldReadACodeAsFfmpegsGetUeGolombLongDoes(int zeros, long value, int consumedBits) {
    var code = "0".repeat(zeros) + (zeros < 63 ? "1" : "") + "0".repeat(Math.min(zeros, 31));
    var reader = new BitReader(bytesOf(code.substring(0, consumedBits) + "1" + "0".repeat(40)));

    assertThat(reader.unsignedExpGolombLong()).isEqualTo(value);
    assertThat(reader.flag()).as("the bit after %d consumed bits", consumedBits).isTrue();
  }

  private static byte[] bytesOf(String bits) {
    var padded = bits + "0".repeat((8 - bits.length() % 8) % 8);
    var bytes = new BigInteger("1" + padded, 2).toByteArray();
    var result = new byte[padded.length() / 8];
    System.arraycopy(bytes, bytes.length - result.length, result, 0, result.length);
    return result;
  }
}
