# Landlock buildpack

This buildpack compiles `landlock-exec`, the static helper through which the worker runs FFmpeg and
ffprobe under [Linux Landlock](https://docs.kernel.org/userspace-api/landlock.html). Its source is
`src/landlock-exec.c`; the Paketo build image's `cc` compiles it into a launch layer whose `bin` is
on the image's `PATH`. Nothing compiled is committed.

```
landlock-exec --abi
landlock-exec [--read PATH]... [--read-pinned PATH DEVICE:INODE]... [--read-execute PATH]...
              [--read-write PATH]... [--device PATH]... -- COMMAND [ARGUMENT]...
```

The helper builds a ruleset that handles every filesystem right the running kernel's Landlock ABI
knows, adds one rule per listed path, denies every TCP bind and connect (ABI 4 and later), sets
`no_new_privs`, restricts itself and executes the command. The command and every process it starts
inherit the restriction; the worker does not. A listed path that does not exist adds no rule, so one
policy serves hosts with and without a GPU device. `--abi` prints the kernel's Landlock ABI version
and is how the worker checks the host at startup.

| Option | Rights beneath the path |
| --- | --- |
| `--read` | read files and list directories |
| `--read-pinned` | read files and list directories, when the path is still the directory with the given device and inode numbers; otherwise the command does not run |
| `--read-execute` | read, list and execute |
| `--read-write` | read, list, create, write, truncate and remove files and directories |
| `--device` | read, write and (ABI 5 and later) ioctl device nodes, and list a device directory |

Exit status: the command's own once it runs; `2` when the kernel offers no Landlock; `124` when a
pinned path is no longer its directory; `125` for a usage error or a ruleset the kernel refused;
`126` when the command cannot be executed; `127` when it is not found.

Compile it anywhere with a C compiler, for the smoke tests or a local worker:

```sh
buildpacks/landlock/bin/compile target/landlock/landlock-exec
export PATH="$PWD/target/landlock:$PATH"
```

`node --test buildpacks/landlock/test/*.test.mjs` compiles the helper and proves what it allows and
denies; the tests skip on a host without Landlock.
