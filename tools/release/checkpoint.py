"""Local-only snapshots and explicit, gated milestone publication (Python 3.10+)."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tempfile

EXPECTED_REMOTE = "https://github.com/Gardhames/made-in-abyss.git"
VERSION_RE = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z.-]+))?")


def git(root, *args, env=None, data=None):
    result = subprocess.run(["git", *args], cwd=root, env=env, input=data,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError("git " + args[0] + " failed: " + result.stderr.decode("utf-8", "replace").strip())
    return result.stdout


def stamp():
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")


def english(text):
    if re.search(r"[\u0400-\u04ff\u0600-\u06ff\u3040-\u30ff\u3400-\u9fff]", text):
        raise ValueError("Public summaries and checkpoint descriptions must be in English")
    if re.search(r"\b[A-Za-z]:[/\\]|/(?:Users|home)/[^/\s]+", text):
        raise ValueError("Personal absolute paths are not allowed in public text")


def parse_version(value):
    match = VERSION_RE.fullmatch(value)
    if not match:
        raise ValueError("Version must be a SemVer number, optionally with a prerelease suffix")
    pre = match[4].split(".") if match[4] else None
    if pre and any(not x or (x.isdigit() and len(x) > 1 and x.startswith("0")) for x in pre):
        raise ValueError("Invalid prerelease identifiers")
    return tuple(map(int, match.group(1, 2, 3))), pre


def newer(new, old):
    a, ap = parse_version(new)
    b, bp = parse_version(old)
    if a != b:
        return a > b
    if ap is None or bp is None:
        return ap is None and bp is not None
    for x, y in zip(ap, bp):
        if x == y:
            continue
        if x.isdigit() and y.isdigit():
            return int(x) > int(y)
        if x.isdigit() != y.isdigit():
            return not x.isdigit()
        return x > y
    return len(ap) > len(bp)


def work_tree(root):
    # An alternate index captures untracked public files without disturbing staged work.
    with tempfile.TemporaryDirectory(prefix="mia-index-") as directory:
        env = os.environ.copy()
        env["GIT_INDEX_FILE"] = str(Path(directory) / "index")
        git(root, "read-tree", "HEAD", env=env)
        git(root, "add", "--all", env=env)
        return git(root, "write-tree", env=env).decode().strip()


def snapshot(root, message):
    english(message)
    tree = work_tree(root)
    head = git(root, "rev-parse", "HEAD").decode().strip()
    commit = git(root, "commit-tree", tree, "-p", head, data=(message + "\n").encode()).decode().strip()
    ref = "refs/mia-snapshots/" + stamp()
    git(root, "update-ref", ref, commit)
    print("Local snapshot " + ref + " " + commit[:12] + "; main and the normal index are unchanged")


def prepare(root, version, summary_file):
    parse_version(version)
    summary = Path(summary_file).read_text(encoding="utf-8-sig").strip()
    english(summary)
    if not summary or not re.search(r"^### (Added|Changed|Fixed|Removed|Security|Deprecated)$", summary, re.M):
        raise ValueError("Summary needs English Keep a Changelog sections")
    old = (root / "VERSION").read_text(encoding="utf-8").strip()
    if not newer(version, old):
        raise ValueError("The milestone version must be newer than VERSION")
    changelog = (root / "CHANGELOG.md").read_text(encoding="utf-8")
    heading = "## [" + version + "]"
    if heading in changelog:
        raise ValueError("Changelog already contains this version")
    entry = heading + " - " + datetime.now(timezone.utc).date().isoformat() + "\n\n" + summary + "\n\n"
    previous = re.search(r"^## \[(?!Unreleased\])", changelog, re.M)
    at = previous.start() if previous else len(changelog)
    updated = changelog[:at].rstrip() + "\n\n" + entry + changelog[at:]
    (root / "VERSION").write_text(version + "\n", encoding="utf-8")
    (root / "CHANGELOG.md").write_text(updated.rstrip() + "\n", encoding="utf-8")
    print("Prepared v" + version + "; no commit, tag, or network operation")


def tree_files(root, tree):
    result = []
    for item in git(root, "ls-tree", "-r", "-z", tree).split(b"\0"):
        if not item:
            continue
        info, name = item.split(b"\t", 1)
        mode, kind, oid = info.decode().split()
        path = name.decode("utf-8")
        parts = PurePosixPath(path).parts
        if kind != "blob" or mode == "120000" or ".." in parts or PurePosixPath(path).is_absolute():
            raise ValueError("Unsupported publication entry: " + path)
        result.append((path, oid))
    return result


def export_and_audit(root, tree, destination):
    asset_file = root / ".github/publish-assets.json"
    assets = json.loads(asset_file.read_text(encoding="utf-8")) if asset_file.exists() else {}
    forbidden_roots = {".git", ".agents", ".claude", ".codex", ".agentbridge", ".gradle", ".local",
                       "dist", "logs", "run", "saves", "worlds", "evidence"}
    forbidden_names = {"AGENTS.md", "CLAUDE.md", ".mcp.json", "local.properties", "start-bridge.cmd"}
    total = 0
    files = tree_files(root, tree)
    for name, oid in files:
        parts = PurePosixPath(name).parts
        if (any(part in forbidden_roots or part in forbidden_names for part in parts) or
                "node_modules" in parts or "__pycache__" in parts or
                any(p == ".env" or p.startswith(".env.") for p in parts) or
                name.startswith(("docs/evidence/", "docs/codex/", "tools/bridge/"))):
            raise ValueError("Private/runtime file in publication: " + name)
        data = git(root, "cat-file", "blob", oid)
        if len(data) > 50 * 1024 * 1024:
            raise ValueError("File exceeds 50 MiB: " + name)
        try:
            text = data.decode("utf-8-sig")
            if "\0" in text:
                raise UnicodeDecodeError("utf-8", data, 0, 1, "binary")
        except UnicodeDecodeError:
            approved = assets.get(name, {})
            if approved.get("sha256") != hashlib.sha256(data).hexdigest():
                raise ValueError("Unapproved or changed binary asset: " + name)
        else:
            english(text)
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        total += len(data)
    return len(files), total


def run_logged(command, cwd, log, env=None):
    with log.open("wb") as stream:
        result = subprocess.run(command, cwd=cwd, env=env, stdout=stream, stderr=subprocess.STDOUT)
    if result.returncode:
        raise RuntimeError("check failed (exit " + str(result.returncode) + "); local log: " + str(log))


def publication_summary(root, version):
    text = (root / "CHANGELOG.md").read_text(encoding="utf-8")
    match = re.search(r"^## \[" + re.escape(version) + r"\][^\n]*\n(.*?)(?=^## \[|\Z)", text, re.M | re.S)
    if not match:
        raise ValueError("Prepared version has no changelog entry")
    summary = match[1].strip()
    english(summary)
    return summary


def publish(root, args):
    english(args.accepted_checkpoint)
    if not args.accepted_checkpoint.strip():
        raise ValueError("An accepted major checkpoint is required")
    if git(root, "branch", "--show-current").decode().strip() != "main":
        raise ValueError("Milestone publication requires main")
    staged = subprocess.run(["git", "diff", "--cached", "--quiet"], cwd=root)
    if staged.returncode:
        raise ValueError("The normal index must be clean before milestone publication")
    remote = git(root, "remote", "get-url", "origin").decode().strip()
    if remote.rstrip("/").removesuffix(".git") != args.expected_remote.rstrip("/").removesuffix(".git"):
        raise ValueError("Origin differs from the expected publication repository")
    remote_main = git(root, "ls-remote", "origin", "refs/heads/main").decode().split()
    if not remote_main:
        raise ValueError("Initial push requires the separate user review; this tool publishes later milestones only")
    if git(root, "rev-parse", "HEAD").decode().strip() != remote_main[0]:
        raise ValueError("Local main differs from remote main or contains unpublished commits; inspect it before publication")
    version = (root / "VERSION").read_text(encoding="utf-8").strip()
    parse_version(version)
    tag = "v" + version
    if git(root, "tag", "--list", tag).strip():
        raise ValueError("This milestone tag already exists")
    summary = publication_summary(root, version)
    commands = json.loads(Path(args.checks_file).read_text(encoding="utf-8-sig")).get("commands")
    if not isinstance(commands, list) or not commands:
        raise ValueError("A nonempty validation command list is required")
    logs = root / ".local/publish" / stamp()
    logs.mkdir(parents=True)
    tree = work_tree(root)
    for index, command in enumerate(commands, 1):
        if not isinstance(command, list) or not command or any(not isinstance(p, str) for p in command):
            raise ValueError("Checks must be nonempty argument arrays, never shell strings")
        command = [str(root / ("gradlew.bat" if os.name == "nt" else "gradlew")) if p == "{gradlew}" else p for p in command]
        print("Running validation check " + str(index), flush=True)
        run_logged(command, root, logs / ("check-" + str(index) + ".log"))
    if work_tree(root) != tree:
        raise ValueError("Public source changed during validation; rerun before publishing")
    leaks = args.gitleaks or shutil.which("gitleaks")
    if not leaks:
        raise ValueError("Gitleaks must be installed; publication is refused without a secret scan")
    with tempfile.TemporaryDirectory(prefix="mia-publication-") as directory:
        exported = Path(directory)
        count, size = export_and_audit(root, tree, exported)
        scan_env = os.environ.copy()
        scan_env.pop("GITLEAKS_CONFIG", None)
        scan_env.pop("GITLEAKS_CONFIG_TOML", None)
        scan_command = [leaks, "dir", str(exported), "--redact=100", "--no-banner", "--no-color",
                        "--ignore-gitleaks-allow", "--timeout", "120", "--report-format", "json",
                        "--report-path", str(logs / "gitleaks.json")]
        if (exported / ".gitleaks.toml").is_file():
            scan_command += ["--config", str(exported / ".gitleaks.toml")]
        run_logged(scan_command, exported, logs / "gitleaks.log", env=scan_env)
    if work_tree(root) != tree:
        raise ValueError("Public source changed during the secret scan; rerun before publishing")
    git(root, "add", "--all")
    if git(root, "write-tree").decode().strip() != tree:
        raise ValueError("Staged tree differs from the validated tree")
    message = "chore(release): publish " + tag + "\n\n" + summary + "\n\nAccepted checkpoint: " + args.accepted_checkpoint + "\n"
    git(root, "commit", "-F", "-", data=message.encode())
    git(root, "tag", "-a", tag, "-F", "-", data=(tag + "\n\n" + summary + "\n").encode())
    # Push only the accepted main tip and its one version tag, atomically. Never local snapshot refs.
    git(root, "push", "--atomic", "origin", "main:main", "refs/tags/" + tag + ":refs/tags/" + tag)
    head = git(root, "rev-parse", "HEAD").decode().strip()
    remote_head = git(root, "ls-remote", "origin", "refs/heads/main").decode().split()[0]
    if head != remote_head:
        raise RuntimeError("Remote verification did not match the published commit")
    print("Published " + tag + ": " + str(count) + " files, " + str(size) + " bytes; local snapshot refs stayed local")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="action")
    snap = commands.add_parser("snapshot", help="Capture a local-only ref without changing main or the normal index")
    snap.add_argument("--message", default="Local development snapshot")
    prep = commands.add_parser("prepare", help="Prepare VERSION and an English changelog entry locally")
    prep.add_argument("--version", required=True)
    prep.add_argument("--summary-file", required=True)
    pub = commands.add_parser("publish", help="Validate and publish a completed major checkpoint")
    pub.add_argument("--accepted-checkpoint", required=True)
    pub.add_argument("--expected-remote", default=EXPECTED_REMOTE)
    pub.add_argument("--checks-file", default="tools/release/checks.json")
    pub.add_argument("--gitleaks", default=os.environ.get("GITLEAKS_BIN"))
    args = parser.parse_args(argv)
    try:
        root = Path(git(Path.cwd(), "rev-parse", "--show-toplevel").decode().strip())
        if args.action in (None, "snapshot"):
            snapshot(root, getattr(args, "message", "Local development snapshot"))
        elif args.action == "prepare":
            prepare(root, args.version, args.summary_file)
        else:
            publish(root, args)
        return 0
    except (OSError, ValueError, RuntimeError, KeyError, IndexError) as error:
        print("ERROR: " + str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
