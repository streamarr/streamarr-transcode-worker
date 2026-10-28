# Third-Party Notices

This notice identifies third-party material included with the Streamarr Transcode Worker and its tests. Each component remains subject to its own license.

## FFmpeg and bundled libraries

Worker container images include Jellyfin builds of FFmpeg and ffprobe and their bundled libraries. See the [redistribution documentation](buildpacks/ffmpeg/README.md#redistribution-materials), [component inventory](buildpacks/ffmpeg/notices/sources.json), and [Corresponding Source instructions](buildpacks/ffmpeg/SOURCE.txt).

The FFmpeg image layer carries the generated `THIRD-PARTY-NOTICES.txt`, `LICENSE.txt`, `SOURCE.txt`, and `notices/` directory. These contain the runtime's license texts, attribution, and source information.

## Java dependencies

The executable worker JAR contains dependency JARs under `BOOT-INF/lib/`. Their bundled license and notice files remain inside those JARs. This index is packaged at `META-INF/THIRD_PARTY_NOTICES.md`.

## Big Buck Bunny test fixture

`src/test/resources/BigBuckBunny_320x180_10s.mp4` is a shortened, 320×180 version of *Big Buck Bunny* used by the worker's media tests.

(c) copyright 2008, Blender Foundation / [www.bigbuckbunny.org](https://www.bigbuckbunny.org/)

The film is licensed under [Creative Commons Attribution 3.0 Unported](https://creativecommons.org/licenses/by/3.0/). See the [original project's licensing and attribution instructions](https://peach.blender.org/about/). The fixture is a ten-second excerpt encoded as H.264/AAC in an MP4 container.

## Test samples

`src/test/resources/probe/` holds ffprobe's output for the probe's test samples, not the samples themselves. The recordings in `src/test/resources/probe/packet-data/` also hold hex dumps of the first video packets of their sources, so they contain bytes of the samples below. Each sample remains subject to its own terms. The [Sources](src/test/resources/probe/README.md#sources) table in the fixture README lists where the phone, camcorder and Jellyfin samples come from. Their recordings hold only ffprobe's description of each file.

### AV1 test vector

`packet-data/ivf-av1-hdr-sizeup.ivf.json` holds the first packets of `av1-1-b8-03-sizeup.ivf`, a libaom test vector that FFmpeg's FATE suite carries as `av1-test-vectors/av1-1-b8-03-sizeup.ivf`.

Copyright (c) 2016, Alliance for Open Media. All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

The vector is also subject to the [Alliance for Open Media Patent License 1.0](https://aomedia.org/license/patent-license/). See libaom's [LICENSE](https://aomedia.googlesource.com/aom/+/refs/heads/main/LICENSE) and [PATENTS](https://aomedia.googlesource.com/aom/+/refs/heads/main/PATENTS). The terms come from libaom, which publishes its test vectors for its own tests; no file-level grant sits beside the vectors.

### FFmpeg FATE samples

The following `packet-data/` recordings hold the first packets of samples from FFmpeg's [FATE suite](https://fate-suite.ffmpeg.org/). No license is stated for these samples: not in the FATE suite, which has no license file, and not in the ffmpeg-devel messages or FFmpeg commits that brought them in. FFmpeg's own license covers FFmpeg's code, not these samples. Their content is synthetic: black frames, FFmpeg's test pattern, or a frame counter.

- `mov/dovi-p5.mp4`, `mov/dovi-p7.mp4`, `mov/dovi-p81.mp4` and `mpegts/dovi-p7.ts`: blank frames that quietvoid encoded with x265 and remuxed with FFmpeg after dlb_mp4base added the Dolby Vision record, posted in "[PATCH] fate: Add regression tests for AVDOVIDecoderConfigurationRecord side data" ([ffmpeg-devel, November 2021](https://ffmpeg.org/pipermail/ffmpeg-devel/2021-November/288397.html)). `mp4-dovi-p5-hev1.mp4.json`, the three `hevc-dovi-p5-*.hevc.json` recordings, `mp4-av1-rpu.mp4.json` and `mp4-av1-rpu-bad-emdf.mp4.json` hold bytes of `mov/dovi-p5.mp4`. The last two carry its first Dolby Vision RPU. `mp4-dovi-p7-two-tracks.mp4.json` holds bytes of `mov/dovi-p7.mp4`, `mp4-dovi-p81-fate.mp4.json` and `hevc-dovi-p81-fate.hevc.json` of `mov/dovi-p81.mp4`, and `mpegts-dovi-p7-two-layers.ts.json` of `mpegts/dovi-p7.ts`.
- `h264/sei-1.h264`: an artificial stream by Mark Thompson whose picture is a white frame counter on black, posted in "[PATCH 6/6] fate/cbs: Add an SEI test" ([ffmpeg-devel, May 2018](https://ffmpeg.org/pipermail/ffmpeg-devel/2018-May/229667.html)) and used by FFmpeg commit f995aa82d8. `h264-sei-1.h264.json` and `mp4-h264-sei-1.mp4.json` hold its bytes.
- `h264/extradata-reload-multi-stsd.mov` and `hevc/extradata-reload-multi-stsd.mov`: FFmpeg's `testsrc` test pattern in H.264 and HEVC by Vittorio Giovara, posted in "[PATCH 3/3] fate: Add h264 and hevc extradata reload tests" ([ffmpeg-devel, November 2016](https://ffmpeg.org/pipermail/ffmpeg-devel/2016-November/202491.html)) and used by FFmpeg commit 481ff3cf01. `mov-h264-extradata-reload.mov.json` and `mov-hevc-extradata-reload.mov.json` hold their bytes.
- `h264/mixed-nal-coding.mp4`: five black frames, added to FATE by Vittorio Giovara in FFmpeg commit 390b95b88b ("fate: Add a mixed NAL coding sample") for the MP4 samples that [FFmpeg ticket 5529](https://trac.ffmpeg.org/ticket/5529) describes. `mp4-h264-mixed-nal-coding.mp4.json` holds its bytes.
