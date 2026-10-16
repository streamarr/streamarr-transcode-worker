#!/usr/bin/env bash
# Regenerates the recorded ffprobe output in src/test/resources/probe.
#
# Every FFmpeg and ffprobe run happens inside the pinned worker image, so the output comes from the
# worker's own ffprobe, run with the probe's own arguments. Most sources are synthesized with lavfi.
# edit-mp4-boxes.py adds the MP4 boxes that FFmpeg cannot write. Nothing is downloaded, and no
# source file is kept: only ffprobe's output is.
#
#   src/test/probe-fixtures/record-fixtures.sh
#   WORKER_IMAGE=streamarr-worker:local src/test/probe-fixtures/record-fixtures.sh
#   FATE_SUITE=/path/to/fate-suite src/test/probe-fixtures/record-fixtures.sh
#   LOCAL_SAMPLES=/path/to/samples src/test/probe-fixtures/record-fixtures.sh
#
# FATE_SUITE names a local copy of FFmpeg's FATE samples (rsync://fate-suite.ffmpeg.org/fate-suite/).
# The audio fixtures copy an E-AC-3 Atmos and a DTS-HD MA stream from it next to a synthesized video
# stream, and the Dolby Vision and HDR10 fixtures probe or remux its Dolby Vision and HDR10 samples.
# LOCAL_SAMPLES names a directory that holds the public Dolby Vision, HDR and phone samples that the
# fixture README lists. Those samples stay local. Without either variable, the script keeps the
# fixtures that need it as they are.
set -euo pipefail

IMAGE="${WORKER_IMAGE:-streamarr/streamarr-transcode-worker:0.1.0-SNAPSHOT@sha256:ad4a844f8fa7f27946ceb79431d62719474b2f3332b80a2222e5a421dc2acaab}"
HERE="$(cd "$(dirname "$0")" && pwd)"
FIXTURES="$(cd "$HERE/../resources/probe" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

in_image() {
  local fate_mount=()
  if [ -n "${FATE_SUITE:-}" ]; then
    fate_mount=(-v "$FATE_SUITE":/fate:ro)
  fi

  if [ -n "${LOCAL_SAMPLES:-}" ]; then
    fate_mount+=(-v "$LOCAL_SAMPLES":/samples:ro)
  fi

  # The buildpack launcher prints JVM lines on its own stdout, so FFmpeg writes only to files.
  docker run --rm --user "$(id -u):$(id -g)" --entrypoint /cnb/lifecycle/launcher \
    -v "$WORK":/work ${fate_mount[@]+"${fate_mount[@]}"} "$IMAGE" bash "/work/$1" > "$WORK/launcher.log" 2>&1 \
    || { cat "$WORK/launcher.log"; exit 1; }
}

cat > "$WORK/sources.sh" <<'CONTAINER'
set -euo pipefail
cd /work
FF=(timeout 60 ffmpeg -hide_banner -nostdin -loglevel error -y)
VIDEO=(-f lavfi -i testsrc2=size=64x36:rate=24:duration=2)
TONE=(-f lavfi -i sine=frequency=440:duration=2)
X264=(-c:v libx264 -preset ultrafast -pix_fmt yuv420p)

printf '1\n00:00:00,000 --> 00:00:01,000\nHello\n' > subtitle.srt
cat > chapters.txt <<'CHAPTERS'
;FFMETADATA1
[CHAPTER]
TIMEBASE=1/1000
START=0
END=500
title=Opening
[CHAPTER]
TIMEBASE=1/1000
START=500
END=2000
title=Ending
CHAPTERS

# H.264 and AAC in MP4: one titled audio stream, one audio stream whose handler name names it, and
# a titled mov_text stream marked for hearing-impaired viewers, which FFmpeg writes as a kind box.
"${FF[@]}" "${VIDEO[@]}" "${TONE[@]}" -i subtitle.srt -i chapters.txt \
  -map 0:v -map 1:a -map 1:a -map 2:s -map_chapters 3 "${X264[@]}" -c:a aac -c:s mov_text \
  -metadata:s:a:0 title=Commentary -metadata:s:a:1 "handler_name=Stereo Mix" \
  -metadata:s:s:0 title=SDH -disposition:s:0 hearing_impaired mp4-titled.mp4

# The same streams remuxed into Matroska, with FlagHearingImpaired on the first audio stream too.
"${FF[@]}" -i mp4-titled.mp4 -map 0:v -map 0:a -map 0:s -c copy -c:s srt \
  -disposition:a:0 hearing_impaired mkv-remuxed-from-mp4.mkv

# MP4 without titles, handler names or chapters, and an unflagged subtitle stream.
"${FF[@]}" "${VIDEO[@]}" "${TONE[@]}" -i subtitle.srt -map 0:v -map 1:a -map 2:s "${X264[@]}" \
  -c:a aac -c:s mov_text -map_chapters -1 mp4-default-handlers.mp4

# Matroska written to a pipe, which has no per-stream DURATION tags. The video is 10-bit 4:2:2 HEVC.
"${FF[@]}" "${VIDEO[@]}" "${TONE[@]}" -c:v libx265 -preset ultrafast -pix_fmt yuv422p10le \
  -x265-params log-level=none -c:a libopus -f matroska pipe:1 > mkv-pipe-hevc-10bit.mkv

# MPEG-TS, whose container starts at 1.4 s, with a second audio stream that has no packets.
"${FF[@]}" "${VIDEO[@]}" "${TONE[@]}" -f lavfi -i anullsrc=sample_rate=48000,atrim=end_sample=0 \
  -map 0:v -map 1:a -map 2:a "${X264[@]}" -c:a aac -t 2 mpegts-empty-audio.ts

# MP3 at 11,025 Hz in Matroska.
"${FF[@]}" "${VIDEO[@]}" -f lavfi -i sine=frequency=440:duration=2:sample_rate=11025 "${X264[@]}" \
  -c:a libmp3lame mkv-mp3-11khz.mkv

# Matroska that starts at 10 s, with three chapters: one before the container's start time, one
# without a title, and one whose title takes 513 bytes of UTF-8.
LONG_TITLE="$(printf 'a%.0s' $(seq 1 513))"
cat > offset-chapters.txt <<CHAPTERS
;FFMETADATA1
[CHAPTER]
TIMEBASE=1/1000
START=0
END=12000
title=Prologue
[CHAPTER]
TIMEBASE=1/90000
START=1080000
END=1125000
[CHAPTER]
TIMEBASE=1/1000
START=12500
END=13000
title=$LONG_TITLE
CHAPTERS
"${FF[@]}" "${VIDEO[@]}" -i offset-chapters.txt -map 0:v -map_chapters 1 "${X264[@]}" \
  -output_ts_offset 10 mkv-offset-chapters.mkv

# Matroska with 400 chapters whose titles each take 100 bytes.
{
  echo ';FFMETADATA1'
  for chapter in $(seq 0 399); do
    printf '[CHAPTER]\nTIMEBASE=1/1000\nSTART=%d\nEND=%d\ntitle=%s%03d\n' \
      "$((chapter * 5))" "$((chapter * 5 + 5))" "$(printf 'c%.0s' $(seq 1 97))" "$chapter"
  done
} > many-chapters.txt
"${FF[@]}" "${VIDEO[@]}" -i many-chapters.txt -map 0:v -map_chapters 1 "${X264[@]}" \
  mkv-many-chapters.mkv

# Matroska titles: a blank title before a name tag, a 513-byte title before a name tag, and a
# titled subtitle stream with a 513-byte handler name. The video stream has a title and a handler
# name too.
"${FF[@]}" "${VIDEO[@]}" "${TONE[@]}" -i subtitle.srt -map 0:v -map 1:a -map 1:a -map 2:s \
  "${X264[@]}" -c:a aac -c:s srt \
  -metadata:s:v:0 title=Picture -metadata:s:v:0 handler_name=VideoHandler \
  -metadata:s:a:0 "title= " -metadata:s:a:0 name=Director \
  -metadata:s:a:1 "title=$LONG_TITLE" -metadata:s:a:1 name=Fallback \
  -metadata:s:s:0 "title=English SDH" -metadata:s:s:0 "handler_name=$LONG_TITLE" mkv-title-tags.mkv

# Matroska with 40 subtitle streams whose titles each take 500 bytes.
SUBTITLE_MAPS=()
for stream in $(seq 0 39); do
  SUBTITLE_MAPS+=(-map 1:s -metadata:s:s:$stream "title=$(printf 't%.0s' $(seq 1 497))$(printf '%03d' "$stream")")
done
"${FF[@]}" "${VIDEO[@]}" -i subtitle.srt -map 0:v "${SUBTITLE_MAPS[@]}" "${X264[@]}" -c:s srt \
  mkv-many-titles.mkv

# A display matrix that rotates the picture by 90 degrees.
"${FF[@]}" "${VIDEO[@]}" "${X264[@]}" video.mp4
"${FF[@]}" -display_rotation 90 -i video.mp4 -c copy mp4-rotated.mp4

# MP4 with cover art, which edit-mp4-boxes.py moves in front of the video stream.
"${FF[@]}" -f lavfi -i color=red:size=32x32 -frames:v 1 cover.png
"${FF[@]}" -i video.mp4 -i cover.png -map 0 -map 1 -c copy -disposition:v:1 attached_pic \
  mp4-cover-art-first.mp4

# AAC in MP4, whose config edit-mp4-boxes.py rewrites as ER AAC LC.
"${FF[@]}" "${VIDEO[@]}" "${TONE[@]}" "${X264[@]}" -c:a aac mp4-er-aac-lc.mp4

# HDR10 static metadata. x265 writes the mastering display and content light level SEI messages
# into a raw HEVC stream. An encode from that stream carries both values as side data into MP4 mdcv
# and clli boxes and into the Matroska mastering elements. A copy keeps them only in the SEI
# messages. The second stream declares a MaxFALL of 0.
MASTERING='G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,50)'
# FFmpeg 8 takes an encode's color signaling from its frames, which setparams tags.
HDR10=(-vf setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc
  -pix_fmt yuv420p10le)
X265=(-c:v libx265 -preset ultrafast)
for light in 1000,400 1000,0; do
  "${FF[@]}" -f lavfi -i testsrc2=size=64x64:rate=24:duration=1 "${HDR10[@]}" "${X265[@]}" \
    -x265-params "log-level=none:hdr10=1:master-display=$MASTERING:max-cll=$light" "sei-$light.hevc"
done
"${FF[@]}" -i sei-1000,400.hevc "${HDR10[@]}" "${X265[@]}" -x265-params log-level=none \
  mp4-hdr10-static.mp4
"${FF[@]}" -i sei-1000,400.hevc "${HDR10[@]}" "${X265[@]}" -x265-params log-level=none \
  mkv-hdr10-static.mkv
"${FF[@]}" -i sei-1000,0.hevc "${HDR10[@]}" "${X265[@]}" -x265-params log-level=none \
  mp4-hdr10-maxfall-zero.mp4
"${FF[@]}" -i sei-1000,0.hevc "${HDR10[@]}" "${X265[@]}" -x265-params log-level=none \
  mkv-hdr10-maxfall-zero.mkv
"${FF[@]}" -i sei-1000,400.hevc -c copy -tag:v hvc1 mp4-hdr10-sei-only.mp4

# 8-bit SDR with BT.709 color signaling.
"${FF[@]}" "${VIDEO[@]}" "${X264[@]}" \
  -vf setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709 mp4-sdr8-bt709.mp4

# MP4 whose clean aperture crops 4 pixels from the left and the right and 2 from the top and the
# bottom of the 64x36 picture, and the same stream remuxed into Matroska as PixelCrop elements.
"${FF[@]}" "${VIDEO[@]}" "${X264[@]}" -map_metadata -1 mp4-clap.mp4
CONTAINER

cat > "$WORK/cropped.sh" <<'CONTAINER'
set -euo pipefail
cd /work
ffmpeg -hide_banner -nostdin -loglevel error -y -i mp4-clap.mp4 -c copy mkv-pixel-crop.mkv
CONTAINER

cat > "$WORK/fate.sh" <<'CONTAINER'
set -euo pipefail
cd /work
FF=(ffmpeg -hide_banner -nostdin -loglevel error -y)
"${FF[@]}" -f lavfi -i testsrc2=size=64x36:rate=24:duration=1 -i /fate/eac3/the_great_wall_7.1.eac3 \
  -i /fate/dts/master_audio_7.1_24bit.dts -map 0:v -map 1:a -map 2:a -c:v libx264 -preset ultrafast \
  -c:a copy -t 1 mkv-eac3-atmos-dts-hd-ma.mkv
cp /fate/mov/dovi-p5.mp4 mp4-dovi-p5-hev1.mp4
cp /fate/mkv/hdr10tags-both.mkv mkv-hdr10-fate.mkv
# Dolby Vision profile 7 in MPEG-TS, whose Dolby Vision descriptor carries the record.
"${FF[@]}" -i /fate/mov/dovi-p7-hvce.mp4 -map 0:v -c copy -strict unofficial mpegts-dovi-p7.ts
CONTAINER

cat > "$WORK/probe.sh" <<'CONTAINER'
set -euo pipefail
cd /work
for source in "$@"; do
  # The probe's own arguments, as FfprobeExecutor passes them.
  ffprobe -v quiet -print_format json -analyzeduration 200M -probesize 1G -scan_all_pmts 0 \
    -show_streams -show_format -show_chapters -show_error "$source" > "$(basename "$source").json"
done
ffprobe -version | head -1 > ffprobe-version.txt
ffprobe -v quiet -print_format json -show_pixel_formats > pixel-formats.json
CONTAINER

in_image sources.sh
python3 "$HERE/edit-mp4-boxes.py" clap "$WORK/mp4-clap.mp4" 56 32
python3 "$HERE/edit-mp4-boxes.py" udta-first "$WORK/mp4-cover-art-first.mp4"
python3 "$HERE/edit-mp4-boxes.py" er-aac-lc "$WORK/mp4-er-aac-lc.mp4"
in_image cropped.sh

FIXTURES_TO_PROBE=(mp4-titled.mp4 mkv-remuxed-from-mp4.mkv mp4-default-handlers.mp4
  mkv-pipe-hevc-10bit.mkv mpegts-empty-audio.ts mkv-mp3-11khz.mkv mkv-offset-chapters.mkv
  mkv-many-chapters.mkv mkv-title-tags.mkv mkv-many-titles.mkv mp4-clap.mp4 mkv-pixel-crop.mkv mp4-rotated.mp4
  mp4-cover-art-first.mp4 mp4-er-aac-lc.mp4 mp4-hdr10-static.mp4 mkv-hdr10-static.mkv
  mp4-hdr10-maxfall-zero.mp4 mkv-hdr10-maxfall-zero.mkv mp4-hdr10-sei-only.mp4 mp4-sdr8-bt709.mp4)
if [ -n "${FATE_SUITE:-}" ]; then
  in_image fate.sh
  FIXTURES_TO_PROBE+=(mkv-eac3-atmos-dts-hd-ma.mkv mp4-dovi-p5-hev1.mp4 mkv-hdr10-fate.mkv
    mpegts-dovi-p7.ts)
fi

LOCAL_SAMPLE_NAMES=(mp4-dovi-p5-dvh1.mp4 mp4-dovi-p5-dvh1-no-record.mp4 mp4-dovi-p81.mp4
  mp4-dovi-p82.mp4 mp4-dovi-p84-hlg.mp4 mp4-dovi-p9-avc1.mp4 mp4-dovi-p9-dva1.mp4 mp4-hevc-hdr10.mp4
  mp4-hevc-sdr10-untagged.mp4 mov-phone-hlg-dovi-p84.mov mov-phone-rotated.mov)
PROBE_ARGUMENTS=("${FIXTURES_TO_PROBE[@]}")
if [ -n "${LOCAL_SAMPLES:-}" ]; then
  for sample in "${LOCAL_SAMPLE_NAMES[@]}"; do
    PROBE_ARGUMENTS+=("/samples/$sample")
  done
  FIXTURES_TO_PROBE+=("${LOCAL_SAMPLE_NAMES[@]}")
fi

printf 'bash /work/probe.sh %s\n' "${PROBE_ARGUMENTS[*]}" > "$WORK/probe-all.sh"
in_image probe-all.sh

for source in "${FIXTURES_TO_PROBE[@]}"; do
  # The source's path says nothing about the source. A phone recording's container tags can hold
  # the place where it was recorded.
  python3 -c 'import json, sys
output = json.load(open(sys.argv[1]))
output.get("format", {}).pop("filename", None)
tags = output.get("format", {}).get("tags", {})
for key in [key for key in tags if "location" in key.lower()]:
    del tags[key]
json.dump(output, open(sys.argv[2], "w"), indent=2, ensure_ascii=False)
open(sys.argv[2], "a").write("\n")' "$WORK/$source.json" "$FIXTURES/$source.json"
done
cp "$WORK/ffprobe-version.txt" "$WORK/pixel-formats.json" "$FIXTURES/"
echo "recorded into $FIXTURES with $IMAGE"
