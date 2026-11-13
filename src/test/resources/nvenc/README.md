# NVENC recordings (worker #56)

These recordings show how `h264_nvenc` and `hevc_nvenc` handle the worker's FFmpeg recipe. They
decide whether an NVENC encoder joins ADR 0037's list of keyframe-verified encoders, and whether
the worker tests `hevc_nvenc` for its 10-bit, PQ and HLG outputs and for the GPU reshape of Dolby
Vision into PQ.

## Results

The committed recordings ran on an RTX 5090 (compute capability 12.0) with NVIDIA driver
615.78.08, in the pinned worker image with FFmpeg 8.1.3. `recordings.json` names the image. At every
rung, for both encoders, with `-bf 3 -rc-lookahead 20`:

- every forced keyframe is a sync sample and an IDR picture with no leading pictures
- in the missed-keyframe recording, the next keyframe lands one extended GOP after the forced
  keyframe at 12 s, so the GOP count restarts at a forced keyframe, and that keyframe is also an IDR
  picture with no leading pictures
- each replacement attempt's delivered initialization segment is byte-identical to its first
  attempt's
- every initialization segment passes the output check with the job's codec string
- the encoders add an IDR picture with no leading pictures at each scene cut (3.5 s, 4.5 s, 27.5 s
  and 28.5 s; at 1080p and 720p only at 4.5 s and 28.5 s), because the lookahead enables NVENC's
  scene-cut detection. The producer tolerates extra keyframes, and none displaced a forced keyframe.

Before choosing those options, 1080p recordings with the encoders' default B-frames and lookahead,
and with `-bf 3 -rc-lookahead 0`, showed the same keyframe verdicts without the scene-cut keyframes.

`hevc_nvenc` in Main 10 shows the same verdicts at every rung, from a 10-bit BT.709, a PQ and an HLG
source that each keep their range:

- every initialization segment declares 10-bit 4:2:0 under the job's Main 10 codec string, and a PQ
  or HLG one declares limited-range BT.2020 with the transfer of its range in its `colr` box
- no sample carries a mastering display or content light level message, although every keyframe of
  the PQ source carries both. NVENC writes the messages of each keyframe's frame into the samples
  and takes no values from the command, so the worker deletes them from the frames, and the
  producer writes the job's values into the `hvc1` record and into the sample entry's `mdcv` and
  `clli` boxes. The recorded initialization segments are FFmpeg's own and hold neither box.

The CPU reshape of Dolby Vision profile 5 or 10.0 into PQ runs before the encoder, which takes the
arguments of a PQ encode from frames in system memory, so the PQ recordings also stand for that
encode. No recording runs the CPU reshape.

The GPU reshape of a profile 5 source has recordings of its own, because the encoder takes its
frames from the GPU: NVDEC decodes the source, and `scale_cuda` and `tonemap_cuda` hand `hevc_nvenc`
CUDA frames. They show the same verdicts at every rung, PQ's colour description and no static HDR
metadata in a sample. They show the keyframes and the signalling, not the colours: the source's
RPUs come from dovi_tool's generator. `HdrEncodeSmokeTest` runs both reshapes into `hevc_nvenc` on
an NVIDIA GPU host: the GPU reshape, and the CPU reshape of a job that also deinterlaces.

## Recording

The recordings need an NVIDIA GPU host: Linux, an NVIDIA GPU, its driver, the NVIDIA Container
Toolkit and Docker. The analysis runs in a digest-pinned `node:24.20.0` image, so the host needs no
Node; the preflight checks that the image holds the release in `buildpacks/ffmpeg/.nvmrc`. From the
repository root:

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
27.5 s to 28.5 s. The Main 10 recordings read the same picture as libx265 Main 10, once for each
range with its colour description. The PQ source also carries constructed HDR10 static metadata: a
P3 D65 display of 1000 cd/m² and 0.005 cd/m², MaxCLL 1000 and MaxFALL 400. The Dolby Vision
profile 5 source is the same picture as libx265 Main 10 under `dvh1`. Each of its frames carries an
RPU of this repository's synthetic `sample-entries/dolby-vision-5-dvh1.mp4`, whose 144 RPUs repeat:
FFmpeg's test sources generate no RPU. The script copies that fixture into the work directory, and
reads no other media file from the host. The run stops at `--time-limit`, 1200 s by default.

## What is recorded

Every job carries a segment list on 6 s slots that starts its 5 media segments at slots 0, 1, 2, 3
and 5. Segment 3 is therefore a merged segment from 18 s to 30 s. As the worker does, each recording
forces keyframes only at the listed starts, so the GOP places the keyframe at 24 s.

For each encoder and each rung of the default ladder (1080p, 720p, 480p and 360p), five encodes run:

| Recording | What it runs |
|---|---|
| `ENCODER-RUNG-first` | The job from the start with the floored GOP of an encoder that is not keyframe-verified (143 frames) |
| `ENCODER-RUNG-replacement` | A replacement attempt of segment 3, which seeks to 12 s, with that GOP |
| `ENCODER-RUNG-extended-first` | The worker's command for the job from the start, with the extended GOP of a keyframe-verified encoder (145 frames) |
| `ENCODER-RUNG-extended-replacement` | The worker's command for the replacement attempt |
| `ENCODER-RUNG-extended-missed` | The first attempt with the extended GOP and without the forced keyframe at 18 s |

`hevc_nvenc` also records the three extended encodes in Main 10 at every rung, once for each 10-bit
range: `hevc_nvenc-RUNG-sdr10-…` from the BT.709 source, `hevc_nvenc-RUNG-pq-…` and
`hevc_nvenc-RUNG-hlg-…`. `hevc_nvenc-RUNG-dolby-vision-…` are the same three encodes of the Dolby
Vision source with hardware decode and the GPU reshape, which is the worker's command once
`hevc_nvenc` passed its GPU reshape test.

`NvencRecorderCommandsTest` runs `record-nvenc.sh --print-commands`. It requires the
`extended-first` and `extended-replacement` commands to equal the worker's own command for the same
job, apart from quiet logging. Every other recording may differ from it only in the GOP and the
forced keyframe times.

For each recording, `recordings.json` keeps:

- the plan (encoder, rung, range, bit depth, level, tier, expected codec string, GOP, forced
  keyframe times) and the FFmpeg arguments
- every video sample's presentation time, the sync samples, the NAL unit type that starts each
  keyframe, and how many HEVC samples carry a mastering display or content light level message
- the initialization segment's digests, codec string, picture format, colour description, and
  whether its record holds its parameter sets
- the verdicts that follow from those facts

The verdicts are:

- whether every forced keyframe is a sync sample
- whether every forced keyframe is an IDR picture with no leading pictures, which starts a closed GOP
- each keyframe the encoder adds, and whether the GOP placed it
- for the missed-keyframe recording, whether the next keyframe lands one GOP after the forced keyframe
  at 12 s, which shows that the GOP count restarted there, and whether that keyframe starts a closed
  GOP
- whether each initialization segment passes the codec, codec profile and level check of worker #25,
  with the plan's bit depth and, for a PQ or HLG plan, the colour description of its range

`initializationSegmentPairs` compares each replacement attempt's initialization segment, as the
worker delivers it, with its first attempt's. `environment` records the image, the FFmpeg version,
the driver version and the GPU's compute capability.

## Checking offline

CI derives every result again without FFmpeg or a GPU. `nvenc-recordings.test.mjs` re-reads the
committed initialization segments and re-derives every verdict from the committed facts.
`NvencRecordingsTest` runs the worker's own rewrite and output check over each initialization
segment, and fails while an encoder that `FfmpegCommandBuilder` marks keyframe-verified has an
extended recording that does not show every verdict above. It also fails while the worker tests an
NVENC encoder for an encode feature whose range lacks a Main 10 recording at a rung, while it tests
one for the GPU reshape without a Dolby Vision recording at a rung, and while a Main 10 recording
carries static HDR metadata in a sample. The same check runs by hand:

```
node src/test/fmp4-recorder/nvenc-recordings.mjs check
```
