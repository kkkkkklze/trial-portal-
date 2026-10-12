"""Builds a clean, distributable archive of a project, for handing to another developer.

This is an ENGINE. Copy it into the target project's ``tools/`` directory and fill in the CONFIG
block below; do not edit the skill's own copy, or every project inherits your last project's
allowlist. Keeping the copy inside the project is the point: the project then ships the packager that
made its own archive, so a recipient can reproduce and audit it.

Usage::

    python tools/make-dist.py                  # writes <root>/dist/<project>-<version>.zip
    python tools/make-dist.py --root ..        # target a different project root
    python tools/make-dist.py --out /tmp/out

Then run the verifier, which is deliberately a separate program::

    python tools/verify-dist.py

## Why this file is shaped the way it is

* **Allowlist, not denylist.** Every included path is named explicitly. A denylist fails open — the
  day someone adds a directory full of secrets, it ships silently. An allowlist fails closed against
  unknown files. Its one weakness is that it also fails *silently* against a forgotten entry, which is
  why the REQUIRED gate exists: see the note there.
* **Scrub in the archive, never in the working tree.** Machine-specific settings (a JDK path, a local
  SDK path) are needed by the author's working copy. Transform them on the way into the archive only.
* **Scan as a hard gate.** The staged bytes are scanned and the script refuses to write an archive if
  it finds anything. Distribution should not proceed with a warning.
* **Deterministic output.** Fixed timestamps and sorted entries, so identical inputs give identical
  bytes and a recipient can verify what they received.
* **Nothing executable by surprise.** Only files you explicitly mark keep the executable bit.
"""

import argparse
import hashlib
import io
import os
import re
import sys
import zipfile

# ==================================================================================================
# CONFIG - fill this in for the project. Everything above is engine.
# ==================================================================================================

CONFIG = {
    # ---- identity -------------------------------------------------------------------------------
    "project": "trial-portal",
    "version": "1.0.0",
    "title": "Trial Portal（传送门试炼，Forge 1.20.1）— 交接包",

    # ---- prebuilt artifact, if the project produces one. None to ship source only. --------------
    "artifact": "build/libs/trial_portal-1.0.0.jar",
    # Where the artifact lands inside the archive. None means the project root.
    "artifact_dest": "dist-jar",

    # ---- exactly what ships. Directories are walked recursively, sorted. ------------------------
    # Where the packager and verifier live in this project. Used to expand {tools_dir} below and to
    # build the command paths printed in the notes, so the notes cannot promise a file that is not in
    # the box. An earlier revision hardcoded the paths in the notes text and shipped notes telling the
    # recipient to run a script that was not included.
    "tools_dir": "tools",

    "include_files": [
        "README.md",
        ".gitignore",
        "TEMPLATE_LICENSE.txt",
        "LICENSE",
        "build.gradle",
        "settings.gradle",
        "gradle.properties",
        "gradlew",
        "gradlew.bat",
        "gradle/wrapper/gradle-wrapper.jar",
        "gradle/wrapper/gradle-wrapper.properties",
        "tools/census_to_pack.py",      # 血量普查 -> 递进难度数据包的流水线（可复跑，不是一次性脚本）
        "{tools_dir}/make-dist.py",     # the packager, so recipients can reproduce and audit
        "{tools_dir}/verify-dist.py",   # the verifier, so they can check what they received
    ],
    "include_dirs": [
        "src/main/java",
        "src/main/resources",
        "src/main/templates",
        "src/tools/java",
        "docs",
    ],

    # ---- files without which the archive is not a working project -------------------------------
    # Required because an allowlist fails silently on omissions: the first version of this engine
    # produced an archive that looked fine and could not build at all, having simply left
    # gradle.properties out of the list. Nothing noticed until an independent verifier tried it.
    "required": [
        "gradle.properties",
        "build.gradle",
        "settings.gradle",
        "gradlew",
        "gradlew.bat",
        "gradle/wrapper/gradle-wrapper.jar",
        "gradle/wrapper/gradle-wrapper.properties",
        "README.md",
        "LICENSE",
        "src/main/java/com/trialportal/TrialPortal.java",
        "src/main/templates/META-INF/mods.toml",
        "src/main/resources/data/trial_portal/recipes/arena_exit.json",
        "src/main/resources/data/trial_portal/loot_tables/blocks/arena_exit.json",
        "src/tools/java/com/trialportal/tools/TrialDataCheck.java",
        "{tools_dir}/make-dist.py",
        "{tools_dir}/verify-dist.py",
    ],

    # ---- what is withheld, and why. Printed into the archive's own notes. -----------------------
    # Recording the reason matters: a recipient who cannot tell an oversight from a decision will
    # assume the worst, and the next person to touch this file will not dare change it.
    "excluded": [
        ("build/", "Compiled output and downloaded dependencies. Machine state, not source."),
        (".gradle/", "Local build cache, including absolute paths to the author's machine."),
        (".git/", "The author's private history; the archive carries the tree, not the lab notebook."),
        ("run/", "Runtime scratch directory: world saves, logs with player names and UUIDs, forced-chunk "
                 "records, crash reports. None of it belongs in a handoff."),
        ("run/eula.txt", "EULA acceptance has to come from whoever runs the game or server, not from the "
                         "packager - so the archive ships no run/ at all."),
        ("verify-logs/", "23 MB of local gate output (gradle logs, screenshots, one-off driver scripts). "
                         "docs/开发文档.md cites filenames from here; those are pointers to runs on the "
                         "author's machine. Re-run the two gates and you get your own copies."),
        ("gradle-local.properties", "Untracked, author-only toolchain path (jdk17.path). The build falls "
                                    "back to toolchain auto-detection without it; see gradle.properties."),
        ("tools/__pycache__/", "Python bytecode of the local scripts."),
        ("dist/", "Output of this script."),
    ],

    # ---- machine-specific settings to transform on the way in -----------------------------------
    # Keys are archive-relative paths that exist in the working tree; values are functions applied to
    # the file's text. Only these files are touched, and only inside the archive.
    "scrub": {
        "docs/开发文档.md": "personal_local_refs",
        "README.md": "personal_local_refs",
        "tools/census_to_pack.py": "personal_local_refs",
    },

    # ---- files that must contain the leak shapes in order to define and explain them ------------
    # The packager and the verifier are added automatically from tools_dir, so this list only needs
    # entries for any *additional* file in your project that legitimately defines such patterns (a
    # security scanner, a log redactor, a test fixture). The derived entries cannot drift from the
    # included paths; anything listed here is on you.
    "self_referential": (),

    # ---- domains and literals that are legitimate and must not trip the scan --------------------
    # EVERY project needs its own entries here. Add the registries, documentation sites and version
    # strings your project legitimately mentions; without that, the scan produces false alarms, and a
    # scanner people learn to ignore is worse than no scanner.
    "allowed": [
        r"services\.gradle\.org", r"repo1?\.maven\.apache\.org", r"maven\.aliyun\.com",
        r"mirrors\.cloud\.tencent\.com", r"bmclapi2\.bangbang93\.com", r"maven\.minecraftforge\.net",
        r"neoforged\.forgecdn\.net", r"maven\.parchmentmc\.org", r"github\.com", r"gradle\.org",
        r"docs\.minecraftforge\.net", r"docs\.neoforged\.net", r"choosealicense\.com",
        r"example\.invalid", r"storage\.googleapis\.com", r"stackoverflow\.com", r"semver\.org",
        r"toml-lang\.toml", r"1\.20\.1", r"20230612\.114412",
    ],

    # ---- fixed timestamp for reproducible archives ----------------------------------------------
    "fixed_time": (2026, 1, 1, 0, 0, 0),
}

# ==================================================================================================
# engine
# ==================================================================================================

# Leak SHAPES, not leak words. An earlier revision also matched the game's name and the Chinese word
# for "development"; both appear in ordinary prose and produced false alarms until nobody would have
# read the output. Match a drive path, a home path, an account name, a token format.
SCAN_PATTERNS = [
    (re.compile(rb"C:\\Users", re.I), "absolute Windows user path"),
    (re.compile(rb"C:/Users", re.I), "absolute Windows user path"),
    # Narrowed in this project's copy: the engine's rule was a case-insensitive "D:\", which fires on
    # ordinary NBT JSON like {"nbt": "{id:\"minecraft:iron_helmet\""} (that is the sequence d : \ ").
    # A false alarm people learn to ignore is worse than no scan, so this now requires a drive letter,
    # a backslash and an actual path component - the same shape verify-dist.py checks independently.
    (re.compile(("[A-Za-z]:" + chr(92) * 2 + "[A-Za-z]").encode("ascii")), "absolute Windows drive path"),
    (re.compile(rb"/Users/[A-Za-z]"), "absolute POSIX home path"),
    (re.compile(rb"/home/[A-Za-z]"), "absolute POSIX home path"),
    (re.compile(rb"BEGIN [A-Z ]*PRIVATE KEY"), "private key material"),
    (re.compile(rb"AKIA[0-9A-Z]{16}"), "AWS access key id"),
    (re.compile(rb"ghp_[A-Za-z0-9]{20,}"), "GitHub personal access token"),
    (re.compile(rb"xox[baprs]-[A-Za-z0-9-]{10,}"), "Slack token"),
    (re.compile(rb"(?i)\b(password|passwd|secret|api[_-]?key|auth[_-]?token)\s*[:=]\s*['\"]?[A-Za-z0-9/+_-]{8,}"),
     "credential assignment"),
    (re.compile(rb"\b(?:\d{1,3}\.){3}\d{1,3}\b"), "IP address"),
    (re.compile(rb"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"), "email address"),
    (re.compile(rb"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b"),
     "UUID"),
]

# Compressed bytecode throws random 32-hex strings and dotted quads by chance, so the broad checks
# are noise there. Only the path-shaped ones are meaningful in a binary.
BINARY_SCAN_PATTERNS = [p for p in SCAN_PATTERNS if "path" in p[1] or "account" in p[1]]

# Project-specific additions: the author's own modpack / launcher folder names. They are words rather
# than shapes, which is exactly why they need to be named here - the generic shape scan cannot see
# them, and once they are decided local references, shipping one is a leak. Text files only.
SCAN_PATTERNS += [
    (re.compile("\u661f\u7f57\u68cb\u5e03".encode("utf-8")), "author's local modpack name"),
    (re.compile("\u7ec8\u7109\u51b3\u6597".encode("utf-8")), "author's local modpack name"),
    (re.compile(rb"PCL"), "author's launcher folder name"),
    # 注意：bytes 正则里反斜杠后面跟非 ASCII 会被解释器吃掉，上面这两条原本是哑规则（永远不命中）。
    # 现在一律按字面量 re.escape 匹配，并由下面的自检确认真的打得中。
    (re.compile(re.escape(("Documents" + chr(92) + "开发").encode("utf-8"))), "author's local project directory"),
    (re.compile(re.escape("Documents/\u5f00\u53d1".encode("utf-8"))), "author\'s local project directory"),
]

def local_account_names():
    """The account names of the machine running this script.

    Scanning for these is precise and needs no project-specific configuration, unlike a word list:
    an earlier revision listed "root", which then failed the legitimate comment "in the root of the
    mod JAR". A word that resembles a leak is not a leak - and a scanner that cries wolf is a scanner
    people stop reading.
    """
    names = set()
    for variable in ("USERNAME", "USER", "USERDOMAIN", "LOGNAME"):
        value = os.environ.get(variable, "").strip()
        if value and len(value) > 2:
            names.add(value)
    return sorted(names)


def account_name_patterns():
    """Matches an account name only where it looks like a path component.

    Requiring a preceding separator is what keeps this from firing on prose. The generic path patterns
    in SCAN_PATTERNS catch the common cases regardless of the name.
    """
    patterns = []
    for name in local_account_names():
        patterns.append((re.compile(re.escape(name).encode("utf-8") + rb"[/\\]"),
                         "packager's account name as a path component"))
        patterns.append((re.compile(rb"[/\\]" + re.escape(name).encode("utf-8") + rb"\b"),
                         "packager's account name as a path component"))
    return patterns


def gradle_jdk_path(text):
    """Replaces a machine-specific JDK path with portable instructions.

    The whole comment block is replaced, not just the setting line. The original comment explains why
    the path had to be spelled out on the author's machine and usually ends with "change or remove
    this line" — which, with the line gone, dangles as a comment about nothing. A contradiction in the
    first config file a recipient opens is worse than no comment at all.
    """
    needle = "org.gradle.java.installations.paths="
    lines = text.split("\n")
    replacement = [
        "# JDK 17 for the Java toolchain.",
        "#",
        "# The absolute path is not shipped, because it names the machine this was developed on.",
        "# Pick whichever applies to you:",
        "#",
        "#   1. Install a JDK 17 normally and let Gradle auto-detect it. Do nothing.",
        "#   2. Uncomment the line below and point it at your own JDK 17.",
        "#   3. Or set it once for every project, in ~/.gradle/gradle.properties:",
        "#        " + needle + "/absolute/path/to/jdk-17",
        "#",
        "# With none of these the build fails while resolving the toolchain. Offline provisioning",
        "# cannot help, because toolchain auto-provisioning downloads from GitHub.",
        "# " + needle + "/absolute/path/to/jdk-17",
    ]
    out = []
    for line in lines:
        if line.startswith(needle):
            start = len(out)
            while start > 0 and out[start - 1].startswith("#"):
                start -= 1
            del out[start:]
            out.extend(replacement)
        else:
            out.append(line)
    return "\n".join(out)


# Project-specific privacy replacements. These are *decided* local references, not leak shapes:
# the author's own modpack folder names and the directory layout of the machine this was developed
# on. They are useful in the author's working copy (they say which instance a measurement came from),
# and they have no business in a package handed to someone else. So they are rewritten on the way
# into the archive only, and the scan below refuses to ship if any of them survives.
LOCAL_REFS = [
    # 通用形状（README 与开发文档里"项目路径含中文"那条注意事项用的就是这种写法）
    ("Documents/开发/...", "<项目根目录>"),
    ("Documents" + chr(92) + "开发" + chr(92) + "...", "<项目根目录>"),
    ("Documents/开发", "<项目根目录>"),
    ("Documents" + chr(92) + "开发", "<项目根目录>"),
    ("Documents/开发/mod/trial-portal-1.20.1", "<工程根>"),
    ("Documents" + chr(92) + "开发" + chr(92) + "mod" + chr(92) + "trial-portal-1.20.1", "<工程根>"),
    ("Documents/开发/mod", "<工程所在目录>"),
    ("Documents" + chr(92) + "开发" + chr(92) + "mod", "<工程所在目录>"),
    (".minecraft/versions", "<实例目录>/versions"),
    (chr(92) + ".minecraft" + chr(92) + "versions", "<实例目录>" + chr(92) + "versions"),
    ("\u7ec8\u7109\u51b3\u6597NW\u516c\u6d4b\u7248", "<某整合包 B>"),
    ("\u7ec8\u7109\u51b3\u6597NW", "<某整合包 B>"),
    ("\u7ec8\u7109\u51b3\u6597", "<某整合包 B>"),
    ("\u661f\u7f57\u68cb\u5e03", "<某整合包 A>"),
    ("PCL2", "<启动器>"),
    ("PCL", "<启动器>"),
]


def personal_local_refs(text):
    for needle, replacement in LOCAL_REFS:
        text = text.replace(needle, replacement)
    return text


SCRUBBERS = {"gradle_jdk_path": gradle_jdk_path, "personal_local_refs": personal_local_refs}


def is_binary(name):
    return name.lower().endswith((".jar", ".png", ".jpg", ".gz", ".zip", ".class", ".so", ".dll"))


def strip_self_reference(name, data):
    """For the scanners themselves, removes comments and string literals before scanning.

    A scanner has to contain the literal shapes it searches for in order to define and explain them.
    Removing comments and quoted strings exempts exactly that, while the code, the conditionals and
    any path outside a literal are still scanned. The residual hole is disclosed rather than hidden: a
    leak inside a string literal in one of these files would not be caught, which is why the exemption
    list is an explicit tuple and the files are kept short enough to read.
    """
    if name not in CONFIG["self_referential"]:
        return data
    text = data.decode("utf-8", "replace")
    text = re.sub(r"(?m)^\s*#.*$", "", text)
    text = re.sub(r'"[^"\n]*"', "STR", text)
    return text.encode("utf-8", "replace")


def scan(name, data, allowed_patterns):
    patterns = BINARY_SCAN_PATTERNS if is_binary(name) else SCAN_PATTERNS
    haystack = strip_self_reference(name, data)
    if not is_binary(name):
        for pattern in allowed_patterns:
            haystack = pattern.sub(b"", haystack)
    if not is_binary(name):
        patterns = list(patterns) + account_name_patterns()
    problems = []
    for pattern, description in patterns:
        for match in pattern.finditer(haystack):
            start = max(0, match.start() - 40)
            end = min(len(haystack), match.end() + 40)
            snippet = haystack[start:end].decode("utf-8", "replace").replace("\n", " ")
            problems.append((description, snippet))
    return problems


def expand(entries, tools_dir):
    """Substitutes {tools_dir} in path lists, so relocating the tools is a one-line change."""
    return [e.replace("{tools_dir}", tools_dir) for e in entries]


def collect(root, include_files, include_dirs):
    entries = []
    for rel in include_files:
        abs_path = os.path.join(root, rel.replace("/", os.sep))
        if not os.path.isfile(abs_path):
            print("  ! listed but missing, skipped: {}".format(rel))
            continue
        entries.append((rel, abs_path))
    for rel_dir in include_dirs:
        abs_dir = os.path.join(root, rel_dir.replace("/", os.sep))
        if not os.path.isdir(abs_dir):
            print("  ! listed but missing, skipped: {} (not a directory)".format(rel_dir))
            continue
        for dirpath, dirnames, filenames in os.walk(abs_dir):
            dirnames.sort()
            for name in sorted(filenames):
                abs_path = os.path.join(dirpath, name)
                entries.append((os.path.relpath(abs_path, root).replace(os.sep, "/"), abs_path))
    entries.sort()
    return entries


def build_notes(project, version, title, archive_name, artifact_name, wrapper_hash,
                artifact_hash, excluded, tools_dir):
    table = "\n".join("| `{}` | {} |".format(path, reason) for path, reason in excluded)
    binaries = ""
    if artifact_name:
        binaries = """
## Binaries

Both are built from the sources here. Verify them rather than trusting this file:

```
sha256  {artifact_hash}
        {artifact_name}
```

Check with `sha256sum -c SHA256SUMS.txt` from inside this directory.
""".format(artifact_hash=artifact_hash or "(none shipped)", artifact_name=artifact_name)
    else:
        binaries = "\n## Binaries\n\nOnly the Gradle wrapper is shipped as a binary.\n"
    return """# {title}

`{archive}` — {project} {version}.

Produced by `{tools_dir}/make-dist.py`, which you can read and re-run. It works from an explicit
allowlist of files, and it refuses to write an archive at all if its scan finds a personal identifier,
a machine path, a credential or an IP address in anything it is about to include.
`{tools_dir}/verify-dist.py` checks the result independently, because a packager grading its own work
proves nothing.

## What is in here

| | |
|---|---|
| `README.md` | What the project is and how to work on it. |
| source tree | All sources and resources. |
| `SHA256SUMS.txt` | SHA-256 of every file here. |

## What is deliberately not in here

| Withheld | Why |
|---|---|
{table}
{binaries}
## Reproducing and checking this archive

```
python {tools_dir}/make-dist.py
python {tools_dir}/verify-dist.py
```

Timestamps are fixed and entries sorted, so identical inputs give identical bytes and an identical
hash. That hash cannot live inside the archive it describes, so it is published beside it.
""".format(title=title, archive=archive_name, project=project, version=version, table=table,
           binaries=binaries, tools_dir=tools_dir)


def assert_patterns_have_teeth():
    """Each project rule must hit at least one token the scrubber is supposed to remove.

    A silent rule is worse than no rule: the scanner reports "clean" forever while shipping the very
    thing it was extended to catch. That is exactly what happened here - the two "Documents" patterns
    were fed a raw literal into re.compile (backslash + non-ASCII in a bytes pattern), never matched,
    and could not be caught by the scan itself because the packager is exempt from scanning.
    The sample source is LOCAL_REFS, so the check cannot drift away from the scrub list.
    """
    needles = set()
    for needle, replacement in LOCAL_REFS:
        if needle != replacement:
            needles.add(needle.encode("utf-8"))
    silent = []
    for pattern, description in SCAN_PATTERNS:
        if not description.startswith("author's"):
            continue
        if not any(pattern.search(sample) for sample in needles):
            silent.append(description)
    if silent:
        for description in silent:
            print("!! toothless rule: %s matches none of the %d scrubbed tokens" % (description, len(needles)))
        sys.exit("refusing to write an archive: a scan rule can never fire")
    print("   teeth check: %d project rules each hit the scrub list"
          % len([d for _, d in SCAN_PATTERNS if d.startswith("author's")]))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                        help="project root, defaults to the parent of this script's directory")
    parser.add_argument("--out", default=None, help="output directory, defaults to <root>/dist")
    args = parser.parse_args()
    root = os.path.abspath(args.root)
    out_dir = os.path.abspath(args.out or os.path.join(root, "dist"))

    project = CONFIG["project"]
    version = CONFIG["version"]
    if "REPLACE_ME" in project or "REPLACE_ME" in str(CONFIG.get("artifact")):
        print("!! CONFIG still contains REPLACE_ME. Fill it in before running.")
        return 1

    archive_name = "{}-{}.zip".format(project, version)
    stage_name = "{}-{}".format(project, version)

    tools_dir = CONFIG.get("tools_dir", "tools").strip("/")
    include_files = expand(CONFIG["include_files"], tools_dir)
    include_dirs = expand(CONFIG["include_dirs"], tools_dir)
    required = expand(CONFIG["required"], tools_dir)
    # Auto-derived, so it cannot drift from the included tool paths; plus anything extra the project
    # declares. The residual hole is disclosed in the notes: a leak inside a string literal in one of
    # these files would not be caught, which is why the scanners are kept short enough to read.
    CONFIG["self_referential"] = tuple(
        {"{}/make-dist.py".format(tools_dir), "{}/verify-dist.py".format(tools_dir)}
        | set(expand(CONFIG["self_referential"], tools_dir)))

    print("== collecting from {} ==".format(root))
    entries = collect(root, include_files, include_dirs)
    print("   {} files from the working tree".format(len(entries)))

    artifact_name = None
    if CONFIG.get("artifact"):
        artifact_path = os.path.join(root, CONFIG["artifact"].replace("/", os.sep))
        if not os.path.isfile(artifact_path):
            print("!! artifact not found: {}. Build the project first, or set \"artifact\": None."
                  .format(CONFIG["artifact"]))
            return 1
        artifact_name = os.path.basename(artifact_path)
        if CONFIG.get("artifact_dest"):
            artifact_name = CONFIG["artifact_dest"].rstrip("/") + "/" + artifact_name
        entries.append((artifact_name, artifact_path))

    assert_patterns_have_teeth()
    print("== staging and scanning ==")
    allowed_patterns = [re.compile(p.encode("utf-8")) for p in CONFIG["allowed"]]
    staged = []
    failures = []
    for rel, abs_path in entries:
        with io.open(abs_path, "rb") as handle:
            data = handle.read()
        scrubber = CONFIG.get("scrub", {}).get(rel)
        if scrubber:
            data = SCRUBBERS[scrubber](data.decode("utf-8")).encode("utf-8")
        for description, snippet in scan(rel, data, allowed_patterns):
            failures.append((rel, description, snippet))
        staged.append((rel, data))

    present = {rel for rel, _ in staged}
    missing = [rel for rel in required if rel not in present]
    if missing:
        print("!! refusing: the allowlist is incomplete, these are missing: {}".format(missing))
        return 1

    if failures:
        print("!! refused: {} file(s) contain material that must not be distributed"
              .format(len({f[0] for f in failures})))
        for rel, description, snippet in failures:
            print("   {}: {} -> ...{}...".format(rel, description, snippet))
        return 1
    print("   clean: no personal identifiers, machine paths, credentials or IP addresses found")

    wrapper = os.path.join(root, "gradle", "wrapper", "gradle-wrapper.jar")
    wrapper_hash = (hashlib.sha256(io.open(wrapper, "rb").read()).hexdigest()
                    if os.path.isfile(wrapper) else None)
    artifact_hash = None
    if artifact_name:
        artifact_hash = hashlib.sha256(
            io.open(os.path.join(root, CONFIG["artifact"].replace("/", os.sep)), "rb").read()
        ).hexdigest()

    notes = build_notes(project, version, CONFIG["title"], archive_name, artifact_name,
                        wrapper_hash, artifact_hash, CONFIG["excluded"], tools_dir).encode("utf-8")
    # Generated content is scanned like anything else, rather than trusted because we wrote it.
    for description, snippet in scan("DISTRIBUTION.md", notes, allowed_patterns):
        failures.append(("DISTRIBUTION.md (generated)", description, snippet))
    staged.append(("DISTRIBUTION.md", notes))
    if failures:
        print("!! refused: the generated notes contain material that must not be distributed")
        for rel, description, snippet in failures:
            print("   {}: {} -> ...{}...".format(rel, description, snippet))
        return 1

    print("== writing archive ==")
    os.makedirs(out_dir, exist_ok=True)
    archive_path = os.path.join(out_dir, archive_name)
    if os.path.exists(archive_path):
        os.remove(archive_path)

    def write(extra):
        written = []
        with zipfile.ZipFile(archive_path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for rel, data in list(staged) + list(extra):
                info = zipfile.ZipInfo("{}/{}".format(stage_name, rel),
                                       date_time=tuple(CONFIG["fixed_time"]))
                info.compress_type = zipfile.ZIP_DEFLATED
                # Only explicitly marked files keep the executable bit.
                mode = 0o100755 if rel in CONFIG.get("executable", ("gradlew",)) else 0o100644
                info.external_attr = mode << 16
                archive.writestr(info, data)
                written.append((rel, data, hashlib.sha256(data).hexdigest()))
        return written

    def manifest(written):
        body = "".join("{}  {}\n".format(digest, rel) for rel, _, digest in written)
        header = ("# SHA-256 manifest for {}\n"
                  "# Verify from inside the extracted directory:  sha256sum -c SHA256SUMS.txt\n"
                  "# This file is not listed below, because it cannot contain its own hash; the\n"
                  "# archive's own hash is published alongside it.\n").format(archive_name)
        return (header + body).encode("utf-8")

    written = write([])
    final = write([("SHA256SUMS.txt", manifest(written))])
    archive_hash = hashlib.sha256(io.open(archive_path, "rb").read()).hexdigest()

    # Prove stability before publishing a hash. This check is what caught an earlier revision whose
    # manifest tried to contain its own hash and therefore changed on every pass.
    stable = write([("SHA256SUMS.txt", manifest(written))])
    if hashlib.sha256(io.open(archive_path, "rb").read()).hexdigest() != archive_hash:
        print("!! archive is not reproducible across passes; refusing to publish a hash")
        return 1
    if [r for r, _, _ in stable] != [r for r, _, _ in final]:
        print("!! the entry list differs between passes; refusing to publish")
        return 1

    with io.open(os.path.join(out_dir, "SHA256SUMS.txt"), "w", encoding="utf-8",
                 newline="\n") as handle:
        handle.write(manifest(written).decode("utf-8"))
        handle.write("{}  {}\n".format(archive_hash, archive_name))

    print("== result ==")
    print("   archive : {}".format(archive_path))
    print("   files   : {}".format(len(final)))
    print("   size    : {} bytes".format(os.path.getsize(archive_path)))
    print("   sha256  : {}".format(archive_hash))
    if wrapper_hash:
        print("   wrapper : {}".format(wrapper_hash))
    if artifact_hash:
        print("   artifact: {}".format(artifact_hash))
    return 0


if __name__ == "__main__":
    sys.exit(main())
