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
| 1080p | Sintel trailer | 93.72 | +0.15 | +0.84 |
| 1080p | Driving POV | 89.58 | +4.00 | +5.82 |
| 1080p | Roller Coaster | 92.47 | +5.06 | +6.51 |
| 1080p | Bar Scene | 94.37 | +1.23 | +1.37 |
| 720p | Sintel trailer | 86.13 | +0.62 | +0.85 |
| 720p | Driving POV | 84.80 | +2.56 | +4.12 |
| 720p | Roller Coaster | 89.41 | +4.49 | +6.10 |
| 720p | Bar Scene | 92.66 | +1.30 | +1.41 |
| 480p | Sintel trailer | 77.89 | +0.49 | +0.81 |
| 480p | Driving POV | 69.65 | +2.40 | +4.45 |
| 480p | Roller Coaster | 77.85 | +3.97 | +5.95 |
| 480p | Bar Scene | 87.17 | +1.11 | +1.56 |
| 360p | Sintel trailer | 67.12 | +0.21 | +0.89 |
| 360p | Driving POV | 52.79 | +2.29 | +4.28 |
| 360p | Roller Coaster | 61.72 | +4.07 | +6.29 |
| 360p | Bar Scene | 78.95 | +1.31 | +1.84 |

Both encoders get the same `-b:v`, `-maxrate` and `-bufsize`, but NVENC fills more of the bitrate
on easy content. On the Sintel trailer at 1080p, `h264_nvenc` delivered 4.0 Mb/s where libx264
delivered 3.5 Mb/s, for the same score. On the hardest clips both run a few percent over the
ladder's bitrate on average, inside what their two-second rate-control buffer allows: at most
4.6 % for libx264, 4.8 % for `h264_nvenc` and 6.1 % for `hevc_nvenc`, all at 360p on the Roller
Coaster clip.

A second full run wrote NVENC encodes of exactly the same sizes. libx264's sizes moved by up to
1.2 %, because its threaded rate control varies from run to run.

## Method

- **Host:** RTX 5090, NVIDIA driver 615.78.08, the pinned FFmpeg 8.1.3 (the FFmpeg layer of the
  worker image streamarr-server pins, `f198b7a3`).
- **Commands:** `PrintWorkerCommand.java` builds each job and prints `FfmpegCommandBuilder`'s
  command, so every encode runs the worker's options: for NVENC, the forced profile and level,
  `-bf 3 -rc-lookahead 20`, the extended GOP and the forced keyframes. The script writes the first
  output to a file instead of the pipe and drops the framecrc output.
- **Score:** VMAF `vmaf_v0.6.1`, the pooled mean over every frame. Each encode is upscaled with
  bicubic to 1920x1080 and compared with the prepared source frame by frame. Both streams are
  restamped by frame index first: the intermediate's container rounds frame times to milliseconds,
  while the worker's MP4 keeps the exact frame grid, so VMAF's frame sync would otherwise pair a
  third of the frames with the reference picture before them. Before scoring a source, the script
  checks that a lossless MP4 of it matches the intermediate on every frame (PSNR infinite), and it
  checks every encode's frame count.
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
