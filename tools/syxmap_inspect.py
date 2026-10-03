#!/usr/bin/env python3
"""Inspect a .syxmap export: print a summary and render preview PNGs.

usage: syxmap_inspect.py [file.syxmap | latest] [--out DIR]
"""
import argparse
import base64
import collections
import gzip
import json
import os
import sys

import numpy as np
from PIL import Image

USERDIR = os.path.expanduser(
    "~/.local/share/Steam/steamapps/compatdata/1162750/pfx/drive_c/users/steamuser/AppData/Roaming/songsofsyx")

DTYPES = {"u8": np.uint8, "i8": np.int8, "u16": "<u2", "rgb8": np.uint8}


def load(path):
    with gzip.open(path, "rt", encoding="utf-8") as f:
        m = json.load(f)
    w, h = m["width"], m["height"]
    layers = {}
    for name, l in m["layers"].items():
        a = np.frombuffer(base64.b64decode(l["data"]), dtype=DTYPES[l["type"]])
        layers[name] = a.reshape(h, w, 3) if l["type"] == "rgb8" else a.reshape(h, w)
    return m, layers


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("file", nargs="?", default="latest")
    ap.add_argument("--out", default=None)
    a = ap.parse_args()
    path = a.file
    if path == "latest":
        path = open(os.path.join(USERDIR, "sos2mc", "latest.txt")).read().strip()
        path = path.replace("Z:", "").replace("\\", "/") if path.startswith("Z:") else path
        if not os.path.exists(path):  # Windows path inside the Proton prefix
            path = os.path.join(USERDIR, "sos2mc", os.path.basename(path.replace("\\", "/")))
    m, L = load(path)
    out = a.out or os.path.dirname(os.path.abspath(path))
    print(f"{path}\n  city={m['city']} game={m['gameVersion']} size={m['width']}x{m['height']}"
          f" format v{m['formatVersion']}  ({os.path.getsize(path)/1e6:.1f} MB)")
    for k, pal in m["palettes"].items():
        print(f"  palette {k}: {len(pal)} entries")
    flags = m["flagBits"]

    def hist(layer, pal, top=40):
        c = collections.Counter(L[layer].ravel().tolist())
        return ", ".join(f"{pal[i]}={n}" for i, n in c.most_common(top))

    print("  terrain:", hist("terrain", m["palettes"]["terrain"]))
    print("  floor:", hist("floor", m["palettes"]["floor"]))
    print("  ground:", hist("ground", m["palettes"]["ground"]))
    print("  mineral:", hist("mineral", m["palettes"]["mineral"]))
    f = L["flags"]
    for k, b in flags.items():
        print(f"  flag {k}: {(f & b != 0).sum()} tiles")
    print(f"  heightEnd range {L['heightEnd'].min()}..{L['heightEnd'].max()}, "
          f"heightStart {L['heightStart'].min()}..{L['heightStart'].max()}")
    print(f"  rooms: {len(m['rooms'])}, placed furniture items: {len(m['furniture'])}")
    bc = collections.Counter(r["blueprint"] for r in m["rooms"])
    print("  room types:", ", ".join(f"{k}={v}" for k, v in bc.most_common()))
    named = unnamed = 0
    for bp in m["blueprints"]:
        fu = bp and bp.get("furnisher")
        if not fu:
            continue
        for s in fu["sprites"]:
            if "key" in s:
                named += 1
            else:
                unnamed += 1
    print(f"  furniture sprites: {named} named, {unnamed} unnamed")
    for bp in m["blueprints"]:
        fu = bp and bp.get("furnisher")
        if fu:
            print(f"    {bp['key']}: groups={[g['name'] for g in fu['groups']]} "
                  f"sprites={[s.get('key', '?') for s in fu['sprites']]}")
    if m["warnings"]:
        print("  WARNINGS:", *m["warnings"][:20], sep="\n    ")

    # preview 1: game minimap colours
    rgb = L["minimapRGB"].astype(np.int32)
    if rgb.max() <= 127:  # SoS stores colour channels as 0..127 signed bytes
        rgb = rgb * 2
    Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8)).save(os.path.join(out, "preview_minimap.png"))
    # preview 2: semantic map (rooms coloured, roofs darker, walls black, furniture bright)
    rng = np.random.default_rng(1)
    pal = rng.integers(60, 230, size=(len(m["palettes"]["blueprint"]) + 1, 3))
    sem = np.full((m["height"], m["width"], 3), 200, np.uint8)
    terr = m["palettes"]["terrain"]
    water = np.isin(L["terrain"], [i for i, k in enumerate(terr) if "WATER" in k.upper()])
    sem[water] = (40, 80, 200)
    sem[L["floor"] > 0] = (150, 140, 120)
    rb = L["roomBlueprint"]
    sem[rb > 0] = pal[rb[rb > 0]]
    sem[(f & flags["roof"]) != 0] = (sem[(f & flags["roof"]) != 0] * 0.7).astype(np.uint8)
    sem[L["furnItem"] > 0] = np.minimum(255, sem[L["furnItem"] > 0].astype(int) + 60)
    wall = np.isin(L["terrain"], [i for i, k in enumerate(terr) if k.startswith("BUILDING_") and "CEILING" not in k])
    sem[wall] = (20, 20, 20)
    Image.fromarray(sem).save(os.path.join(out, "preview_semantic.png"))
    print(f"  previews: {out}/preview_minimap.png, preview_semantic.png")


if __name__ == "__main__":
    sys.exit(main())
