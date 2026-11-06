package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.PlannedCopyOutput.ContentLightLevel;
import com.streamarr.transcode.engine.PlannedCopyOutput.MasteringDisplay;
import lombok.NonNull;

/**
 * The HDR10 static metadata that a PQ encode writes: the mastering display color volume and the
 * content light level, in the units of the HEVC SEI messages' fields. Every job attempt of a
 * variant carries the same values, which the server stored, so no attempt reads them from frames.
 */
public record StaticHdrMetadata(
    @NonNull MasteringDisplay masteringDisplay, @NonNull ContentLightLevel contentLightLevel) {}
