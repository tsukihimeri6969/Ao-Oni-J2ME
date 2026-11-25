import java.io.DataInputStream;
import java.io.IOException;

public class RoomMap {
    public int mapId;
    public int tsId;
    public int width;
    public int height;
    public String bgmName = "";

    public byte[] collision;
    public short[] layer0;
    public short[] layer1;
    public short[] layer2;
    public String[] strings;
    public MapEvent[] events;

    // Zero-allocation BFS scratch buffers for Ao Oni chaser pathfinding
    private int[] bfsStamp = new int[1024];
    private short[] bfsQueue = new short[1024];
    private byte[] bfsFirstDir = new byte[1024];
    private int bfsGen = 0;
    private final int[] dirOrder = new int[4];

    // Common events (AOC1, loaded once globally)
    public static String[] commonStrings;
    public static int[] commonIds;
    public static byte[] commonTriggers;
    public static int[] commonSwitches;
    public static byte[][] commonCodes;

    public static void loadCommonEvents(GameData data) throws IOException {
        DataInputStream dis = data.openPakEntry(0);
        if (dis == null) {
            commonStrings = new String[0];
            commonIds = new int[0];
            commonTriggers = new byte[0];
            commonSwitches = new int[0];
            commonCodes = new byte[0][];
            return;
        }
        try {
            int m0 = dis.readUnsignedByte();
            int m1 = dis.readUnsignedByte();
            int m2 = dis.readUnsignedByte();
            int m3 = dis.readUnsignedByte();
            if (m0 != 'A' || m1 != 'O' || m2 != 'C' || m3 != '1') {
                throw new IOException("Bad AOC1 magic");
            }
            int sc = dis.readUnsignedShort();
            commonStrings = new String[sc];
            for (int i = 0; i < sc; i++) {
                commonStrings[i] = dis.readUTF();
            }
            int ceCount = dis.readUnsignedShort();
            commonIds = new int[ceCount];
            commonTriggers = new byte[ceCount];
            commonSwitches = new int[ceCount];
            commonCodes = new byte[ceCount][];
            for (int i = 0; i < ceCount; i++) {
                commonIds[i] = dis.readUnsignedShort();
                commonTriggers[i] = dis.readByte();
                commonSwitches[i] = dis.readUnsignedShort();
                int clen = dis.readUnsignedShort();
                byte[] bc = new byte[clen];
                dis.readFully(bc);
                commonCodes[i] = bc;
            }
        } finally {
            try { dis.close(); } catch (Exception e) {}
        }
    }

    public static int findCommonIndex(int ceId) {
        if (commonIds == null) return -1;
        for (int i = 0; i < commonIds.length; i++) {
            if (commonIds[i] == ceId) return i;
        }
        return -1;
    }

    /**
     * Walks a compiled AOC1 CommonEvent bytecode stream safely to check whether it
     * tests (OP_BRANCH_FALSE kind=3) or modifies (OP_CHANGE_ITEM) the given itemId.
     * Used by the inventory MERGE menu to dispatch the exact RMXP combine script
     * regardless of which item the player selected first.
     */
    public static boolean commonReferencesItem(int ceId, int targetItemId) {
        int idx = findCommonIndex(ceId);
        if (idx < 0 || commonCodes == null) return false;
        byte[] code = commonCodes[idx];
        if (code == null) return false;
        int pc = 0;
        int len = code.length;
        while (pc < len) {
            int op = code[pc++] & 0xFF;
            switch (op) {
                case 0x00: case 0x0C: case 0x0F: case 0x11: case 0x13: case 0x1E:
                    break;
                case 0x01: case 0x03: case 0x0A: case 0x10: case 0x14: case 0x1A:
                    pc += 2;
                    break;
                case 0x02: { // OP_CHOICE
                    if (pc + 2 > len) return false;
                    int cnt = code[pc++] & 0xFF;
                    int ctype = code[pc++] & 0xFF;
                    pc += cnt * 4 + (ctype == 5 ? 2 : 0);
                    break;
                }
                case 0x04: { // OP_BRANCH_FALSE
                    if (pc + 8 > len) return false;
                    int kind = code[pc] & 0xFF;
                    int arg1 = ((code[pc + 1] & 0xFF) << 8) | (code[pc + 2] & 0xFF);
                    if (kind == 3 && arg1 == targetItemId) return true;
                    pc += 8;
                    break;
                }
                case 0x05: pc += 5; break;
                case 0x06: pc += 10; break;
                case 0x07: case 0x1C: pc += 2; break;
                case 0x08: { // OP_CHANGE_ITEM
                    if (pc + 4 > len) return false;
                    int iid = ((code[pc] & 0xFF) << 8) | (code[pc + 1] & 0xFF);
                    if (iid == targetItemId) return true;
                    pc += 4;
                    break;
                }
                case 0x09: pc += 7; break;
                case 0x0B: { // OP_MOVE_EVENT
                    if (pc + 4 > len) return false;
                    int mcnt = code[pc + 3] & 0xFF;
                    pc += 4 + mcnt * 3;
                    break;
                }
                case 0x0D: case 0x21: pc += 4; break;
                case 0x0E: pc += 5; break;
                case 0x12: pc += 7; break;
                case 0x15: pc += 8; break;
                case 0x16: case 0x19: case 0x1D: case 0x20: pc += 3; break;
                case 0x17: case 0x18: pc += 1; break;
                case 0x1B: pc += 9; break;
                case 0x1F: { // OP_BRANCH_EXPR
                    if (pc + 1 > len) return false;
                    int elen = code[pc++] & 0xFF;
                    pc += elen + 2;
                    break;
                }
                default:
                    return false;
            }
        }
        return false;
    }

    /**
     * Checks whether a compiled AOC1 CommonEvent contains OP_GAME_OVER (0x11)
     * so parallel room events calling a death CommonEvent (e.g. CE 01) can be
     * promoted safely to the foreground VM and never dropped if sw49 turns off.
     */
    public static boolean commonTriggersGameOver(int ceId) {
        int idx = findCommonIndex(ceId);
        if (idx < 0 || commonCodes == null) return false;
        byte[] code = commonCodes[idx];
        if (code == null) return false;
        int pc = 0;
        int len = code.length;
        while (pc < len) {
            int op = code[pc++] & 0xFF;
            if (op == 0x11) return true;
            switch (op) {
                case 0x00: case 0x0C: case 0x0F: case 0x13: case 0x1E:
                    break;
                case 0x01: case 0x03: case 0x0A: case 0x10: case 0x14: case 0x1A:
                    pc += 2;
                    break;
                case 0x02: {
                    if (pc + 2 > len) return false;
                    int cnt = code[pc++] & 0xFF;
                    int ctype = code[pc++] & 0xFF;
                    pc += cnt * 4 + (ctype == 5 ? 2 : 0);
                    break;
                }
                case 0x04: pc += 8; break;
                case 0x05: pc += 5; break;
                case 0x06: pc += 10; break;
                case 0x07: case 0x1C: pc += 2; break;
                case 0x08: pc += 4; break;
                case 0x09: pc += 7; break;
                case 0x0B: {
                    if (pc + 4 > len) return false;
                    int mcnt = code[pc + 3] & 0xFF;
                    pc += 4 + mcnt * 3;
                    break;
                }
                case 0x0D: case 0x21: pc += 4; break;
                case 0x0E: pc += 5; break;
                case 0x12: pc += 7; break;
                case 0x15: pc += 8; break;
                case 0x16: case 0x19: case 0x1D: case 0x20: pc += 3; break;
                case 0x17: case 0x18: pc += 1; break;
                case 0x1B: pc += 9; break;
                case 0x1F: {
                    if (pc + 1 > len) return false;
                    int elen = code[pc++] & 0xFF;
                    pc += elen + 2;
                    break;
                }
                default:
                    return false;
            }
        }
        return false;
    }

    public void load(GameData data, int targetMapId) throws IOException {
        DataInputStream dis = data.openPakEntry(targetMapId);
        if (dis == null) {
            throw new IOException("Map not found in rooms.pak: " + targetMapId);
        }
        try {
            int m0 = dis.readUnsignedByte();
            int m1 = dis.readUnsignedByte();
            int m2 = dis.readUnsignedByte();
            int m3 = dis.readUnsignedByte();
            if (m0 != 'A' || m1 != 'O' || m2 != 'M' || (m3 != '1' && m3 != '2')) {
                throw new IOException("Bad AOM magic on map " + targetMapId);
            }
            boolean isV2 = (m3 == '2');

            mapId = dis.readUnsignedShort();
            tsId = dis.readUnsignedShort();
            width = dis.readUnsignedByte();
            height = dis.readUnsignedByte();
            int bgmStrId = dis.readUnsignedShort();

            int plane = width * height;
            if (plane > bfsStamp.length) {
                bfsStamp = new int[plane];
                bfsQueue = new short[plane];
                bfsFirstDir = new byte[plane];
                bfsGen = 0;
            }

            collision = new byte[plane];
            dis.readFully(collision);

            layer0 = readRemappedLayer(dis, data, tsId, plane);
            layer1 = readRemappedLayer(dis, data, tsId, plane);
            layer2 = readRemappedLayer(dis, data, tsId, plane);

            int sc = dis.readUnsignedShort();
            strings = new String[sc];
            for (int i = 0; i < sc; i++) {
                strings[i] = dis.readUTF();
            }
            bgmName = (bgmStrId != 0xFFFF && bgmStrId < strings.length) ? strings[bgmStrId] : "";

            int evCount = dis.readUnsignedShort();
            events = new MapEvent[evCount];
            for (int i = 0; i < evCount; i++) {
                MapEvent ev = new MapEvent();
                ev.id = dis.readUnsignedShort();
                int ex = dis.readUnsignedByte();
                int ey = dis.readUnsignedByte();
                ev.setTilePos(ex, ey);

                int pc = dis.readUnsignedByte();
                ev.pageCount = pc;
                ev.pCondFlags = new int[pc];
                ev.pSw1 = new int[pc];
                ev.pSw2 = new int[pc];
                ev.pVarId = new int[pc];
                ev.pVarVal = new int[pc];
                ev.pSelfSw = new int[pc];
                ev.pGStr = new int[pc];
                ev.pGTile = new int[pc];
                ev.pGDir = new int[pc];
                ev.pGPat = new int[pc];
                ev.pGOpac = new int[pc];
                ev.pTrigger = new int[pc];
                ev.pFlags = new int[pc];
                ev.pMoveType = new int[pc];
                ev.pMoveSpeed = new int[pc];
                ev.pMoveFreq = new int[pc];
                ev.pPRFlags = new int[pc];
                ev.pPRCodes = new byte[pc][];
                ev.pPRArgs = new short[pc][];
                ev.pCode = new byte[pc][];

                for (int p = 0; p < pc; p++) {
                    ev.pCondFlags[p] = dis.readUnsignedShort();
                    ev.pSw1[p] = dis.readUnsignedShort();
                    ev.pSw2[p] = dis.readUnsignedShort();
                    ev.pVarId[p] = dis.readUnsignedShort();
                    ev.pVarVal[p] = dis.readShort();
                    ev.pSelfSw[p] = dis.readUnsignedByte();
                    ev.pGStr[p] = dis.readUnsignedShort();
                    ev.pGTile[p] = dis.readUnsignedShort();
                    ev.pGDir[p] = dis.readUnsignedByte();
                    ev.pGPat[p] = dis.readUnsignedByte();
                    if (isV2) {
                        ev.pGOpac[p] = dis.readUnsignedByte();
                        ev.pTrigger[p] = dis.readUnsignedByte();
                        ev.pFlags[p] = dis.readUnsignedByte();
                        ev.pMoveType[p] = dis.readUnsignedByte();
                        ev.pMoveSpeed[p] = dis.readUnsignedByte();
                        ev.pMoveFreq[p] = dis.readUnsignedByte();
                        ev.pPRFlags[p] = dis.readUnsignedByte();
                        int prLen = dis.readUnsignedByte();
                        if (prLen > 0) {
                            byte[] prc = new byte[prLen];
                            short[] pra = new short[prLen];
                            for (int r = 0; r < prLen; r++) {
                                prc[r] = dis.readByte();
                                pra[r] = dis.readShort();
                            }
                            ev.pPRCodes[p] = prc;
                            ev.pPRArgs[p] = pra;
                        }
                    } else {
                        ev.pGOpac[p] = 255;
                        ev.pTrigger[p] = dis.readUnsignedByte();
                        ev.pFlags[p] = dis.readUnsignedByte();
                        ev.pMoveType[p] = 0;
                        ev.pMoveSpeed[p] = 4;
                        ev.pMoveFreq[p] = 3;
                        ev.pPRFlags[p] = 0;
                    }
                    int clen = dis.readUnsignedShort();
                    byte[] bc = new byte[clen];
                    dis.readFully(bc);
                    ev.pCode[p] = bc;
                }
                events[i] = ev;
            }
        } finally {
            try { dis.close(); } catch (Exception e) {}
        }

        refreshPages(data);
    }

    private static short[] readRemappedLayer(DataInputStream dis, GameData data, int tsId, int plane)
            throws IOException {
        short[] arr = new short[plane];
        for (int i = 0; i < plane; i++) {
            int packed = dis.readUnsignedShort();
            int rawTid = packed & 0x7FFF;
            int hiBit = packed & 0x8000;
            int slot = data.remapTile(tsId, rawTid);
            arr[i] = (short) (slot | hiBit);
        }
        return arr;
    }

    public void refreshPages(GameData data) {
        if (events == null) return;
        for (int i = 0; i < events.length; i++) {
            MapEvent ev = events[i];
            if (ev.erased) {
                ev.activePage = -1;
                continue;
            }
            int chosen = -1;
            for (int p = ev.pageCount - 1; p >= 0; p--) {
                int cf = ev.pCondFlags[p];
                if ((cf & 1) != 0 && !data.getSwitch(ev.pSw1[p])) continue;
                if ((cf & 2) != 0 && !data.getSwitch(ev.pSw2[p])) continue;
                if ((cf & 4) != 0 && data.getVar(ev.pVarId[p]) < ev.pVarVal[p]) continue;
                if ((cf & 8) != 0 && !data.getSelfSwitch(mapId, ev.id, ev.pSelfSw[p])) continue;
                chosen = p;
                break;
            }
            if (chosen != ev.activePage) {
                ev.activePage = chosen;
                if (chosen >= 0) {
                    int gStr = ev.pGStr[chosen];
                    if (gStr != 0xFFFF && gStr < strings.length) {
                        ev.charId = data.resolveCharId(strings[gStr]);
                    } else {
                        ev.charId = -1;
                    }
                    ev.oniType = data.getOniType(ev.charId);
                    int rawGTile = ev.pGTile[chosen];
                    ev.tileSlot = (rawGTile > 0) ? data.remapTile(tsId, rawGTile) : 0;
                    ev.dir = ev.pGDir[chosen];
                    ev.pattern = ev.pGPat[chosen];
                    ev.opacity = ev.pGOpac[chosen];
                    int fl = ev.pFlags[chosen];
                    ev.through = (fl & 1) != 0;
                    ev.alwaysOnTop = (fl & 2) != 0;
                    ev.dirFix = (fl & 4) != 0;
                    ev.tilePassable = (fl & 8) != 0;

                    ev.moveType = ev.pMoveType[chosen];
                    ev.moveSpeed = rmxpEventSpeedToPx(ev.pMoveSpeed[chosen]);
                    ev.moveFreq = ev.pMoveFreq[chosen];
                    ev.stopCount = 0;

                    byte[] prc = ev.pPRCodes[chosen];
                    if (ev.moveType == 3 && prc != null && prc.length > 0) {
                        ev.pageRouteCodes = prc;
                        ev.pageRouteArgs = ev.pPRArgs[chosen];
                        ev.pageRouteLen = prc.length;
                        ev.pageRouteIdx = 0;
                        ev.pageRouteRepeat = (ev.pPRFlags[chosen] & 1) != 0;
                        ev.pageRouteSkippable = (ev.pPRFlags[chosen] & 2) != 0;
                        ev.pageRouteWait = 0;
                    } else {
                        ev.pageRouteLen = 0;
                        ev.pageRouteIdx = 0;
                    }
                } else {
                    ev.charId = -1;
                    ev.oniType = MapEvent.ONI_NONE;
                    ev.tileSlot = 0;
                    ev.through = true;
                    ev.alwaysOnTop = false;
                    ev.moveType = 0;
                    ev.pageRouteLen = 0;
                }
            }
        }
    }

    public static int rmxpEventSpeedToPx(int rmxpSpeed) {
        if (rmxpSpeed <= 2) return 1;
        if (rmxpSpeed == 3) return 2;
        if (rmxpSpeed == 4) return 3; // Slightly under player's 4px/f so chases feel tense & fair
        if (rmxpSpeed == 5) return 4; // Matches player 1:1
        return 8;
    }

    public MapEvent getEventById(int evId) {
        if (events == null) return null;
        for (int i = 0; i < events.length; i++) {
            if (events[i].id == evId) return events[i];
        }
        return null;
    }

    public boolean canStep(int fromX, int fromY, int dir, int ignoreEvId, boolean ignoreEvents) {
        int dx = 0, dy = 0;
        int leaveBit = 0, enterBit = 0;
        if (dir == 2)      { dy = 1;  leaveBit = 1; enterBit = 8; }
        else if (dir == 4) { dx = -1; leaveBit = 2; enterBit = 4; }
        else if (dir == 6) { dx = 1;  leaveBit = 4; enterBit = 2; }
        else if (dir == 8) { dy = -1; leaveBit = 8; enterBit = 1; }
        else return false;

        int nx = fromX + dx;
        int ny = fromY + dy;
        if (nx < 0 || ny < 0 || nx >= width || ny >= height) return false;

        int cFrom = collision[fromY * width + fromX] & 0xFF;
        int cTo = collision[ny * width + nx] & 0xFF;
        if ((cFrom & leaveBit) != 0 || (cTo & enterBit) != 0) return false;

        if (!ignoreEvents && events != null) {
            for (int i = 0; i < events.length; i++) {
                MapEvent ev = events[i];
                if (ev.id == ignoreEvId) continue;
                if (ev.tileX == nx && ev.tileY == ny && ev.isBlocking()) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Zero-allocation BFS pathfinder for Ao Oni chasing.
     * Routes cleanly around tables, walls, bookshelves, and doorways.
     * Returns RMXP direction (2=down, 4=left, 6=right, 8=up), or 0 if trapped.
     */
    public int findNextStepToward(
        int fromX, int fromY, int targetX, int targetY, int ignoreEvId, boolean through
    ) {
        if (fromX == targetX && fromY == targetY) return 0;
        if (width <= 0 || height <= 0) return 0;

        // Order initial directions toward target for natural open-room pursuit
        int dx0 = targetX - fromX;
        int dy0 = targetY - fromY;
        int adx = dx0 < 0 ? -dx0 : dx0;
        int ady = dy0 < 0 ? -dy0 : dy0;
        int hMain = dx0 < 0 ? 4 : 6;
        int hAlt = dx0 < 0 ? 6 : 4;
        int vMain = dy0 < 0 ? 8 : 2;
        int vAlt = dy0 < 0 ? 2 : 8;

        if (adx >= ady) {
            dirOrder[0] = hMain;
            dirOrder[1] = vMain;
            dirOrder[2] = vAlt;
            dirOrder[3] = hAlt;
        } else {
            dirOrder[0] = vMain;
            dirOrder[1] = hMain;
            dirOrder[2] = hAlt;
            dirOrder[3] = vAlt;
        }

        // Fast path when through == true (ghost/cutscene): straight toward target
        if (through) {
            if (adx >= ady && dx0 != 0) return hMain;
            if (dy0 != 0) return vMain;
            return hMain;
        }

        bfsGen++;
        if (bfsGen <= 0) {
            for (int i = 0; i < bfsStamp.length; i++) bfsStamp[i] = 0;
            bfsGen = 1;
        }
        int stamp = bfsGen;
        int startIdx = fromY * width + fromX;
        bfsStamp[startIdx] = stamp;

        int head = 0, tail = 0;
        int bestDist = adx + ady;
        int bestDir = 0;

        // Seed 4 neighbors of start tile
        for (int k = 0; k < 4; k++) {
            int d = dirOrder[k];
            int nx = fromX + (d == 4 ? -1 : (d == 6 ? 1 : 0));
            int ny = fromY + (d == 8 ? -1 : (d == 2 ? 1 : 0));
            if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;

            if (nx == targetX && ny == targetY) {
                if (canStep(fromX, fromY, d, ignoreEvId, true)) return d;
                continue;
            }
            if (!canStep(fromX, fromY, d, ignoreEvId, false)) continue;

            int nIdx = ny * width + nx;
            bfsStamp[nIdx] = stamp;
            bfsFirstDir[nIdx] = (byte) d;
            bfsQueue[tail++] = (short) nIdx;

            int dist = abs(targetX - nx) + abs(targetY - ny);
            if (dist < bestDist) {
                bestDist = dist;
                bestDir = d;
            }
        }

        int expanded = 0;
        while (head < tail && expanded < 512) {
            int cIdx = bfsQueue[head++] & 0xFFFF;
            expanded++;
            int cx = cIdx % width;
            int cy = cIdx / width;
            int firstD = bfsFirstDir[cIdx] & 0xFF;

            for (int k = 0; k < 4; k++) {
                int d = dirOrder[k];
                int nx = cx + (d == 4 ? -1 : (d == 6 ? 1 : 0));
                int ny = cy + (d == 8 ? -1 : (d == 2 ? 1 : 0));
                if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;

                if (nx == targetX && ny == targetY) {
                    if (canStep(cx, cy, d, ignoreEvId, true)) return firstD;
                    continue;
                }
                int nIdx = ny * width + nx;
                if (bfsStamp[nIdx] == stamp) continue;
                if (!canStep(cx, cy, d, ignoreEvId, false)) continue;

                bfsStamp[nIdx] = stamp;
                bfsFirstDir[nIdx] = (byte) firstD;
                bfsQueue[tail++] = (short) nIdx;

                int dist = abs(targetX - nx) + abs(targetY - ny);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestDir = firstD;
                }
            }
        }

        if (bestDir != 0) return bestDir;
        // Final fallback: pick any passable step prioritizing target axes
        for (int k = 0; k < 4; k++) {
            if (canStep(fromX, fromY, dirOrder[k], ignoreEvId, false)) {
                return dirOrder[k];
            }
        }
        return 0;
    }

    /**
     * Checks whether a straight cardinal line up to maxDist tiles reaches (targetX, targetY)
     * without hitting a wall or blocking event (used for Fuwatty/Blockman lunge locks).
     */
    public boolean hasClearLine(
        int fromX, int fromY, int dir, int maxDist, int targetX, int targetY, int ignoreEvId
    ) {
        int dx = dir == 4 ? -1 : (dir == 6 ? 1 : 0);
        int dy = dir == 8 ? -1 : (dir == 2 ? 1 : 0);
        if (dx == 0 && dy == 0) return false;
        int cx = fromX, cy = fromY;
        for (int step = 0; step < maxDist; step++) {
            int nx = cx + dx;
            int ny = cy + dy;
            if (nx == targetX && ny == targetY) {
                return canStep(cx, cy, dir, ignoreEvId, true);
            }
            if (!canStep(cx, cy, dir, ignoreEvId, false)) return false;
            cx = nx;
            cy = ny;
        }
        return false;
    }

    private static int abs(int v) {
        return v < 0 ? -v : v;
    }
}
