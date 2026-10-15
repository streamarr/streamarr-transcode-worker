# Sample-entry fixtures

Small sources that FFmpeg's mp4 muxer cannot write. They test ADR 0013's tag rule on real FFmpeg:
every HEVC copy is written as `hvc1` and every H.264 copy as `avc1`.
`../../sample-entry-fixtures/record-fixtures.sh` makes every file. Each source is 2 s of 64x64
`testsrc2` video at 24 fps with a keyframe every second, from FFmpeg's lavfi test sources. No file
comes from a media library.

| File | What it is | How it was made |
|---|---|---|
| `dolby-vision-8.2-dvh1.mp4` | Dolby Vision profile 8.2 under `dvh1`: 10-bit Main 10 HEVC at level 2.0 with a BT.709 SDR compatible base, a `dvvC` record and an RPU after every frame | dovi_tool's profile 8.1 generator writes the RPUs; x265 encodes with them and signals profile 8.2; GPAC muxes with `dvp=f8.2` |
| `hev1-parameter-sets-in-samples.mp4` | 8-bit Main HEVC under `hev1`, whose `hvcC` holds no parameter sets: every keyframe sample carries them | libx265 from the pinned worker image; GPAC muxes with `xps_inband=all` |
| `avc3-parameter-sets-in-samples.mp4` | Constrained Baseline H.264 under `avc3`, whose `avcC` holds no SPS or PPS: every keyframe sample carries them | libx264 from the pinned worker image; GPAC muxes with `xps_inband=all` |
| `versions.txt` | The first version line of FFmpeg, x265, dovi_tool and GPAC from the recording | |

FFmpeg's test sources cannot generate Dolby Vision video. So `dolby-vision-8.2-dvh1.mp4` is the
only committed fixture that they do not generate (ADR 0029). Its RPUs carry dovi_tool's generated
metadata, not metadata from a real master. The tests check only that each frame keeps its RPU.

`WorkerMediaSmokeTest` copies each file with the worker and real FFmpeg. It renames the sample entry
of the `hev1` and `avc3` files to `dvhe` and `dvav` at test time. It checks the sample entry that
each copy carries, that the producer accepts each copy's record, and that each copy decodes. For the
Dolby Vision file it also checks the codec string of the base layer and an RPU in every frame. With
the locked FFmpeg it checks that the copy loses the `dvvC` record, which ADR 0013 accepts.

One test changes a byte of the SEI message in the second keyframe of the `hev1` file. A replacement
attempt from that keyframe must still upload the same initialization segment as an attempt from the
start, and its samples must keep the changed SEI message.

## Regenerating

From the repository root, with Docker:

```
src/test/sample-entry-fixtures/record-fixtures.sh
WORKER_IMAGE=streamarr-worker:local src/test/sample-entry-fixtures/record-fixtures.sh
```

The script overwrites the files in this directory, so `git diff` shows what a new recording
changed. It downloads dovi_tool from its GitHub release and checks the archive's SHA-256. Alpine's
package index supplies x265, so a later run can get a newer x265.
