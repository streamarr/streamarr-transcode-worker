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
# HDR10 and PQ10 in MPEG-TS: the x265 stream whose SEI messages carry the HDR10 values, and a PQ
# encode that carries none.
"${FF[@]}" -i mp4-hdr10-sei-only.mp4 -c copy mpegts-hdr10-sei-only.ts
"${FF[@]}" -f lavfi -i testsrc2=size=64x64:rate=24:duration=1 "${HDR10[@]}" "${X265[@]}" \
  -x265-params log-level=none mpegts-pq10.ts
# Three frames of raw HEVC with HDR10+ and HDR10 SEI messages, and of raw H.264 with a mastering
# display SEI message, which reject-hdr-sei.py hides from FFmpeg's decoders.
HDR10_PLUS_SCENE='{"BezierCurveData":{"Anchors":[256,512,768],"KneePointX":0,"KneePointY":0},
  "LuminanceParameters":{"AverageRGB":100,"LuminanceDistributions":{
  "DistributionIndex":[1,5,10,25,50,75,90,95,99],"DistributionValues":[0,1,2,3,4,5,6,7,8]},
  "MaxScl":[400,400,400]},"NumberOfWindows":1,"TargetedSystemDisplayMaximumLuminance":400,
  "SceneFrameIndex":%d,"SceneId":0,"SequenceFrameIndex":%d}'
{
  printf '{"SceneInfo":['
  printf "$HDR10_PLUS_SCENE," 0 0 1 1
  printf "$HDR10_PLUS_SCENE" 2 2
  printf '],"JSONInfo":{"HDR10plusProfile":"A","Version":"1.0"},'
  printf '"SceneInfoSummary":{"SceneFirstFrameIndex":[0],"SceneFrameNumbers":[3]}}'
} > hdr10-plus.json
THREE_FRAMES=(-f lavfi -i testsrc2=size=64x36:rate=24:duration=0.125)
"${FF[@]}" "${THREE_FRAMES[@]}" "${HDR10[@]}" "${X265[@]}" -x265-params \
  "log-level=none:hdr10=1:master-display=$MASTERING:max-cll=1000,400:dhdr10-info=hdr10-plus.json" \
  -f hevc hdr10-plus.hevc
"${FF[@]}" "${THREE_FRAMES[@]}" "${HDR10[@]}" -c:v libx264 -preset ultrafast \
  -x264-params "mastering-display=$MASTERING" -f h264 hdr10.h264

# Codec strings. H.264 and HEVC in MPEG-TS carry their parameter sets in Annex B form. AV1 in IVF
# and in a raw OBU file carries only its sequence header. AAC in ADTS has no AudioSpecificConfig.
"${FF[@]}" "${VIDEO[@]}" "${TONE[@]}" -c:v libx265 -preset ultrafast -x265-params log-level=none \
  -c:a ac3 mpegts-hevc-ac3.ts
SVTAV1=(-c:v libsvtav1 -preset 10 -svtav1-params log-level=0 -pix_fmt yuv420p10le)
"${FF[@]}" "${VIDEO[@]}" "${SVTAV1[@]}" ivf-av1-10bit.ivf
"${FF[@]}" -i ivf-av1-10bit.ivf -c copy obu-av1-10bit.obu
"${FF[@]}" -i ivf-av1-10bit.ivf -c copy mkv-av1-10bit.mkv
"${FF[@]}" -i ivf-av1-10bit.ivf -c copy mp4-av1-10bit.mp4
# Nine frames of PQ AV1 in IVF, into which insert-av1-metadata.py puts HDR metadata OBUs.
"${FF[@]}" -f lavfi -i testsrc2=size=64x36:rate=24:duration=0.375 "${HDR10[@]}" "${SVTAV1[@]}" \
  av1-pq.ivf
# AC-3, E-AC-3, FLAC and ALAC in MP4.
"${FF[@]}" "${VIDEO[@]}" "${TONE[@]}" -map 0:v -map 1:a -map 1:a -map 1:a -map 1:a "${X264[@]}" \
  -c:a:0 ac3 -c:a:1 eac3 -c:a:2 flac -c:a:3 alac -strict -2 mp4-audio-codecs.mp4

# H.264 and HEVC in MP4, whose record headers edit-mp4-boxes.py rewrites to name another level than
# their parameter sets do.
"${FF[@]}" "${VIDEO[@]}" "${X264[@]}" mp4-avc-record-level.mp4
"${FF[@]}" "${VIDEO[@]}" -c:v libx265 -preset ultrafast -x265-params log-level=none -tag:v hvc1 \
  mp4-hevc-record-level.mp4
# AAC LC in ADTS, whose frame headers edit-adts-profile.py rewrites as AAC Main and AAC LTP.
"${FF[@]}" "${TONE[@]}" -c:a aac -f adts aac-lc.adts
# Frame rates above 60, which the frame-rate rule divides evenly: 120 in Matroska, whose 1 ms
# timestamps alternate 8 and 9 ms, and 100, 90 and 72 in MP4.
"${FF[@]}" -f lavfi -i testsrc2=size=64x36:rate=120:duration=1 "${X264[@]}" mkv-120fps.mkv
for rate in 100 90 72; do
  "${FF[@]}" -f lavfi -i "testsrc2=size=64x36:rate=$rate:duration=1" "${X264[@]}" "mp4-${rate}fps.mp4"
done

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

cat > "$WORK/adts.sh" <<'CONTAINER'
set -euo pipefail
cd /work
FF=(timeout 60 ffmpeg -hide_banner -nostdin -loglevel error -y)
for profile in main ltp; do
  "${FF[@]}" -f lavfi -i testsrc2=size=64x36:rate=24:duration=2 -i "aac-$profile.adts" \
    -map 0:v -map 1:a -c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:a copy \
    "mpegts-aac-$profile.ts"
done
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
# HDR10+ in raw HEVC, profile 7 in Matroska, and profile 7 in MPEG-TS with its base and
# enhancement layers in separate streams.
cp /fate/hevc/hdr10_plus_h265_sample.hevc hevc-hdr10-plus.hevc
cp /fate/mkv/dovi-p7-hvce.mkv mkv-dovi-p7-hvce.mkv
cp /fate/mpegts/dovi-p7.ts mpegts-dovi-p7-two-layers.ts
# The profile 5 sample as raw HEVC, whose RPUs move-rpus-ahead.py moves ahead of the next picture
# or of their own.
"${FF[@]}" -i /fate/mov/dovi-p5.mp4 -map 0:v -c copy -f hevc dovi-p5.hevc
CONTAINER

# Each AV1 stream in IVF that insert-av1-metadata.py wrote, copied into MP4, whose av1C record the
# probe's walk of the metadata OBUs reads.
cat > "$WORK/av1.sh" <<'CONTAINER'
set -euo pipefail
cd /work
for source in av1-*.ivf; do
  ffmpeg -hide_banner -nostdin -loglevel error -y -i "$source" -c copy "mp4-${source%.ivf}.mp4"
done
CONTAINER

cat > "$WORK/probe.sh" <<'CONTAINER'
set -euo pipefail
cd /work
mkdir -p frames packet-data
LIMITS=(-analyzeduration 200M -probesize 1G -scan_all_pmts 0)
for source in "$@"; do
  # The probe's own arguments, as FfprobeExecutor passes them, and -show_data, which prints the
  # extradata that the probe reads for each video and audio stream in separate runs.
  ffprobe -v quiet -print_format json "${LIMITS[@]}" \
    -show_streams -show_format -show_chapters -show_data -show_error "$source" \
    > "$(basename "$source").json"
  # The first-frames read of the chosen video stream, with FirstFramesProbe's arguments. V:0 selects
  # the first video stream that is not an attached picture, which is the chosen video stream.
  chosen="$(ffprobe -v quiet "${LIMITS[@]}" -select_streams V:0 -show_entries stream=index \
    -of csv=p=0 "$source")"
  # MPEG-TS prints the stream again inside each program that holds it, and a stream with side data
  # prints an empty field for it.
  chosen="${chosen%%[,$'\n']*}"
  if [ -n "$chosen" ]; then
    # The probe widens the read until it decodes 3 frames; the recording holds the widest read's
    # output that the probe takes.
    for packets in 3 12 48 192; do
      ffprobe -v quiet -print_format json "${LIMITS[@]}" -select_streams "$chosen" \
        -read_intervals "%+#$packets" -show_entries "frame=stream_index:frame_side_data=side_data_type,red_x,red_y,green_x,green_y,blue_x,blue_y,white_point_x,white_point_y,min_luminance,max_luminance,max_content,max_average,vdr_rpu_profile,bl_video_full_range_flag,el_spatial_resampling_filter_flag,disable_residual_flag,vdr_bit_depth" \
        "$source" > "frames/$(basename "$source").json"
      if [ "$(grep -c '"stream_index"' "frames/$(basename "$source").json")" -ge 3 ]; then
        break
      fi
    done
    # The first-packets read of an H.264, HEVC or AV1 chosen video stream, with FirstPacketsProbe's
    # arguments: as many packets as the first-frames read took.
    codec="$(ffprobe -v quiet "${LIMITS[@]}" -select_streams "$chosen" \
      -show_entries stream=codec_name -of csv=p=0 "$source")"
    codec="${codec%%[,$'\n']*}"
    if [ "$codec" = h264 ] || [ "$codec" = hevc ] || [ "$codec" = av1 ]; then
      ffprobe -v quiet -print_format json "${LIMITS[@]}" -select_streams "$chosen" \
        -read_intervals "%+#$packets" \
        -show_entries packet=data:packet_side_data=side_data_type -show_data \
        "$source" > "packet-data/$(basename "$source").json"
    fi
  fi
done
ffprobe -version | head -1 > ffprobe-version.txt
ffprobe -v quiet -print_format json -show_pixel_formats > pixel-formats.json
CONTAINER

in_image sources.sh
python3 "$HERE/edit-mp4-boxes.py" clap "$WORK/mp4-clap.mp4" 56 32
python3 "$HERE/edit-mp4-boxes.py" udta-first "$WORK/mp4-cover-art-first.mp4"
python3 "$HERE/edit-mp4-boxes.py" er-aac-lc "$WORK/mp4-er-aac-lc.mp4"
python3 "$HERE/edit-mp4-boxes.py" record-level "$WORK/mp4-avc-record-level.mp4" 51
python3 "$HERE/edit-mp4-boxes.py" record-level "$WORK/mp4-hevc-record-level.mp4" 153
python3 "$HERE/edit-adts-profile.py" "$WORK/aac-lc.adts" "$WORK/aac-main.adts" 0
python3 "$HERE/edit-adts-profile.py" "$WORK/aac-lc.adts" "$WORK/aac-ltp.adts" 3
python3 "$HERE/reject-hdr-sei.py" hevc "$WORK/hdr10-plus.hevc" \
  "$WORK/hevc-hdr10-plus-rejected-sei.hevc"
python3 "$HERE/reject-hdr-sei.py" h264 "$WORK/hdr10.h264" "$WORK/h264-hdr10-rejected-sei.h264"
for variant in hdr10-plus-rejected static-after-frame; do
  python3 "$HERE/insert-av1-metadata.py" "$variant" "$WORK/av1-pq.ivf" "$WORK/av1-$variant.ivf"
done
in_image cropped.sh
in_image adts.sh

FIXTURES_TO_PROBE=(mp4-titled.mp4 mkv-remuxed-from-mp4.mkv mp4-default-handlers.mp4
  mkv-pipe-hevc-10bit.mkv mpegts-empty-audio.ts mkv-mp3-11khz.mkv mkv-offset-chapters.mkv
  mkv-many-chapters.mkv mkv-title-tags.mkv mkv-many-titles.mkv mp4-clap.mp4 mkv-pixel-crop.mkv mp4-rotated.mp4
  mp4-cover-art-first.mp4 mp4-er-aac-lc.mp4 mp4-hdr10-static.mp4 mkv-hdr10-static.mkv
  mp4-hdr10-maxfall-zero.mp4 mkv-hdr10-maxfall-zero.mkv mp4-hdr10-sei-only.mp4 mp4-sdr8-bt709.mp4
  mpegts-hdr10-sei-only.ts mpegts-pq10.ts
  mpegts-hevc-ac3.ts ivf-av1-10bit.ivf obu-av1-10bit.obu mkv-av1-10bit.mkv
  mp4-av1-10bit.mp4 mp4-audio-codecs.mp4 mp4-avc-record-level.mp4 mp4-hevc-record-level.mp4
  mpegts-aac-main.ts mpegts-aac-ltp.ts mkv-120fps.mkv mp4-100fps.mp4 mp4-90fps.mp4 mp4-72fps.mp4
  hevc-hdr10-plus-rejected-sei.hevc h264-hdr10-rejected-sei.h264 mp4-av1-pq.mp4
  mp4-av1-hdr10-plus-rejected.mp4 mp4-av1-static-after-frame.mp4)
if [ -n "${FATE_SUITE:-}" ]; then
  in_image fate.sh
  python3 "$HERE/move-rpus-ahead.py" next "$WORK/dovi-p5.hevc" "$WORK/hevc-dovi-p5-rpus-ahead.hevc"
  python3 "$HERE/move-rpus-ahead.py" own "$WORK/dovi-p5.hevc" \
    "$WORK/hevc-dovi-p5-rpus-before-own-picture.hevc"
  for variant in rpu rpu-bad-emdf; do
    python3 "$HERE/insert-av1-metadata.py" "$variant" "$WORK/av1-pq.ivf" "$WORK/av1-$variant.ivf" \
      "$WORK/dovi-p5.hevc"
  done
  FIXTURES_TO_PROBE+=(mkv-eac3-atmos-dts-hd-ma.mkv mp4-dovi-p5-hev1.mp4 mkv-hdr10-fate.mkv
    mpegts-dovi-p7.ts hevc-hdr10-plus.hevc mkv-dovi-p7-hvce.mkv mpegts-dovi-p7-two-layers.ts
    hevc-dovi-p5-rpus-ahead.hevc hevc-dovi-p5-rpus-before-own-picture.hevc mp4-av1-rpu.mp4
    mp4-av1-rpu-bad-emdf.mp4)
fi
in_image av1.sh

# HE-AAC in ADTS, which a local sample holds, next to a synthesized video stream in MPEG-TS. The
# record-less Dolby Vision profile 5 sample, rewritten to signal BT.709 in its VUI under hvc1, and
# copied into MPEG-TS, which then carries no Dolby Vision descriptor.
cat > "$WORK/local.sh" <<'CONTAINER'
set -euo pipefail
cd /work
FF=(timeout 60 ffmpeg -hide_banner -nostdin -loglevel error -y)
"${FF[@]}" -f lavfi -i testsrc2=size=64x36:rate=24:duration=2 -i /samples/aac-he.adts \
  -map 0:v -map 1:a -c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:a copy -t 2 mpegts-aac-he.ts
"${FF[@]}" -i /samples/mp4-dovi-p5-dvh1-no-record.mp4 -map 0:v -t 3 -c copy \
  -bsf:v hevc_metadata=colour_primaries=1:transfer_characteristics=1:matrix_coefficients=1 \
  -tag:v hvc1 mp4-dovi-p5-no-record-bt709.mp4
"${FF[@]}" -i /samples/mp4-dovi-p5-dvh1-no-record.mp4 -map 0:v -t 3 -c copy \
  mpegts-dovi-p5-no-descriptor.ts
# Dolby Vision profile 9 in MPEG-TS, whose samples delimit their NAL units with start codes.
"${FF[@]}" -i /samples/mp4-dovi-p9-avc1.mp4 -map 0:v -t 1 -c copy mpegts-dovi-p9.ts
CONTAINER

LOCAL_SAMPLE_NAMES=(mp4-dovi-p5-dvh1.mp4 mp4-dovi-p5-dvh1-no-record.mp4 mp4-dovi-p81.mp4
  mp4-dovi-p82.mp4 mp4-dovi-p84-hlg.mp4 mp4-dovi-p9-avc1.mp4 mp4-dovi-p9-dva1.mp4 mp4-hevc-hdr10.mp4
  mp4-hevc-sdr10-untagged.mp4 mov-phone-hlg-dovi-p84.mov mov-phone-rotated.mov mov-phone-one-gap.mov
  mov-phone-120-declared.mov mov-phone-60.mov mov-phone-slow-motion.mov mpegts-avchd-interlaced.mts)
PROBE_ARGUMENTS=("${FIXTURES_TO_PROBE[@]}")
if [ -n "${LOCAL_SAMPLES:-}" ]; then
  in_image local.sh
  FIXTURES_TO_PROBE+=(mpegts-aac-he.ts mp4-dovi-p5-no-record-bt709.mp4
    mpegts-dovi-p5-no-descriptor.ts mpegts-dovi-p9.ts)
  PROBE_ARGUMENTS+=(mpegts-aac-he.ts mp4-dovi-p5-no-record-bt709.mp4 mpegts-dovi-p5-no-descriptor.ts
    mpegts-dovi-p9.ts)
  for sample in "${LOCAL_SAMPLE_NAMES[@]}"; do
    PROBE_ARGUMENTS+=("/samples/$sample")
  done
  FIXTURES_TO_PROBE+=("${LOCAL_SAMPLE_NAMES[@]}")
fi

printf 'bash /work/probe.sh %s\n' "${PROBE_ARGUMENTS[*]}" > "$WORK/probe-all.sh"
in_image probe-all.sh

# The frame-rate rule's packet scan of the chosen video stream, with the probe's own arguments, for
# the sources that the frame-rate tests read.
FRAME_RATE_SCANS=(mov-phone-hlg-dovi-p84.mov mov-phone-rotated.mov mov-phone-one-gap.mov
  mov-phone-120-declared.mov mov-phone-60.mov mov-phone-slow-motion.mov mpegts-avchd-interlaced.mts
  mkv-120fps.mkv mp4-100fps.mp4 mp4-90fps.mp4 mp4-72fps.mp4)
python3 "$HERE/frame-rate-scans.py" "$WORK" "${FRAME_RATE_SCANS[@]}" > "$WORK/scan-all.sh"
in_image scan-all.sh
mkdir -p "$FIXTURES/packets"
for source in "${FRAME_RATE_SCANS[@]}"; do
  if [ -f "$WORK/packets/$source.csv" ]; then
    cp "$WORK/packets/$source.csv" "$FIXTURES/packets/"
  fi
done

# Each video and audio stream that the probe computes a codec string for, copied with the worker's
# copy arguments into a fragmented MP4. copy-codec-strings.py reads the codec string of each copy's
# initialization segment, so the tests compare the probe's string with what a copy carries.
# The raw streams that move-rpus-ahead.py and reject-hdr-sei.py wrote hold no timestamps, which the
# mp4 muxer refuses, so they have no copy.
FIXTURES_TO_COPY=()
for source in "${FIXTURES_TO_PROBE[@]}"; do
  case "$source" in
    hevc-dovi-p5-rpus-* | *-rejected-sei.*) ;;
    *) FIXTURES_TO_COPY+=("$source") ;;
  esac
done
python3 "$HERE/copy-codec-strings.py" commands "$WORK" "${FIXTURES_TO_COPY[@]}" > "$WORK/copy-all.sh"
in_image copy-all.sh
python3 "$HERE/copy-codec-strings.py" read "$WORK" "$FIXTURES/copy-codec-strings.json"

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
mkdir -p "$FIXTURES/frames"
for source in "${FIXTURES_TO_PROBE[@]}"; do
  if [ -f "$WORK/frames/$source.json" ]; then
    python3 -c 'import json, sys
json.dump(json.load(open(sys.argv[1])), open(sys.argv[2], "w"), indent=2)
open(sys.argv[2], "a").write("\n")' "$WORK/frames/$source.json" "$FIXTURES/frames/$source.json"
  fi
done
# The first-packets reads that the tests answer with. Every other H.264, HEVC and AV1 source's read
# fails, so only its frames show its RPUs and HDR10+, and its frames rule out neither, nor any static
# HDR metadata that its SEI messages or metadata OBUs could hold. The large local samples keep no read: 3 of their
# packets take hundreds of kilobytes.
PACKET_DATA=(mp4-sdr8-bt709.mp4 mp4-dovi-p9-avc1.mp4 mp4-dovi-p9-dva1.mp4 mpegts-dovi-p9.ts
  mp4-dovi-p5-hev1.mp4 hevc-dovi-p5-rpus-ahead.hevc hevc-dovi-p5-rpus-before-own-picture.hevc
  mpegts-dovi-p7-two-layers.ts mpegts-pq10.ts
  mkv-pipe-hevc-10bit.mkv mpegts-hevc-ac3.ts hevc-hdr10-plus-rejected-sei.hevc
  h264-hdr10-rejected-sei.h264 mp4-av1-pq.mp4 mp4-av1-hdr10-plus-rejected.mp4
  mp4-av1-static-after-frame.mp4 mp4-av1-rpu.mp4 mp4-av1-rpu-bad-emdf.mp4)
mkdir -p "$FIXTURES/packet-data"
for source in "${PACKET_DATA[@]}"; do
  if [ -f "$WORK/packet-data/$source.json" ]; then
    python3 -c 'import json, sys
json.dump(json.load(open(sys.argv[1])), open(sys.argv[2], "w"), indent=2)
open(sys.argv[2], "a").write("\n")' "$WORK/packet-data/$source.json" "$FIXTURES/packet-data/$source.json"
  fi
done
cp "$WORK/ffprobe-version.txt" "$WORK/pixel-formats.json" "$FIXTURES/"
echo "recorded into $FIXTURES with $IMAGE"
