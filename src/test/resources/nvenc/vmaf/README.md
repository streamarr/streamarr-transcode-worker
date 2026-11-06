# NVENC picture quality per bit (worker #56)

`ladder.tsv` compares `h264_nvenc` and `hevc_nvenc` with libx264 at `faster`, the preset that
Automatic gives software H.264 encodes (ADR 0044). Each encode runs the worker's own FFmpeg command
for a start-0 job at each default-ladder rung's bitrate: 5, 3, 1.5 and 0.8 Mb/s at 1080p, 720p,
480p and 360p. `src/test/nvenc-quality/compare-vmaf.sh` made the file.

## Result

At every rung of every source, `h264_nvenc` scored the same as or higher than libx264 at `faster`,
and `hevc_nvenc` scored higher still. The ladder's bitrates therefore stay as they are for NVENC,
and streamarr-server needs no change.

VMAF difference from libx264 at `faster`:

| Rung | Source | libx264 | `h264_nvenc` | `hevc_nvenc` |
| --- | --- | --- | --- | --- |
| 1080p | Sintel trailer | 81.96 | +0.01 | +0.68 |
| 1080p | Driving POV | 61.08 | +2.56 | +3.86 |
| 1080p | Roller Coaster | 60.23 | +3.35 | +4.43 |
| 1080p | Bar Scene | 86.76 | +0.98 | +1.09 |
| 720p | Sintel trailer | 74.84 | +0.47 | +0.65 |
| 720p | Driving POV | 57.50 | +1.76 | +2.88 |
| 720p | Roller Coaster | 58.23 | +2.68 | +3.82 |
| 720p | Bar Scene | 85.39 | +0.98 | +1.07 |
| 480p | Sintel trailer | 67.68 | +0.38 | +0.63 |
| 480p | Driving POV | 47.24 | +1.55 | +2.94 |
| 480p | Roller Coaster | 50.48 | +2.52 | +3.84 |
| 480p | Bar Scene | 80.64 | +0.86 | +1.21 |
| 360p | Sintel trailer | 58.37 | +0.09 | +0.63 |
| 360p | Driving POV | 35.41 | +1.54 | +2.90 |
| 360p | Roller Coaster | 39.52 | +2.69 | +4.16 |
| 360p | Bar Scene | 73.54 | +0.91 | +1.31 |

Both encoders get the same `-b:v`, `-maxrate` and `-bufsize`, but NVENC fills more of the bitrate
on easy content. On the Sintel trailer at 1080p, `h264_nvenc` delivered 4.0 Mb/s where libx264
delivered 3.5 Mb/s, for the same score. On the hardest clips both run a few percent over the
ladder's bitrate on average, inside what their two-second rate-control buffer allows: at most
4.6 % for libx264, 4.8 % for `h264_nvenc` and 6.1 % for `hevc_nvenc`, all at 360p on the Roller
Coaster clip.

A second run of the Bar Scene gave the same NVENC sizes and scores. libx264's scores moved by at
most 0.04, because its threaded rate control varies from run to run.

## Method

- **Host:** RTX 5090, NVIDIA driver 615.78.08, the pinned FFmpeg 8.1.3 (the FFmpeg layer of the
  worker image streamarr-server pins, `f198b7a3`).
- **Commands:** `PrintWorkerCommand.java` builds each job and prints `FfmpegCommandBuilder`'s
  command, so every encode runs the worker's options: for NVENC, the forced profile and level,
  `-bf 3 -rc-lookahead 20`, the extended GOP and the forced keyframes. The script writes the first
  output to a file instead of the pipe and drops the framecrc output.
- **Score:** VMAF `vmaf_v0.6.1`, the pooled mean over every frame. Each encode is upscaled with
  bicubic to 1920x1080 and compared with the prepared source.
- **Levels:** the server's H.264 levels per rung. A 30 fps source at 480p takes level 3.1,
  because 854x480 at 30 fps is past level 3.0's macroblock rate. NVENC refuses level 3.0 there,
  while libx264 writes it anyway.
- **Sources:** each source becomes an FFV1 intermediate at 1920x1080, 8-bit 4:2:0:
  - Sintel trailer, `sintel_trailer_2k_1080p24.y4m` from media.xiph.org: © Blender Foundation,
    CC BY 3.0. All 52 s at 24 fps. Animation.
  - Driving POV, Roller Coaster and Bar Scene, the `Netflix_*_4096x2160_60fps_10bit_420.webm` clips
    from media.xiph.org's Chimera set: © Netflix, Inc., CC BY-NC-ND 4.0. Each is center-cropped to
    3840x2160, scaled with Lanczos and halved to 30 fps: 20 s of live action each.

No source or encode is committed.
