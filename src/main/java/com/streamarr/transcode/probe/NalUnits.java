package com.streamarr.transcode.probe;

import java.util.List;

/**
 * NAL units that the probe delimited in a sample or a decoder configuration record, each without
 * its emulation prevention bytes, and whether they reach the end of the bytes that hold them.
 */
record NalUnits(List<byte[]> units, boolean complete) {}
