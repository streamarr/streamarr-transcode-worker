/*
 * landlock-exec runs a command under a Linux Landlock ruleset that limits the command's filesystem
 * access to the listed paths and denies every TCP bind and connect. The ruleset is applied to this
 * process right before it executes the command, so the command and everything it starts inherit
 * it, and nothing else does.
 *
 *   landlock-exec --abi
 *   landlock-exec [--read PATH]... [--read-execute PATH]... [--read-write PATH]...
 *                 [--device PATH]... -- COMMAND [ARGUMENT]...
 *
 * --abi prints the Landlock ABI version the kernel offers. A listed path that does not exist is
 * skipped, so one policy fits hosts with and without a device. Exit status: the command's own once
 * it runs; 2 when the kernel offers no Landlock; 125 for a usage error or a ruleset the kernel
 * refused; 126 when the command cannot be executed; 127 when it is not found.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/landlock.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

/* Rights that headers older than the kernel lack; the kernel's own ABI version gates their use. */
#ifndef LANDLOCK_ACCESS_FS_REFER
#define LANDLOCK_ACCESS_FS_REFER (1ULL << 13)
#endif
#ifndef LANDLOCK_ACCESS_FS_TRUNCATE
#define LANDLOCK_ACCESS_FS_TRUNCATE (1ULL << 14)
#endif
#ifndef LANDLOCK_ACCESS_FS_IOCTL_DEV
#define LANDLOCK_ACCESS_FS_IOCTL_DEV (1ULL << 15)
#endif
#ifndef LANDLOCK_ACCESS_NET_BIND_TCP
#define LANDLOCK_ACCESS_NET_BIND_TCP (1ULL << 0)
#endif
#ifndef LANDLOCK_ACCESS_NET_CONNECT_TCP
#define LANDLOCK_ACCESS_NET_CONNECT_TCP (1ULL << 1)
#endif

/* The attribute as ABI 4 and later lay it out; ABI 1 to 3 are given only its first field. */
struct ruleset_attr {
  uint64_t handled_access_fs;
  uint64_t handled_access_net;
};

enum exit_status {
  EXIT_NO_LANDLOCK = 2,
  EXIT_USAGE_OR_RULESET = 125,
  EXIT_CANNOT_EXECUTE = 126,
  EXIT_NOT_FOUND = 127,
};

static const uint64_t ACCESS_FS_ABI_1 =
    LANDLOCK_ACCESS_FS_EXECUTE | LANDLOCK_ACCESS_FS_WRITE_FILE | LANDLOCK_ACCESS_FS_READ_FILE |
    LANDLOCK_ACCESS_FS_READ_DIR | LANDLOCK_ACCESS_FS_REMOVE_DIR | LANDLOCK_ACCESS_FS_REMOVE_FILE |
    LANDLOCK_ACCESS_FS_MAKE_CHAR | LANDLOCK_ACCESS_FS_MAKE_DIR | LANDLOCK_ACCESS_FS_MAKE_REG |
    LANDLOCK_ACCESS_FS_MAKE_SOCK | LANDLOCK_ACCESS_FS_MAKE_FIFO | LANDLOCK_ACCESS_FS_MAKE_BLOCK |
    LANDLOCK_ACCESS_FS_MAKE_SYM;

static const uint64_t ACCESS_READ = LANDLOCK_ACCESS_FS_READ_FILE | LANDLOCK_ACCESS_FS_READ_DIR;
static const uint64_t ACCESS_READ_EXECUTE =
    LANDLOCK_ACCESS_FS_READ_FILE | LANDLOCK_ACCESS_FS_READ_DIR | LANDLOCK_ACCESS_FS_EXECUTE;
static const uint64_t ACCESS_READ_WRITE =
    LANDLOCK_ACCESS_FS_READ_FILE | LANDLOCK_ACCESS_FS_READ_DIR | LANDLOCK_ACCESS_FS_WRITE_FILE |
    LANDLOCK_ACCESS_FS_REMOVE_DIR | LANDLOCK_ACCESS_FS_REMOVE_FILE | LANDLOCK_ACCESS_FS_MAKE_DIR |
    LANDLOCK_ACCESS_FS_MAKE_REG | LANDLOCK_ACCESS_FS_TRUNCATE;
static const uint64_t ACCESS_DEVICE = LANDLOCK_ACCESS_FS_READ_FILE | LANDLOCK_ACCESS_FS_WRITE_FILE |
                                      LANDLOCK_ACCESS_FS_READ_DIR | LANDLOCK_ACCESS_FS_IOCTL_DEV;

/* The kernel refuses a rule that gives a non-directory any right outside this set. */
static const uint64_t ACCESS_FILE = LANDLOCK_ACCESS_FS_EXECUTE | LANDLOCK_ACCESS_FS_WRITE_FILE |
                                    LANDLOCK_ACCESS_FS_READ_FILE | LANDLOCK_ACCESS_FS_TRUNCATE |
                                    LANDLOCK_ACCESS_FS_IOCTL_DEV;

struct rule {
  const char *path;
  uint64_t access;
};

static void fail(int status, const char *what, const char *subject) {
  int saved = errno;
  const char *name = strerrorname_np(saved);
  fprintf(stderr, "landlock-exec: %s%s%s: %s: %s\n", what, subject ? " " : "", subject ? subject : "",
          name ? name : "errno", strerror(saved));
  exit(status);
}

static void usage(void) {
  fputs("usage: landlock-exec --abi\n"
        "       landlock-exec [--read PATH]... [--read-execute PATH]... [--read-write PATH]...\n"
        "                     [--device PATH]... -- COMMAND [ARGUMENT]...\n",
        stderr);
  exit(EXIT_USAGE_OR_RULESET);
}

static int landlock_abi(void) {
  return (int)syscall(__NR_landlock_create_ruleset, NULL, 0, LANDLOCK_CREATE_RULESET_VERSION);
}

static uint64_t handled_fs_for(int abi) {
  uint64_t handled = ACCESS_FS_ABI_1;
  if (abi >= 2) {
    handled |= LANDLOCK_ACCESS_FS_REFER;
  }
  if (abi >= 3) {
    handled |= LANDLOCK_ACCESS_FS_TRUNCATE;
  }
  if (abi >= 5) {
    handled |= LANDLOCK_ACCESS_FS_IOCTL_DEV;
  }
  return handled;
}

static uint64_t access_for(const char *option) {
  if (strcmp(option, "--read") == 0) {
    return ACCESS_READ;
  }
  if (strcmp(option, "--read-execute") == 0) {
    return ACCESS_READ_EXECUTE;
  }
  if (strcmp(option, "--read-write") == 0) {
    return ACCESS_READ_WRITE;
  }
  if (strcmp(option, "--device") == 0) {
    return ACCESS_DEVICE;
  }
  usage();
  return 0;
}

/* Adds the rule for a path that exists; a missing path adds no rule and allows nothing. */
static void add_rule(int ruleset_fd, const struct rule *rule, uint64_t handled) {
  int fd = open(rule->path, O_PATH | O_CLOEXEC);
  if (fd < 0) {
    if (errno == ENOENT || errno == ENOTDIR) {
      return;
    }
    fail(EXIT_USAGE_OR_RULESET, "cannot open", rule->path);
  }

  struct stat attributes;
  if (fstat(fd, &attributes) != 0) {
    fail(EXIT_USAGE_OR_RULESET, "cannot stat", rule->path);
  }

  uint64_t access = rule->access & handled;
  if (!S_ISDIR(attributes.st_mode)) {
    access &= ACCESS_FILE;
  }

  struct landlock_path_beneath_attr beneath = {.allowed_access = access, .parent_fd = fd};
  if (syscall(__NR_landlock_add_rule, ruleset_fd, LANDLOCK_RULE_PATH_BENEATH, &beneath, 0) != 0) {
    fail(EXIT_USAGE_OR_RULESET, "landlock_add_rule", rule->path);
  }
  close(fd);
}

static void restrict_self(const struct rule *rules, int rule_count) {
  int abi = landlock_abi();
  if (abi < 0) {
    fail(EXIT_NO_LANDLOCK, "Landlock is unavailable: landlock_create_ruleset", NULL);
  }

  uint64_t handled = handled_fs_for(abi);
  struct ruleset_attr attr = {
      .handled_access_fs = handled,
      .handled_access_net =
          abi >= 4 ? LANDLOCK_ACCESS_NET_BIND_TCP | LANDLOCK_ACCESS_NET_CONNECT_TCP : 0,
  };
  size_t attr_size = abi >= 4 ? sizeof attr : sizeof attr.handled_access_fs;
  int ruleset_fd = (int)syscall(__NR_landlock_create_ruleset, &attr, attr_size, 0);
  if (ruleset_fd < 0) {
    fail(EXIT_USAGE_OR_RULESET, "landlock_create_ruleset", NULL);
  }

  for (int i = 0; i < rule_count; i++) {
    add_rule(ruleset_fd, &rules[i], handled);
  }

  if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) {
    fail(EXIT_USAGE_OR_RULESET, "prctl(PR_SET_NO_NEW_PRIVS)", NULL);
  }
  if (syscall(__NR_landlock_restrict_self, ruleset_fd, 0) != 0) {
    fail(EXIT_USAGE_OR_RULESET, "landlock_restrict_self", NULL);
  }
  close(ruleset_fd);
}

int main(int argc, char **argv) {
  if (argc == 2 && strcmp(argv[1], "--abi") == 0) {
    int abi = landlock_abi();
    if (abi < 0) {
      fail(EXIT_NO_LANDLOCK, "Landlock is unavailable: landlock_create_ruleset", NULL);
    }
    printf("%d\n", abi);
    return 0;
  }

  struct rule *rules = calloc((size_t)argc, sizeof *rules);
  if (rules == NULL) {
    fail(EXIT_USAGE_OR_RULESET, "cannot allocate", NULL);
  }

  int rule_count = 0;
  int command = 0;
  for (int i = 1; i < argc; i++) {
    if (strcmp(argv[i], "--") == 0) {
      command = i + 1;
      break;
    }
    if (i + 1 >= argc) {
      usage();
    }
    rules[rule_count].access = access_for(argv[i]);
    rules[rule_count].path = argv[i + 1];
    rule_count++;
    i++;
  }
  if (command == 0 || command >= argc) {
    usage();
  }

  restrict_self(rules, rule_count);
  execvp(argv[command], &argv[command]);
  fail(errno == ENOENT ? EXIT_NOT_FOUND : EXIT_CANNOT_EXECUTE, "cannot execute", argv[command]);
}
