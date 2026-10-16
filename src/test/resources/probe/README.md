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
| `pixel-formats.json` | `ffprobe -show_pixel_formats`: FFmpeg's description of every pixel format it knows |
| `ffprobe-version.txt` | The first line of `ffprobe -version` from the recording image |

## Regenerating

From the repository root, with Docker and Python 3:

```
src/test/probe-fixtures/record-fixtures.sh
FATE_SUITE=/path/to/fate-suite src/test/probe-fixtures/record-fixtures.sh
```

The script overwrites the files in this directory, so `git diff` shows what a new recording
changed. Without `FATE_SUITE`, it keeps `mkv-eac3-atmos-dts-hd-ma.mkv.json` as it is. Re-record
after an FFmpeg lock update, then run the probe tests.
