# Changelog

## 0.1.0 (2026-10-17)


### Changes

* answer and advertise probe version 4 only ([#91](https://github.com/streamarr/streamarr-transcode-worker/issues/91)) ([75ce167](https://github.com/streamarr/streamarr-transcode-worker/commit/75ce167c763d42eb7b4bc1c239990b1a2bc8e26b))
* bound every wait, the memory and the slots of a job attempt's producer ([#47](https://github.com/streamarr/streamarr-transcode-worker/issues/47)) ([a89c1b3](https://github.com/streamarr/streamarr-transcode-worker/commit/a89c1b3e05d153aa8b4d05589925911e0ff815c4))
* carry unchanged FFmpeg notice reviews forward automatically ([#19](https://github.com/streamarr/streamarr-transcode-worker/issues/19)) ([8063b91](https://github.com/streamarr/streamarr-transcode-worker/commit/8063b912e5f5c64cd41a8df9bc7c20e61a608efd))
* deliver segments from FFmpeg's standard output through a producer ([#46](https://github.com/streamarr/streamarr-transcode-worker/issues/46)) ([c3cb8c3](https://github.com/streamarr/streamarr-transcode-worker/commit/c3cb8c321327479f8baa645d6d1a5c6d5c893ef2))
* encode every video variant as 8-bit 4:2:0 ([#58](https://github.com/streamarr/streamarr-transcode-worker/issues/58)) ([b6d2b86](https://github.com/streamarr/streamarr-transcode-worker/commit/b6d2b86541ebcd2dd67dc09ccdf079fc48a5484c))
* inventory libva as an embedded VA-API import shim ([#33](https://github.com/streamarr/streamarr-transcode-worker/issues/33)) ([a49a099](https://github.com/streamarr/streamarr-transcode-worker/commit/a49a0999c5e55ccaa373a379581b2266c9cc461d))
* match FFmpeg display matrix rotation ([#99](https://github.com/streamarr/streamarr-transcode-worker/issues/99)) ([cc75016](https://github.com/streamarr/streamarr-transcode-worker/commit/cc75016f78e97868ffffde5b82ae6949607ca6b6))
* propose import shims as embedded and inspect patches in every notice review ([#34](https://github.com/streamarr/streamarr-transcode-worker/issues/34)) ([4279487](https://github.com/streamarr/streamarr-transcode-worker/commit/4279487319fc07709ed0567a23250a4d5462862d))
* raise segment cap to 128 MiB ([#51](https://github.com/streamarr/streamarr-transcode-worker/issues/51)) ([11cf84e](https://github.com/streamarr/streamarr-transcode-worker/commit/11cf84e539e5bd6e8247f44fd9f276226e49b66d))
* read FFmpeg's fragmented MP4 and group its fragments on the segment grid ([#45](https://github.com/streamarr/streamarr-transcode-worker/issues/45)) ([67c7c9b](https://github.com/streamarr/streamarr-transcode-worker/commit/67c7c9b5a257068e542783edcb1304e1964a2587))
* refuse a video job whose encoder preset is ultrafast ([#85](https://github.com/streamarr/streamarr-transcode-worker/issues/85)) ([f0e3cda](https://github.com/streamarr/streamarr-transcode-worker/commit/f0e3cda23dabe1ac3bb6941aeb732e4d79447ab7))
* regenerate FFmpeg notice inputs from upstream pins ([#20](https://github.com/streamarr/streamarr-transcode-worker/issues/20)) ([ee1e4eb](https://github.com/streamarr/streamarr-transcode-worker/commit/ee1e4ebda4f986ab018670c18bf85e90c5b0b72b))
* regenerate FFmpeg notices on Renovate updates and bind them on approval ([#21](https://github.com/streamarr/streamarr-transcode-worker/issues/21)) ([177ec16](https://github.com/streamarr/streamarr-transcode-worker/commit/177ec16362c6634bb4d5891df7aace9ab11fa38c))
* reject output dynamic range on video copies ([d63f6f5](https://github.com/streamarr/streamarr-transcode-worker/commit/d63f6f566daeaf84860131034b4dfe5add49aa4c))
* reject output dynamic range on video copies ([#101](https://github.com/streamarr/streamarr-transcode-worker/issues/101)) ([1e5e8ca](https://github.com/streamarr/streamarr-transcode-worker/commit/1e5e8ca888eb5a094c876c1884e0aba65774d086))
* tone map HDR encodes to SDR ([#96](https://github.com/streamarr/streamarr-transcode-worker/issues/96)) ([0a6eaf3](https://github.com/streamarr/streamarr-transcode-worker/commit/0a6eaf3acd37d3bbe918df330ec38301dcd9e0c2))
* upload a segment FFmpeg writes just before exiting ([#38](https://github.com/streamarr/streamarr-transcode-worker/issues/38)) ([f12c738](https://github.com/streamarr/streamarr-transcode-worker/commit/f12c7387afc1da1719cfe6e0449b8dd7e5e9727e))
* verify worker images and automate releases ([#8](https://github.com/streamarr/streamarr-transcode-worker/issues/8)) ([6c53128](https://github.com/streamarr/streamarr-transcode-worker/commit/6c531289088135aed5d0231cb369b9a26efa8da8))


### Refactoring

* add agent guidelines adapted from streamarr-server ([#17](https://github.com/streamarr/streamarr-transcode-worker/issues/17)) ([2edb46f](https://github.com/streamarr/streamarr-transcode-worker/commit/2edb46f1181f7b00c5e044b4a9999a07cae41151))
* add GitNexus agent context and skills ([#30](https://github.com/streamarr/streamarr-transcode-worker/issues/30)) ([0487a1c](https://github.com/streamarr/streamarr-transcode-worker/commit/0487a1c9e117f1361c10f3e4daa55993a9681f1f))
* point at the shared domain glossary in streamarr-adr ([#41](https://github.com/streamarr/streamarr-transcode-worker/issues/41)) ([3dda880](https://github.com/streamarr/streamarr-transcode-worker/commit/3dda8804c877cff11f07953cdba1069c8782ff02))
* simplify README and separate developer setup ([#32](https://github.com/streamarr/streamarr-transcode-worker/issues/32)) ([89329cd](https://github.com/streamarr/streamarr-transcode-worker/commit/89329cdf1ef599e230087a34487328609db07c44))
* verify build and release tooling in its own CI job ([#29](https://github.com/streamarr/streamarr-transcode-worker/issues/29)) ([ffb4aaf](https://github.com/streamarr/streamarr-transcode-worker/commit/ffb4aaf968476c90c1ed415d1fada8bb6dab62ec))
