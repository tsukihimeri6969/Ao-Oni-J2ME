public class MapEvent {
    public static final int TILE = 16;

    // Late-game Ao Oni variant classifications
    public static final int ONI_NONE          = 0;
    public static final int ONI_CLASSIC       = 1; // Standard Ao Oni: steady BFS pursuer
    public static final int ONI_FUWATTY       = 2; // Blockman/Fuwatty: high-speed straight-line dash
    public static final int ONI_INFECTED_FAST = 3; // Mika / Takuro Oni: relentless fast BFS
    public static final int ONI_TAKESHI       = 4; // Takeshi Oni: slightly twitchy/hesitant BFS
    public static final int ONI_SQUATTO       = 5; // Squatto Oni: heavy stomping pace + wide reach
    public static final int ONI_JELLYFISH     = 6; // Jellyfish Oni: frenetic bursts + erratic veers
    public static final int ONI_BLOB          = 7; // Blob / Sludge / Slug Oni: slow relentless ooze
    public static final int ONI_HUNCHBACK     = 8; // Hunchback Oni: steady relentless basement stalker

    public int id;
    public int tileX, tileY;
    public int px, py;
    public int targetX, targetY;
    public int dir = 2; // RMXP: 2=down, 4=left, 6=right, 8=up
    public int pattern = 0;
    public int stepAnim = 0;
    public boolean moving = false;
    public boolean erased = false;
    public boolean through = false;
    public boolean alwaysOnTop = false;
    public boolean dirFix = false;
    public boolean tilePassable = false;
    public int opacity = 255;

    // Oni variant runtime behavior state
    public int oniType = ONI_NONE;
    public int dashDir = 0;
    public int dashSteps = 0;
    public int dashCooldown = 0;
    public int aiTicks = 0;

    // Autonomous movement settings from active page
    public int moveType = 0;  // 0=fixed, 1=random, 2=approach, 3=custom
    public int moveSpeed = 3; // pixels per frame at 20 FPS
    public int moveFreq = 3;  // RMXP 1..6
    public int stopCount = 0;

    // Page-defined custom route (when moveType == 3)
    public byte[] pageRouteCodes;
    public short[] pageRouteArgs;
    public int pageRouteLen = 0;
    public int pageRouteIdx = 0;
    public boolean pageRouteRepeat = false;
    public boolean pageRouteSkippable = false;
    public int pageRouteWait = 0;

    public int activePage = -1;
    public int charId = -1;
    public int tileSlot = 0;

    // Event pages (parallel arrays per event to keep class count low)
    public int pageCount;
    public int[] pCondFlags;
    public int[] pSw1, pSw2, pVarId, pVarVal, pSelfSw;
    public int[] pGStr, pGTile, pGDir, pGPat, pGOpac;
    public int[] pTrigger, pFlags;
    public int[] pMoveType, pMoveSpeed, pMoveFreq, pPRFlags;
    public byte[][] pPRCodes;
    public short[][] pPRArgs;
    public byte[][] pCode;

    // Forced move route (from OP_MOVE_EVENT 0x0B)
    public byte[] routeCodes;
    public short[] routeArgs;
    public String[] routeStrings;
    public int routeLen = 0;
    public int routeIdx = 0;
    public boolean routeRepeat = false;
    public boolean routeSkippable = false;
    public int routeWait = 0;
    public int routeStuckTicks = 0;

    public void setTilePos(int tx, int ty) {
        tileX = tx;
        tileY = ty;
        px = tx * TILE;
        py = ty * TILE;
        targetX = px;
        targetY = py;
        moving = false;
        routeStuckTicks = 0;
        dashDir = 0;
        dashSteps = 0;
    }

    public boolean hasActivePage() {
        return !erased && activePage >= 0 && activePage < pageCount;
    }

    public int getActiveTrigger() {
        return hasActivePage() ? pTrigger[activePage] : -1;
    }

    public byte[] getActiveCode() {
        return hasActivePage() ? pCode[activePage] : null;
    }

    public boolean isBlocking() {
        if (!hasActivePage() || through || opacity == 0) return false;
        if (charId >= 0) return true;
        return (tileSlot > 0 && !tilePassable);
    }

    public boolean isRouteActive() {
        return routeLen > 0 && routeIdx < routeLen;
    }

    public void clearMoveRoute() {
        routeLen = 0;
        routeIdx = 0;
        routeWait = 0;
        routeStuckTicks = 0;
    }

    public void setMoveRoute(
        byte[] codes, short[] args, String[] pool,
        int len, boolean repeat, boolean skippable
    ) {
        routeCodes = codes;
        routeArgs = args;
        routeStrings = pool;
        routeLen = len;
        routeIdx = 0;
        routeRepeat = repeat;
        routeSkippable = skippable;
        routeWait = 0;
        routeStuckTicks = 0;
    }
}
