# NVENC recordings (worker #56)

These recordings show how `h264_nvenc` and `hevc_nvenc` handle the worker's FFmpeg recipe. They
decide whether an NVENC encoder joins ADR 0037's list of keyframe-verified encoders. Until a run on
an NVIDIA GPU host commits them, this directory holds only this file, and the tests that read the
recordings are skipped.

## Recording

The recordings need an NVIDIA GPU host: Linux, an NVIDIA GPU, its driver, the NVIDIA Container
Toolkit, Docker, and Node (the major in `buildpacks/ffmpeg/.nvmrc`). From the repository root:

```
src/test/fmp4-recorder/record-nvenc.sh
```

The script writes `recordings.json` and one `NAME.init.mp4` per recording into this directory. Review
`git diff`, then commit both. On any other host the preflight names the missing piece and exits with
status 3 without writing anything. `--help` lists the flags. `--print-commands` prints every planned
FFmpeg command and needs neither Docker nor a GPU.

Every FFmpeg run happens inside the pinned worker image, started through its launcher as the worker
is. The containers get the GPU with `NVIDIA_DRIVER_CAPABILITIES=compute,video,utility`, no network,
no capabilities and a read-only root filesystem. The only mount is a work directory inside the
results directory, which the script removes on every exit. The source is synthesized with lavfi:
35 s of 1920x1080 `testsrc2` at 24000/1001, with a cut to SMPTE bars from 3.5 s to 4.5 s and from
27.5 s to 28.5 s. No media file from the host is read. The run stops at `--time-limit`, 1200 s by
default.

## What is recorded

For each encoder and each rung of the default ladder (1080p, 720p, 480p and 360p), five encodes run:

| Recording | What it runs |
|---|---|
| `ENCODER-RUNG-first` | The worker's command for the job from the start, with the GOP the worker passes today (143 frames) |
| `ENCODER-RUNG-replacement` | The worker's command for a replacement attempt of segment 3, which seeks to 12 s |
| `ENCODER-RUNG-extended-first` | The first attempt with the GOP a keyframe-verified encoder gets (145 frames) |
| `ENCODER-RUNG-extended-replacement` | The replacement attempt with that GOP |
| `ENCODER-RUNG-extended-missed` | The first attempt with that GOP and without the forced keyframe at 18 s |

`NvencRecorderCommandsTest` runs `record-nvenc.sh --print-commands`. It requires each command to equal
the worker's own command for the same job, apart from quiet logging. The extended recordings may
differ only in the GOP and the forced keyframe times.

For each recording, `recordings.json` keeps:

- the plan (encoder, rung, level, tier, expected codec string, GOP, forced keyframe times) and the
  FFmpeg arguments
- every video sample's presentation time, the sync samples, and the NAL unit type that starts each
  keyframe
- the initialization segment's digests, codec string, picture format, and whether its record holds
  its parameter sets
- the verdicts that follow from those facts

The verdicts are:

- whether every forced keyframe is a sync sample
- whether every forced keyframe is an IDR picture with no leading pictures, which starts a closed GOP
- each keyframe the encoder adds, and whether the GOP placed it
- for the missed-keyframe recording, whether the next keyframe lands one GOP after the forced keyframe
  at 12 s, which shows that the GOP count restarted there
- whether each initialization segment passes the codec, codec profile and level check of worker #25

`initializationSegmentPairs` compares each replacement attempt's initialization segment, as the
worker delivers it, with its first attempt's. `environment` records the image, the FFmpeg version,
the driver version and the GPU's compute capability.

## Checking offline

CI derives every result again without FFmpeg or a GPU. `nvenc-recordings.test.mjs` re-reads the
committed initialization segments and re-derives every verdict from the committed facts.
`NvencRecordingsTest` runs the worker's own output check over each initialization segment. The same
check runs by hand:

```
node src/test/fmp4-recorder/nvenc-recordings.mjs check
```
