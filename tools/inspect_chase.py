import os, re
from rubymarshal.reader import load

DATA = r"H:\Ao Oni J2ME\res\Data"

def attr(o, k, d=None):
    if o is None or not hasattr(o, "attributes"): return d
    a = o.attributes
    return a.get(k, a.get(k.encode(), d))

def dstr(v):
    if not v: return ""
    if isinstance(v, str): return v
    if isinstance(v, (bytes, bytearray)):
        try: return v.decode("utf-8")
        except: return v.decode("cp932", errors="replace")
    return str(v)

def fmt_cmd(c):
    code = int(attr(c, "@code", 0))
    ps = attr(c, "@parameters", []) or []
    out = []
    for p in ps[:5]:
        if isinstance(p, (bytes, bytearray)):
            out.append(repr(dstr(p)))
        elif hasattr(p, "attributes"):
            nm = attr(p, "@name", None)
            out.append(f"Obj({dstr(nm)!r})" if nm is not None else "Obj(...)")
        else:
            out.append(repr(p))
    return f"{code}({', '.join(out)})"

map_names = {}
mi_path = os.path.join(DATA, "MapInfos.rxdata")
if os.path.exists(mi_path):
    with open(mi_path, "rb") as f:
        for mid, info in (load(f) or {}).items():
            map_names[int(mid)] = dstr(attr(info, "@name", ""))

print("=== COMMON EVENTS (GameOver / Title / Chase / Death) ===")
with open(os.path.join(DATA, "CommonEvents.rxdata"), "rb") as f:
    for ce in load(f) or []:
        if ce is None: continue
        cid = int(attr(ce, "@id", 0))
        cname = dstr(attr(ce, "@name", ""))
        tr = int(attr(ce, "@trigger", 0))
        sw = int(attr(ce, "@switch_id", 0))
        cmds = [c for c in (attr(ce, "@list", []) or []) if int(attr(c, "@code", 0)) != 0]
        codes = {int(attr(c, "@code", 0)) for c in cmds}
        if (353 in codes or 354 in codes or tr in (1, 2)
                or any(k in cname.lower() for k in ("ao", "oni", "dead", "over", "death", "chase", "鬼", "死"))):
            print(f"\nCE {cid:02d} {cname!r} (tr={tr}, sw={sw}, {len(cmds)} cmds):")
            for c in cmds[:35]:
                print("  " + fmt_cmd(c))

print("\n=== LIBRARY & EARLY AO ONI MAPS ===")
shown = 0
for fn in sorted(os.listdir(DATA)):
    if not re.fullmatch(r"Map\d{3}\.rxdata", fn, re.I): continue
    mid = int(fn[3:6])
    mname = map_names.get(mid, "")
    with open(os.path.join(DATA, fn), "rb") as f:
        m = load(f)
    evs = attr(m, "@events", {}) or {}
    ao_evs = []
    for eid, ev in sorted(evs.items()):
        for pg in attr(ev, "@pages", []) or []:
            ch = dstr(attr(attr(pg, "@graphic"), "@character_name", "")).lower()
            if ch == "ao" or ch.startswith("ao_"):
                ao_evs.append(eid)
                break
    is_lib = any(k in mname.lower() for k in ("lib", "書", "図", "book"))
    if not is_lib and not (ao_evs and shown < 4):
        continue
    if ao_evs:
        shown += 1
    print(f"\n--- Map {mid:03d} ({mname!r}) | total_events={len(evs)} | ao_evs={ao_evs} ---")
    for eid in sorted(evs.keys()):
        ev = evs[eid]
        pages = attr(ev, "@pages", []) or []
        is_interesting = (eid in ao_evs) or any(int(attr(pg, "@trigger", 0)) == 4 for pg in pages)
        if not is_interesting:
            continue
        print(f"  EV {eid:02d} @({attr(ev,'@x')},{attr(ev,'@y')}) name={dstr(attr(ev,'@name'))!r}:")
        for pi, pg in enumerate(pages):
            g = attr(pg, "@graphic")
            cond = attr(pg, "@condition")
            cf = []
            if attr(cond, "@switch1_valid"): cf.append(f"sw{attr(cond,'@switch1_id')}")
            if attr(cond, "@switch2_valid"): cf.append(f"sw{attr(cond,'@switch2_id')}")
            if attr(cond, "@variable_valid"): cf.append(f"v{attr(cond,'@variable_id')}>={attr(cond,'@variable_value')}")
            if attr(cond, "@self_switch_valid"): cf.append(f"self{dstr(attr(cond,'@self_switch_ch'))}")
            cmds = [c for c in (attr(pg, "@list", []) or []) if int(attr(c, "@code", 0)) != 0]
            print(f"    p{pi}: cond={cf} tr={attr(pg,'@trigger')} thru={attr(pg,'@through')} "
                  f"mt={attr(pg,'@move_type')} spd={attr(pg,'@move_speed')} frq={attr(pg,'@move_frequency')} "
                  f"ch={dstr(attr(g,'@character_name'))!r} cmds={len(cmds)}")
            for c in cmds[:20]:
                print("      " + fmt_cmd(c))