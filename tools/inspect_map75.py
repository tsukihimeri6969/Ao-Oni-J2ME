import os, re, struct
from PIL import Image
from rubymarshal.reader import load

DATA = r"H:\Ao Oni J2ME\res\Data"
RES = r"H:\Ao Oni J2ME\res"

def attr(o, k, d=None):
    if o is None or not hasattr(o, "attributes"): return d
    a = o.attributes
    return a.get(k, a.get(k.encode(), d))

def dstr(v):
    if not v: return ""
    if isinstance(v, str): return v
    try: return v.decode("utf-8")
    except: return v.decode("cp932", errors="replace")

def utab(t):
    raw = getattr(t, "_private_data", None) or getattr(t, "data", b"")
    _, x, y, z, tot = struct.unpack_from("<5I", raw, 0)
    return x, y, z, struct.unpack_from(f"<{tot}h", raw, 20)

with open(os.path.join(DATA, "Map075.rxdata"), "rb") as f:
    m75 = load(f)
ev1 = attr(m75, "@events")[1]
print("=== Map075 ev01 commands ===")
for c in attr(attr(ev1, "@pages")[0], "@list"):
    code = attr(c, "@code")
    if code != 0:
        print(f"  code={code} params={attr(c, '@parameters')}")

with open(os.path.join(DATA, "Tilesets.rxdata"), "rb") as f:
    tilesets = load(f)

with open(os.path.join(RES, "meta.bin"), "rb") as f:
    f.read(12)
    ic = struct.unpack(">H", f.read(2))[0]
    for _ in range(ic):
        f.read(2)
        for _ in range(2):
            f.read(struct.unpack(">H", f.read(2))[0])
    tc = struct.unpack(">H", f.read(2))[0]
    print("\n=== Baked tileset blank/black slot audit ===")
    bad_tids_by_ts = {}
    for _ in range(tc):
        ts_id, cols, cnt = struct.unpack(">HHH", f.read(6))
        raw_tids = struct.unpack(f">{cnt}H", f.read(2 * cnt))
        im = Image.open(os.path.join(RES, "gfx", f"t{ts_id:02d}.png")).convert("RGBA")
        blanks, blacks = [], []
        for idx, rtid in enumerate(raw_tids):
            s = idx + 1
            x0, y0 = (s % cols) * 16, (s // cols) * 16
            crop = im.crop((x0, y0, x0 + 16, y0 + 16))
            vis = [p for p in crop.getdata() if p[3] > 0]
            if not vis: blanks.append((s, rtid))
            elif all(p[0] < 8 and p[1] < 8 and p[2] < 8 for p in vis): blacks.append((s, rtid))
        bad_tids_by_ts[ts_id] = set(r for _, r in blanks + blacks)
        if blanks or blacks:
            ts_name = dstr(attr(tilesets[ts_id], "@tileset_name"))
            print(f"  t{ts_id:02d} ({ts_name}): blank={blanks[:12]} (tot {len(blanks)}), black={blacks[:12]} (tot {len(blacks)})")

ts3 = tilesets[3]
print("\n=== Tileset 3 (003-Forest01) ===")
print("Autotiles:", [dstr(x) for x in attr(ts3, "@autotile_names", [])])
bad3 = bad_tids_by_ts.get(3, set())

for fn in sorted(os.listdir(DATA)):
    if not re.fullmatch(r"Map\d{3}\.rxdata", fn, re.I): continue
    with open(os.path.join(DATA, fn), "rb") as f:
        m = load(f)
    if attr(m, "@tileset_id") != 3: continue
    w, h = attr(m, "@width"), attr(m, "@height")
    _, _, _, tv = utab(attr(m, "@data"))
    plane = w * h
    print(f"\n--- {fn}: {w}x{h} (X=all-zero/bad tile, .=normal) ---")
    for y in range(h):
        line = []
        for x in range(w):
            c = y * w + x
            t0, t1, t2 = tv[c], tv[c + plane], tv[c + 2 * plane]
            if (t0 == 0 and t1 == 0 and t2 == 0) or (t0 in bad3) or (t1 in bad3) or (t2 in bad3):
                line.append("X")
            else:
                line.append(".")
        if "X" in line:
            print(f"  y={y:02d}: " + "".join(line))
    evs = attr(m, "@events", {}) or {}
    for eid in sorted(evs.keys()):
        ev = evs[eid]
        ps = []
        for pi, pg in enumerate(attr(ev, "@pages", [])):
            g = attr(pg, "@graphic")
            pics = [dstr(attr(c, "@parameters")[1]) for c in attr(pg, "@list", []) if attr(c, "@code") == 231]
            pstr = f",pics={pics}" if pics else ""
            ps.append(f"p{pi}(tr={attr(pg,'@trigger')},ch={dstr(attr(g,'@character_name'))!r},tid={attr(g,'@tile_id')},op={attr(g,'@opacity')}{pstr})")
        print(f"  ev{eid:02d} ({attr(ev,'@x')},{attr(ev,'@y')}): " + ", ".join(ps))