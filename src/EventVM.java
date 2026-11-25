import java.util.Random;

public class EventVM {
    private static final int MAX_DEPTH = 8;
    private static final int MAX_STEPS_PER_TICK = 64;
    private static final Random RNG = new Random();

    // Call stack frames (parallel arrays = zero object allocation during execution)
    private byte[][] stackCode = new byte[MAX_DEPTH][];
    private String[][] stackStrings = new String[MAX_DEPTH][];
    private int[] stackPc = new int[MAX_DEPTH];
    private int depth = 0;

    public int ownerEvId = 0;
    public int ownerPage = -1;
    public boolean active = false;
    public boolean isParallel = false;

    private int waitFrames = 0;
    private boolean waitMessage = false;
    private boolean waitChoice = false;
    private boolean waitMove = false;
    private boolean waitInputNum = false;
    private boolean waitButton = false;
    private boolean waitNameInput = false;

    private int pendingVarId = 0;
    private int pendingActorId = 2;
    private int choiceCancelType = 0;
    private int choiceCancelPc = 0;
    private int[] choiceTargets = new int[6];

    // Pre-allocated RPN evaluation stack for OP_BRANCH_EXPR (0x1F)
    private int[] rpn = new int[32];

    public EventVM(boolean parallel) {
        this.isParallel = parallel;
    }

    public void start(int evId, int pageIdx, byte[] code, String[] strings) {
        if (code == null || code.length == 0) {
            stop();
            return;
        }
        ownerEvId = evId;
        ownerPage = pageIdx;
        depth = 1;
        stackCode[0] = code;
        stackStrings[0] = strings;
        stackPc[0] = 0;
        waitFrames = 0;
        waitMessage = false;
        waitChoice = false;
        waitMove = false;
        waitInputNum = false;
        waitButton = false;
        waitNameInput = false;
        active = true;
    }

    public void stop() {
        active = false;
        depth = 0;
        waitFrames = 0;
        waitMessage = false;
        waitChoice = false;
        waitMove = false;
        waitInputNum = false;
        waitButton = false;
        waitNameInput = false;
    }

    public boolean isWaitingMessage() {
        return active && waitMessage;
    }

    public boolean hasNestedCall() {
        return active && depth > 1;
    }

    public void onMessageClosed() {
        waitMessage = false;
    }

    public void onChoicePicked(int idx) {
        waitChoice = false;
        if (!active || depth <= 0) return;
        int top = depth - 1;
        if (idx >= 0 && idx < choiceTargets.length) {
            stackPc[top] = choiceTargets[idx];
        } else if (idx == -1) {
            if (choiceCancelType >= 1 && choiceCancelType <= 4) {
                stackPc[top] = choiceTargets[choiceCancelType - 1];
            } else if (choiceCancelType == 5) {
                stackPc[top] = choiceCancelPc;
            }
        }
    }

    public void onNumberEntered(GameData data, RoomMap map, int value) {
        waitInputNum = false;
        data.setVar(pendingVarId, value);
        map.refreshPages(data);
    }

    public void onButtonPressed(GameData data, RoomMap map, int rmxpBtnCode) {
        waitButton = false;
        data.setVar(pendingVarId, rmxpBtnCode);
        map.refreshPages(data);
    }

    public void onNameEntered(GameData data, RoomMap map, String name) {
        waitNameInput = false;
        data.setActorName(pendingActorId, name);
        map.refreshPages(data);
    }

    public void update(GameScreen screen, GameData data, RoomMap map) {
        if (!active || depth <= 0) return;

        if (waitMessage || waitChoice || waitInputNum || waitButton || waitNameInput) return;
        if (waitMove) {
            if (screen.isAnyMoveRouteActive()) return;
            waitMove = false;
        }
        if (waitFrames > 0) {
            waitFrames--;
            return;
        }

        int steps = 0;
        while (active && depth > 0 && steps < MAX_STEPS_PER_TICK) {
            steps++;
            int top = depth - 1;
            byte[] code = stackCode[top];
            String[] pool = stackStrings[top];
            int pc = stackPc[top];

            if (pc >= code.length) {
                popFrame();
                continue;
            }

            int op = code[pc++] & 0xFF;
            switch (op) {
                case 0x00: // OP_END
                    stackPc[top] = pc;
                    popFrame();
                    break;

                case 0x01: { // OP_MSG
                    int sid = readU16(code, pc); pc += 2;
                    if (!isParallel || !screen.isUiBusy()) {
                        stackPc[top] = pc;
                        screen.openMessage(getStr(pool, sid), ownerEvId);
                        waitMessage = true;
                        return;
                    }
                    // Yield until foreground UI clears
                    return;
                }

                case 0x02: { // OP_CHOICE
                    int count = code[pc++] & 0xFF;
                    int cancelType = code[pc++] & 0xFF;
                    String[] labels = new String[count];
                    for (int i = 0; i < count; i++) {
                        int sid = readU16(code, pc); pc += 2;
                        int tpc = readU16(code, pc); pc += 2;
                        labels[i] = getStr(pool, sid);
                        if (i < choiceTargets.length) choiceTargets[i] = tpc;
                    }
                    choiceCancelType = cancelType;
                    choiceCancelPc = pc;
                    if (cancelType == 5) {
                        choiceCancelPc = readU16(code, pc); pc += 2;
                    }
                    stackPc[top] = pc;
                    if (!isParallel) {
                        screen.openChoices(labels, cancelType);
                        waitChoice = true;
                        return;
                    }
                    break;
                }

                case 0x03: { // OP_JMP
                    int tpc = readU16(code, pc);
                    stackPc[top] = tpc;
                    break;
                }

                case 0x04: { // OP_BRANCH_FALSE
                    int kind = code[pc++] & 0xFF;
                    int arg1 = readU16(code, pc); pc += 2;
                    int arg2 = readI16(code, pc); pc += 2;
                    int cmpOp = code[pc++] & 0xFF;
                    int elsePc = readU16(code, pc); pc += 2;
                    boolean cond = evalBranch(screen, data, map, pool, kind, arg1, arg2, cmpOp);
                    stackPc[top] = cond ? pc : elsePc;
                    break;
                }

                case 0x05: { // OP_SET_SWITCH
                    int sId = readU16(code, pc); pc += 2;
                    int eId = readU16(code, pc); pc += 2;
                    boolean val = (code[pc++] & 0xFF) == 0;
                    for (int id = sId; id <= eId; id++) {
                        data.setSwitch(id, val);
                    }
                    map.refreshPages(data);
                    stackPc[top] = pc;
                    break;
                }

                case 0x06: { // OP_SET_VAR
                    int sId = readU16(code, pc); pc += 2;
                    int eId = readU16(code, pc); pc += 2;
                    int vOp = code[pc++] & 0xFF;
                    int otype = code[pc++] & 0xFF;
                    int p1 = readI16(code, pc); pc += 2;
                    int p2 = readI16(code, pc); pc += 2;
                    int rhs = resolveVarOperand(screen, data, map, otype, p1, p2);
                    for (int id = sId; id <= eId; id++) {
                        int cur = data.getVar(id);
                        int res = cur;
                        if (vOp == 0) res = rhs;
                        else if (vOp == 1) res = cur + rhs;
                        else if (vOp == 2) res = cur - rhs;
                        else if (vOp == 3) res = cur * rhs;
                        else if (vOp == 4 && rhs != 0) res = cur / rhs;
                        else if (vOp == 5 && rhs != 0) res = cur % rhs;
                        data.setVar(id, res);
                    }
                    map.refreshPages(data);
                    stackPc[top] = pc;
                    break;
                }

                case 0x07: { // OP_SET_SELF_SW
                    int ch = code[pc++] & 0xFF;
                    boolean val = (code[pc++] & 0xFF) == 0;
                    data.setSelfSwitch(map.mapId, ownerEvId, ch, val);
                    map.refreshPages(data);
                    stackPc[top] = pc;
                    break;
                }

                case 0x08: { // OP_CHANGE_ITEM
                    int itemId = readU16(code, pc); pc += 2;
                    int iOp = code[pc++] & 0xFF;
                    int qty = code[pc++] & 0xFF;
                    data.addItem(itemId, iOp == 0 ? qty : -qty);
                    map.refreshPages(data);
                    stackPc[top] = pc;
                    break;
                }

                case 0x09: { // OP_TRANSFER
                    int tMap = readU16(code, pc); pc += 2;
                    int tX = readU16(code, pc); pc += 2;
                    int tY = readU16(code, pc); pc += 2;
                    int flags = code[pc++] & 0xFF;
                    if ((flags & 0x80) != 0) {
                        tMap = data.getVar(tMap);
                        tX = data.getVar(tX);
                        tY = data.getVar(tY);
                    }
                    int tDir = flags & 0x0F;
                    stackPc[top] = pc;
                    screen.transferPlayer(tMap, tX, tY, tDir);
                    return; // Yield one frame after room load
                }

                case 0x0A: { // OP_WAIT (RMXP 40 FPS -> 20 FPS)
                    int frames = readU16(code, pc); pc += 2;
                    stackPc[top] = pc;
                    waitFrames = (frames + 1) >> 1;
                    if (waitFrames < 1 && frames > 0) waitFrames = 1;
                    return;
                }

                case 0x0B: { // OP_MOVE_EVENT
                    int targetId = readI16(code, pc); pc += 2;
                    int rFlags = code[pc++] & 0xFF;
                    int cmdCount = code[pc++] & 0xFF;
                    byte[] rCodes = new byte[cmdCount];
                    short[] rArgs = new short[cmdCount];
                    for (int i = 0; i < cmdCount; i++) {
                        rCodes[i] = code[pc++];
                        rArgs[i] = (short) readI16(code, pc); pc += 2;
                    }
                    screen.assignMoveRoute(
                        targetId, ownerEvId, rCodes, rArgs, pool, cmdCount,
                        (rFlags & 1) != 0, (rFlags & 2) != 0
                    );
                    stackPc[top] = pc;
                    break;
                }

                case 0x0C: // OP_WAIT_MOVE
                    stackPc[top] = pc;
                    waitMove = true;
                    return;

                case 0x0D: { // OP_AUDIO
                    int aKind = code[pc++] & 0xFF;
                    int aStr = readU16(code, pc); pc += 2;
                    int aVol = code[pc++] & 0xFF;
                    screen.handleAudio(aKind, getStr(pool, aStr), aVol);
                    stackPc[top] = pc;
                    break;
                }

                case 0x0E: { // OP_SCREEN_FX
                    int fxKind = code[pc++] & 0xFF;
                    int a1 = readI16(code, pc); pc += 2;
                    int a2 = readI16(code, pc); pc += 2;
                    screen.handleScreenFx(fxKind, a1, a2);
                    stackPc[top] = pc;
                    break;
                }

                case 0x0F: { // OP_ERASE_EVENT
                    MapEvent self = map.getEventById(ownerEvId);
                    if (self != null) {
                        self.erased = true;
                        self.activePage = -1;
                    }
                    stackPc[top] = pc;
                    break;
                }

                case 0x10: { // OP_CALL_COMMON
                    int ceId = readU16(code, pc); pc += 2;
                    stackPc[top] = pc;
                    if (isParallel && RoomMap.commonTriggersGameOver(ceId)) {
                        stop();
                        screen.startDeathCommonEvent(ceId);
                        return;
                    }
                    int idx = RoomMap.findCommonIndex(ceId);
                    if (idx >= 0 && depth < MAX_DEPTH) {
                        stackCode[depth] = RoomMap.commonCodes[idx];
                        stackStrings[depth] = RoomMap.commonStrings;
                        stackPc[depth] = 0;
                        depth++;
                    }
                    break;
                }

                case 0x11: // OP_GAME_OVER
                    stop();
                    screen.triggerGameOver();
                    return;

                case 0x12: { // OP_PICTURE
                    int picId = code[pc++] & 0xFF;
                    int pStr = readU16(code, pc); pc += 2;
                    int px = readI16(code, pc); pc += 2;
                    int py = readI16(code, pc); pc += 2;
                    if (pStr == 0xFFFF) {
                        screen.erasePicture(picId);
                    } else {
                        screen.showPicture(picId, getStr(pool, pStr), px, py);
                    }
                    stackPc[top] = pc;
                    break;
                }

                case 0x13: // OP_EXIT_EVENT
                    stop();
                    return;

                case 0x14: { // OP_SCRIPT_HOOK
                    int sid = readU16(code, pc); pc += 2;
                    String script = getStr(pool, sid);
                    if (script.indexOf("Graphics.frame_count") >= 0) {
                        screen.frameCount = 0;
                    } else if (script.indexOf("$game_variables[45]") >= 0) {
                        int sec = data.getVar(15);
                        data.setVar(45, sec / 3600);
                        data.setVar(46, (sec / 60) % 60);
                        data.setVar(47, sec % 60);
                    }
                    stackPc[top] = pc;
                    break;
                }

                case 0x15: { // OP_SET_EVENT_LOC
                    int evId = readI16(code, pc); pc += 2;
                    int mode = code[pc++] & 0xFF;
                    int lx = readU16(code, pc); pc += 2;
                    int ly = readU16(code, pc); pc += 2;
                    int ldir = code[pc++] & 0xFF;
                    MapEvent ev = map.getEventById(evId == 0 ? ownerEvId : evId);
                    if (ev != null) {
                        if (mode == 0) {
                            ev.setTilePos(lx, ly);
                        } else if (mode == 1) {
                            ev.setTilePos(data.getVar(lx), data.getVar(ly));
                        } else if (mode == 2) {
                            MapEvent other = map.getEventById(lx);
                            if (other != null) {
                                int ox = other.tileX, oy = other.tileY;
                                other.setTilePos(ev.tileX, ev.tileY);
                                ev.setTilePos(ox, oy);
                            }
                        }
                        if (ldir != 0) ev.dir = ldir;
                    }
                    stackPc[top] = pc;
                    break;
                }

                case 0x16: { // OP_TIMER
                    int tOp = code[pc++] & 0xFF;
                    int sec = readU16(code, pc); pc += 2;
                    if (tOp == 0) screen.startTimer(sec);
                    else screen.stopTimer();
                    stackPc[top] = pc;
                    break;
                }

                case 0x17: { // OP_TRANSPARENT (0 = transparent ON, 1 = normal)
                    int flag = code[pc++] & 0xFF;
                    screen.playerTransparent = (flag == 0);
                    stackPc[top] = pc;
                    break;
                }

                case 0x18: { // OP_MENU_ACCESS
                    int flag = code[pc++] & 0xFF;
                    screen.menuEnabled = (flag != 0);
                    stackPc[top] = pc;
                    break;
                }

                case 0x19: { // OP_INPUT_NUM
                    int varId = readU16(code, pc); pc += 2;
                    int digits = code[pc++] & 0xFF;
                    stackPc[top] = pc;
                    if (!isParallel) {
                        pendingVarId = varId;
                        screen.openNumberInput(digits);
                        waitInputNum = true;
                        return;
                    }
                    break;
                }

                case 0x1A: { // OP_BUTTON_INPUT
                    int varId = readU16(code, pc); pc += 2;
                    stackPc[top] = pc;
                    if (!isParallel) {
                        pendingVarId = varId;
                        screen.openButtonInput();
                        waitButton = true;
                        return;
                    }
                    break;
                }

                case 0x1B: { // OP_MOVE_PICTURE
                    int picId = code[pc++] & 0xFF;
                    int dur = readU16(code, pc); pc += 2;
                    int flags = code[pc++] & 0xFF;
                    int px = readI16(code, pc); pc += 2;
                    int py = readI16(code, pc); pc += 2;
                    int opac = code[pc++] & 0xFF;
                    if ((flags & 1) != 0) {
                        px = data.getVar(px);
                        py = data.getVar(py);
                    }
                    screen.movePicture(picId, (dur + 1) >> 1, px, py, opac);
                    stackPc[top] = pc;
                    break;
                }

                case 0x1C: { // OP_TEXT_OPT
                    int pos = code[pc++] & 0xFF;
                    int frame = code[pc++] & 0xFF;
                    screen.textPos = pos;
                    screen.textFrame = frame;
                    stackPc[top] = pc;
                    break;
                }

                case 0x1D: { // OP_PARTY
                    int actorId = readU16(code, pc); pc += 2;
                    int pOp = code[pc++] & 0xFF;
                    data.setInParty(actorId, pOp == 0);
                    stackPc[top] = pc;
                    break;
                }

                case 0x1E: // OP_RETURN_TITLE
                    stop();
                    screen.returnToTitle();
                    return;

                case 0x1F: { // OP_BRANCH_EXPR (compiled RPN)
                    int exprLen = code[pc++] & 0xFF;
                    int exprStart = pc;
                    pc += exprLen;
                    int elsePc = readU16(code, pc); pc += 2;
                    boolean ok = evalRpn(screen, data, map, code, exprStart, exprStart + exprLen);
                    stackPc[top] = ok ? pc : elsePc;
                    break;
                }

                case 0x20: { // OP_NAME_INPUT (RMXP 303)
                    int actorId = readU16(code, pc); pc += 2;
                    int maxChars = code[pc++] & 0xFF;
                    stackPc[top] = pc;
                    if (!isParallel) {
                        pendingActorId = actorId;
                        screen.openNameInput(actorId, maxChars);
                        waitNameInput = true;
                        return;
                    }
                    break;
                }

                case 0x21: { // OP_SET_ACTOR_NAME (RMXP 320)
                    int actorId = readU16(code, pc); pc += 2;
                    int nameSid = readU16(code, pc); pc += 2;
                    data.setActorName(actorId, getStr(pool, nameSid));
                    map.refreshPages(data);
                    stackPc[top] = pc;
                    break;
                }

                default:
                    // Unknown opcode: safely terminate frame
                    stop();
                    return;
            }
        }
    }

    private void popFrame() {
        depth--;
        if (depth <= 0) {
            if (isParallel) {
                active = false;
                depth = 0;
            } else {
                stop();
            }
        }
    }

    private boolean evalBranch(
        GameScreen screen, GameData data, RoomMap map, String[] pool,
        int kind, int arg1, int arg2, int cmpOp
    ) {
        switch (kind) {
            case 0: // Switch
                return data.getSwitch(arg1) == (arg2 == 0);
            case 1: { // Variable
                int lhs = data.getVar(arg1);
                int rhs = ((cmpOp & 0x80) != 0) ? data.getVar(arg2) : arg2;
                int op = cmpOp & 0x0F;
                if (op == 0) return lhs == rhs;
                if (op == 1) return lhs >= rhs;
                if (op == 2) return lhs <= rhs;
                if (op == 3) return lhs > rhs;
                if (op == 4) return lhs < rhs;
                if (op == 5) return lhs != rhs;
                return false;
            }
            case 2: // Self switch
                return data.getSelfSwitch(map.mapId, ownerEvId, arg1) == (arg2 == 0);
            case 3: // Has item
                return data.getItemCount(arg1) > 0;
            case 4: // Character facing
                return screen.getCharProp((short) arg1, ownerEvId, 2) == arg2;
            case 5: { // Uncompiled Ruby fallback
                String s = getStr(pool, arg1);
                if (s.indexOf("$game_variables[6] % 2 == 1") >= 0) {
                    return (data.getVar(6) & 1) != 0;
                }
                return false;
            }
            case 6: { // Timer
                int sec = screen.getTimerSeconds();
                return cmpOp == 0 ? (sec >= arg1) : (sec <= arg1);
            }
            case 7: { // Actor
                if (cmpOp == 0) return data.isInParty(arg1);
                if (cmpOp == 1) {
                    String target = (arg2 >= 0) ? getStr(pool, arg2) : "";
                    return data.getActorName(arg1).equals(target);
                }
                return false;
            }
            default:
                return false;
        }
    }

    private boolean evalRpn(GameScreen screen, GameData data, RoomMap map, byte[] code, int p, int end) {
        int sp = 0;
        while (p < end) {
            int rop = code[p++] & 0xFF;
            switch (rop) {
                case 0x01: // EXPR_LIT
                    rpn[sp++] = readI16(code, p); p += 2;
                    break;
                case 0x02: { // EXPR_PROP
                    int tid = readI16(code, p); p += 2;
                    int prop = code[p++] & 0xFF;
                    rpn[sp++] = screen.getCharProp(tid, ownerEvId, prop);
                    break;
                }
                case 0x03: { // EXPR_SW
                    int sid = readU16(code, p); p += 2;
                    rpn[sp++] = data.getSwitch(sid) ? 1 : 0;
                    break;
                }
                case 0x04: { // EXPR_VAR
                    int vid = readU16(code, p); p += 2;
                    rpn[sp++] = data.getVar(vid);
                    break;
                }
                case 0x10: { int b = rpn[--sp], a = rpn[--sp]; rpn[sp++] = (a == b) ? 1 : 0; break; }
                case 0x11: { int b = rpn[--sp], a = rpn[--sp]; rpn[sp++] = (a != b) ? 1 : 0; break; }
                case 0x12: { int b = rpn[--sp], a = rpn[--sp]; rpn[sp++] = (a < b) ? 1 : 0; break; }
                case 0x13: { int b = rpn[--sp], a = rpn[--sp]; rpn[sp++] = (a <= b) ? 1 : 0; break; }
                case 0x14: { int b = rpn[--sp], a = rpn[--sp]; rpn[sp++] = (a > b) ? 1 : 0; break; }
                case 0x15: { int b = rpn[--sp], a = rpn[--sp]; rpn[sp++] = (a >= b) ? 1 : 0; break; }
                case 0x18: { int b = rpn[--sp], a = rpn[--sp]; rpn[sp++] = a + b; break; }
                case 0x19: { int b = rpn[--sp], a = rpn[--sp]; rpn[sp++] = a - b; break; }
                case 0x20: { int b = rpn[--sp], a = rpn[--sp]; rpn[sp++] = (a != 0 && b != 0) ? 1 : 0; break; }
                case 0x21: { int b = rpn[--sp], a = rpn[--sp]; rpn[sp++] = (a != 0 || b != 0) ? 1 : 0; break; }
                case 0x22: { int a = rpn[--sp]; rpn[sp++] = (a == 0) ? 1 : 0; break; }
                default:
                    return false;
            }
        }
        return sp > 0 && rpn[sp - 1] != 0;
    }

    private int resolveVarOperand(GameScreen screen, GameData data, RoomMap map, int otype, int p1, int p2) {
        if (otype == 0) return p1;
        if (otype == 1) return data.getVar(p1);
        if (otype == 2) {
            int lo = p1 < p2 ? p1 : p2;
            int hi = p1 < p2 ? p2 : p1;
            int span = hi - lo + 1;
            if (span <= 1) return lo;
            return lo + ((RNG.nextInt() & 0x7FFFFFFF) % span);
        }
        if (otype == 3) return data.getItemCount(p1);
        if (otype == 6) return screen.getCharProp(p1, ownerEvId, p2);
        if (otype == 7) {
            if (p1 == 0) return map.mapId;
            if (p1 == 1) return data.getPartySize();
            if (p1 == 2) return 0; // Gold
            if (p1 == 3) return screen.stepCount;
            if (p1 == 4) return screen.frameCount / 20;
            if (p1 == 5) return screen.getTimerSeconds();
            if (p1 == 6) return screen.saveCount;
        }
        return 0;
    }

    private static int readU16(byte[] b, int o) {
        return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF);
    }

    private static int readI16(byte[] b, int o) {
        return (short) (((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF));
    }

    private static String getStr(String[] pool, int id) {
        if (pool == null || id == 0xFFFF || id < 0 || id >= pool.length) return "";
        return pool[id];
    }
}
