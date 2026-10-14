package com.streamarr.transcode.engine;

import lombok.NonNull;

/** A video stream's bit depth and chroma subsampling. */
public record PictureFormat(int bitDepth, @NonNull ChromaSubsampling chromaSubsampling) {

  /** The 8-bit 4:2:0 format that every encode produces. */
  public static final PictureFormat EIGHT_BIT_420 =
      new PictureFormat(8, ChromaSubsampling.CHROMA_420);
}
