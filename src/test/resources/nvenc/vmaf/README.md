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
| 1080p | Sintel trailer | 96.92 | +0.06 | +0.66 |
| 1080p | Driving POV | 91.17 | +3.30 | +5.02 |
| 1080p | Roller Coaster | 93.25 | +4.63 | +5.95 |
| 1080p | Bar Scene | 95.48 | +1.13 | +1.27 |
| 720p | Sintel trailer | 90.19 | +0.40 | +0.62 |
| 720p | Driving POV | 85.86 | +2.25 | +3.89 |
| 720p | Roller Coaster | 89.97 | +4.20 | +5.75 |
| 720p | Bar Scene | 93.47 | +1.19 | +1.31 |
| 480p | Sintel trailer | 82.00 | +0.25 | +0.58 |
| 480p | Driving POV | 70.56 | +2.14 | +4.19 |
| 480p | Roller Coaster | 78.09 | +4.00 | +6.02 |
| 480p | Bar Scene | 88.17 | +1.17 | +1.51 |
| 360p | Sintel trailer | 71.46 | +0.16 | +0.70 |
| 360p | Driving POV | 53.43 | +2.05 | +4.05 |
| 360p | Roller Coaster | 61.81 | +4.05 | +6.33 |
| 360p | Bar Scene | 80.03 | +0.94 | +1.56 |

Both encoders get the same `-b:v`, `-maxrate` and `-bufsize`, but NVENC fills more of the bitrate
on easy content. On the Sintel trailer at 1080p, `h264_nvenc` delivered 4.0 Mb/s where libx264
delivered 3.5 Mb/s, for the same score. On the hardest clips both run a few percent over the
ladder's bitrate on average, inside what their two-second rate-control buffer allows: at most
4.6 % for libx264, 4.7 % for `h264_nvenc` and 6.2 % for `hevc_nvenc`, all at 360p on the Roller
Coaster clip.

Two runs from the same intermediates wrote NVENC encodes of exactly the same sizes. libx264's sizes
moved by up to 1.2 %, because its threaded rate control varies from run to run.

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
  checks that a lossless MP4 made through the worker's own 1080p filter matches the intermediate on
  every frame (PSNR infinite), and it checks every encode's frame count.
- **Levels:** the server's H.264 levels per rung. A 30 fps source at 480p takes level 3.1,
  because 854x480 at 30 fps is past level 3.0's macroblock rate. NVENC refuses level 3.0 there,
  while libx264 writes it anyway.
- **Colours:** none of the sources tags its colours, so each intermediate is tagged as BT.709.
  Untagged, the worker's scale would read it as BT.601 and convert its colours in every encode.
- **Sources:** each source becomes an FFV1 intermediate at 1920x1080, 8-bit 4:2:0:
  - Sintel trailer, `sintel_trailer_2k_1080p24.y4m` from media.xiph.org: © Blender Foundation,
    CC BY 3.0. All 52 s at 24 fps. Animation.
  - Driving POV, Roller Coaster and Bar Scene, the `Netflix_*_4096x2160_60fps_10bit_420.webm` clips
    from media.xiph.org's Chimera set: © Netflix, Inc., CC BY-NC-ND 4.0. Each is center-cropped to
    3840x2160, scaled with Lanczos and halved to 30 fps: 20 s of live action each.

No source or encode is committed.
