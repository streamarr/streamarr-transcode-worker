package com.streamarr.transcode.engine;

/**
 * Admits the bytes of the next box before a {@link FragmentedMp4Reader} allocates memory for it.
 */
@FunctionalInterface
interface BoxAdmission {

  /**
   * Returns once the reader may hold the box's bytes; until then the reader reads nothing further.
   * Throws to end reading instead.
   */
  void admit(long boxBytes);
}
