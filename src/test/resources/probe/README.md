# Recorded probe output

ffprobe's JSON output for small sources, recorded with the ffprobe in the pinned worker image and
the probe's own arguments, which include `-show_data` for the extradata.
`../../probe-fixtures/record-fixtures.sh` makes the sources and records the output. It keeps only the output, without the source's path. FFmpeg synthesizes most sources
from its lavfi test sources. No file comes from a media library.

| File | What the source is |
|---|---|
| `mp4-titled.mp4.json` | H.264 and two AAC streams in MP4. The first audio stream has a title, the second a handler name of its own. A titled `mov_text` stream is marked for hearing-impaired viewers. Two titled chapters |
| `mkv-remuxed-from-mp4.mkv.json` | `mp4-titled.mp4` remuxed into Matroska, with `FlagHearingImpaired` on the first audio stream too. The handler names become `HANDLER_NAME` tags and the titles `NAME` tags |
| `mp4-default-handlers.mp4.json` | H.264, AAC and `mov_text` in MP4, without titles, handler names, flags or chapters |
| `mkv-pipe-hevc-10bit.mkv.json` | 10-bit 4:2:2 HEVC and Opus in Matroska written to a pipe, so no stream has a `DURATION` tag |
| `mpegts-empty-audio.ts.json` | H.264 and AAC in MPEG-TS, which starts at 1.4 s, with a second AAC stream that has no packets |
| `mkv-mp3-11khz.mkv.json` | MP3 at 11,025 Hz in Matroska |
| `mp4-avc-record-level.mp4.json`, `mp4-hevc-record-level.mp4.json` | H.264 and HEVC in MP4 whose record headers `edit-mp4-boxes.py` rewrote to name level 5.1, while their parameter sets name level 1.0 |
| `mpegts-aac-main.ts.json`, `mpegts-aac-ltp.ts.json` | AAC LC in ADTS whose frame headers `edit-adts-profile.py` rewrote as AAC Main and AAC LTP, next to a synthesized H.264 stream in MPEG-TS |
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
| `mpegts-hdr10-sei-only.ts.json` | The same copy in MPEG-TS |
| `mpegts-pq10.ts.json` | 10-bit HEVC with PQ in MPEG-TS, encoded without static HDR metadata |
| `hevc-hdr10-plus.hevc.json` | FFmpeg's FATE sample `hevc/hdr10_plus_h265_sample.hevc`: one raw HEVC frame with HDR10+ metadata |
| `mkv-dovi-p7-hvce.mkv.json` | FFmpeg's FATE sample `mkv/dovi-p7-hvce.mkv`: Dolby Vision profile 7 in Matroska |
| `mpegts-dovi-p7-two-layers.ts.json` | FFmpeg's FATE sample `mpegts/dovi-p7.ts`: Dolby Vision profile 7 whose base layer and enhancement layer are separate streams. Only the second stream carries the record and the RPUs |
| `mp4-dovi-p5-no-record-bt709.mp4.json` | Local sample: the first 3 s of `mp4-dovi-p5-dvh1-no-record.mp4` under `hvc1`, with `hevc_metadata` rewriting its VUI to signal BT.709 |
| `mpegts-dovi-p9.ts.json` | `mp4-dovi-p9-avc1.mp4` copied into MPEG-TS, whose samples delimit their NAL units with start codes |
| `mpegts-dovi-p5-no-descriptor.ts.json` | Local sample: the first 3 s of `mp4-dovi-p5-dvh1-no-record.mp4` copied into MPEG-TS, which then carries no Dolby Vision descriptor |
| `mp4-sdr8-bt709.mp4.json` | 8-bit H.264 tagged BT.709 |
| `mp4-dovi-p5-hev1.mp4.json` | FFmpeg's FATE sample `mov/dovi-p5.mp4`: Dolby Vision profile 5 under `hev1`, with a `dvcC` record |
| `hevc-dovi-p5-rpus-ahead.hevc.json` | FFmpeg's FATE sample `mov/dovi-p5.mp4` as raw HEVC, whose RPUs `move-rpus-ahead.py` moved ahead of the next picture, behind a copy of the parameter sets. FFmpeg's HEVC decoder ignores each RPU, so only the packets show them |
| `hevc-dovi-p5-rpus-before-own-picture.hevc.json` | The same sample, whose RPUs `move-rpus-ahead.py` moved ahead of their own pictures, behind the parameter sets that start each sample. No known muxer writes this layout. FFmpeg's HEVC decoder ignores each RPU, so only the packets show them |
| `hevc-dovi-p5-rpus-temporal-id-1.hevc.json` | The same sample as raw HEVC, whose RPUs `raise-rpu-temporal-id.py` put on TemporalId 1. FFmpeg's HEVC decoder reads a type 62 NAL unit as an RPU only on layer 0 and TemporalId 0, so the stream holds no RPU |
| `mkv-hdr10-fate.mkv.json` | FFmpeg's FATE sample `mkv/hdr10tags-both.mkv`: HDR10 mastering values stored as 32-bit floats |
| `mpegts-dovi-p7.ts.json` | FFmpeg's FATE sample `mov/dovi-p7-hvce.mp4` copied into MPEG-TS, whose Dolby Vision descriptor carries the profile 7 record |
| `mp4-dovi-p7-two-tracks.mp4.json` | FFmpeg's FATE sample `mov/dovi-p7.mp4`: Dolby Vision profile 7 whose base layer and enhancement layer are separate MP4 tracks. Only the second track carries the record and the RPUs |
| `mp4-dovi-p81-fate.mp4.json` | FFmpeg's FATE sample `mov/dovi-p81.mp4`: Dolby Vision profile 8.1 under `hev1`, PQ, with a `dvcC` record and each RPU after its own picture. Its HEVC parameter sets are only in its samples |
| `hevc-dovi-p81-fate.hevc.json` | The same sample copied into raw HEVC, which keeps no record, so only the RPUs show Dolby Vision |
| `ivf-av1-hdr-sizeup.ivf.json` | FFmpeg's FATE sample `av1-test-vectors/av1-1-b8-03-sizeup.ivf`: a libaom stream in IVF whose temporal units carry HDR_MDCV and HDR_CLL metadata OBUs |
| `h264-sei-1.h264.json` | FFmpeg's FATE sample `h264/sei-1.h264`: raw H.264 whose mastering display SEI message has a maximum luminance above 100,000,000, which FFmpeg drops, so the first frame carries only the primaries |
| `mp4-h264-sei-1.mp4.json` | The same stream copied into MP4, whose 5 packets decode fewer than 3 frames in a read of 3. The first frame of the read that the probe keeps carries no mastering display |
| `mov-h264-extradata-reload.mov.json`, `mov-hevc-extradata-reload.mov.json` | FFmpeg's FATE samples `h264/extradata-reload-multi-stsd.mov` and `hevc/extradata-reload-multi-stsd.mov`: H.264 and HEVC whose packets switch between sample descriptions, so they carry new extradata side data |
| `mp4-h264-mixed-nal-coding.mp4.json` | FFmpeg's FATE sample `h264/mixed-nal-coding.mp4`: length-prefixed MP4 samples that also hold start codes |
| `flv-h264-sei-without-trailing-bits.flv.json` | H.264 in FLV whose x264 SEI NAL unit `drop-sei-trailing-bits.py` ended without its RBSP trailing bits, as some encoders write it |
| `avi-h264-unknown-extradata.avi.json` | H.264 in AVI whose extradata `set-avi-extradata.py` replaced with 4 bytes that are neither a configuration record nor Annex B. `h264_mp4toannexb` refuses that extradata, so the stream has no copy |
| `webm-vp9-hdr10-plus.webm.json` | FFmpeg's FATE sample `mkv/hdr10_plus_vp9_sample.webm`: VP9 whose HDR10+ metadata a WebM BlockAdditional carries to the frame |
| `hevc-hdr-vivid-repeated.hevc.json` | One HLG frame whose x265 SEI messages carry a mastering display and a light level of 0, and into which `insert-hdr-vivid-sei.py` put a CUVA HDR Vivid message, repeated 3 times so that the stream holds 3 frames |
| `mp4-dovi-p5-dvh1.mp4.json` | Local sample: Dolby Vision profile 5 under `dvh1` with a `dvcC` record and no transfer tag (see Sources) |
| `mp4-dovi-p5-dvh1-no-record.mp4.json` | Local sample: the same file with its record removed |
| `mp4-dovi-p81.mp4.json` | Local sample: Dolby Vision profile 8.1 under `hvc1`, PQ (see Sources) |
| `mp4-dovi-p82.mp4.json` | Local sample: Dolby Vision profile 8.2 under `hvc1`, BT.709, made by adding a `dvvC` record to an SDR HEVC encode |
| `mp4-dovi-p84-hlg.mp4.json` | Local sample: Dolby Vision profile 8.4 under `hvc1`, HLG (see Sources) |
| `mp4-dovi-p9-avc1.mp4.json` | Dolby Vision profile 9 under `avc1`: one second of 8-bit H.264 at 1080p24 tagged BT.709, after each picture of which `insert-avc-rpus.py` put an RPU that `dovi_tool generate` made, with a `dvvC` record that GPAC's MP4Box added |
| `mp4-dovi-p9-dva1.mp4.json` | The same stream, to which MP4Box gave a `dva1` sample entry |
| `mp4-hevc-hdr10.mp4.json` | Local sample: 10-bit HEVC with PQ. Neither its container nor the SEI messages of its first 600 frames carry static HDR metadata, so it is PQ10 (see Sources) |
| `mp4-hevc-sdr10-untagged.mp4.json` | Local sample: 10-bit HEVC SDR without a transfer tag (see Sources) |
| `mov-phone-hlg-dovi-p84.mov.json` | Local sample: a phone camera original, HEVC HLG with Dolby Vision 8.4 (see Sources) |
| `mov-phone-rotated.mov.json` | Local sample: a phone camera original, H.264 recorded upright (see Sources) |
| `mpegts-hevc-ac3.ts.json` | HEVC and AC-3 in MPEG-TS, whose HEVC parameter sets ffprobe dumps in Annex B form |
| `ivf-av1-10bit.ivf.json` | 10-bit AV1 in IVF, whose extradata is the sequence header OBU |
| `obu-av1-10bit.obu.json` | The same stream as a raw OBU file |
| `mkv-av1-10bit.mkv.json` | The same stream in Matroska, whose extradata is an `av1C` record |
| `mp4-av1-10bit.mp4.json` | The same stream in MP4 |
| `mp4-av1-pq.mp4.json` | Nine frames of 10-bit AV1 with PQ in MP4, without metadata OBUs |
| `mp4-av1-hdr10-plus-rejected.mp4.json` | The same stream, whose first temporal unit `insert-av1-metadata.py` gave an HDR10+ metadata OBU with a body FFmpeg cannot parse, ahead of the frame. FFmpeg's AV1 decoders drop that frame, so only the packets show HDR10+ |
| `mp4-av1-static-after-frame.mp4.json` | The same stream, whose first temporal unit `insert-av1-metadata.py` gave HDR_MDCV and HDR_CLL metadata OBUs after the frame. They reach the second frame, so the first frame carries neither |
| `mp4-av1-rpu.mp4.json` | The same stream, each of whose temporal units `insert-av1-metadata.py` gave the first Dolby Vision RPU of FFmpeg's FATE sample `mov/dovi-p5.mp4` in its EMDF container, ahead of the frame |
| `mp4-av1-rpu-bad-emdf.mp4.json` | As `mp4-av1-rpu.mp4`, with an EMDF header that FFmpeg rejects, so only the packets show the RPUs |
| `mp4-audio-codecs.mp4.json` | H.264 with AC-3, E-AC-3, FLAC and ALAC in MP4 |
| `mpegts-aac-he.ts.json` | FFmpeg's FATE sample `aac/al_sbr_cm_48_2.mp4`, HE-AAC copied into ADTS, next to a synthesized H.264 stream in MPEG-TS |
| `mkv-120fps.mkv.json` | H.264 at 120 fps in Matroska, whose 1 ms timestamps alternate 8 and 9 ms |
| `mp4-100fps.mp4.json`, `mp4-90fps.mp4.json`, `mp4-72fps.mp4.json` | H.264 at 100, 90 and 72 fps in MP4 |
| `mov-phone-one-gap.mov.json` | Local sample: a phone camera original at 30 fps with one 133 ms gap (see Sources) |
| `mov-phone-120-declared.mov.json` | Local sample: a phone camera original at 30 fps that declares `r_frame_rate` 120 (see Sources) |
| `mov-phone-60.mov.json` | Local sample: a phone camera original at 60 fps whose average rate is 59.956 (see Sources) |
| `mov-phone-slow-motion.mov.json` | Local sample: a phone slow-motion original that runs at up to 240 fps (see Sources) |
| `mpegts-avchd-interlaced.mts.json` | Local sample: an AVCHD camcorder file, top field first, one field per packet (see Sources) |
| `frames/*.json` | The first-frames read of a recording's chosen video stream, with `FirstFramesProbe`'s arguments: the side data of the frames that ffprobe decodes from the stream's first packets. The probe widens the read from 3 to 12, 48 and 192 packets until it decodes 3 frames, and the recording holds the output of the read where it stops |
| `packet-data/*.json` | The first-packets read of an H.264, HEVC or AV1 recording's chosen video stream, with `FirstPacketsProbe`'s arguments: hex dumps of as many of its first packets as the first-frames read took, their pts when the demuxer gives one, and the types of their side data. Only the recordings that the tests read keep theirs |
| `packets/*.csv` | The frame-rate rule's packet scan of a recording's chosen video stream: the first 30 s of packets as `pts,dts,size,flags`, from `frame-rate-scans.py` |
| `copy-codec-strings.json` | The codec string of each copied stream: `copy-codec-strings.py` copies every H.264, HEVC, AV1, AAC, AC-3, E-AC-3, Opus, FLAC and ALAC stream above with the worker's copy arguments, and reads the string from the copy's initialization segment |
| `pixel-formats.json` | `ffprobe -show_pixel_formats`: FFmpeg's description of every pixel format it knows |
| `ffprobe-version.txt` | The first line of `ffprobe -version` from the recording image |

## Sources

These fixtures hold only ffprobe's description of each file, and the phone and camcorder fixtures also its packet timing. The files themselves stay local. The phone and camcorder files come from the Internet Archive, and the Dolby Vision and HEVC files from the Jellyfin project's [test videos](https://repo.jellyfin.org/test-videos/), which it states are courtesy of Gnattu and licensed under CC BY-SA 4.0.

| Fixture | Source | Author | License |
| --- | --- | --- | --- |
| `mp4-dovi-p5-dvh1`, `mp4-dovi-p5-dvh1-no-record`, `mp4-dovi-p5-no-record-bt709`, `mpegts-dovi-p5-no-descriptor` | [Test Jellyfin 1080p DV P5.mp4](https://repo.jellyfin.org/test-videos/HDR/Dolby%20Vision/Test%20Jellyfin%201080p%20DV%20P5.mp4) | Gnattu, via the Jellyfin project | [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) |
| `mp4-dovi-p81` | [Test Jellyfin 1080p DV P8.1.mp4](https://repo.jellyfin.org/test-videos/HDR/Dolby%20Vision/Test%20Jellyfin%201080p%20DV%20P8.1.mp4) | Gnattu, via the Jellyfin project | [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) |
| `mp4-dovi-p84-hlg` | [Test Jellyfin 1080p DV P8.4.mp4](https://repo.jellyfin.org/test-videos/HDR/Dolby%20Vision/Test%20Jellyfin%201080p%20DV%20P8.4.mp4) | Gnattu, via the Jellyfin project | [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) |
| `mp4-hevc-hdr10` | [Test Jellyfin 1080p HEVC HDR10 10M.mp4](https://repo.jellyfin.org/test-videos/HDR/HDR10/HEVC/Test%20Jellyfin%201080p%20HEVC%20HDR10%2010M.mp4) | Gnattu, via the Jellyfin project | [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) |
| `mp4-hevc-sdr10-untagged` | [Test Jellyfin 1080p HEVC 10bit 10M.mp4](https://repo.jellyfin.org/test-videos/SDR/HEVC%2010bit/Test%20Jellyfin%201080p%20HEVC%2010bit%2010M.mp4) | Gnattu, via the Jellyfin project | [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) |
| `mov-phone-hlg-dovi-p84` | [img-5945_202311](https://archive.org/details/img-5945_202311) | Simon Robin Lehn | [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/) |
| `mov-phone-rotated` | [img-6851_202505](https://archive.org/details/img-6851_202505) | Imad Rahi | [Public Domain Mark 1.0](https://creativecommons.org/publicdomain/mark/1.0/) |
| `mov-phone-one-gap` | [img-4999_202406](https://archive.org/details/img-4999_202406) | Maung Fate | [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) |
| `mov-phone-120-declared` | [img-1119_202603](https://archive.org/details/img-1119_202603), file `IMG_1087.MOV` | Maen | [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/) |
| `mov-phone-60` | [img-0372_202404](https://archive.org/details/img-0372_202404) | Uploader on the Internet Archive | [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/) |
| `mov-phone-slow-motion` | [img-3068_202403](https://archive.org/details/img-3068_202403), file `IMG_3074.MOV` | Uploader on the Internet Archive | [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/) |
| `mpegts-avchd-interlaced` | [Flickr-5838795894](https://archive.org/details/Flickr-5838795894) | Lawrence, via Flickr | [CC BY-SA 2.0](https://creativecommons.org/licenses/by-sa/2.0/) |

## Regenerating

From the repository root, with Docker, Python 3 and [dovi_tool](https://github.com/quietvoid/dovi_tool):

```
src/test/probe-fixtures/record-fixtures.sh
FATE_SUITE=/path/to/fate-suite LOCAL_SAMPLES=/path/to/samples src/test/probe-fixtures/record-fixtures.sh
```

The script overwrites the files in this directory, so `git diff` shows what a new recording
changed. Without `FATE_SUITE`, it keeps the recordings of FATE samples as they are, and without
`dovi_tool` on the `PATH`, the Dolby Vision profile 9 recordings. The local
samples are public test files that stay out of the repository. `LOCAL_SAMPLES` names a directory
that holds them under the names above, without the `.json` suffix. Without `LOCAL_SAMPLES`, the
script keeps their recordings as they are. The script removes the source's path and any location
tag from each recording. Re-record
after an FFmpeg lock update, then run the probe tests.
