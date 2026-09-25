package com.streamarr.transcode.engine;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/** The initialization segment and the fragments a reader returns from one stream, in order. */
record Mp4Stream(InitializationSegment initializationSegment, List<Fragment> fragments) {

  /** The server's 16 MiB segment cap. */
  static final long SEGMENT_CAP = 16L * 1024 * 1024;

  static FragmentedMp4Reader readerOf(byte[] bytes) {
    return new FragmentedMp4Reader(new ByteArrayInputStream(bytes), SEGMENT_CAP);
  }

  /** Reads every unit up to a clean end of the stream. */
  static Mp4Stream read(FragmentedMp4Reader reader) throws IOException {
    var initializationSegment = Optional.<InitializationSegment>empty();
    var fragments = new ArrayList<Fragment>();
    for (var unit = reader.next(); unit.isPresent(); unit = reader.next()) {
      switch (unit.orElseThrow()) {
        case InitializationSegment segment -> initializationSegment = Optional.of(segment);
        case Fragment fragment -> fragments.add(fragment);
      }
    }

    return new Mp4Stream(
        initializationSegment.orElseThrow(
            () -> new AssertionError("the stream held no initialization segment")),
        fragments);
  }

  static Fragment nextFragment(FragmentedMp4Reader reader) throws IOException {
    return switch (reader.next().orElseThrow()) {
      case Fragment fragment -> fragment;
      case InitializationSegment _ ->
          throw new AssertionError("expected a fragment, read the initialization segment");
    };
  }

  /** The position after the initialization segment at which the reader returned this fragment. */
  int indexOf(Fragment fragment) {
    return IntStream.range(0, fragments.size())
        .filter(index -> fragments.get(index) == fragment)
        .findFirst()
        .orElseThrow();
  }
}
