# Summarizes one quilt patch for bin/review-release as tab-separated records:
#   created    <target>  the patch creates this file
#   sensitive  <target>  a file section for configure, LICENSE* or COPYING* starts
#   change     <line>    a line that section adds or removes
# Targets lose their first path component, because `quilt push` applies patches with -p1.
#
# The grammar is deliberately narrower than what patch(1) accepts: whatever patch(1) could read
# differently ends the summary with an `ambiguous` record and exit status 1.

function ambiguous(reason) {
  printf "ambiguous\t%s at line %d\n", reason, NR
  failed = 1
  exit 1
}

function target_of(name) {
  sub(/\t.*/, "", name)
  if (name == "/dev/null") {
    return name
  }
  if (name !~ "^[A-Za-z0-9._+-]+/[A-Za-z0-9._/+-]+$" || index("/" name "/", "/../") > 0) {
    ambiguous("unsupported file name")
  }
  sub("^[^/]*/", "", name)
  return name
}

function is_sensitive(target,    parts, depth, basename) {
  depth = split(target, parts, "/")
  basename = tolower(parts[depth])
  return basename == "configure" || basename ~ /^license/ || basename ~ /^copying/
}

function name_section(target) {
  if (target == "/dev/null") {
    return
  }
  if (section != "" && section != target) {
    ambiguous("file section names more than one file")
  }
  if (section != "") {
    return
  }
  section = target
  sections += 1
  section_is_sensitive = is_sensitive(target)
  if (section_is_sensitive) {
    printf "sensitive\t%s\n", target
  }
}

function record_created() {
  if (!(section in created)) {
    created[section] = 1
    printf "created\t%s\n", section
  }
}

function hunk_size(range,    parts) {
  if (split(range, parts, ",") == 2) {
    return parts[2] + 0
  }
  return 1
}

old_remaining > 0 || new_remaining > 0 {
  marker = substr($0, 1, 1)
  # patch(1) reads a blank line as context whose trailing space was stripped.
  if ((marker == " " || $0 == "") && old_remaining > 0 && new_remaining > 0) {
    old_remaining -= 1
    new_remaining -= 1
    next
  }
  if (marker == "\\") {
    next
  }
  if (marker == "-" && old_remaining > 0) {
    old_remaining -= 1
  } else if (marker == "+" && new_remaining > 0) {
    new_remaining -= 1
  } else {
    ambiguous("malformed hunk")
  }
  if (section_is_sensitive) {
    printf "change\t%s\n", $0
  }
  next
}

expected == "new name" {
  if ($0 !~ /^[+][+][+] /) {
    ambiguous("old file name without a new file name")
  }
  new_name = target_of(substr($0, 5))
  if (old_name == new_name && new_name == "/dev/null") {
    ambiguous("file section names no file")
  }
  name_section(old_name)
  name_section(new_name)
  if (old_name == "/dev/null") {
    record_created()
  }
  header = ""
  expected = "hunk"
  next
}

/^@@ -[0-9]+(,[0-9]+)? [+][0-9]+(,[0-9]+)? @@/ {
  if (section == "" || header != "") {
    ambiguous("hunk outside a file section")
  }
  split($0, fields, " ")
  old_remaining = hunk_size(fields[2])
  new_remaining = hunk_size(fields[3])
  expected = ""
  next
}

expected == "hunk" {
  ambiguous("file section without a hunk")
}

/^Index: / || /^diff --git / {
  # An Index: line alone also names the file for the normal and ed diffs this grammar ignores.
  if (header == "index") {
    ambiguous("file header without a file section")
  }
  section = ""
}

/^Index: / {
  name_section(target_of(substr($0, 8)))
  header = "index"
  next
}

/^diff --git / {
  if (split($0, fields, " ") != 4) {
    ambiguous("unsupported file name")
  }
  name_section(target_of(fields[3]))
  name_section(target_of(fields[4]))
  header = "git"
  next
}

/^new file mode / {
  if (header != "git") {
    ambiguous("file mode outside a file section")
  }
  record_created()
  next
}

/^--- / {
  if (header == "") {
    section = ""
  }
  old_name = target_of(substr($0, 5))
  expected = "new name"
  next
}

# patch(1) also accepts indented patches, context diffs, renames, copies and binary patches.
/^[ \t]*([+][+][+] |--- |[*][*][*] |Index:|diff --git |@@ |rename |copy |GIT binary patch|Binary files )/ {
  ambiguous("unsupported patch syntax")
}

END {
  if (failed) {
    exit 1
  }
  if (old_remaining > 0 || new_remaining > 0) {
    ambiguous("truncated hunk")
  }
  if (expected != "" || header == "index") {
    ambiguous("file header without a file section")
  }
  if (sections == 0) {
    ambiguous("no file section")
  }
}
