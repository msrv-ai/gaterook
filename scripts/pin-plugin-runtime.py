#!/usr/bin/env python3
"""Update a Java runtime pin from publisher metadata and a locally downloaded ZIP.

Usage: python3 scripts/pin-plugin-runtime.py PLATFORM METADATA_JSON ARCHIVE_ZIP
Get metadata from https://api.azul.com/metadata/v1/zulu/packages/{package_uuid}.
Review the publisher, version, HTTPS URL and SHA-256 before updating a pin.
This maintenance helper is not part of normal builds or runtime installation.
"""
import hashlib
import json
from pathlib import Path, PurePosixPath
import stat
import sys
import zipfile


def pin_runtime(platform, metadata_path, archive_path):
    if platform not in {"darwin-arm64", "darwin-x64", "windows-x64", "linux-x64"}:
        raise ValueError("Unsupported runtime platform")
    metadata = json.loads(Path(metadata_path).read_text())
    suffix = {"darwin-arm64": "macosx_aarch64.zip", "darwin-x64": "macosx_x64.zip",
              "windows-x64": "win_x64.zip", "linux-x64": "linux_x64.zip"}[platform]
    if metadata.get("java_package_type") != "jre" or metadata["java_version"][0] != 25 or not metadata["name"].endswith(suffix):
        raise ValueError("Expected a Java 25 JRE for the selected platform")
    archive = Path(archive_path)
    if not metadata["download_url"].startswith("https://cdn.azul.com/zulu/bin/"):
        raise ValueError("Expected an immutable Azul CDN URL")
    sha = hashlib.sha256(archive.read_bytes()).hexdigest()
    if sha != metadata["sha256_hash"]:
        raise ValueError("Archive SHA-256 differs from publisher metadata")
    prefix = metadata["name"].removesuffix(".zip") + "/"
    files = []
    with zipfile.ZipFile(archive) as source:
        seen = set()
        for entry in sorted(source.infolist(), key=lambda e: e.filename):
            if entry.is_dir():
                continue
            if not entry.filename.startswith(prefix):
                raise ValueError("Unexpected archive root")
            name = entry.filename[len(prefix):]
            if ".." in PurePosixPath(name).parts or "\\" in name or ":" in name or name.startswith("/"):
                raise ValueError("Unsafe archive path")
            if name.casefold() in seen:
                raise ValueError("Duplicate archive path")
            seen.add(name.casefold())
            data = source.read(entry)
            mode = entry.external_attr >> 16
            file = {"path": name, "size": len(data), "sha256": hashlib.sha256(data).hexdigest()}
            if stat.S_ISLNK(mode):
                file["link"] = data.decode("utf-8")
            elif mode & 0o111:
                file["executable"] = True
            files.append(file)
    return {"version": ".".join(map(str, metadata["java_version"])), "url": metadata["download_url"],
            "sha256": sha, "size": archive.stat().st_size, "prefix": prefix,
            "home": "Contents/Home" if platform.startswith("darwin") else ".", "files": files}


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    platform, metadata, archive = sys.argv[1:]
    destination = Path(__file__).resolve().parent.parent / "core/src/main/resources/ai/msrv/gaterook/core/connectors/plugin-runtimes.json"
    pins = json.loads(destination.read_text()) if destination.exists() else {}
    pins[platform] = pin_runtime(platform, metadata, archive)
    destination.write_text(json.dumps(pins, indent=2) + "\n")
