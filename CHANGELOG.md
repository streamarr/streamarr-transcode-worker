# Changelog

## 0.1.0 (2026-09-25)


### Changes

* bound every wait, the memory and the slots of a job attempt's producer ([#47](https://github.com/streamarr/streamarr-transcode-worker/issues/47)) ([a89c1b3](https://github.com/streamarr/streamarr-transcode-worker/commit/a89c1b3e05d153aa8b4d05589925911e0ff815c4))
* carry unchanged FFmpeg notice reviews forward automatically ([#19](https://github.com/streamarr/streamarr-transcode-worker/issues/19)) ([8063b91](https://github.com/streamarr/streamarr-transcode-worker/commit/8063b912e5f5c64cd41a8df9bc7c20e61a608efd))
* deliver segments from FFmpeg's standard output through a producer ([#46](https://github.com/streamarr/streamarr-transcode-worker/issues/46)) ([c3cb8c3](https://github.com/streamarr/streamarr-transcode-worker/commit/c3cb8c321327479f8baa645d6d1a5c6d5c893ef2))
* inventory libva as an embedded VA-API import shim ([#33](https://github.com/streamarr/streamarr-transcode-worker/issues/33)) ([a49a099](https://github.com/streamarr/streamarr-transcode-worker/commit/a49a0999c5e55ccaa373a379581b2266c9cc461d))
* propose import shims as embedded and inspect patches in every notice review ([#34](https://github.com/streamarr/streamarr-transcode-worker/issues/34)) ([4279487](https://github.com/streamarr/streamarr-transcode-worker/commit/4279487319fc07709ed0567a23250a4d5462862d))
* read FFmpeg's fragmented MP4 and group its fragments on the segment grid ([#45](https://github.com/streamarr/streamarr-transcode-worker/issues/45)) ([67c7c9b](https://github.com/streamarr/streamarr-transcode-worker/commit/67c7c9b5a257068e542783edcb1304e1964a2587))
* regenerate FFmpeg notice inputs from upstream pins ([#20](https://github.com/streamarr/streamarr-transcode-worker/issues/20)) ([ee1e4eb](https://github.com/streamarr/streamarr-transcode-worker/commit/ee1e4ebda4f986ab018670c18bf85e90c5b0b72b))
* regenerate FFmpeg notices on Renovate updates and bind them on approval ([#21](https://github.com/streamarr/streamarr-transcode-worker/issues/21)) ([177ec16](https://github.com/streamarr/streamarr-transcode-worker/commit/177ec16362c6634bb4d5891df7aace9ab11fa38c))
* upload a segment FFmpeg writes just before exiting ([#38](https://github.com/streamarr/streamarr-transcode-worker/issues/38)) ([f12c738](https://github.com/streamarr/streamarr-transcode-worker/commit/f12c7387afc1da1719cfe6e0449b8dd7e5e9727e))
* verify worker images and automate releases ([#8](https://github.com/streamarr/streamarr-transcode-worker/issues/8)) ([6c53128](https://github.com/streamarr/streamarr-transcode-worker/commit/6c531289088135aed5d0231cb369b9a26efa8da8))


### Refactoring

* add agent guidelines adapted from streamarr-server ([#17](https://github.com/streamarr/streamarr-transcode-worker/issues/17)) ([2edb46f](https://github.com/streamarr/streamarr-transcode-worker/commit/2edb46f1181f7b00c5e044b4a9999a07cae41151))
* add GitNexus agent context and skills ([#30](https://github.com/streamarr/streamarr-transcode-worker/issues/30)) ([0487a1c](https://github.com/streamarr/streamarr-transcode-worker/commit/0487a1c9e117f1361c10f3e4daa55993a9681f1f))
* point at the shared domain glossary in streamarr-adr ([#41](https://github.com/streamarr/streamarr-transcode-worker/issues/41)) ([3dda880](https://github.com/streamarr/streamarr-transcode-worker/commit/3dda8804c877cff11f07953cdba1069c8782ff02))
* simplify README and separate developer setup ([#32](https://github.com/streamarr/streamarr-transcode-worker/issues/32)) ([89329cd](https://github.com/streamarr/streamarr-transcode-worker/commit/89329cdf1ef599e230087a34487328609db07c44))
* verify build and release tooling in its own CI job ([#29](https://github.com/streamarr/streamarr-transcode-worker/issues/29)) ([ffb4aaf](https://github.com/streamarr/streamarr-transcode-worker/commit/ffb4aaf968476c90c1ed415d1fada8bb6dab62ec))
