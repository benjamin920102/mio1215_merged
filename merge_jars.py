#!/usr/bin/env python3
"""
Merge two Minecraft Fabric mod JARs into one.

Loader JAR provides: MANIFEST.MF, fabric.mod.json (entrypoints, accessWidener),
access widener file, nick/ entrypoint classes, assets, mio/ resources.
Payload JAR provides: obfuscated me/mioclient/ classes, mixins, mixin config.

The script merges fabric.mod.json from both JARs, preserving loader's fields
but adding the mixins list from the payload's fabric.mod.json.
"""
import json
import sys
import os
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED, ZIP_STORED

if len(sys.argv) != 4:
    print("Usage: python3 merge_jars.py <loader.jar> <payload.jar> <output.jar>")
    sys.exit(1)

loader_path, payload_path, out_path = sys.argv[1:4]

PROTECTED_EXACT = {
    "META-INF/MANIFEST.MF",
}

# Loader's boot classes and entrypoints must not be overwritten
PROTECTED_PREFIXES = (
    "nick/",
)


def is_signature_file(name: str) -> bool:
    n = name.lower()
    return (n.startswith("meta-inf/") and
            any(n.endswith(sfx) for sfx in (".sf", ".rsa", ".dsa", ".ec")))


def read_text(zf: ZipFile, name: str) -> str | None:
    try:
        return zf.read(name).decode("utf-8")
    except KeyError:
        return None


def merge_fabric_json(loader_fabric: dict, payload_fabric: dict) -> dict:
    """Merge payload's mixins/config into loader's fabric.mod.json."""
    merged = dict(loader_fabric)

    # Merge mixins from payload (loader's mixins should be replaced)
    payload_mixins = payload_fabric.get("mixins", [])
    if payload_mixins:
        merged["mixins"] = list(payload_mixins)

    # Merge depends
    loader_depends = merged.get("depends", {})
    payload_depends = payload_fabric.get("depends", {})
    for k, v in payload_depends.items():
        if k not in loader_depends:
            loader_depends[k] = v
    if loader_depends:
        merged["depends"] = loader_depends

    return merged


with ZipFile(loader_path, "r") as loader_zip, \
     ZipFile(payload_path, "r") as payload_zip:

    loader_names = {info.filename for info in loader_zip.infolist()}

    # --- Merge fabric.mod.json ---
    loader_fabric_json = read_text(loader_zip, "fabric.mod.json")
    payload_fabric_json = read_text(payload_zip, "fabric.mod.json")

    merged_fabric = None
    if loader_fabric_json and payload_fabric_json:
        lfj = json.loads(loader_fabric_json)
        pfj = json.loads(payload_fabric_json)
        merged_fabric = merge_fabric_json(lfj, pfj)
        print(f"[merge] fabric.mod.json: merged payload mixins into loader base")
    elif loader_fabric_json:
        print("[merge] fabric.mod.json: using loader's only")

    # --- Build entry map ---
    entries: dict[str, tuple[ZipFile, ZipInfo, str]] = {}
    order: list[str] = []

    def add_entry(zf: ZipFile, info: ZipInfo, source: str):
        name = info.filename
        if name not in entries:
            order.append(name)
            entries[name] = (zf, info, source)
        else:
            prev_source = entries[name][2]
            entries[name] = (zf, info, source)
            if prev_source != source:
                print(f"  [overwrite] {name}: {prev_source} <- {source}")

    # Phase 1: Loader first
    for info in loader_zip.infolist():
        name = info.filename
        if is_signature_file(name):
            print(f"  [skip-sig] {name}")
            continue
        add_entry(loader_zip, info, "loader")

    # Phase 2: Payload
    added = 0
    overwritten = 0
    skipped = 0

    for info in payload_zip.infolist():
        name = info.filename

        if is_signature_file(name):
            skipped += 1
            continue

        if name in PROTECTED_EXACT:
            skipped += 1
            continue

        if name.startswith(PROTECTED_PREFIXES):
            skipped += 1
            continue

        if name == "META-INF/MANIFEST.MF" and "META-INF/MANIFEST.MF" in loader_names:
            skipped += 1
            continue

        if name == "fabric.mod.json":
            skipped += 1
            continue

        if name in entries:
            overwritten += 1
        else:
            added += 1

        add_entry(payload_zip, info, "payload")

    # --- Write output ---
    with ZipFile(out_path, "w", ZIP_DEFLATED, allowZip64=True) as out_zip:
        # Write merged fabric.mod.json first
        if merged_fabric:
            merged_text = json.dumps(merged_fabric, indent="\t")
            zi = ZipInfo("fabric.mod.json")
            zi.compress_type = ZIP_DEFLATED
            out_zip.writestr(zi, merged_text)
            print(f"\n[output] fabric.mod.json (merged):")
            for k, v in merged_fabric.items():
                if k in ("entrypoints", "mixins", "accessWidener", "depends"):
                    print(f"    {k}: {json.dumps(v, ensure_ascii=False)}")

        # Write all entries
        for name in order:
            zf, info, source = entries[name]
            data = zf.read(info)

            if name == "fabric.mod.json" and merged_fabric:
                continue

            zi = ZipInfo(name, date_time=info.date_time)
            zi.external_attr = info.external_attr
            zi.create_system = info.create_system

            if name.endswith("/"):
                zi.compress_type = ZIP_STORED
            elif name.endswith(".class"):
                zi.compress_type = ZIP_DEFLATED
            elif name.endswith((".png", ".bin")):
                zi.compress_type = ZIP_STORED
            else:
                zi.compress_type = ZIP_DEFLATED

            out_zip.writestr(zi, data)

    # --- Summary ---
    out_size_mb = os.path.getsize(out_path) / (1024 * 1024)
    print(f"\n{'='*55}")
    print(f"Output: {out_path}  ({out_size_mb:.1f} MB)")
    print(f"{'='*55}")
    print(f"  Total entries:          {len(order)}")
    print(f"  Added from payload:     {added}")
    print(f"  Overwritten by payload: {overwritten}")
    print(f"  Skipped from payload:   {skipped}")
    print(f"  Protected exact:        {len(PROTECTED_EXACT & loader_names)} file(s)")
    print(f"  Protected prefixes:     {len([n for n in loader_names if n.startswith(PROTECTED_PREFIXES)])} entries")