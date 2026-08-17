#!/usr/bin/env python3
"""Create a Gitea release from a Jenkins tag build.

Runs after gradle buildAndCollect has produced the per-version jars under
build/libs/<mod_version>/. Reads the tag name from $TAG_NAME (Jenkins sets
this when the build was triggered by a tag), creates a release on the Gitea
repo, and uploads each jar (mod + sources, for each mc version) as a release
asset.

Requires:
    $GITEA_URL, $GITEA_REPO, $GITEA_TOKEN in the environment.
    The jars already exist under build/libs/<mod_version>/.
"""
import os
import sys
from pathlib import Path

import requests

GITEA_URL = os.environ["GITEA_URL"].rstrip("/")
GITEA_REPO = os.environ["GITEA_REPO"]
GITEA_TOKEN = os.environ["GITEA_TOKEN"]

TAG_NAME = os.environ.get("TAG_NAME") or (sys.argv[1] if len(sys.argv) > 1 else None)
if not TAG_NAME:
    # Fall back to git itself — the `when` block in Jenkinsfile already proved
    # HEAD is exactly on a tag, so this will return something like `v1.2.3`.
    try:
        TAG_NAME = subprocess.check_output(
            ["git", "describe", "--tags", "--exact-match", "HEAD"],
            text=True, stderr=subprocess.DEVNULL, timeout=10,
        ).strip()
    except (subprocess.CalledProcessError, FileNotFoundError, subprocess.TimeoutExpired):
        sys.exit("TAG_NAME not set and HEAD is not on a tag")

API = f"{GITEA_URL}/api/v1/repos/{GITEA_REPO}"
HEADERS = {"Authorization": f"token {GITEA_TOKEN}"}


def mod_version() -> str:
    for line in Path("stonecutter.properties.toml").read_text().splitlines():
        if line.startswith("mod.version"):
            return line.split("=", 1)[1].strip().strip('"')
    sys.exit("mod.version not found in stonecutter.properties.toml")


def main() -> int:
    version = mod_version()
    name = f"Storage Manager {version}"
    body = (
        f"Release of Storage Manager {version}.\n\n"
        f"Jars are attached per Minecraft version (1.21.8, 26.1.2, 26.2). "
        f"Each .jar is the mod; each *-sources.jar is the matching source drop."
    )

    print(f"creating release {TAG_NAME} ({name})...")
    r = requests.post(
        f"{API}/releases",
        json={
            "tag_name": TAG_NAME,
            "name": name,
            "body": body,
            "draft": False,
            "prerelease": False,
        },
        headers=HEADERS,
        timeout=30,
    )
    if r.status_code == 409:
        # release already exists for this tag — fetch it instead
        print(f"  release exists, fetching...")
        r = requests.get(f"{API}/releases/tags/{TAG_NAME}", headers=HEADERS, timeout=30)
    r.raise_for_status()
    release = r.json()
    release_id = release["id"]
    print(f"  -> {release['html_url']}")

    jar_dir = Path("build/libs") / version
    if not jar_dir.is_dir():
        sys.exit(f"jar dir not found: {jar_dir} — run gradle buildAndCollect first")

    jars = sorted(jar_dir.glob("*.jar"))
    if not jars:
        sys.exit(f"no jars in {jar_dir}")

    for jar in jars:
        print(f"uploading {jar.name}...")
        with jar.open("rb") as f:
            r = requests.post(
                f"{API}/releases/{release_id}/assets",
                headers={**HEADERS, "Content-Type": "application/java-archive"},
                files={"attachment": (jar.name, f)},
                timeout=120,
            )
        r.raise_for_status()
        asset = r.json()
        print(f"  -> {asset['browser_download_url']}")

    print("done.")
    return 0


if __name__ == "__main__":
    sys.exit(main())