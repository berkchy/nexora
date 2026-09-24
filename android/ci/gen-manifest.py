#!/usr/bin/env python3
"""Generate an incremental-update manifest and stage individual .so files.

  gen-manifest.py <abi> <libdir> <outdir> [--plugins-dir <dir>]

For each .so in <libdir>, computes SHA-256 and writes:
  <outdir>/manifest.json          — file inventory for the patcher
  <outdir>/<abi>/<filename>.so    — individual .so files (copy)

The manifest format is consumed by the patcher's IncrementalUpdateManager.
Asset names use ABI prefix (e.g. arm64-v8a__libamxmodx.so) to avoid GitHub
Release asset name conflicts when both ABIs exist in the same release.
"""
import argparse
import hashlib
import json
import os
import shutil
import sys

VERSION = os.environ.get("RELEASE_VERSION", "1.10.0-dev")

MODULES = [
    "cstrike", "csx", "engine", "fakemeta", "fun", "geoip",
    "hamsandwich", "json", "nvault", "reapi", "regex", "sockets", "sqlite",
]

# ABI -> (android runtime lib suffix, AMXX module suffix)
ABI_MAP = {
    "arm64-v8a": ("arm64", "amd64"),
    "armeabi-v7a": ("armv7l", "arm"),
}


def sha256_of(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def collect_entries(libdir: str, abi: str) -> list:
    suffix, mod_suffix = ABI_MAP[abi]
    entries = []

    def add(name, target, required, desc):
        src = os.path.join(libdir, name)
        if not os.path.exists(src):
            print(f"WARN: {name} not found, skipping", file=sys.stderr)
            return
        asset_name = f"{abi}__{name}"
        entries.append({
            "name": name,
            "asset": asset_name,
            "path": f"lib/{abi}/{name}",
            "target": target,
            "sha256": sha256_of(src),
            "size": os.path.getsize(src),
            "required": required,
            "description": desc,
        })

    add("libamxmodx.so", f"lib/{abi}/libamxmodx.so", True, "AMX Mod X core")

    metamod_name = "libyapb_android_" + suffix + ".so"
    metamod_src = os.path.join(libdir, "libmetamod.so")
    if os.path.exists(metamod_src):
        asset_name = f"{abi}__libmetamod.so"
        entries.append({
            "name": "libmetamod.so",
            "asset": asset_name,
            "path": f"lib/{abi}/libmetamod.so",
            "target": f"lib/{abi}/{metamod_name}",
            "sha256": sha256_of(metamod_src),
            "size": os.path.getsize(metamod_src),
            "required": True,
            "description": "Metamod HL1 (as libyapb for -dll @yapb)",
        })

    add("libyapb.so", f"lib/{abi}/libyapb.so", False, "YaPB bot plugin")

    client_name = f"libclient_android_{suffix}.so"
    add(client_name, f"lib/{abi}/{client_name}", False, "CS16Client client DLL (crash handler)")

    # ReGameDLL game DLL, bundled as libcs. Shipped from the berkchy fork branch
    # fix/first-spawn-equip (arm64 only); skipped silently when not present, then
    # the patcher falls back to patching the base APK's libcs in place.
    libcs_name = "libcs_android_" + suffix + ".so"
    add(libcs_name, f"lib/{abi}/{libcs_name}", False, "ReGameDLL game DLL (first-spawn fix)")

    # libmenu intentionally NOT shipped: the text-based menu is broken, stock stays.
    for mod in MODULES:
        modname = f"lib{mod}_amxx_{mod_suffix}.so"
        add(modname, f"lib/{abi}/{modname}", True, f"{mod} module")

    return entries


def main():
    parser = argparse.ArgumentParser(description="Generate incremental update manifest")
    parser.add_argument("abi", help="Target ABI (arm64-v8a or armeabi-v7a)")
    parser.add_argument("libdir", help="Directory containing built .so files")
    parser.add_argument("outdir", help="Output directory for manifest + staged files")
    parser.add_argument("--plugins-dir", help="Directory containing compiled .amxx plugins")
    args = parser.parse_args()

    if args.abi not in ABI_MAP:
        print(f"unsupported ABI: {args.abi}", file=sys.stderr)
        sys.exit(1)

    # Collect .so entries
    entries = collect_entries(args.libdir, args.abi)

    manifest = {
        "version": VERSION,
        "game": "cs16client",
        "abi": args.abi,
        "files": entries,
    }

    # Write manifest
    os.makedirs(args.outdir, exist_ok=True)
    manifest_path = os.path.join(args.outdir, "manifest.json")
    with open(manifest_path, "w") as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)
    print(f"manifest: {manifest_path} ({len(entries)} files)")

    # Stage individual .so files (ABI-prefixed to avoid conflicts)
    staged_dir = os.path.join(args.outdir, args.abi)
    os.makedirs(staged_dir, exist_ok=True)
    for entry in entries:
        src = os.path.join(args.libdir, entry["name"])
        dst = os.path.join(staged_dir, entry["asset"])
        shutil.copy2(src, dst)
        os.chmod(dst, 0o755)

    print(f"staged: {staged_dir} ({len(entries)} files)")

    # Stage plugins if provided
    if args.plugins_dir and os.path.isdir(args.plugins_dir):
        plugins_dir = os.path.join(args.outdir, "plugins")
        os.makedirs(plugins_dir, exist_ok=True)
        count = 0
        for name in sorted(os.listdir(args.plugins_dir)):
            if name.endswith(".amxx"):
                shutil.copy2(os.path.join(args.plugins_dir, name),
                             os.path.join(plugins_dir, name))
                count += 1
        print(f"plugins: {plugins_dir} ({count} files)")


if __name__ == "__main__":
    main()
