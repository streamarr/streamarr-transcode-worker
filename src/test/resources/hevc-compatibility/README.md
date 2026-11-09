# Synthetic HEVC compatibility recordings

These recordings contain only FFmpeg's generated `testsrc2` pattern. There is no
source file, soundtrack, subtitle, artwork, or extracted configuration from a
commercial title. The synthetic media is dedicated to the public domain under
[CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/).

Recorded on 2026-11-07, using the same immutable worker image and command:

| Recording | GPU | Driver | Original codec string |
| --- | --- | --- | --- |
| `a2000-main.fmp4` | NVIDIA RTX A2000 12GB | 580.82.07 | `hvc1.1.2.L90.90` |
| `rtx5090-main.fmp4` | NVIDIA GeForce RTX 5090 | 615.78.08 | `hvc1.1.6.L90.90` |

The first reproduces a producer `OUTPUT_MISMATCH` despite forcing Main profile,
Main tier, level 3.0 and 8-bit 4:2:0. The second already declares Main and Main 10
compatibility. Both must deliver `hvc1.1.6.L90.90`, with identical encoded sample
bytes, and matching compatibility flags in the configuration record and VPS/SPS.
The different GPU/driver combinations are established; this experiment does not
isolate the GPU generation from its driver version.

The image's FFmpeg binary SHA-256 is
`002932042931418b18dd535d159941f8c4757de97d49b6238b104122bb5b5031`.
It reports `8.1.3-Jellyfin`. Regenerate on the corresponding GPU host:

```bash
docker run --rm --network none --gpus all \
  -e NVIDIA_DRIVER_CAPABILITIES=compute,video,utility \
  --cap-drop ALL --read-only \
  --entrypoint /layers/com.streamarr.ffmpeg/ffmpeg/bin/ffmpeg \
  streamarr/streamarr-transcode-worker@sha256:399f66aca5d58fbd22d8864e8b7b3ccb5e572349a90ce14f331ee068f971b4df \
  -hide_banner -nostdin -loglevel error \
  -f lavfi -i testsrc2=size=854x480:rate=24000/1001 -t 1 \
  -c:v hevc_nvenc -preset p4 -tag:v hvc1 -profile:v main -level:v 3.0 -tier:v main \
  -pix_fmt yuv420p -b:v 1500000 -maxrate 1500000 -bufsize 3000000 \
  -bf 3 -rc-lookahead 20 -g 145 -forced-idr 1 \
  -f mp4 -movflags cmaf+delay_moov+skip_trailer+frag_keyframe+frag_discont \
  -frag_duration 250000 pipe:1 > synthetic-main.fmp4
```

The A2000 recording originally ran through the image's launcher; its text preamble
was removed before the first `ftyp` box. No media bytes were changed. The direct
FFmpeg entry point above avoids that preamble.
