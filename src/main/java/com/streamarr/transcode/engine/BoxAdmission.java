package com.streamarr.transcode.engine;

/**
 * Admits the bytes of the next box before a {@link FragmentedMp4Reader} allocates memory for it.
 */
@FunctionalInterface
interface BoxAdmission {

  /**
   * Returns true once the reader may hold the box's bytes, and false when the reader must end
   * reading instead; until it returns, the reader reads nothing further.
   */
  boolean tryAdmit(long boxBytes);
}
