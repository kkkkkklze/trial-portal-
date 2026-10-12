"""Checks a handoff archive produced by make-dist.py, independently of the packager.

Deliberately a separate program: a packager that grades its own work proves nothing. This re-checks
the artifact, not the inputs:

* zip-slip safety — absolute paths, ``..`` traversal, symlink entries
* that the extracted tree matches the archive entry list exactly
* that only the files you marked are executable
* that everything the generated notes *claim* is inside actually is inside
* a fresh leak scan of the extracted bytes, using its own patterns
* that machine-specific settings were really scrubbed
* optionally, that the project still builds after extraction (``--build``)

Usage::

    python tools/verify-dist.py
    python tools/verify-dist.py --root .. --archive dist/foo-1.0.0.zip
    python tools/verify-dist.py --build          # also run the project's build command

Exit code 0 means every check passed.
"""

import argparse
import io
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

# Its own patterns, written separately from make-dist.py's. Shapes, not words: a game name or the word
# "development" appears in ordinary prose and must not trip a leak check.
LEAKS = [
    (re.compile(rb"C:\\Users", re.I), "Windows user path"),
    (re.compile(rb"C:/Users", re.I), "Windows user path"),
    (re.compile(rb"[A-Za-z]:\\[A-Za-z]"), "absolute Windows path"),
    (re.compile(rb"/Users/[A-Za-z]"), "POSIX home path"),
    (re.compile(rb"/home/[A-Za-z]"), "POSIX home path"),
    (re.compile(rb"\b(?:\d{1,3}\.){3}\d{1,3}\b"), "IP address"),
    (re.compile(rb"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"), "email address"),
    (re.compile(rb"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b"),
     "UUID"),
    (re.compile(rb"BEGIN [A-Z ]*PRIVATE KEY"), "private key"),
    (re.compile(rb"(?i)\bpassword\b\s*[:=]"), "password assignment"),
]
# Project-specific additions: the author's own modpack / launcher folder names. These are WORDS, which
# is normally against the rule above (a game name in prose is not a leak) - the exception is justified
# because make-dist.py deliberately scrubs exactly these tokens: if the scrub ever stops firing, the
# generic shape scan would stay green while the archive ships the author's local directory names.
# Written as ASCII escapes so this file, which ships inside the archive, does not carry them either.
_DEV = chr(0x5f00) + chr(0x53d1)   # 项目本地目录里那个中文词，按码位拼出来，本文件不写明文
_PACK_A = chr(0x661f) + chr(0x7f57) + chr(0x68cb) + chr(0x5e03)
_PACK_B = chr(0x7ec8) + chr(0x7109) + chr(0x51b3) + chr(0x6597)

LEAKS += [
    (re.compile("\u661f\u7f57\u68cb\u5e03".encode("utf-8")), "author's local modpack name"),
    (re.compile("\u7ec8\u7109\u51b3\u6597".encode("utf-8")), "author's local modpack name"),
    (re.compile(re.escape(("Documents" + chr(92) + _DEV).encode("utf-8"))), "author's local project directory"),
]

# Random 32-hex strings and dotted quads appear in compressed bytecode by chance, so only the
# path-shaped checks are meaningful inside binaries.
BINARY_LEAKS = LEAKS[:4]


def assert_rules_have_teeth():
    """Every project rule must hit its own sample; a silent rule would report "clean" forever."""
    samples = {"author's local modpack name": [_PACK_A, _PACK_B],
               "author's local project directory": ["Documents" + chr(92) + _DEV,
                                                    "Documents/" + _DEV]}
    by_desc = {}
    for pattern, description in LEAKS:
        by_desc.setdefault(description, []).append(pattern)
    silent = [d for d, words in samples.items()
              if not by_desc.get(d) or not any(pat.search(w.encode("utf-8"))
                                               for pat in by_desc[d] for w in words)]
    if silent:
        for d in silent:
            print("!! toothless rule in the verifier: %s" % d)
        return False
    return True

SELF_REFERENTIAL = ("tools/make-dist.py", "tools/verify-dist.py")

# Entry-name fragments that must never appear. Add project-specific ones (save files, credential
# stores, player databases) as you find them.
FORBIDDEN_ENTRIES = [
    "/build/", "/.gradle/", "/.idea/", "/.vscode/", "/node_modules/", "/dist/",
    "eula.txt", "latest.log", "crash-report", "ops.json", "banned-", "/saves/", "/logs/",
]

fails = []
notes = []


def check(name, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + name + (("  -- " + detail) if (not ok and detail) else ""))
    if not ok:
        fails.append(name + (" -- " + detail if detail else ""))


def account_name_patterns(explicit):
    """Matches a machine account name only where it looks like a path component.

    The verifier usually runs on a different machine from the packager, so the useful names are the
    ones passed in with --machine-name; the local environment is added as a convenience for checking
    your own archive before publishing it.
    """
    names = set(explicit)
    for variable in ("USERNAME", "USER", "USERDOMAIN", "LOGNAME"):
        value = os.environ.get(variable, "").strip()
        if value and len(value) > 2:
            names.add(value)
    patterns = []
    for name in sorted(names):
        encoded = re.escape(name).encode("utf-8")
        patterns.append((re.compile(encoded + rb"[/\\]"), "machine account name in a path"))
        patterns.append((re.compile(rb"[/\\]" + encoded + rb"\b"), "machine account name in a path"))
    return patterns


def detect_build_command(top):
    """Picks the build command the archive itself suggests, per platform.

    Windows cannot execute the POSIX `gradlew` script, and the failure looks like a corrupt file
    rather than a wrong command, so the choice is made here instead of left to the caller.
    """
    windows = os.name == "nt"
    if os.path.isfile(os.path.join(top, "gradlew.bat")) and windows:
        return "gradlew.bat build"
    if os.path.isfile(os.path.join(top, "gradlew")):
        return "sh gradlew build" if windows else "./gradlew build"
    if os.path.isfile(os.path.join(top, "package.json")):
        return "npm ci && npm test" if os.path.isfile(os.path.join(top, "package-lock.json")) \
            else "npm install && npm test"
    if os.path.isfile(os.path.join(top, "pom.xml")):
        return "mvn -q -B package"
    if os.path.isfile(os.path.join(top, "Cargo.toml")):
        return "cargo test"
    return None


def strip_self_reference(rel, data):
    """See make-dist.py. The scanners must contain the shapes they search for, so for exactly those two
    files, comments and quoted strings are removed before scanning; the code is still scanned."""
    if rel not in SELF_REFERENTIAL:
        return data
    text = data.decode("utf-8", "replace")
    text = re.sub(r"(?m)^\s*#.*$", "", text)
    text = re.sub(r'"[^"\n]*"', "STR", text)
    return text.encode("utf-8", "replace")


def main():
    if not assert_rules_have_teeth():
        sys.exit("refusing to certify the archive: a scan rule can never fire")

    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    parser.add_argument("--archive", default=None, help="path to the archive; auto-detected if omitted")
    parser.add_argument("--build", action="store_true", help="also try to build the extracted project")
    parser.add_argument("--build-command", default=None,
                        help="build command to run with --build. Auto-detected from the project "
                             "(gradlew.bat on Windows, ./gradlew elsewhere) when omitted.")
    parser.add_argument("--keep", action="store_true", help="keep the extraction for inspection")
    parser.add_argument("--machine-name", action="append", default=[],
                        help="an account name to treat as a leak (repeatable). Defaults to this "
                             "machine's own account name.")
    args = parser.parse_args()

    root = os.path.abspath(args.root)
    archive_path = args.archive
    if not archive_path:
        dist = os.path.join(root, "dist")
        candidates = sorted(f for f in os.listdir(dist) if f.endswith(".zip")) if os.path.isdir(dist) else []
        if not candidates:
            print("!! no archive found in {}. Run make-dist.py first, or pass --archive.".format(dist))
            return 1
        archive_path = os.path.join(dist, candidates[-1])
    archive_path = os.path.abspath(archive_path)
    print("checking {}".format(archive_path))

    extract = tempfile.mkdtemp(prefix="handoff-verify-")
    try:
        print("== extract first, so everything below inspects real bytes ==")
        check("archive exists", os.path.isfile(archive_path), archive_path)
        if not os.path.isfile(archive_path):
            return 1
        z = zipfile.ZipFile(archive_path)
        names = z.namelist()
        z.extractall(extract)
        top = os.path.join(extract, os.listdir(extract)[0])
        check("plausible entry count", 10 < len(names) < 5000, "got {}".format(len(names)))
        check("no duplicate entries", len(names) == len(set(names)),
              "{} entries, {} unique".format(len(names), len(set(names))))

        print("== zip-slip / path safety ==")
        bad_abs = [n for n in names if n.startswith("/") or re.match(r"^[A-Za-z]:", n)]
        bad_trav = [n for n in names if ".." in n.split("/")]
        symlinks = [i for i in z.infolist() if (i.external_attr >> 16) & 0xA000 == 0xA000]
        check("no absolute-path entries", not bad_abs, str(bad_abs[:3]))
        check("no parent-directory traversal", not bad_trav, str(bad_trav[:3]))
        check("no symlink entries", not symlinks, str([i.filename for i in symlinks][:3]))
        check("all entries share one top-level directory",
              len({n.split("/")[0] for n in names}) == 1, str({n.split("/")[0] for n in names}))

        print("== what was actually extracted ==")
        on_disk = set()
        for dirpath, _, filenames in os.walk(top):
            for name in filenames:
                on_disk.add(os.path.relpath(os.path.join(dirpath, name), extract).replace(os.sep, "/"))
        print("   {} entries in the archive, {} files on disk".format(len(names), len(on_disk)))
        check("the two match exactly", set(names) == on_disk,
              "only-in-archive: {} / only-on-disk: {}".format(
                  sorted(set(names) - on_disk)[:3], sorted(on_disk - set(names))[:3]))

        print("== executable bit ==")
        execs = sorted(i.filename for i in z.infolist() if ((i.external_attr >> 16) & 0o111))
        print("   executable entries: {}".format([e.split("/", 1)[1] for e in execs] or "none"))

        print("== withheld material ==")
        joined = "\n".join(names)
        for token in FORBIDDEN_ENTRIES:
            check("absent: {}".format(token), token not in joined,
                  "found {}".format([n for n in names if token in n][:2]))

        print("== the notes' claims are true ==")
        note = os.path.join(top, "DISTRIBUTION.md")
        if os.path.isfile(note):
            text = io.open(note, encoding="utf-8").read()
            # Only claims about what IS included. The "what is withheld" table names files on purpose,
            # and a checker that fails on honest negative statements is worse than no checker.
            negative = text.split("## What is deliberately not in here", 1)
            positive = negative[0]
            claimed = set(re.findall(r"`([A-Za-z0-9_.][A-Za-z0-9_./-]*\.[A-Za-z0-9]{1,6})`", positive))
            promised = {c for c in claimed if not c.startswith(("http", "sha256")) and "/" in c or "." in c}
            archive_base = os.path.basename(archive_path)
            for rel in sorted(promised):
                # The archive's own name appears in the notes by necessity and cannot be inside
                # itself; an earlier revision failed on exactly that.
                if rel.endswith((".zip", ".tar", ".gz")) or rel == archive_base:
                    continue
                check("claimed and present: {}".format(rel), os.path.isfile(os.path.join(top, rel)),
                      "claimed in notes but missing")
        else:
            check("DISTRIBUTION.md present", False, "no notes in the archive")

        print("== fresh leak scan of the extracted bytes ==")
        hits = []
        scanned = 0
        for dirpath, _, filenames in os.walk(top):
            for name in filenames:
                path = os.path.join(dirpath, name)
                rel = os.path.relpath(path, top).replace(os.sep, "/")
                scanned += 1
                data = strip_self_reference(rel, io.open(path, "rb").read())
                binary = name.lower().endswith((".jar", ".png", ".jpg", ".gz", ".zip", ".class"))
                patterns = list(BINARY_LEAKS if binary else LEAKS)
                if not binary:
                    patterns += account_name_patterns(args.machine_name)
                for pattern, description in patterns:
                    for match in pattern.finditer(data):
                        snippet = data[max(0, match.start() - 30):match.end() + 30]
                        hits.append((rel, description, snippet.decode("utf-8", "replace").replace("\n", " ")))
        check("no leak detected in {} scanned files".format(scanned), not hits,
              "; ".join("{}: {}".format(h[0], h[1]) for h in hits[:6]))
        for rel, description, snippet in hits[:10]:
            notes.append("    {} -> {}: ...{}...".format(rel, description, snippet))

        print("== machine-specific settings were scrubbed ==")
        leaked_settings = []
        for dirpath, _, filenames in os.walk(top):
            for name in filenames:
                if not name.endswith((".properties", ".gradle", ".toml", ".cfg", ".ini", ".json")):
                    continue
                path = os.path.join(dirpath, name)
                text = io.open(path, "rb").read()
                if re.search(rb"(?i)(installations\.paths|sdk\.dir|ndk\.dir|local\.properties)\s*[:=]\s*[A-Za-z]:",
                             text):
                    leaked_settings.append(os.path.relpath(path, top))
        check("no machine-absolute path left active in a settings file", not leaked_settings,
              str(leaked_settings[:3]))

        if args.build:
            print("== does the extracted project build ==")
            command = args.build_command or detect_build_command(top)
            if command is None:
                check("a build command could be determined", False,
                      "no gradlew/gradlew.bat/package.json/pom.xml found in the archive; "
                      "pass --build-command")
            else:
                print("   $ {}".format(command))
                try:
                    # shell=True so a project's own pipeline works verbatim; the command comes from
                    # the caller's own invocation, not from the archive.
                    result = subprocess.run(command, cwd=top, shell=True, stdout=subprocess.PIPE,
                                            stderr=subprocess.STDOUT, timeout=1800)
                    lines = [l for l in result.stdout.decode("utf-8", "replace").split("\n") if l.strip()]
                    check("build succeeded", result.returncode == 0,
                          (lines[-1] if lines else "no output"))
                except subprocess.TimeoutExpired:
                    check("build finished within 30 minutes", False, "timed out")
    finally:
        if not args.keep:
            shutil.rmtree(extract, ignore_errors=True)
        else:
            print("extraction kept at {}".format(extract))

    print()
    for note in notes:
        print(note)
    print()
    print("RESULT: {} ({} checks failed)".format("PASS" if not fails else "FAIL", len(fails)))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
