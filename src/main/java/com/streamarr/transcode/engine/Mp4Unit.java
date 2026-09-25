package com.streamarr.transcode.engine;

/** A unit of FFmpeg's fragmented MP4 output: the initialization segment, then each fragment. */
sealed interface Mp4Unit permits InitializationSegment, Fragment {}
