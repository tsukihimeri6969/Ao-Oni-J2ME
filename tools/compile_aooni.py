import ast
import os
import re
import sys
import zlib
import struct
from collections import Counter
from rubymarshal.reader import load

# Bytecode opcodes
OP_END           = 0x00
OP_MSG           = 0x01
OP_CHOICE        = 0x02
OP_JMP           = 0x03
OP_BRANCH_FALSE  = 0x04
OP_SET_SWITCH    = 0x05
OP_SET_VAR       = 0x06
OP_SET_SELF_SW   = 0x07
OP_CHANGE_ITEM   = 0x08
OP_TRANSFER      = 0x09
OP_WAIT          = 0x0A
OP_MOVE_EVENT    = 0x0B
OP_WAIT_MOVE     = 0x0C
OP_AUDIO         = 0x0D
OP_SCREEN_FX     = 0x0E
OP_ERASE_EVENT   = 0x0F
OP_CALL_COMMON   = 0x10
OP_GAME_OVER     = 0x11
OP_PICTURE       = 0x12
OP_EXIT_EVENT    = 0x13
OP_SCRIPT_HOOK   = 0x14
OP_SET_EVENT_LOC = 0x15  # 202: h eventId, B mode, H x, H y, B dir
OP_TIMER         = 0x16  # 124: B op (0=start, 1=stop), H seconds
OP_TRANSPARENT   = 0x17  # 208: B flag (0=transparent, 1=normal)
OP_MENU_ACCESS   = 0x18  # 135: B flag (0=disable, 1=enable)
OP_INPUT_NUM     = 0x19  # 103: H varId, B digits
OP_BUTTON_INPUT  = 0x1A  # 105: H varId
OP_MOVE_PICTURE  = 0x1B  # 232: B picId, H dur, B flags, h x, h y, B opacity
OP_TEXT_OPT      = 0x1C  # 104: B pos, B frame
OP_PARTY         = 0x1D  # 129: H actorId, B op (0=add, 1=remove)
OP_RETURN_TITLE  = 0x1E  # 354: none
OP_BRANCH_EXPR   = 0x1F  # 111-12 compiled RPN: B exprLen, <exprLen bytes>, H elsePc
OP_NAME_INPUT    = 0x20  # 303: H actorId, B maxChars
OP_SET_ACTOR_NAME = 0x21 # 320: H actorId, H strId

# RPN micro-opcodes for OP_BRANCH_EXPR
EXPR_LIT  = 0x01  # h val
EXPR_PROP = 0x02  # h targetId (-1=player, 0=self, >0=event), B prop (0=x, 1=y, 2=dir)
EXPR_SW   = 0x03  # H switchId
EXPR_VAR  = 0x04  # H varId
EXPR_EQ   = 0x10
EXPR_NE   = 0x11
EXPR_LT   = 0x12
EXPR_LE   = 0x13
EXPR_GT   = 0x14
EXPR_GE   = 0x15
EXPR_ADD  = 0x18
EXPR_SUB  = 0x19
EXPR_AND  = 0x20
EXPR_OR   = 0x21
EXPR_NOT  = 0x22

SELF_SW_MAP = {"A": 0, "B": 1, "C": 2, "D": 3}

CMP_MAP = {
    ast.Eq: EXPR_EQ,
    ast.NotEq: EXPR_NE,
    ast.Lt: EXPR_LT,
    ast.LtE: EXPR_LE,
    ast.Gt: EXPR_GT,
    ast.GtE: EXPR_GE,
}
PROP_MAP = {"x": 0, "y": 1, "direction": 2}


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


def clamp_i16(v):
    return max(-32768, min(32767, int(v)))


def clamp_u16(v):
    return max(0, min(65535, int(v)))


def pack_java_utf(text):
    raw = text.encode("utf-8")
    if len(raw) > 65535:
        raw = raw[:65535]
    return struct.pack(">H", len(raw)) + raw


class StringPool:
    def __init__(self):
        self.strings = []
        self.index = {}

    def intern(self, text):
        s = decode_str(text)
        if not s:
            return 0xFFFF
        idx = self.index.get(s)
        if idx is None:
            idx = len(self.strings)
            self.strings.append(s)
            self.index[s] = idx
        return idx

    def to_bytes(self):
        out = bytearray()
        out += struct.pack(">H", len(self.strings))
        for s in self.strings:
            out += pack_java_utf(s)
        return bytes(out)


def load_tilesets(data_dir):
    path = os.path.join(data_dir, "Tilesets.rxdata")
    with open(path, "rb") as f:
        raw_tilesets = load(f)

    tilesets = {}
    for ts in raw_tilesets:
        if ts is None:
            continue
        ts_id = int(attr(ts, "@id", 0))
        _, _, _, passages = unpack_table(attr(ts, "@passages"))
        _, _, _, priorities = unpack_table(attr(ts, "@priorities"))
        tilesets[ts_id] = {
            "name": decode_str(attr(ts, "@tileset_name", "")),
            "passages": passages,
            "priorities": priorities,
        }
    return tilesets


def bake_collision_and_layers(w, h, tile_vals, ts_info):
    passages = ts_info["passages"] if ts_info else ()
    priorities = ts_info["priorities"] if ts_info else ()
    p_len = len(passages)
    pr_len = len(priorities)

    plane = w * h
    collision = bytearray(plane)
    layers = [bytearray(plane * 2) for _ in range(3)]

    for y in range(h):
        for x in range(w):
            cell = y * w + x
            t0 = tile_vals[cell] if cell < len(tile_vals) else 0
            t1 = tile_vals[cell + plane] if (cell + plane) < len(tile_vals) else 0
            t2 = tile_vals[cell + plane * 2] if (cell + plane * 2) < len(tile_vals) else 0
            stack = (t0, t1, t2)

            block_mask = 0
            for dir_bit in (1, 2, 4, 8):
                for z in (2, 1, 0):
                    tid = stack[z]
                    if tid <= 0:
                        continue
                    p = passages[tid] if tid < p_len else 0
                    pr = priorities[tid] if tid < pr_len else 0
                    if (p & dir_bit) != 0 or (p & 0x0F) == 0x0F:
                        block_mask |= dir_bit
                        break
                    if pr == 0:
                        break
            collision[cell] = block_mask

            for z in range(3):
                tid = stack[z]
                if tid < 0:
                    tid = 0
                pr = priorities[tid] if tid < pr_len else 0
                packed = (tid & 0x7FFF) | (0x8000 if (tid > 0 and pr > 0) else 0)
                struct.pack_into(">H", layers[z], cell * 2, packed)

    return bytes(collision), b"".join(layers)


def patch_u16(buf, pos, value):
    struct.pack_into(">H", buf, pos, clamp_u16(value))


def pack_move_route_cmds(route, pool):
    if route is None:
        return 0, b""
    r_flags = (1 if attr(route, "@repeat", False) else 0) | (2 if attr(route, "@skippable", False) else 0)
    r_cmds = [c for c in (attr(route, "@list", []) or []) if int(attr(c, "@code", 0)) != 0]
    r_cmds = r_cmds[:255]
    buf = bytearray()
    buf += struct.pack(">B", len(r_cmds))
    for mc in r_cmds:
        mcode = int(attr(mc, "@code", 0))
        mp = attr(mc, "@parameters", []) or []
        if mcode == 41:  # Change Graphic: mp[0] is character_name string
            sid = pool.intern(mp[0]) if mp else 0xFFFF
            marg = -1 if sid == 0xFFFF else clamp_i16(sid)
        elif mp and isinstance(mp[0], int):
            marg = clamp_i16(mp[0])
        else:
            marg = 0
        buf += struct.pack(">Bh", mcode & 0xFF, marg)
    return r_flags, bytes(buf)


def extract_ast_int(node):
    if isinstance(node, ast.Constant) and isinstance(node.value, (int, bool)):
        return int(node.value)
    if isinstance(node, ast.UnaryOp) and isinstance(node.op, ast.USub):
        if isinstance(node.operand, ast.Constant) and isinstance(node.operand.value, int):
            return -int(node.operand.value)
    raise ValueError("expected integer literal")


def compile_ruby_expr(raw_expr):
    s = raw_expr.strip().replace("\r", "").replace("\n", " ")
    s = re.sub(r"\$game_map\.events\[\s*@event_id\s*\]", "_ev(0)", s)
    s = re.sub(r"\$game_map\.events\[\s*(\d+)\s*\]", r"_ev(\1)", s)
    s = s.replace("$game_player", "_ev(-1)")
    s = re.sub(r"\$game_switches\[\s*(\d+)\s*\]", r"_sw(\1)", s)
    s = re.sub(r"\$game_variables\[\s*(\d+)\s*\]", r"_var(\1)", s)
    s = s.replace("&&", " and ").replace("||", " or ")
    s = re.sub(r"!(?!=)", " not ", s)
    s = re.sub(r"\btrue\b", "True", s)
    s = re.sub(r"\bfalse\b", "False", s)

    tree = ast.parse(s, mode="eval").body
    out = bytearray()

    def emit(node):
        if isinstance(node, ast.Constant) and isinstance(node.value, (int, bool)):
            out.extend(struct.pack(">Bh", EXPR_LIT, clamp_i16(int(node.value))))
        elif isinstance(node, ast.UnaryOp) and isinstance(node.op, ast.USub):
            out.extend(struct.pack(">Bh", EXPR_LIT, clamp_i16(extract_ast_int(node))))
        elif isinstance(node, ast.UnaryOp) and isinstance(node.op, ast.Not):
            emit(node.operand)
            out.append(EXPR_NOT)
        elif isinstance(node, ast.Attribute):
            if (isinstance(node.value, ast.Call)
                    and isinstance(node.value.func, ast.Name)
                    and node.value.func.id == "_ev"
                    and node.attr in PROP_MAP):
                ev_id = extract_ast_int(node.value.args[0])
                out.extend(struct.pack(">BhB", EXPR_PROP, clamp_i16(ev_id), PROP_MAP[node.attr]))
            else:
                raise ValueError("unsupported attr")
        elif isinstance(node, ast.Call) and isinstance(node.func, ast.Name):
            fname = node.func.id
            idx = extract_ast_int(node.args[0])
            if fname == "_sw":
                out.extend(struct.pack(">BH", EXPR_SW, clamp_u16(idx)))
            elif fname == "_var":
                out.extend(struct.pack(">BH", EXPR_VAR, clamp_u16(idx)))
            else:
                raise ValueError("unsupported call")
        elif isinstance(node, ast.BinOp) and isinstance(node.op, (ast.Add, ast.Sub)):
            emit(node.left)
            emit(node.right)
            out.append(EXPR_ADD if isinstance(node.op, ast.Add) else EXPR_SUB)
        elif isinstance(node, ast.Compare):
            lhs = node.left
            for idx, (op_node, rhs) in enumerate(zip(node.ops, node.comparators)):
                op_byte = CMP_MAP.get(type(op_node))
                if op_byte is None:
                    raise ValueError("unsupported cmp")
                emit(lhs)
                emit(rhs)
                out.append(op_byte)
                if idx > 0:
                    out.append(EXPR_AND)
                lhs = rhs
        elif isinstance(node, ast.BoolOp) and isinstance(node.op, (ast.And, ast.Or)):
            bop = EXPR_AND if isinstance(node.op, ast.And) else EXPR_OR
            emit(node.values[0])
            for val in node.values[1:]:
                emit(val)
                out.append(bop)
        else:
            raise ValueError(f"unsupported node: {type(node).__name__}")

    emit(tree)
    if len(out) > 255:
        raise ValueError("expr too long")
    return bytes(out)


def compile_commands(cmd_list, pool, stats):
    code = bytearray()
    stack = []
    n = len(cmd_list)
    i = 0

    while i < n:
        cmd = cmd_list[i]
        op = int(attr(cmd, "@code", 0))
        indent = int(attr(cmd, "@indent", 0))
        params = attr(cmd, "@parameters", []) or []

        if op == 0:
            i += 1
            continue

        if op == 101:
            lines = [decode_str(params[0]) if params else ""]
            j = i + 1
            while j < n and int(attr(cmd_list[j], "@code", 0)) == 401:
                p401 = attr(cmd_list[j], "@parameters", []) or []
                lines.append(decode_str(p401[0]) if p401 else "")
                j += 1
            str_id = pool.intern("\n".join(lines))
            code += struct.pack(">BH", OP_MSG, str_id)
            i = j
            continue

        if op == 102:
            choices = params[0] if len(params) > 0 else []
            cancel_type = int(params[1]) if len(params) > 1 else 0
            code += struct.pack(">BBB", OP_CHOICE, len(choices), cancel_type)
            targets = {}
            for idx, ch_text in enumerate(choices):
                str_id = pool.intern(ch_text)
                code += struct.pack(">H", str_id)
                targets[idx] = len(code)
                code += struct.pack(">H", 0)
            if cancel_type == 5:
                targets["cancel"] = len(code)
                code += struct.pack(">H", 0)
            stack.append({
                "type": "choice",
                "indent": indent,
                "targets": targets,
                "end_patches": [],
                "in_branch": False,
            })
            i += 1
            continue

        if op == 402:
            choice_idx = int(params[0]) if params else 0
            if stack and stack[-1]["type"] == "choice" and stack[-1]["indent"] == indent:
                frame = stack[-1]
                if frame["in_branch"]:
                    code += struct.pack(">B", OP_JMP)
                    frame["end_patches"].append(len(code))
                    code += struct.pack(">H", 0)
                if choice_idx in frame["targets"]:
                    patch_u16(code, frame["targets"].pop(choice_idx), len(code))
                frame["in_branch"] = True
            i += 1
            continue

        if op == 403:
            if stack and stack[-1]["type"] == "choice" and stack[-1]["indent"] == indent:
                frame = stack[-1]
                if frame["in_branch"]:
                    code += struct.pack(">B", OP_JMP)
                    frame["end_patches"].append(len(code))
                    code += struct.pack(">H", 0)
                if "cancel" in frame["targets"]:
                    patch_u16(code, frame["targets"].pop("cancel"), len(code))
                frame["in_branch"] = True
            i += 1
            continue

        if op == 404:
            if stack and stack[-1]["type"] == "choice" and stack[-1]["indent"] == indent:
                frame = stack.pop()
                for pos in frame["targets"].values():
                    patch_u16(code, pos, len(code))
                for pos in frame["end_patches"]:
                    patch_u16(code, pos, len(code))
            i += 1
            continue

        if op == 111:
            ctype = int(params[0]) if params else -1

            if ctype == 12:
                script_text = decode_str(params[1])
                try:
                    expr_bytes = compile_ruby_expr(script_text)
                    stats["rpn_compiled"] += 1
                    code += struct.pack(">BB", OP_BRANCH_EXPR, len(expr_bytes))
                    code += expr_bytes
                    false_patch = len(code)
                    code += struct.pack(">H", 0)
                    stack.append({
                        "type": "if",
                        "indent": indent,
                        "false_patch": false_patch,
                        "end_patches": [],
                    })
                    i += 1
                    continue
                except Exception:
                    pass

            kind, arg1, arg2, cmp_op = 0xFF, 0, 0, 0
            if ctype == 0:
                kind, arg1, arg2 = 0, clamp_u16(params[1]), int(params[2])
            elif ctype == 1:
                kind = 1
                arg1 = clamp_u16(params[1])
                is_var_rhs = 1 if int(params[2]) == 1 else 0
                arg2 = clamp_i16(params[3])
                cmp_op = (int(params[4]) & 0x0F) | (0x80 if is_var_rhs else 0)
            elif ctype == 2:
                kind = 2
                arg1 = SELF_SW_MAP.get(decode_str(params[1]), 0)
                arg2 = int(params[2])
            elif ctype == 3:
                kind = 6
                arg1 = clamp_u16(params[1])
                cmp_op = int(params[2]) & 0xFF
            elif ctype == 4:
                kind = 7
                arg1 = clamp_u16(params[1])
                cmp_op = int(params[2]) & 0xFF
                if cmp_op == 1 and len(params) > 3:
                    sid = pool.intern(params[3])
                    arg2 = -1 if sid == 0xFFFF else clamp_i16(sid)
                elif len(params) > 3 and isinstance(params[3], int):
                    arg2 = clamp_i16(params[3])
            elif ctype == 6:
                kind = 4
                arg1 = clamp_u16(int(params[1]) & 0xFFFF)
                arg2 = int(params[2])
            elif ctype == 8:
                kind = 3
                arg1 = clamp_u16(params[1])
            elif ctype == 12:
                kind = 5
                script_text = decode_str(params[1])
                arg1 = pool.intern(script_text)
                stats["scripts"].append(script_text)
            else:
                stats["unhandled_cond"][ctype] += 1

            code += struct.pack(">BBHhB", OP_BRANCH_FALSE, kind, arg1, arg2, cmp_op)
            false_patch = len(code)
            code += struct.pack(">H", 0)
            stack.append({
                "type": "if",
                "indent": indent,
                "false_patch": false_patch,
                "end_patches": [],
            })
            i += 1
            continue

        if op == 411:
            if stack and stack[-1]["type"] == "if" and stack[-1]["indent"] == indent:
                frame = stack[-1]
                code += struct.pack(">B", OP_JMP)
                frame["end_patches"].append(len(code))
                code += struct.pack(">H", 0)
                if frame["false_patch"] is not None:
                    patch_u16(code, frame["false_patch"], len(code))
                    frame["false_patch"] = None
            i += 1
            continue

        if op == 412:
            if stack and stack[-1]["type"] == "if" and stack[-1]["indent"] == indent:
                frame = stack.pop()
                if frame["false_patch"] is not None:
                    patch_u16(code, frame["false_patch"], len(code))
                for pos in frame["end_patches"]:
                    patch_u16(code, pos, len(code))
            i += 1
            continue

        if op == 112:
            stack.append({
                "type": "loop",
                "indent": indent,
                "start_pc": len(code),
                "break_patches": [],
            })
            i += 1
            continue

        if op == 113:
            for frame in reversed(stack):
                if frame["type"] == "loop":
                    code += struct.pack(">B", OP_JMP)
                    frame["break_patches"].append(len(code))
                    code += struct.pack(">H", 0)
                    break
            i += 1
            continue

        if op == 413:
            if stack and stack[-1]["type"] == "loop" and stack[-1]["indent"] == indent:
                frame = stack.pop()
                code += struct.pack(">BH", OP_JMP, frame["start_pc"])
                for pos in frame["break_patches"]:
                    patch_u16(code, pos, len(code))
            i += 1
            continue

        if op == 103:
            code += struct.pack(">BHB", OP_INPUT_NUM, clamp_u16(params[0]), int(params[1]) & 0xFF)
        elif op == 104:
            code += struct.pack(">BBB", OP_TEXT_OPT, int(params[0]) & 0xFF, int(params[1]) & 0xFF)
        elif op == 105:
            code += struct.pack(">BH", OP_BUTTON_INPUT, clamp_u16(params[0]))
        elif op == 106:
            code += struct.pack(">BH", OP_WAIT, clamp_u16(params[0]))
        elif op == 115:
            code += struct.pack(">B", OP_EXIT_EVENT)
        elif op == 116:
            code += struct.pack(">B", OP_ERASE_EVENT)
        elif op == 117:
            code += struct.pack(">BH", OP_CALL_COMMON, clamp_u16(params[0]))
        elif op == 121:
            code += struct.pack(">BHHB", OP_SET_SWITCH,
                                clamp_u16(params[0]), clamp_u16(params[1]), int(params[2]))
        elif op == 122:
            s_id = clamp_u16(params[0])
            e_id = clamp_u16(params[1])
            v_op = int(params[2])
            otype = int(params[3])
            p1 = clamp_i16(params[4]) if len(params) > 4 and isinstance(params[4], int) else 0
            p2 = clamp_i16(params[5]) if len(params) > 5 and isinstance(params[5], int) else 0
            code += struct.pack(">BHHBBhh", OP_SET_VAR, s_id, e_id, v_op, otype, p1, p2)
        elif op == 123:
            ch = SELF_SW_MAP.get(decode_str(params[0]), 0)
            code += struct.pack(">BBB", OP_SET_SELF_SW, ch, int(params[1]))
        elif op == 124:
            t_op = int(params[0]) & 0xFF
            sec = clamp_u16(params[1]) if (t_op == 0 and len(params) > 1) else 0
            code += struct.pack(">BBH", OP_TIMER, t_op, sec)
        elif op == 126:
            item_id = clamp_u16(params[0])
            i_op = int(params[1])
            qty = int(params[3]) if len(params) > 3 else 1
            code += struct.pack(">BHBB", OP_CHANGE_ITEM, item_id, i_op, min(255, max(0, qty)))
        elif op == 129:
            code += struct.pack(">BHB", OP_PARTY, clamp_u16(params[0]), int(params[1]) & 0xFF)
        elif op == 135:
            code += struct.pack(">BB", OP_MENU_ACCESS, int(params[0]) & 0xFF)
        elif op == 201:
            var_mode = 0x80 if int(params[0]) == 1 else 0
            t_map = clamp_u16(params[1])
            t_x = clamp_u16(params[2])
            t_y = clamp_u16(params[3])
            t_dir = (int(params[4]) & 0x0F) | var_mode
            code += struct.pack(">BHHHB", OP_TRANSFER, t_map, t_x, t_y, t_dir)
        elif op == 202:
            ev_t = clamp_i16(params[0])
            mode = int(params[1]) & 0xFF
            lx = clamp_u16(params[2]) if len(params) > 2 else 0
            ly = clamp_u16(params[3]) if len(params) > 3 else 0
            ldir = int(params[4]) & 0xFF if len(params) > 4 else 0
            code += struct.pack(">BhBHHB", OP_SET_EVENT_LOC, ev_t, mode, lx, ly, ldir)
        elif op == 203:
            code += struct.pack(">BBhh", OP_SCREEN_FX, 6, clamp_i16(params[0]), clamp_i16(params[1]))
        elif op == 208:
            code += struct.pack(">BB", OP_TRANSPARENT, int(params[0]) & 0xFF)
        elif op == 209:
            target = clamp_i16(params[0])
            r_flags, r_blob = pack_move_route_cmds(params[1], pool)
            code += struct.pack(">BhB", OP_MOVE_EVENT, target, r_flags) + r_blob
        elif op == 210:
            code += struct.pack(">B", OP_WAIT_MOVE)
        elif op == 221:
            code += struct.pack(">BBhh", OP_SCREEN_FX, 0, 0, 0)
        elif op == 222:
            code += struct.pack(">BBhh", OP_SCREEN_FX, 1, 0, 0)
        elif op == 223:
            dur = clamp_i16(params[1]) if len(params) > 1 else 0
            code += struct.pack(">BBhh", OP_SCREEN_FX, 4, dur, 0)
        elif op == 224:
            dur = clamp_i16(params[1]) if len(params) > 1 else 0
            code += struct.pack(">BBhh", OP_SCREEN_FX, 2, dur, 0)
        elif op == 225:
            power = clamp_i16(params[0]) if len(params) > 0 else 5
            dur = clamp_i16(params[2]) if len(params) > 2 else 20
            code += struct.pack(">BBhh", OP_SCREEN_FX, 3, power, dur)
        elif op == 231:
            pic_id = int(params[0]) & 0xFF
            p_str = pool.intern(params[1])
            px = clamp_i16(params[4]) if len(params) > 4 and isinstance(params[4], int) else 0
            py = clamp_i16(params[5]) if len(params) > 5 and isinstance(params[5], int) else 0
            code += struct.pack(">BBHhh", OP_PICTURE, pic_id, p_str, px, py)
        elif op == 232:
            pic_id = int(params[0]) & 0xFF
            dur = clamp_u16(params[1])
            flags = 1 if (len(params) > 3 and int(params[3]) == 1) else 0
            px = clamp_i16(params[4]) if len(params) > 4 and isinstance(params[4], int) else 0
            py = clamp_i16(params[5]) if len(params) > 5 and isinstance(params[5], int) else 0
            opac = int(params[8]) & 0xFF if len(params) > 8 else 255
            code += struct.pack(">BBHBhhB", OP_MOVE_PICTURE, pic_id, dur, flags, px, py, opac)
        elif op == 235:
            pic_id = int(params[0]) & 0xFF
            code += struct.pack(">BBHhh", OP_PICTURE, pic_id, 0xFFFF, 0, 0)
        elif op == 236:
            code += struct.pack(">BBhh", OP_SCREEN_FX, 5, clamp_i16(params[0]), clamp_i16(params[1]))
        elif op == 241:
            af = params[0]
            name_id = pool.intern(attr(af, "@name", ""))
            vol = int(attr(af, "@volume", 100)) & 0xFF
            code += struct.pack(">BBHB", OP_AUDIO, 0, name_id, vol)
        elif op == 242:
            code += struct.pack(">BBHB", OP_AUDIO, 2, 0xFFFF, 0)
        elif op == 245:
            af = params[0]
            name_id = pool.intern(attr(af, "@name", ""))
            vol = int(attr(af, "@volume", 80)) & 0xFF
            code += struct.pack(">BBHB", OP_AUDIO, 3, name_id, vol)
        elif op == 246:
            code += struct.pack(">BBHB", OP_AUDIO, 4, 0xFFFF, 0)
        elif op == 250:
            af = params[0]
            name_id = pool.intern(attr(af, "@name", ""))
            vol = int(attr(af, "@volume", 100)) & 0xFF
            code += struct.pack(">BBHB", OP_AUDIO, 1, name_id, vol)
        elif op == 303:
            actor_id = clamp_u16(params[0]) if len(params) > 0 else 2
            max_chars = int(params[1]) if len(params) > 1 else 10
            if max_chars < 1: max_chars = 1
            if max_chars > 12: max_chars = 12
            code += struct.pack(">BHB", OP_NAME_INPUT, actor_id, max_chars & 0xFF)
        elif op == 320:
            actor_id = clamp_u16(params[0]) if len(params) > 0 else 2
            name_sid = pool.intern(params[1]) if len(params) > 1 else 0xFFFF
            code += struct.pack(">BHH", OP_SET_ACTOR_NAME, actor_id, name_sid)
        elif op == 353:
            code += struct.pack(">B", OP_GAME_OVER)
        elif op == 354:
            code += struct.pack(">B", OP_RETURN_TITLE)
        elif op == 355:
            lines = [decode_str(params[0]) if params else ""]
            j = i + 1
            while j < n and int(attr(cmd_list[j], "@code", 0)) == 655:
                p655 = attr(cmd_list[j], "@parameters", []) or []
                lines.append(decode_str(p655[0]) if p655 else "")
                j += 1
            script_text = "\n".join(lines)
            str_id = pool.intern(script_text)
            stats["scripts"].append(script_text)
            code += struct.pack(">BH", OP_SCRIPT_HOOK, str_id)
            i = j
            continue
        elif op in (108, 408, 509, 207, 233, 247, 248, 251):
            pass
        else:
            stats["unhandled_ops"][op] += 1

        i += 1

    while stack:
        frame = stack.pop()
        if frame["type"] == "if":
            if frame["false_patch"] is not None:
                patch_u16(code, frame["false_patch"], len(code))
            for pos in frame["end_patches"]:
                patch_u16(code, pos, len(code))
        elif frame["type"] == "choice":
            for pos in frame["targets"].values():
                patch_u16(code, pos, len(code))
            for pos in frame["end_patches"]:
                patch_u16(code, pos, len(code))
        elif frame["type"] == "loop":
            for pos in frame["break_patches"]:
                patch_u16(code, pos, len(code))

    code += struct.pack(">B", OP_END)
    return bytes(code)


def compile_map(rxdata_path, map_id, tilesets, stats):
    with open(rxdata_path, "rb") as f:
        rmap = load(f)

    w = int(attr(rmap, "@width", 0))
    h = int(attr(rmap, "@height", 0))
    ts_id = int(attr(rmap, "@tileset_id", 1))
    ts_info = tilesets.get(ts_id)
    passages = ts_info["passages"] if ts_info else ()
    _, _, _, tile_vals = unpack_table(attr(rmap, "@data"))

    pool = StringPool()
    bgm_id = 0xFFFF
    if attr(rmap, "@autoplay_bgm", False):
        bgm_obj = attr(rmap, "@bgm")
        bgm_id = pool.intern(attr(bgm_obj, "@name", ""))

    collision, layers = bake_collision_and_layers(w, h, tile_vals, ts_info)

    events_dict = attr(rmap, "@events", {}) or {}
    events_buf = bytearray()
    event_keys = sorted(events_dict.keys())
    events_buf += struct.pack(">H", len(event_keys))

    for ev_id in event_keys:
        ev = events_dict[ev_id]
        ev_x = int(attr(ev, "@x", 0)) & 0xFF
        ev_y = int(attr(ev, "@y", 0)) & 0xFF
        pages = attr(ev, "@pages", []) or []
        events_buf += struct.pack(">HBBB", clamp_u16(ev_id), ev_x, ev_y, min(255, len(pages)))

        for page in pages[:255]:
            cond = attr(page, "@condition")
            cflags = 0
            if attr(cond, "@switch1_valid", False): cflags |= 1
            if attr(cond, "@switch2_valid", False): cflags |= 2
            if attr(cond, "@variable_valid", False): cflags |= 4
            if attr(cond, "@self_switch_valid", False): cflags |= 8

            sw1 = clamp_u16(attr(cond, "@switch1_id", 0))
            sw2 = clamp_u16(attr(cond, "@switch2_id", 0))
            var_id = clamp_u16(attr(cond, "@variable_id", 0))
            var_val = clamp_i16(attr(cond, "@variable_value", 0))
            self_sw = SELF_SW_MAP.get(decode_str(attr(cond, "@self_switch_ch", "A")), 0)

            graphic = attr(page, "@graphic")
            g_str = pool.intern(attr(graphic, "@character_name", ""))
            g_tile = clamp_u16(attr(graphic, "@tile_id", 0))
            g_dir = int(attr(graphic, "@direction", 2)) & 0xFF
            g_pat = int(attr(graphic, "@pattern", 0)) & 0xFF
            g_opac = int(attr(graphic, "@opacity", 255)) & 0xFF

            trigger = int(attr(page, "@trigger", 0)) & 0xFF
            tile_passable = False
            if g_tile > 0 and g_tile < len(passages):
                tile_passable = (passages[g_tile] & 0x0F) == 0

            pflags = (
                (1 if attr(page, "@through", False) else 0)
                | (2 if attr(page, "@always_on_top", False) else 0)
                | (4 if attr(page, "@direction_fix", False) else 0)
                | (8 if tile_passable else 0)
            )

            move_type = int(attr(page, "@move_type", 0)) & 0xFF
            move_speed = int(attr(page, "@move_speed", 3)) & 0xFF
            move_freq = int(attr(page, "@move_frequency", 3)) & 0xFF

            if move_type == 3:
                pr_flags, pr_blob = pack_move_route_cmds(attr(page, "@move_route"), pool)
                stats["auto_routes"] += 1
            else:
                pr_flags, pr_blob = 0, b"\x00"
            if move_type == 2:
                stats["approach_pages"] += 1

            bytecode = compile_commands(attr(page, "@list", []) or [], pool, stats)

            events_buf += struct.pack(
                ">HHHHhBHHBBBBBBBBB",
                cflags, sw1, sw2, var_id, var_val, self_sw,
                g_str, g_tile, g_dir, g_pat, g_opac,
                trigger, pflags, move_type, move_speed, move_freq, pr_flags,
            )
            events_buf += pr_blob
            events_buf += struct.pack(">H", len(bytecode))
            events_buf += bytecode

    out = bytearray()
    out += struct.pack(">4sHHBBH", b"AOM2", map_id, ts_id, w & 0xFF, h & 0xFF, bgm_id)
    out += collision
    out += layers
    out += pool.to_bytes()
    out += events_buf
    return bytes(out)


def compile_common_events(data_dir, stats):
    path = os.path.join(data_dir, "CommonEvents.rxdata")
    if not os.path.exists(path):
        return None
    with open(path, "rb") as f:
        ce_list = load(f)

    pool = StringPool()
    body = bytearray()
    entries = [ce for ce in ce_list if ce is not None]
    body += struct.pack(">H", len(entries))

    for ce in entries:
        ce_id = clamp_u16(attr(ce, "@id", 0))
        trigger = int(attr(ce, "@trigger", 0)) & 0xFF
        sw_id = clamp_u16(attr(ce, "@switch_id", 0))
        bytecode = compile_commands(attr(ce, "@list", []) or [], pool, stats)
        body += struct.pack(">HBHH", ce_id, trigger, sw_id, len(bytecode))
        body += bytecode

    return b"AOC1" + pool.to_bytes() + body


def build_rooms_pak(entries):
    sorted_entries = sorted(entries, key=lambda item: item[0])
    count = len(sorted_entries)
    header_size = 6 + count * 10

    index_buf = bytearray()
    body_buf = bytearray()
    offset = header_size

    for entry_id, blob in sorted_entries:
        length = len(blob)
        index_buf += struct.pack(">HII", clamp_u16(entry_id), offset, length)
        body_buf += blob
        offset += length

    return struct.pack(">4sH", b"AOPK", count) + bytes(index_buf) + bytes(body_buf)


def main():
    args = [a for a in sys.argv[1:] if a != "--keep-bins"]
    keep_bins = "--keep-bins" in sys.argv[1:]

    if len(args) < 2:
        print("Usage: python compile_aooni.py <path_to_Data_dir> <out_rooms.pak_or_dir> [--keep-bins]")
        sys.exit(1)

    data_dir = args[0]
    out_target = args[1]

    if out_target.lower().endswith(".pak"):
        pak_path = out_target
        out_dir = os.path.dirname(os.path.abspath(pak_path))
    else:
        out_dir = out_target
        pak_path = os.path.join(out_dir, "rooms.pak")

    os.makedirs(out_dir, exist_ok=True)

    tilesets = load_tilesets(data_dir)
    stats = {
        "unhandled_ops": Counter(),
        "unhandled_cond": Counter(),
        "scripts": [],
        "rpn_compiled": 0,
        "approach_pages": 0,
        "auto_routes": 0,
    }

    map_files = sorted(
        f for f in os.listdir(data_dir)
        if re.fullmatch(r"Map\d{3}\.rxdata", f, re.IGNORECASE)
    )

    pak_entries = []
    for fname in map_files:
        map_id = int(fname[3:6])
        blob = compile_map(os.path.join(data_dir, fname), map_id, tilesets, stats)
        pak_entries.append((map_id, blob))
        if keep_bins:
            with open(os.path.join(out_dir, f"m{map_id:03d}.bin"), "wb") as f:
                f.write(blob)

    ce_blob = compile_common_events(data_dir, stats)
    if ce_blob is not None:
        pak_entries.append((0, ce_blob))
        if keep_bins:
            with open(os.path.join(out_dir, "common.bin"), "wb") as f:
                f.write(ce_blob)

    pak_blob = build_rooms_pak(pak_entries)
    with open(pak_path, "wb") as f:
        f.write(pak_blob)

    deflated_est = len(zlib.compress(pak_blob, level=9))
    print(
        f"Packed {len(map_files)} maps (AOM2)"
        f"{' + common.bin' if ce_blob is not None else ''} -> {pak_path}"
    )
    print(
        f"Archive size: {len(pak_blob):,} bytes raw | "
        f"~{deflated_est:,} bytes inside JAR (DEFLATE)."
    )
    print(
        f"Exported {stats['approach_pages']} Approach pages, "
        f"{stats['auto_routes']} Custom autonomous routes, "
        f"{stats['rpn_compiled']} RPN branches."
    )
    if stats["unhandled_ops"]:
        print("Unhandled RMXP opcodes:", dict(stats["unhandled_ops"].most_common(15)))


if __name__ == "__main__":
    main()
