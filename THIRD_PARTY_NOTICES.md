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
