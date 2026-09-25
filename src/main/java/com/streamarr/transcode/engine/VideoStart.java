package com.streamarr.transcode.engine;

/**
 * The presentation time of a fragment's first video sample in the video track's timescale, and
 * whether that sample is a sync sample.
 */
record VideoStart(long presentationTime, long timescale, boolean syncSample) {}
