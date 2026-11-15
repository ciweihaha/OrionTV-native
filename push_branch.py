#!/usr/bin/env python3
"""Push local branch jewel/round-9-fix-player to GitHub via the git database API.

Replicates two local commits on top of main (1401a97):
  1. 40932d7 revert of the UI redesign merge (tree T1)
  2. 28a7c64 version bump to 1.4.23 (tree T2)
Creates branch ref jewel/round-9-fix-player, then two commits with the same trees,
messages and author/committer metadata, then updates the ref.
Uses the same credential flow as gh_write.py (dynamic_credentials surrogate).
"""
import base64
import json
import subprocess
import sys

sys.path.insert(0, "/opt/muse/skills/custom-connector-setup/bin")
sys.path.insert(0, "/home/hatch/workspace/custom-connectors/custom.github/bin")

from dynamic_credentials import add_surrogate_to_request, read_json_response
import urllib.request
import urllib.error
import time

API_BASE = "https://api.github.com"
REPO = "ciweihaha/OrionTV-native"
BRANCH = "jewel/round-9-fix-player"
BASE_SHA = "0020dd1ebbdc2fdff9f7712c1d6407bd9a3154b7"
LOCAL_COMMITS = ["7a786a0"]


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        raise RuntimeError("redirect refused")


def request_json(url, method="GET", payload=None):
    body = None
    req = urllib.request.Request(url, method=method)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    req.add_header("User-Agent", "Muse")
    if payload is not None:
        body = json.dumps(payload).encode("utf-8")
        req.add_header("Content-Type", "application/json")
    add_surrogate_to_request(req, "custom.github", allowed_hosts=["api.github.com"])
    last = None
    for attempt in range(3):
        try:
            opener = urllib.request.build_opener(NoRedirect())
            with opener.open(req, data=body, timeout=30) as resp:
                return read_json_response(resp)
        except urllib.error.HTTPError as e:
            last = GitHubError(f"HTTP {e.code} for {method} {url}: {e.read()[:300]!r}", e.code)
            if e.code in (401, 403, 429):
                raise last
            time.sleep(1 if attempt == 0 else 2)
    raise last


class GitHubError(RuntimeError):
    def __init__(self, message, status=None):
        super().__init__(message)
        self.status = status


def git(*args):
    return subprocess.check_output(["git", "-C", "/home/hatch/workspace/oriontv-native-fix9-player", *args], text=True).strip()


def changed_files(commit):
    out = git("diff-tree", "--no-commit-id", "--name-only", "-r", commit)
    return [l for l in out.splitlines() if l]


def main():
    # 1. Create the branch ref at base (ignore 422 = already exists).
    try:
        request_json(f"{API_BASE}/repos/{REPO}/git/refs", "POST",
                     {"ref": f"refs/heads/{BRANCH}", "sha": BASE_SHA})
        print("branch ref created at", BASE_SHA)
    except GitHubError as e:
        if e.status == 422:
            ref = request_json(f"{API_BASE}/repos/{REPO}/git/refs/heads/{BRANCH}")
            print("branch already exists at", ref["object"]["sha"])
        else:
            raise

    parent = BASE_SHA
    for local in LOCAL_COMMITS:
        tree_sha = git("rev-parse", f"{local}^{{tree}}")
        msg = git("log", "-1", "--format=%B", local)
        author_name = git("log", "-1", "--format=%an", local)
        author_email = git("log", "-1", "--format=%ae", local)
        author_date = git("log", "-1", "--format=%aI", local)
        files = changed_files(local)
        # Build tree entries: new blobs for changed files, on top of parent tree.
        parent_commit = request_json(f"{API_BASE}/repos/{REPO}/git/commits/{parent}")
        base_tree = parent_commit["tree"]["sha"]
        entries = []
        for path in files:
            with open(f"/home/hatch/workspace/oriontv-native-fix9-player/{path}", "rb") as f:
                content = f.read()
            blob = request_json(f"{API_BASE}/repos/{REPO}/git/blobs", "POST",
                                {"content": base64.b64encode(content).decode(), "encoding": "base64"})
            entries.append({"path": path, "mode": "100644", "type": "blob", "sha": blob["sha"]})
        tree = request_json(f"{API_BASE}/repos/{REPO}/git/trees", "POST",
                            {"base_tree": base_tree, "tree": entries})
        print(f"local {local}: tree {tree_sha} -> remote tree {tree['sha']} ({len(files)} files)")
        assert tree["sha"] == tree_sha, f"tree mismatch! remote {tree['sha']} != local {tree_sha}"
        commit = request_json(f"{API_BASE}/repos/{REPO}/git/commits", "POST", {
            "message": msg,
            "tree": tree["sha"],
            "parents": [parent],
            "author": {"name": author_name, "email": author_email, "date": author_date},
            "committer": {"name": author_name, "email": author_email, "date": author_date},
        })
        print(f"remote commit for local {local}: {commit['sha']}")
        parent = commit["sha"]

    # 3. Update branch ref to the final commit.
    ref = request_json(f"{API_BASE}/repos/{REPO}/git/refs/heads/{BRANCH}", "PATCH", {"sha": parent, "force": False})
    print("branch", BRANCH, "now at", ref["object"]["sha"])
    print("REMOTE_HEAD=" + parent)


if __name__ == "__main__":
    main()
