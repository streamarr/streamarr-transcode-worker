# Recorded probe output

ffprobe's JSON output for small sources, recorded with the ffprobe in the pinned worker image and
the probe's own arguments. `../../probe-fixtures/record-fixtures.sh` makes the sources and records
the output. It keeps only the output, without the source's path. FFmpeg synthesizes most sources
from its lavfi test sources. No file comes from a media library.

| File | What the source is |
|---|---|
| `mp4-titled.mp4.json` | H.264 and two AAC streams in MP4. The first audio stream has a title, the second a handler name of its own. A titled `mov_text` stream is marked for hearing-impaired viewers. Two titled chapters |
| `mkv-remuxed-from-mp4.mkv.json` | `mp4-titled.mp4` remuxed into Matroska, with `FlagHearingImpaired` on the first audio stream too. The handler names become `HANDLER_NAME` tags and the titles `NAME` tags |
| `mp4-default-handlers.mp4.json` | H.264, AAC and `mov_text` in MP4, without titles, handler names, flags or chapters |
| `mkv-pipe-hevc-10bit.mkv.json` | 10-bit 4:2:2 HEVC and Opus in Matroska written to a pipe, so no stream has a `DURATION` tag |
| `mpegts-empty-audio.ts.json` | H.264 and AAC in MPEG-TS, which starts at 1.4 s, with a second AAC stream that has no packets |
| `mkv-mp3-11khz.mkv.json` | MP3 at 11,025 Hz in Matroska |
| `mp4-er-aac-lc.mp4.json` | AAC in MP4 whose config `edit-mp4-boxes.py` rewrote as ER AAC LC, a profile FFmpeg has no name for |
| `mkv-eac3-atmos-dts-hd-ma.mkv.json` | E-AC-3 with Atmos and DTS-HD MA, copied from FFmpeg's FATE samples `eac3/the_great_wall_7.1.eac3` and `dts/master_audio_7.1_24bit.dts`, in Matroska |
| `mkv-offset-chapters.mkv.json` | Matroska that starts at 10 s. Its chapters start before the container, have no title, or have a 513-byte title |
| `mkv-many-chapters.mkv.json` | Matroska with 400 chapters whose titles each take 100 bytes |
| `mkv-title-tags.mkv.json` | Matroska with a blank title before a `NAME` tag, a 513-byte title before a `NAME` tag, a titled subtitle stream with a 513-byte handler name, and a titled video stream |
| `mkv-many-titles.mkv.json` | Matroska with 40 subtitle streams whose titles each take 500 bytes |
| `mp4-clap.mp4.json` | MP4 whose `clap` box, added by `edit-mp4-boxes.py`, keeps the centered 56x32 of a 64x36 picture |
| `mkv-pixel-crop.mkv.json` | `mp4-clap.mp4` remuxed into Matroska, which writes the crop as `PixelCrop` elements |
| `mp4-rotated.mp4.json` | MP4 whose display matrix rotates the picture by 90 degrees (`-display_rotation 90`) |
| `mp4-cover-art-first.mp4.json` | MP4 whose cover art `edit-mp4-boxes.py` moved in front of the video stream |
| `mp4-hdr10-static.mp4.json` | 10-bit HEVC with PQ, encoded from an x265 stream whose SEI messages carry a mastering display (`G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,50)`) and a light level of 1000 and 400. The encode writes both into `mdcv` and `clli` boxes |
| `mkv-hdr10-static.mkv.json` | The same encode in Matroska, which stores the values as floats |
| `mp4-hdr10-maxfall-zero.mp4.json` | As `mp4-hdr10-static.mp4`, with a MaxFALL of 0 |
| `mkv-hdr10-maxfall-zero.mkv.json` | As `mkv-hdr10-static.mkv`, with a MaxFALL of 0, which ffprobe does not report for Matroska |
| `mp4-hdr10-sei-only.mp4.json` | The x265 stream copied into MP4, so only its SEI messages carry the HDR10 values |
| `mp4-sdr8-bt709.mp4.json` | 8-bit H.264 tagged BT.709 |
| `mp4-dovi-p5-hev1.mp4.json` | FFmpeg's FATE sample `mov/dovi-p5.mp4`: Dolby Vision profile 5 under `hev1`, with a `dvcC` record |
| `mkv-hdr10-fate.mkv.json` | FFmpeg's FATE sample `mkv/hdr10tags-both.mkv`: HDR10 mastering values stored as 32-bit floats |
| `mpegts-dovi-p7.ts.json` | FFmpeg's FATE sample `mov/dovi-p7-hvce.mp4` copied into MPEG-TS, whose Dolby Vision descriptor carries the profile 7 record |
| `mp4-dovi-p5-dvh1.mp4.json` | Local sample: Dolby Vision profile 5 under `dvh1` with a `dvcC` record and no transfer tag |
| `mp4-dovi-p5-dvh1-no-record.mp4.json` | Local sample: the same file with its record removed |
| `mp4-dovi-p81.mp4.json` | Local sample: Dolby Vision profile 8.1 under `hvc1`, PQ |
| `mp4-dovi-p82.mp4.json` | Local sample: Dolby Vision profile 8.2 under `hvc1`, BT.709, made by adding a `dvvC` record to an SDR HEVC encode |
| `mp4-dovi-p84-hlg.mp4.json` | Local sample: Dolby Vision profile 8.4 under `hvc1`, HLG |
| `mp4-dovi-p9-avc1.mp4.json` | Local sample: Dolby Vision profile 9 under `avc1`, BT.709, made by adding a `dvvC` record to an SDR H.264 encode |
| `mp4-dovi-p9-dva1.mp4.json` | Local sample: the same file with its sample entry renamed `dva1` |
| `mp4-hevc-hdr10.mp4.json` | Local sample: 10-bit HEVC with PQ, whose HDR10 values travel only in SEI messages |
| `mp4-hevc-sdr10-untagged.mp4.json` | Local sample: 10-bit HEVC SDR without a transfer tag |
| `mov-phone-hlg-dovi-p84.mov.json` | Local sample: a public-domain phone camera original, HEVC HLG with Dolby Vision 8.4 |
| `mov-phone-rotated.mov.json` | Local sample: a public-domain phone camera original, H.264 recorded upright |
| `pixel-formats.json` | `ffprobe -show_pixel_formats`: FFmpeg's description of every pixel format it knows |
| `ffprobe-version.txt` | The first line of `ffprobe -version` from the recording image |

## Regenerating

From the repository root, with Docker and Python 3:

```
src/test/probe-fixtures/record-fixtures.sh
FATE_SUITE=/path/to/fate-suite LOCAL_SAMPLES=/path/to/samples src/test/probe-fixtures/record-fixtures.sh
```

The script overwrites the files in this directory, so `git diff` shows what a new recording
changed. Without `FATE_SUITE`, it keeps the recordings of FATE samples as they are. The local
samples are public test files that stay out of the repository. `LOCAL_SAMPLES` names a directory
that holds them under the names above, without the `.json` suffix. Without `LOCAL_SAMPLES`, the
script keeps their recordings as they are. The script removes the source's path and any location
tag from each recording. Re-record
after an FFmpeg lock update, then run the probe tests.
