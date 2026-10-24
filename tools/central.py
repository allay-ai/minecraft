#!/usr/bin/env python3
"""Publish a Maven component to Maven Central through the Central Portal's API: bundle what Gradle staged (the jars,
the POM, their signatures and checksums), upload it to be published at once (publishingType=AUTOMATIC, no draft to
click), wait until the Portal says PUBLISHED, then until repo1.maven.org serves the POM.

    python3 -I tools/central.py expansion/build/staging chat.allay allay-expansion 1.0.0
    python3 -I tools/central.py expansion/build/staging chat.allay allay-expansion 1.0.0 --bundle-only

MAVEN_CENTRAL_USERNAME and MAVEN_CENTRAL_PASSWORD are a Portal user token. A version can't be published twice, so the
script stops when it's already on Central.
"""

from __future__ import annotations

import argparse
import base64
import io
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid
import zipfile
from pathlib import Path

PORTAL = "https://central.sonatype.com/api/v1/publisher"
REPO = "https://repo1.maven.org/maven2"


def auth() -> str:
    user, password = os.environ.get("MAVEN_CENTRAL_USERNAME"), os.environ.get("MAVEN_CENTRAL_PASSWORD")
    if not user or not password:
        sys.exit("MAVEN_CENTRAL_USERNAME and MAVEN_CENTRAL_PASSWORD must be set")
    return "Bearer " + base64.b64encode(f"{user}:{password}".encode()).decode()


def request(method: str, url: str, body: bytes | None = None, headers: dict[str, str] | None = None) -> tuple[int, bytes]:
    req = urllib.request.Request(url, data=body, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def bundle(staging: Path, group: str, artifact: str, version: str) -> bytes:
    """The component's folder as the Portal wants it, paths from the repository root, without maven-metadata."""
    folder = staging / group.replace(".", "/") / artifact / version
    files = sorted(p for p in folder.iterdir() if p.is_file() and not p.name.startswith("maven-metadata"))
    if not any(p.name.endswith(".pom") for p in files):
        sys.exit(f"No POM in {folder}: run the publication to the staging repository first")
    unsigned = [p.name for p in files if not p.name.endswith((".asc", ".md5", ".sha1", ".sha256", ".sha512")) and not (folder / (p.name + ".asc")).exists()]
    if unsigned:
        sys.exit(f"Not signed: {', '.join(unsigned)}; set MAVEN_SIGNING_KEY and MAVEN_SIGNING_PASSWORD")
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as zip:
        for path in files:
            zip.write(path, path.relative_to(staging).as_posix())
    print(f"Bundle: {len(files)} files from {folder}")
    return buffer.getvalue()


def published(group: str, artifact: str, version: str, token: str) -> bool:
    status, body = request("GET", f"{PORTAL}/published?namespace={group}&name={artifact}&version={version}", headers={"Authorization": token})
    if status != 200:
        sys.exit(f"The Portal didn't say whether {version} is published: HTTP {status} {body[:200]!r}")
    return bool(json.loads(body).get("published"))


def upload(data: bytes, name: str, token: str) -> str:
    boundary = uuid.uuid4().hex
    body = (
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"bundle\"; filename=\"{name}.zip\"\r\nContent-Type: application/octet-stream\r\n\r\n".encode()
        + data + f"\r\n--{boundary}--\r\n".encode()
    )
    status, answer = request(
        "POST", f"{PORTAL}/upload?name={name}&publishingType=AUTOMATIC", body,
        {"Authorization": token, "Content-Type": f"multipart/form-data; boundary={boundary}"},
    )
    if status not in (200, 201):
        sys.exit(f"Upload refused: HTTP {status} {answer[:500]!r}")
    return answer.decode().strip()


def wait_published(deployment: str, token: str, minutes: int) -> None:
    end = time.time() + minutes * 60
    last = None
    while time.time() < end:
        status, body = request("POST", f"{PORTAL}/status?id={deployment}", headers={"Authorization": token})
        if status == 200:
            answer = json.loads(body)
            state = answer.get("deploymentState")
            if state != last:
                print(f"Deployment {deployment}: {state}", flush=True)
                last = state
            if state == "PUBLISHED":
                return
            if state == "FAILED":
                print(json.dumps(answer.get("errors", answer), indent=2))
                sys.exit("The Portal refused the deployment; nothing was published")
        time.sleep(15)
    sys.exit(f"Not published after {minutes} minutes; the Portal may still finish: check deployment {deployment}")


def wait_repo(group: str, artifact: str, version: str, minutes: int) -> None:
    url = f"{REPO}/{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.pom"
    end = time.time() + minutes * 60
    while time.time() < end:
        status, _ = request("GET", url)
        if status == 200:
            print(f"On Maven Central: {url}")
            return
        time.sleep(30)
    sys.exit(f"Published, but {url} still answers nothing after {minutes} minutes; it usually appears within half an hour")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("staging", type=Path)
    parser.add_argument("group")
    parser.add_argument("artifact")
    parser.add_argument("version")
    parser.add_argument("--bundle-only", action="store_true", help="build the bundle and stop, for a check")
    parser.add_argument("--minutes", type=int, default=60, help="how long to wait for each of the two")
    args = parser.parse_args()
    data = bundle(args.staging, args.group, args.artifact, args.version)
    if args.bundle_only:
        return 0
    token = auth()
    if published(args.group, args.artifact, args.version, token):
        sys.exit(f"{args.group}:{args.artifact}:{args.version} is already on Maven Central; a version is published once")
    deployment = upload(data, f"{args.artifact}-{args.version}", token)
    print(f"Uploaded as deployment {deployment}, to be published at once", flush=True)
    wait_published(deployment, token, args.minutes)
    wait_repo(args.group, args.artifact, args.version, args.minutes)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
