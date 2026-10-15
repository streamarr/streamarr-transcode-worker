# Initialization-segment fixtures

Initialization segments (`ftyp` and `moov`) that the worker's pipe recipe wrote with the FFmpeg of
the pinned worker image, `streamarr/streamarr-transcode-worker:0.1.0-SNAPSHOT@sha256:9d2d286c…caa`.
`CodecConfigurationReaderTest` reads them and expects the codec strings below.
Every file also carries a stereo AAC track, `mp4a.40.2`.

| File | Source and arguments | Video codec string | Picture format |
|---|---|---|---|
| `libx264-1080p60.mp4` | 1920x1080 at 60 fps, `-profile:v high -level:v 42` | `avc1.64002A` | 8-bit 4:2:0 |
| `libx264-1080p30.mp4` | 1920x1080 at 30 fps, `-profile:v high -level:v 40` | `avc1.640028` | 8-bit 4:2:0 |
| `libsvtav1-1080p60.mp4` | 1920x1080 at 60 fps, `-level:v 41` | `av01.0.09M.08` | 8-bit 4:2:0 |
| `libsvtav1-1080p30.mp4` | 1920x1080 at 30 fps, `-level:v 40` | `av01.0.08M.08` | 8-bit 4:2:0 |
| `libx264-high10.mp4` | 64x36, `format=yuv420p10le` | `avc1.6E000A` | 10-bit 4:2:0 |
| `libx264-high444.mp4` | 64x36, `format=yuv444p` | `avc1.F4000A` | 8-bit 4:4:4 |
| `libsvtav1-10bit.mp4` | 64x36, `format=yuv420p10le` | `av01.0.00M.10` | 10-bit 4:2:0 |
| `copy-hvc1.mp4` | stream copy of a libx265 MP4 tagged `hvc1` | `hvc1.1.6.L120.90` | 8-bit 4:2:0 |
| `copy-hev1.mp4` | stream copy of the same libx265 encode from Matroska | `hev1.1.6.L120.90` | 8-bit 4:2:0 |

The sources are 1-second lavfi `testsrc2` pictures with silent audio, and they are not committed.
An MP4 copy keeps the source's `hvc1` sample entry, and a Matroska copy becomes `hev1`.

To record them again, run `src/test/fmp4-recorder/record-initialization-segments.sh` from the
repository root. It needs Docker and Python 3, and `WORKER_IMAGE` names another image to record
with.
