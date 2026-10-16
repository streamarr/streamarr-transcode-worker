package com.streamarr.transcode.engine;

/**
 * Admits the bytes of the next box, or the heap of the samples it is about to build from a
 * fragment, before a {@link FragmentedMp4Reader} allocates memory for them.
 */
@FunctionalInterface
interface BoxAdmission {

  /**
   * Returns true once the reader may hold the bytes, and false when the reader must end reading
   * instead; until it returns, the reader reads nothing further.
   *
   * @throws FragmentedMp4Exception when the bytes can never be admitted, which fails the output
   */
  boolean tryAdmit(long bytes);
}
