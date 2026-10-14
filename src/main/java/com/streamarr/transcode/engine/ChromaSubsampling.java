package com.streamarr.transcode.engine;

/** How a video stream samples its chroma planes against its luma plane. */
public enum ChromaSubsampling {
  MONOCHROME,
  CHROMA_420,
  CHROMA_422,
  CHROMA_444
}
