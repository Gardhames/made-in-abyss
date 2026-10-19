"""Integration tests use temporary local repositories, never GitHub."""
from pathlib import Path
import json
import os
import shutil
import subprocess
import sys
import tempfile
import random
import string
import unittest

TOOL = Path(__file__).with_name("checkpoint.py").resolve()
LEAKS = os.environ.get("GITLEAKS_BIN") or shutil.which("gitleaks")

class CheckpointTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(dir=os.environ.get("MIA_RELEASE_TEST_ROOT"))
        self.base = Path(self.tmp.name)
        self.repo = self.base / "work"
        self.remote = self.base / "remote.git"
        self.repo.mkdir()
        self.git("init", "-b", "main")
        self.git("config", "user.name", "Fixture")
        self.git("config", "user.email", "fixture@users.noreply.github.com")
        self.write(".gitignore", ".local/\n.env\nprivate.notes\n")
        self.write("VERSION", "0.1.0-probe\n")
        self.write("CHANGELOG.md", "# Changelog\n\n## [Unreleased]\n\n## [0.1.0-probe] - 2026-10-20\n")
        self.write("LICENSE", "All rights reserved.\n")
        self.write("README.md", "# Fixture\n")
        self.git("add", "--all")
        self.git("commit", "-m", "chore: create fixture")
        self.git("init", "--bare", str(self.remote))
        self.git("remote", "add", "origin", str(self.remote))
        self.git("push", "origin", "main")
        self.remote_baseline = self.git("ls-remote", "origin")
        self.write(".local/summary.md", "### Fixed\n\n- Preserve local revisions during publication.\n")
        self.write(".local/pass.json", json.dumps({"commands": [[sys.executable, "-c", "raise SystemExit(0)"]]}))
        self.write(".local/fail.json", json.dumps({"commands": [[sys.executable, "-c", "raise SystemExit(9)"]]}))

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, name, text):
        p = self.repo / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding="utf-8")

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.repo, stderr=subprocess.STDOUT).decode().strip()

    def run_tool(self, *args):
        return subprocess.run([sys.executable, str(TOOL), *args], cwd=self.repo,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)

    def prepare(self):
        return self.run_tool("prepare", "--version", "0.1.1-probe", "--summary-file", ".local/summary.md")

    def publish(self, checks):
        return self.run_tool("publish", "--accepted-checkpoint", "Fixture accepted",
                             "--expected-remote", str(self.remote), "--checks-file", checks,
                             "--gitleaks", LEAKS or "missing-gitleaks")

    def test_default_snapshot_is_local_and_keeps_head_and_index(self):
        head = self.git("rev-parse", "HEAD")
        index = (self.repo / ".git/index").read_bytes()
        self.write("README.md", "# Changed fixture\n")
        self.write("new.txt", "Local public source\n")
        self.write(".env", "Private local settings\n")
        r = self.run_tool()
        self.assertEqual(0, r.returncode, r.stdout)
        self.assertEqual(head, self.git("rev-parse", "HEAD"))
        self.assertEqual(index, (self.repo / ".git/index").read_bytes())
        refs = self.git("for-each-ref", "--format=%(refname)", "refs/mia-snapshots/").splitlines()
        self.assertEqual(1, len(refs))
        tree = self.git("ls-tree", "-r", "--name-only", refs[0]).splitlines()
        self.assertIn("new.txt", tree)
        self.assertNotIn(".env", tree)
        self.assertEqual(self.remote_baseline, self.git("ls-remote", "origin"))

    def test_prepare_updates_version_and_summary_without_commit(self):
        head = self.git("rev-parse", "HEAD")
        r = self.prepare()
        self.assertEqual(0, r.returncode, r.stdout)
        self.assertEqual("0.1.1-probe", (self.repo / "VERSION").read_text().strip())
        self.assertIn("## [0.1.1-probe]", (self.repo / "CHANGELOG.md").read_text())
        self.assertEqual(head, self.git("rev-parse", "HEAD"))

    def test_non_english_summary_is_refused_before_writing(self):
        self.write(".local/summary.md", "\u041d\u043e\u0432\u0430\u044f \u0432\u0435\u0440\u0441\u0438\u044f")
        r = self.prepare()
        self.assertNotEqual(0, r.returncode)
        self.assertIn("English", r.stdout)
        self.assertEqual("0.1.0-probe", (self.repo / "VERSION").read_text().strip())

    def test_failed_check_prevents_commit_tag_and_push(self):
        self.assertEqual(0, self.prepare().returncode)
        head = self.git("rev-parse", "HEAD")
        r = self.publish(".local/fail.json")
        self.assertNotEqual(0, r.returncode)
        self.assertIn("check failed", r.stdout)
        self.assertEqual(head, self.git("rev-parse", "HEAD"))
        self.assertEqual("", self.git("tag", "--list"))
        self.assertEqual(self.remote_baseline, self.git("ls-remote", "origin"))

    @unittest.skipUnless(LEAKS, "Gitleaks is required for the local publication integration test")
    def test_atomic_publish_excludes_snapshot_refs(self):
        self.write("new.txt", "Checkpoint source\n")
        self.assertEqual(0, self.run_tool("snapshot").returncode)
        self.assertEqual(0, self.prepare().returncode)
        r = self.publish(".local/pass.json")
        self.assertEqual(0, r.returncode, r.stdout)
        refs = self.git("ls-remote", "origin")
        self.assertIn("refs/heads/main", refs)
        self.assertIn("refs/tags/v0.1.1-probe", refs)
        self.assertNotIn("mia-snapshots", refs)
        self.assertEqual("", self.git("status", "--porcelain"))


    def test_wrong_remote_is_refused_without_network(self):
        self.assertEqual(0, self.prepare().returncode)
        r = self.run_tool("publish", "--accepted-checkpoint", "Fixture accepted",
                          "--expected-remote", "https://example.invalid/unapproved.git",
                          "--checks-file", ".local/pass.json")
        self.assertNotEqual(0, r.returncode)
        self.assertIn("Origin differs", r.stdout)
        self.assertEqual(self.remote_baseline, self.git("ls-remote", "origin"))

    def test_unapproved_binary_prevents_commit_and_push(self):
        self.assertEqual(0, self.prepare().returncode)
        (self.repo / "unapproved.bin").write_bytes(bytes([0, 1, 2, 3]))
        head = self.git("rev-parse", "HEAD")
        r = self.publish(".local/pass.json")
        self.assertNotEqual(0, r.returncode)
        self.assertIn("Unapproved", r.stdout)
        self.assertEqual(head, self.git("rev-parse", "HEAD"))
        self.assertEqual(self.remote_baseline, self.git("ls-remote", "origin"))

    @unittest.skipUnless(LEAKS, "Gitleaks is required for the secret refusal integration test")
    def test_secret_scan_failure_prevents_commit_and_push(self):
        self.assertEqual(0, self.prepare().returncode)
        self.write("credential.txt", "token = " + "gh" + "p_" + "".join(random.Random(7).choices(string.ascii_letters + string.digits, k=36)) + "\n")
        head = self.git("rev-parse", "HEAD")
        r = self.publish(".local/pass.json")
        self.assertNotEqual(0, r.returncode)
        self.assertIn("check failed", r.stdout)
        self.assertEqual(head, self.git("rev-parse", "HEAD"))
        self.assertEqual("", self.git("tag", "--list"))
        self.assertEqual(self.remote_baseline, self.git("ls-remote", "origin"))

    def test_automatic_publisher_refuses_initial_push(self):
        self.assertEqual(0, self.prepare().returncode)
        self.git("remote", "remove", "origin")
        empty = self.base / "empty.git"
        self.git("init", "--bare", str(empty))
        self.git("remote", "add", "origin", str(empty))
        head = self.git("rev-parse", "HEAD")
        r = self.run_tool("publish", "--accepted-checkpoint", "Fixture accepted",
                          "--expected-remote", str(empty), "--checks-file", ".local/pass.json")
        self.assertNotEqual(0, r.returncode)
        self.assertIn("Initial push requires", r.stdout)
        self.assertEqual(head, self.git("rev-parse", "HEAD"))
        self.assertEqual("", self.git("ls-remote", "origin"))

    def test_nested_internal_agent_file_is_refused(self):
        self.assertEqual(0, self.prepare().returncode)
        self.write("forge/AGENTS.md", "Private local agent instructions.\n")
        head = self.git("rev-parse", "HEAD")
        r = self.publish(".local/pass.json")
        self.assertNotEqual(0, r.returncode)
        self.assertIn("Private/runtime file", r.stdout)
        self.assertEqual(head, self.git("rev-parse", "HEAD"))
        self.assertEqual(self.remote_baseline, self.git("ls-remote", "origin"))

    def test_unpublished_local_commits_are_not_pushed_as_milestones(self):
        self.write("local.txt", "Local work belongs in snapshot refs.\n")
        self.git("add", "--all")
        self.git("commit", "-m", "wip: local-only work")
        self.assertEqual(0, self.prepare().returncode)
        head = self.git("rev-parse", "HEAD")
        r = self.publish(".local/pass.json")
        self.assertNotEqual(0, r.returncode)
        self.assertIn("unpublished commits", r.stdout)
        self.assertEqual(head, self.git("rev-parse", "HEAD"))
        self.assertEqual(self.remote_baseline, self.git("ls-remote", "origin"))

if __name__ == "__main__":
    unittest.main()
