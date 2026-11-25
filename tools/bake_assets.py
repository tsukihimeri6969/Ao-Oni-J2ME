import os
import re
import sys
import struct
from PIL import Image
from rubymarshal.reader import load

# Canonical RMXP 48-pattern autotile quarter-tile table (1-based indices into 6x8 grid of 16x16 subtiles)
AUTOTILE_TABLE = (
    (27, 28, 33, 34), ( 5, 28, 33, 34), (27,  6, 33, 34), ( 5,  6, 33, 34),
    (27, 28, 33, 12), ( 5, 28, 33, 12), (27,  6, 33, 12), ( 5,  6, 33, 12),
    (27, 28, 11, 34), ( 5, 28, 11, 34), (27,  6, 11, 34), ( 5,  6, 11, 34),
    (27, 28, 11, 12), ( 5, 28, 11, 12), (27,  6, 11, 12), ( 5,  6, 11, 12),
    (25, 26, 31, 32), (25,  6, 31, 32), (25, 26, 31, 12), (25,  6, 31, 12),
    (15, 16, 21, 22), (15, 16, 21, 12), (15, 16, 11, 22), (15, 16, 11, 12),
    (29, 30, 35, 36), (29, 30, 11, 36), ( 5, 30, 35, 36), ( 5, 30, 11, 36),
    (39, 40, 45, 46), ( 5, 40, 45, 46), (39,  6, 45, 46), ( 5,  6, 45, 46),
    (25, 30, 31, 36), (15, 16, 45, 46), (13, 14, 19, 20), (13, 14, 19, 12),
    (17, 18, 23, 24), (17, 18, 11, 24), (41, 42, 47, 48), ( 5, 42, 47, 48),
    (37, 38, 43, 44), (37,  6, 43, 44), (13, 18, 19, 24), (13, 14, 43, 44),
    (37, 42, 43, 48), (17, 18, 47, 48), (13, 18, 43, 48), ( 1,  2,  7,  8),
)

ATLAS_COLS = 16
TILE_OUT = 16
FACE_OUT = 42
IMG_EXTS = (".png", ".bmp", ".jpg", ".jpeg", ".gif")

FACE_TAG_RE = re.compile(r"(?:\\|\u00A5|\uFFE5)(?:f|face|p|pic|z|l)?\[([^\]]+)\]", re.IGNORECASE)
ALL_TAG_RE = re.compile(r"(?:\\|\u00A5|\uFFE5)(?:[A-Za-z]+\[[^\]]*\]|\[[^\]]*\]|.)")


def attr(obj, name, default=None):
    if obj is None or not hasattr(obj, "attributes"):
        return default
    attrs = obj.attributes
    if name in attrs:
        return attrs[name]
    bname = name.encode("ascii")
    if bname in attrs:
        return attrs[bname]
    return default


def decode_str(val):
    if val is None:
        return ""
    if isinstance(val, str):
        return val.replace("\x00", "")
    if isinstance(val, (bytes, bytearray)):
        try:
            return val.decode("utf-8").replace("\x00", "")
        except UnicodeDecodeError:
            return val.decode("cp932", errors="replace").replace("\x00", "")
    return str(val).replace("\x00", "")


def unpack_table(table_obj):
    if table_obj is None:
        return 0, 0, 0, ()
    raw = getattr(table_obj, "_private_data", None)
    if raw is None:
        raw = getattr(table_obj, "data", b"")
    if not raw or len(raw) < 20:
        return 0, 0, 0, ()
    dims, x, y, z, total = struct.unpack_from("<5I", raw, 0)
    vals = struct.unpack_from(f"<{total}h", raw, 20)
    return x, y, z, vals


def pack_java_utf(text):
    raw = text.encode("utf-8")
    if len(raw) > 65535:
        raw = raw[:65535]
    return struct.pack(">H", len(raw)) + raw


def flat_pixels(img):
    if hasattr(img, "get_flattened_data"):
        return img.get_flattened_data()
    return img.getdata()


def build_asset_index(search_roots, subfolders):
    """Maps lowercased stem -> full file path, preferring earlier roots and earlier subfolders."""
    if isinstance(subfolders, str):
        subfolders = (subfolders,)
    index = {}
    for root in search_roots:
        if not root:
            continue
        for sub in subfolders:
            folder = os.path.join(root, sub)
            if not os.path.isdir(folder):
                continue
            for fname in os.listdir(folder):
                stem, ext = os.path.splitext(fname)
                if ext.lower() in IMG_EXTS:
                    key = stem.lower()
                    if key not in index:
                        index[key] = os.path.join(folder, fname)
    return index


def open_rgba(path):
    return Image.open(path).convert("RGBA")


def save_indexed_png(rgba_img, out_path, max_colors=64, alpha_cutoff=16):
    """
    Saves an RGBA image as a Nokia S40-safe indexed PNG with palette index 0
    strictly reserved for full transparency (single-entry tRNS chunk).
    """
    max_colors = max(4, min(256, int(max_colors)))
    w, h = rgba_img.size
    rgba = rgba_img.convert("RGBA")
    px = rgba.load()

    rgb = Image.new("RGB", (w, h), (0, 0, 0))
    rgb_px = rgb.load()
    transparent_coords = []

    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a < alpha_cutoff:
                transparent_coords.append((x, y))
            else:
                rgb_px[x, y] = (r, g, b)

    q = rgb.quantize(colors=max_colors - 1, method=Image.Quantize.FASTOCTREE, dither=Image.Dither.NONE)
    old_pal = q.getpalette() or [0] * (3 * (max_colors - 1))
    new_pal = [255, 0, 255] + old_pal[: 3 * (max_colors - 1)]
    if len(new_pal) < 768:
        new_pal.extend([0] * (768 - len(new_pal)))

    out = Image.new("P", (w, h), 0)
    out.putpalette(new_pal[:768])

    src_data = flat_pixels(q)
    shifted = bytearray(len(src_data))
    for i, val in enumerate(src_data):
        shifted[i] = val + 1
    out.frombytes(bytes(shifted))

    out_px = out.load()
    for x, y in transparent_coords:
        out_px[x, y] = 0

    out.save(out_path, format="PNG", optimize=True, transparency=0)
    return os.path.getsize(out_path)


def compose_autotile_32(auto_img, pattern):
    w, h = auto_img.size
    if h <= 32 or w < 96:
        return auto_img.crop((0, 0, min(32, w), min(32, h))).resize((32, 32), Image.NEAREST)

    tile = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    quads = AUTOTILE_TABLE[pattern % 48]
    dst_pos = ((0, 0), (16, 0), (0, 16), (16, 16))
    for q_idx, (dx, dy) in zip(quads, dst_pos):
        sub = q_idx - 1
        sx = (sub % 6) * 16
        sy = (sub // 6) * 16
        if sx + 16 <= w and sy + 16 <= h:
            tile.paste(auto_img.crop((sx, sy, sx + 16, sy + 16)), (dx, dy))
    return tile


def extract_tile_16(raw_tid, ts_img, auto_imgs):
    """Returns (tile16_rgba, was_oob)."""
    if raw_tid < 48:
        return Image.new("RGBA", (TILE_OUT, TILE_OUT), (0, 0, 0, 0)), False

    if raw_tid < 384:
        slot = (raw_tid - 48) // 48
        pattern = (raw_tid - 48) % 48
        aimg = auto_imgs[slot] if slot < len(auto_imgs) else None
        if aimg is None:
            return Image.new("RGBA", (TILE_OUT, TILE_OUT), (0, 0, 0, 0)), True
        tile32 = compose_autotile_32(aimg, pattern)
        return tile32.resize((TILE_OUT, TILE_OUT), Image.NEAREST), False

    if ts_img is None:
        return Image.new("RGBA", (TILE_OUT, TILE_OUT), (0, 0, 0, 0)), True

    idx = raw_tid - 384
    sx = (idx % 8) * 32
    sy = (idx // 8) * 32
    w, h = ts_img.size
    if sx + 32 > w or sy + 32 > h:
        return Image.new("RGBA", (TILE_OUT, TILE_OUT), (0, 0, 0, 0)), True
    return ts_img.crop((sx, sy, sx + 32, sy + 32)).resize((TILE_OUT, TILE_OUT), Image.NEAREST), False


def scan_commands_for_assets(cmd_list, used_chars, used_pics, used_faces, tag_samples):
    for cmd in cmd_list or []:
        op = int(attr(cmd, "@code", 0))
        params = attr(cmd, "@parameters", []) or []
        if op in (101, 401) and params:
            txt = decode_str(params[0])
            for m in FACE_TAG_RE.finditer(txt):
                fname = m.group(1).strip()
                if fname and not fname.isdigit():
                    used_faces.add(fname)
            for m in ALL_TAG_RE.finditer(txt):
                tag_samples.add(m.group(0))
        elif op == 231 and len(params) > 1:
            pname = decode_str(params[1])
            if pname:
                used_pics.add(pname)
        elif op == 209 and len(params) > 1:
            route = params[1]
            for mc in attr(route, "@list", []) or []:
                if int(attr(mc, "@code", 0)) == 41:
                    mp = attr(mc, "@parameters", []) or []
                    if mp:
                        cname = decode_str(mp[0])
                        if cname:
                            used_chars.add(cname)


def scan_project_data(data_dir):
    with open(os.path.join(data_dir, "Tilesets.rxdata"), "rb") as f:
        raw_ts = load(f)
    tilesets = {}
    for ts in raw_ts:
        if ts is None:
            continue
        ts_id = int(attr(ts, "@id", 0))
        tilesets[ts_id] = {
            "name": decode_str(attr(ts, "@tileset_name", "")),
            "autotiles": [decode_str(x) for x in (attr(ts, "@autotile_names", []) or [])],
        }

    start_map, start_x, start_y = 1, 0, 0
    init_party = [1]
    gameover_name = ""
    sys_path = os.path.join(data_dir, "System.rxdata")
    if os.path.exists(sys_path):
        with open(sys_path, "rb") as f:
            sys_obj = load(f)
        start_map = int(attr(sys_obj, "@start_map_id", 1))
        start_x = int(attr(sys_obj, "@start_x", 0))
        start_y = int(attr(sys_obj, "@start_y", 0))
        gameover_name = decode_str(attr(sys_obj, "@gameover_name", ""))
        pm = attr(sys_obj, "@party_members", []) or []
        if pm:
            init_party = [int(x) for x in pm]

    actors = []  # (actor_id, name, char_name)
    used_chars = set()
    act_path = os.path.join(data_dir, "Actors.rxdata")
    if os.path.exists(act_path):
        with open(act_path, "rb") as f:
            for a in load(f):
                if a is None:
                    continue
                aid = int(attr(a, "@id", 0))
                aname = decode_str(attr(a, "@name", ""))
                achar = decode_str(attr(a, "@character_name", ""))
                if aid > 0:
                    actors.append((aid, aname, achar))
                    if achar:
                        used_chars.add(achar)

    items = []
    items_path = os.path.join(data_dir, "Items.rxdata")
    if os.path.exists(items_path):
        with open(items_path, "rb") as f:
            for it in load(f):
                if it is None:
                    continue
                iid = int(attr(it, "@id", 0))
                iname = decode_str(attr(it, "@name", ""))
                idesc = decode_str(attr(it, "@description", ""))
                ce_id = int(attr(it, "@common_event_id", 0)) & 0xFFFF
                consumable = 1 if attr(it, "@consumable", False) else 0
                occasion = int(attr(it, "@occasion", 0))
                menu_usable = 2 if occasion in (0, 2) else 0
                iflags = consumable | menu_usable
                if iid > 0 and (iname or idesc):
                    items.append((iid, ce_id, iflags, iname, idesc))

    used_tiles = {}
    used_pics = set()
    used_faces = set()
    tag_samples = set()

    ce_path = os.path.join(data_dir, "CommonEvents.rxdata")
    if os.path.exists(ce_path):
        with open(ce_path, "rb") as f:
            for ce in load(f):
                if ce is not None:
                    scan_commands_for_assets(attr(ce, "@list", []), used_chars, used_pics, used_faces, tag_samples)

    map_files = sorted(
        f for f in os.listdir(data_dir)
        if re.fullmatch(r"Map\d{3}\.rxdata", f, re.IGNORECASE)
    )
    for fname in map_files:
        with open(os.path.join(data_dir, fname), "rb") as f:
            rmap = load(f)
        ts_id = int(attr(rmap, "@tileset_id", 1))
        tset = used_tiles.setdefault(ts_id, set())

        _, _, _, tile_vals = unpack_table(attr(rmap, "@data"))
        for tid in tile_vals:
            if tid > 0:
                tset.add(int(tid))

        events = attr(rmap, "@events", {}) or {}
        for ev in events.values():
            for page in attr(ev, "@pages", []) or []:
                gfx = attr(page, "@graphic")
                cname = decode_str(attr(gfx, "@character_name", ""))
                gtid = int(attr(gfx, "@tile_id", 0))
                if cname:
                    used_chars.add(cname)
                if gtid > 0:
                    tset.add(gtid)
                scan_commands_for_assets(attr(page, "@list", []), used_chars, used_pics, used_faces, tag_samples)

    return {
        "tilesets": tilesets,
        "start_map": start_map,
        "start_x": start_x,
        "start_y": start_y,
        "init_party": init_party,
        "gameover_name": gameover_name,
        "actors": actors,
        "items": items,
        "used_tiles": used_tiles,
        "used_chars": sorted(used_chars),
        "used_pics": sorted(used_pics),
        "used_faces": sorted(used_faces),
        "tag_samples": sorted(tag_samples),
    }


def fit_box(img, box):
    w, h = img.size
    if w <= box and h <= box:
        return img
    scale = min(float(box) / w, float(box) / h)
    nw = max(1, int(round(w * scale)))
    nh = max(1, int(round(h * scale)))
    return img.resize((nw, nh), Image.NEAREST)


def make_fullscreen_240x320(img):
    """
    Produces a true 240x320 full-screen image.
    If the source has uniform corners (e.g. solid black/red Game Over backdrop around centered
    text or a face), pads vertically with that exact corner color so nothing is stretched or
    clipped. Otherwise stretches/fills edge-to-edge across 240x320.
    """
    rgba = img.convert("RGBA")
    w, h = rgba.size
    if w == 240 and h == 320:
        return rgba
    corners = (
        rgba.getpixel((0, 0)),
        rgba.getpixel((w - 1, 0)),
        rgba.getpixel((0, h - 1)),
        rgba.getpixel((w - 1, h - 1)),
    )
    opaque_corners = all(c[3] >= 200 for c in corners)
    uniform = opaque_corners and all(
        abs(c[0] - corners[0][0]) <= 16
        and abs(c[1] - corners[0][1]) <= 16
        and abs(c[2] - corners[0][2]) <= 16
        for c in corners[1:]
    )
    if uniform and (w > h):
        bg = (corners[0][0], corners[0][1], corners[0][2], 255)
        canvas = Image.new("RGBA", (240, 320), bg)
        nh = max(1, int(round(h * (240.0 / w))))
        if nh > 320:
            nh = 320
        resized = rgba.resize((240, nh), Image.NEAREST)
        canvas.paste(resized, (0, (320 - nh) >> 1), resized)
        return canvas
    return rgba.resize((240, 320), Image.NEAREST)


def bake_all(data_dir, gfx_roots, out_res_dir, max_colors=64):
    scan = scan_project_data(data_dir)
    gfx_out = os.path.join(out_res_dir, "gfx")
    os.makedirs(gfx_out, exist_ok=True)

    idx_tilesets = build_asset_index(gfx_roots, "Tilesets")
    idx_autotiles = build_asset_index(gfx_roots, "Autotiles")
    idx_chars = build_asset_index(gfx_roots, "Characters")
    idx_pics = build_asset_index(gfx_roots, "Pictures")
    idx_faces = build_asset_index(gfx_roots, ("Faces", "Pictures", "Characters", "Windowskins"))
    idx_gameovers = build_asset_index(gfx_roots, ("Gameovers", "Pictures"))

    missing = []
    total_png_bytes = 0
    max_ts_heap = 0

    # 1. Bake pruned 16x16 tileset atlases
    tileset_tables = []
    for ts_id in sorted(scan["used_tiles"].keys()):
        raw_tids = sorted(scan["used_tiles"][ts_id])
        if not raw_tids:
            continue
        ts_meta = scan["tilesets"].get(ts_id, {"name": "", "autotiles": []})
        ts_name = ts_meta["name"]
        ts_img = None
        ts_path = None
        if ts_name:
            ts_path = idx_tilesets.get(ts_name.lower())
            if ts_path:
                ts_img = open_rgba(ts_path)
            else:
                missing.append(f"Tilesets/{ts_name}")

        auto_imgs = []
        for aname in ts_meta["autotiles"]:
            if not aname:
                auto_imgs.append(None)
                continue
            apath = idx_autotiles.get(aname.lower())
            if apath:
                auto_imgs.append(open_rgba(apath))
            else:
                missing.append(f"Autotiles/{aname}")
                auto_imgs.append(None)

        slot_count = len(raw_tids) + 1
        rows = (slot_count + ATLAS_COLS - 1) // ATLAS_COLS
        atlas_w = ATLAS_COLS * TILE_OUT
        atlas_h = rows * TILE_OUT
        atlas = Image.new("RGBA", (atlas_w, atlas_h), (0, 0, 0, 0))

        oob_count = 0
        max_tid = max(raw_tids)
        for i, raw_tid in enumerate(raw_tids):
            slot = i + 1
            dx = (slot % ATLAS_COLS) * TILE_OUT
            dy = (slot // ATLAS_COLS) * TILE_OUT
            tile16, was_oob = extract_tile_16(raw_tid, ts_img, auto_imgs)
            if was_oob:
                oob_count += 1
            atlas.paste(tile16, (dx, dy))

        out_path = os.path.join(gfx_out, f"t{ts_id:02d}.png")
        fsize = save_indexed_png(atlas, out_path, max_colors=max_colors, alpha_cutoff=16)
        total_png_bytes += fsize
        heap_est = atlas_w * atlas_h * 2
        if heap_est > max_ts_heap:
            max_ts_heap = heap_est
        tileset_tables.append((ts_id, raw_tids))

        oob_note = ""
        if oob_count > 0:
            need_h = (((max_tid - 384) // 8) + 1) * 32 if max_tid >= 384 else 0
            src_sz = ts_img.size if ts_img else (0, 0)
            oob_note = f"  *** OOB={oob_count} tiles! src={ts_path} {src_sz}, need height>={need_h}px ***"
        print(
            f"  Tileset {ts_id:02d} ({ts_name or 'unnamed'}): "
            f"{len(raw_tids)} tiles -> {atlas_w}x{atlas_h} "
            f"({fsize:,} B, ~{heap_est // 1024} KB heap){oob_note}"
        )

    # 2. Bake character sheets (50% nearest-neighbor)
    char_map = {}
    for cname in scan["used_chars"]:
        cpath = idx_chars.get(cname.lower())
        if not cpath:
            missing.append(f"Characters/{cname}")
            continue
        cid = len(char_map)
        char_map[cname] = cid
        img = open_rgba(cpath)
        nw = max(4, img.size[0] // 2)
        nh = max(4, img.size[1] // 2)
        scaled = img.resize((nw, nh), Image.NEAREST)
        out_path = os.path.join(gfx_out, f"c{cid:03d}.png")
        total_png_bytes += save_indexed_png(scaled, out_path, max_colors=min(max_colors, 48), alpha_cutoff=16)

    # 3. Bake pictures (scaled to fit 240x320)
    pic_map = {}
    for pname in scan["used_pics"]:
        ppath = idx_pics.get(pname.lower())
        if not ppath:
            missing.append(f"Pictures/{pname}")
            continue
        pid = len(pic_map)
        pic_map[pname] = pid
        img = open_rgba(ppath)
        low_p = pname.lower()
        is_full_screen = (
            (img.size[0] >= 600 and img.size[1] >= 440)
            or "dead" in low_p
            or "gameover" in low_p
            or "black" in low_p
        )
        if is_full_screen:
            scaled = make_fullscreen_240x320(img)
        else:
            nw = max(1, img.size[0] // 2)
            nh = max(1, img.size[1] // 2)
            if nw > 240 or nh > 320:
                scale = min(240.0 / nw, 320.0 / nh)
                nw = max(1, int(round(nw * scale)))
                nh = max(1, int(round(nh * scale)))
            scaled = img.resize((nw, nh), Image.NEAREST)
        out_path = os.path.join(gfx_out, f"p{pid:03d}.png")
        total_png_bytes += save_indexed_png(scaled, out_path, max_colors=min(max_colors, 64), alpha_cutoff=16)

    # 4. Bake dialogue face portraits referenced via \f[...] tags (42x42 fit -> fXXX.png)
    face_map = {}
    for fname in scan["used_faces"]:
        stem = os.path.splitext(fname)[0].lower()
        fpath = idx_faces.get(stem)
        if not fpath:
            missing.append(f"Faces/Pictures/{fname}")
            continue
        fid = len(face_map)
        face_map[stem] = fid
        img = open_rgba(fpath)
        thumb = fit_box(img, FACE_OUT)
        out_path = os.path.join(gfx_out, f"f{fid:03d}.png")
        total_png_bytes += save_indexed_png(thumb, out_path, max_colors=min(max_colors, 48), alpha_cutoff=16)

    # 4b. Bake full-screen 240x320 Game Over graphic -> res/gfx/gameover.png
    go_name = scan.get("gameover_name", "")
    go_path = idx_gameovers.get(go_name.lower()) if go_name else None
    if not go_path:
        # Fallback: pick first file inside Graphics/Gameovers if System.rxdata left it blank
        for root in gfx_roots:
            gdir = os.path.join(root, "Gameovers")
            if os.path.isdir(gdir):
                for fn in sorted(os.listdir(gdir)):
                    if os.path.splitext(fn)[1].lower() in IMG_EXTS:
                        go_path = os.path.join(gdir, fn)
                        break
            if go_path:
                break
    if go_path:
        go_img = make_fullscreen_240x320(open_rgba(go_path))
        go_size = save_indexed_png(go_img, os.path.join(gfx_out, "gameover.png"), max_colors=min(max_colors, 64), alpha_cutoff=16)
        total_png_bytes += go_size
        print(f"  GameOver ({os.path.basename(go_path)}): 240x320 full screen ({go_size:,} B)")

    # 5. Write res/meta.bin (AOG3, Big-Endian)
    meta = bytearray()
    meta += struct.pack(
        ">4sHHH",
        b"AOG3",
        scan["start_map"] & 0xFFFF,
        scan["start_x"] & 0xFFFF,
        scan["start_y"] & 0xFFFF,
    )

    # Initial party order
    party = scan["init_party"][:16]
    meta += struct.pack(">B", len(party))
    for aid in party:
        meta += struct.pack(">H", aid & 0xFFFF)

    # All actors (id, charId or 0xFFFF, name)
    meta += struct.pack(">H", len(scan["actors"]))
    for aid, aname, achar in scan["actors"]:
        cid = char_map.get(achar, 0xFFFF) if achar else 0xFFFF
        meta += struct.pack(">HH", aid & 0xFFFF, cid & 0xFFFF)
        meta += pack_java_utf(aname)

    # Items (AOG3: id, commonEventId, flags[bit0=consumable, bit1=menuUsable], name, desc)
    meta += struct.pack(">H", len(scan["items"]))
    for iid, ce_id, iflags, iname, idesc in scan["items"]:
        meta += struct.pack(">HHB", iid & 0xFFFF, ce_id & 0xFFFF, iflags & 0xFF)
        meta += pack_java_utf(iname)
        meta += pack_java_utf(idesc)

    # Tileset rawTileId -> atlasSlot remap tables
    meta += struct.pack(">H", len(tileset_tables))
    for ts_id, raw_tids in tileset_tables:
        meta += struct.pack(">HHH", ts_id & 0xFFFF, ATLAS_COLS, len(raw_tids))
        for raw_tid in raw_tids:
            meta += struct.pack(">H", raw_tid & 0xFFFF)

    # Characters
    meta += struct.pack(">H", len(char_map))
    for cname, cid in char_map.items():
        meta += struct.pack(">H", cid & 0xFFFF)
        meta += pack_java_utf(cname)

    # Pictures
    meta += struct.pack(">H", len(pic_map))
    for pname, pid in pic_map.items():
        meta += struct.pack(">H", pid & 0xFFFF)
        meta += pack_java_utf(pname)

    # Faces
    meta += struct.pack(">H", len(face_map))
    for fstem, fid in face_map.items():
        meta += struct.pack(">H", fid & 0xFFFF)
        meta += pack_java_utf(fstem)

    meta_path = os.path.join(out_res_dir, "meta.bin")
    with open(meta_path, "wb") as f:
        f.write(meta)

    print(
        f"\nBaked {len(tileset_tables)} tilesets, {len(char_map)} chars, "
        f"{len(pic_map)} pics, {len(face_map)} faces -> {gfx_out}"
    )
    print(f"Wrote {meta_path} ({len(meta):,} B, AOG3) | Start: Map {scan['start_map']} ({scan['start_x']}, {scan['start_y']})")
    ce_items = [(iid, iname, ce_id) for iid, ce_id, _, iname, _ in scan["items"] if ce_id > 0]
    print(f"Items exported: {len(scan['items'])} total ({len(ce_items)} wired to CommonEvents)")
    print(f"Total baked graphics: {total_png_bytes:,} B | Peak tileset heap: ~{max_ts_heap // 1024} KB")
    if scan["tag_samples"]:
        print(f"Script escape tags found ({len(scan['tag_samples'])}): {scan['tag_samples'][:25]}")
    if missing:
        unique_missing = sorted(set(missing))
        print(f"\nWARNING: {len(unique_missing)} missing graphics:")
        for m in unique_missing:
            print("  MISSING ->", m)


def main():
    args = sys.argv[1:]
    rtp_dir = None
    max_colors = 64
    positional = []

    i = 0
    while i < len(args):
        if args[i] == "--rtp" and i + 1 < len(args):
            rtp_dir = args[i + 1]
            i += 2
        elif args[i] == "--colors" and i + 1 < len(args):
            max_colors = int(args[i + 1])
            i += 2
        else:
            positional.append(args[i])
            i += 1

    if len(positional) < 3:
        print("Usage: python bake_assets.py <Data_dir> <Graphics_dir> <out_res_dir> [--rtp <RTP_Graphics_dir>] [--colors 64]")
        sys.exit(1)

    data_dir, gfx_dir, out_res_dir = positional[0], positional[1], positional[2]
    gfx_roots = [gfx_dir]
    if rtp_dir:
        gfx_roots.append(rtp_dir)

    bake_all(data_dir, gfx_roots, out_res_dir, max_colors=max_colors)


if __name__ == "__main__":
    main()
