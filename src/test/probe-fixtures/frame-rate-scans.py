#!/usr/bin/env python3
"""Prints the frame-rate rule's packet scan of each recorded source's chosen video stream.

  frame-rate-scans.py WORK FIXTURE...

The chosen video stream is the first video stream that is not an attached picture. The scan uses
the arguments that FfprobeExecutor passes: the first 30 s of the stream's packets, as
pts,dts,size,flags lines. A source that is missing, such as a local sample that was not provided,
is skipped.
"""
import json
import os
import sys

work, fixtures = sys.argv[1], sys.argv[2:]
print('set -euo pipefail')
print('mkdir -p /work/packets')
for fixture in fixtures:
    recorded = os.path.join(work, fixture + '.json')
    if not os.path.exists(recorded):
        continue
    streams = json.load(open(recorded)).get('streams', [])
    chosen = next(stream['index'] for stream in streams
                  if stream.get('codec_type') == 'video'
                  and stream.get('disposition', {}).get('attached_pic') != 1)
    source = f'/work/{fixture}' if os.path.exists(os.path.join(work, fixture)) else f'/samples/{fixture}'
    print(f"ffprobe -v error -select_streams {chosen} -read_intervals %+30 "
          f"-show_entries packet=pts,dts,size,flags -of csv=p=0 '{source}' > '/work/packets/{fixture}.csv'")
