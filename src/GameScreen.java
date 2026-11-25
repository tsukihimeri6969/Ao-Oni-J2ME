import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.game.GameCanvas;

public class GameScreen extends GameCanvas implements Runnable {
    private static final int TILE = 16;
    private static final int FPS_DELAY = 50; // 20 FPS

    private static final int MODE_TITLE = 0;
    private static final int MODE_PLAY = 1;
    private static final int MODE_MENU = 2;
    private static final int MODE_GAMEOVER = 3;
    private static final int MODE_ERROR = 4;

    private static final int FACE_BOX_SIZE = 44;
    private static final int MAX_VISIBLE_MSG_LINES = 4;

    private static final String[] TITLE_ITEMS = {
        "NEW GAME",
        "CONTINUE",
        "SHUTDOWN"
    };

    private static final int[] RMXP_COLORS = {
        0xFFFFFF, // 0: white
        0x80B8FF, // 1: blue
        0xFF6060, // 2: red
        0x70E870, // 3: green
        0x70E8F0, // 4: cyan
        0xE080FF, // 5: purple
        0xFFE860, // 6: yellow
        0xA0A0A0  // 7: gray
    };

    private final AoOniMIDlet midlet;
    private final GameData data = new GameData();
    private final RoomMap map = new RoomMap();
    private final EventVM mainVm = new EventVM(false);

    // Parallel event VMs (map parallel events + parallel common events)
    private static final int MAX_PAR_VMS = 8;
    private final EventVM[] parVms = new EventVM[MAX_PAR_VMS];
    private final int[] parKeys = new int[MAX_PAR_VMS];

    private volatile boolean running;
    private volatile boolean paused;
    private int mode = MODE_TITLE;
    private int titleSel = 0;
    private String errorText = "";
    private Image gameOverImg = null;
    private int gameOverSel = 0;
    private int gameOverTicks = 0;
    private int rngSeed = 0x13579BDF;

    // Rigid live D-pad input stack (no GameCanvas.getKeyStates() latching)
    private final int[] heldDirs = new int[4];
    private int heldCount = 0;
    private int tapDir = 0;

    // Player state
    private int playerTileX, playerTileY;
    private int playerPx, playerPy;
    private int playerTargetX, playerTargetY;
    private int playerDir = 2; // RMXP: 2=down, 4=left, 6=right, 8=up
    private int playerPattern = 0;
    private int playerStepFrame = 0;
    private boolean playerMoving = false;
    private boolean playerThrough = false;
    public boolean playerTransparent = false;
    private int playerSpeed = 4; // 16px / 4 = 4 frames = 200ms/tile (rigid 1:1 RMXP speed)

    // Player move route
    private byte[] pRouteCodes;
    private short[] pRouteArgs;
    private int pRouteLen = 0;
    private int pRouteIdx = 0;
    private boolean pRouteRepeat = false;
    private boolean pRouteSkippable = false;
    private int pRouteWait = 0;
    private int pRouteStuckTicks = 0;

    // Camera & timers
    private int camX, camY;
    public int frameCount = 0;
    public int stepCount = 0;
    public int saveCount = 0;
    private int timerFrames = 0;
    private boolean timerActive = false;
    public boolean menuEnabled = true;

    // Screen effects
    private boolean fadedOut = false;
    private int flashFrames = 0;
    private int shakeFrames = 0;
    private int shakePower = 0;

    // Active pictures (RMXP picture slots 1..16)
    private static final int MAX_PICS = 16;
    private Image[] picImgs = new Image[MAX_PICS];
    private int[] picX = new int[MAX_PICS];
    private int[] picY = new int[MAX_PICS];
    private boolean[] picVisible = new boolean[MAX_PICS];

    // Dialogue / Face / Choice / Number / Button UI state
    private final Font uiFont = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
    public int textPos = 2;   // 0=top, 1=middle, 2=bottom
    public int textFrame = 0; // 0=normal, 1=transparent
    private String[] msgLines = null;
    private int msgPageStart = 0;
    private int msgColor = 0xFFFFFF;
    private Image msgFaceImg = null;
    private int msgDockedPicSlot = -1;

    private String[] choiceLabels = null;
    private int choiceSel = 0;
    private int choiceCancelType = 0;

    private boolean numInputActive = false;
    private int numDigits = 4;
    private int[] numBuf = new int[8];
    private int numCursor = 0;

    // Character Name Input screen (OP_NAME_INPUT / RMXP 303)
    private static final String[] NAME_GRID = {
        "ABCDEFGHIJ",
        "KLMNOPQRST",
        "UVWXYZ0123",
        "abcdefghij",
        "klmnopqrst",
        "uvwxyz4567",
        "89-_!?.'/ "
    };
    private boolean nameInputActive = false;
    private int nameActorId = 2;
    private int nameMaxLen = 10;
    private char[] nameBuf = new char[12];
    private int nameLen = 0;
    private int nameRow = 0; // 0..6 = character grid, 7 = [DEL] [CLR] [OK]
    private int nameCol = 0; // 0..9 on grid rows
    private int nameAct = 2; // 0=DEL, 1=CLR, 2=OK on action row

    private boolean btnInputActive = false;
    private int menuSel = 0;
    private int menuSubState = 0; // 0=browse, 1=action prompt (USE/MERGE), 2=pick 2nd item to merge
    private int menuActionSel = 0; // 0=USE, 1=MERGE
    private int mergeFirstItemId = -1;
    private String menuStatus = "";

    public GameScreen(AoOniMIDlet midlet) {
        super(false);
        this.midlet = midlet;
        setFullScreenMode(true);
        for (int i = 0; i < MAX_PAR_VMS; i++) {
            parVms[i] = new EventVM(true);
            parKeys[i] = -1;
        }
        try {
            data.loadMetadata();
            data.loadPakIndex();
            RoomMap.loadCommonEvents(data);
        } catch (Exception e) {
            mode = MODE_ERROR;
            errorText = "Boot error: " + e.getMessage();
        }
    }

    public void start() {
        running = true;
        paused = false;
        new Thread(this).start();
    }

    public void pause() {
        paused = true;
        clearHeldDirs();
    }

    public void resume() {
        paused = false;
        clearHeldDirs();
    }

    public void stop() {
        running = false;
    }

    protected void hideNotify() {
        clearHeldDirs();
    }

    private void clearHeldDirs() {
        heldCount = 0;
        tapDir = 0;
    }

    private void pushHeldDir(int dir) {
        removeHeldDir(dir);
        if (heldCount < heldDirs.length) {
            heldDirs[heldCount++] = dir;
        }
        tapDir = dir;
    }

    private void removeHeldDir(int dir) {
        for (int i = 0; i < heldCount; i++) {
            if (heldDirs[i] == dir) {
                for (int j = i; j < heldCount - 1; j++) {
                    heldDirs[j] = heldDirs[j + 1];
                }
                heldCount--;
                break;
            }
        }
    }

    private int activeMoveDir() {
        if (heldCount > 0) {
            int d = heldDirs[heldCount - 1];
            tapDir = 0;
            return d;
        }
        if (tapDir != 0) {
            int d = tapDir;
            tapDir = 0;
            return d;
        }
        return 0;
    }

    // D-pad only (explicitly excludes KEY_NUM0..KEY_NUM9)
    private int mapDpadDir(int keyCode) {
        if (keyCode >= KEY_NUM0 && keyCode <= KEY_NUM9) return 0;
        if (keyCode == -1) return 8;
        if (keyCode == -2) return 2;
        if (keyCode == -3) return 4;
        if (keyCode == -4) return 6;
        int ga = 0;
        try { ga = getGameAction(keyCode); } catch (Exception e) {}
        if (ga == UP) return 8;
        if (ga == DOWN) return 2;
        if (ga == LEFT) return 4;
        if (ga == RIGHT) return 6;
        return 0;
    }

    private void startNewGame() {
        data.resetState();
        frameCount = 0;
        stepCount = 0;
        timerActive = false;
        timerFrames = 0;
        menuEnabled = true;
        playerTransparent = false;
        playerThrough = false;
        playerSpeed = 4;
        fadedOut = false;
        clearHeldDirs();
        clearPictures();
        clearMessageState();
        mainVm.stop();
        stopParallelVms();
        transferPlayer(data.startMap, data.startX, data.startY, 2);
        mode = MODE_PLAY;
    }

    private boolean continueIfSaved() {
        int[] meta = new int[5];
        if (!data.loadState(meta)) return false;
        data.setSwitch(50, false);
        frameCount = meta[4];
        timerActive = false;
        timerFrames = 0;
        menuEnabled = true;
        playerTransparent = false;
        playerThrough = false;
        playerSpeed = 4;
        fadedOut = false;
        clearHeldDirs();
        clearPictures();
        clearMessageState();
        mainVm.stop();
        stopParallelVms();
        transferPlayer(meta[0], meta[1], meta[2], meta[3]);
        mode = MODE_PLAY;
        return true;
    }

    private void continueGame() {
        int[] meta = new int[5];
        if (!data.loadState(meta)) return;
        data.setSwitch(50, false);
        frameCount = meta[4];
        timerActive = false;
        timerFrames = 0;
        menuEnabled = true;
        playerTransparent = false;
        playerThrough = false;
        playerSpeed = 4;
        fadedOut = false;
        clearHeldDirs();
        clearPictures();
        clearMessageState();
        mainVm.stop();
        stopParallelVms();
        transferPlayer(meta[0], meta[1], meta[2], meta[3]);
        mode = MODE_PLAY;
    }

    public void transferPlayer(int mapId, int tx, int ty, int dir) {
        try {
            clearHeldDirs();
            stopParallelVms();
            data.clearCharCache();
            map.load(data, mapId);
            data.getTilesetImage(map.tsId);
            playerTileX = tx;
            playerTileY = ty;
            playerPx = tx * TILE;
            playerPy = ty * TILE;
            playerTargetX = playerPx;
            playerTargetY = playerPy;
            playerMoving = false;
            playerPattern = 0;
            playerStepFrame = 0;
            pRouteLen = 0;
            if (dir == 2 || dir == 4 || dir == 6 || dir == 8) {
                playerDir = dir;
            }
            if (map.bgmName.length() > 0) {
                handleAudio(0, map.bgmName, 100);
            }
            updateCamera();
        } catch (Exception e) {
            mode = MODE_ERROR;
            errorText = "Map " + mapId + ": " + e.getMessage();
        }
    }

    private void stopParallelVms() {
        for (int i = 0; i < MAX_PAR_VMS; i++) {
            parVms[i].stop();
            parKeys[i] = -1;
        }
    }

    private void clearPictures() {
        for (int i = 0; i < MAX_PICS; i++) {
            picImgs[i] = null;
            picVisible[i] = false;
        }
    }

    private void clearMessageState() {
        msgLines = null;
        msgPageStart = 0;
        msgFaceImg = null;
        msgDockedPicSlot = -1;
        choiceLabels = null;
        numInputActive = false;
        nameInputActive = false;
        btnInputActive = false;
    }

    public void run() {
        Graphics g = getGraphics();
        while (running) {
            long t0 = System.currentTimeMillis();
            if (!paused) {
                if (mode == MODE_PLAY) {
                    updatePlay();
                } else if (mode == MODE_GAMEOVER) {
                    gameOverTicks++;
                }
                render(g);
                flushGraphics();
            }
            long dt = System.currentTimeMillis() - t0;
            long sl = FPS_DELAY - dt;
            if (sl > 0) {
                try { Thread.sleep(sl); } catch (InterruptedException ie) {}
            }
        }
    }

    private void updatePlay() {
        frameCount++;
        if (timerActive && timerFrames > 0) {
            timerFrames--;
        }
        if (flashFrames > 0) flashFrames--;
        if (shakeFrames > 0) shakeFrames--;

        // 1. Update foreground EventVM or check autorun triggers
        if (mainVm.active) {
            mainVm.update(this, data, map);
        } else {
            checkAutorunTriggers();
        }

        // 2. Update parallel map events and parallel common events
        updateParallelEvents();

        // 3. Update player movement (move route or rigid D-pad)
        updatePlayerMovement();

        // 4. Update all map event move routes
        updateMapEvents();

        // 5. Catch check if an Event Touch (Oni) shares tile with player
        if (!mainVm.active) {
            checkEventOverlapTouch();
        }

        // 6. Center & clamp camera
        updateCamera();
    }

    private void checkAutorunTriggers() {
        if (map.events != null) {
            for (int i = 0; i < map.events.length; i++) {
                MapEvent ev = map.events[i];
                if (ev.getActiveTrigger() == 3) {
                    clearHeldDirs();
                    mainVm.start(ev.id, ev.activePage, ev.getActiveCode(), map.strings);
                    mainVm.update(this, data, map);
                    return;
                }
            }
        }
        if (RoomMap.commonIds != null) {
            for (int i = 0; i < RoomMap.commonIds.length; i++) {
                if (RoomMap.commonTriggers[i] == 1 && data.getSwitch(RoomMap.commonSwitches[i])) {
                    clearHeldDirs();
                    mainVm.start(0, i, RoomMap.commonCodes[i], RoomMap.commonStrings);
                    mainVm.update(this, data, map);
                    return;
                }
            }
        }
    }

    private final boolean[] parSeen = new boolean[MAX_PAR_VMS];

    private void updateParallelEvents() {
        for (int i = 0; i < MAX_PAR_VMS; i++) {
            parSeen[i] = false;
        }
        if (map.events != null) {
            for (int i = 0; i < map.events.length; i++) {
                if (mode != MODE_PLAY) return;
                MapEvent ev = map.events[i];
                if (ev.getActiveTrigger() == 4) {
                    tickParallelKey(ev.id, ev.id, ev.activePage, ev.getActiveCode(), map.strings);
                }
            }
        }
        if (RoomMap.commonIds != null) {
            for (int i = 0; i < RoomMap.commonIds.length; i++) {
                if (mode != MODE_PLAY) return;
                if (RoomMap.commonTriggers[i] == 2 && data.getSwitch(RoomMap.commonSwitches[i])) {
                    int key = 0x10000 | RoomMap.commonIds[i];
                    tickParallelKey(key, 0, i, RoomMap.commonCodes[i], RoomMap.commonStrings);
                }
            }
        }
        for (int i = 0; i < MAX_PAR_VMS; i++) {
            if (!parSeen[i] && parKeys[i] != -1) {
                parVms[i].stop();
                parKeys[i] = -1;
            }
        }
    }

    private void tickParallelKey(int key, int ownerEvId, int pageIdx, byte[] code, String[] pool) {
        int slot = -1;
        for (int i = 0; i < MAX_PAR_VMS; i++) {
            if (parKeys[i] == key) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            for (int i = 0; i < MAX_PAR_VMS; i++) {
                if (!parVms[i].active) {
                    slot = i;
                    parKeys[i] = key;
                    break;
                }
            }
        }
        if (slot < 0) return;
        parSeen[slot] = true;
        EventVM vm = parVms[slot];
        if (!vm.active || vm.ownerPage != pageIdx) {
            vm.start(ownerEvId, pageIdx, code, pool);
        }
        vm.update(this, data, map);
    }

    private void updatePlayerMovement() {
        if (!playerMoving) {
            if (pRouteLen > 0 && pRouteIdx < pRouteLen) {
                stepPlayerRoute();
            } else if (!mainVm.active && msgLines == null && choiceLabels == null
                       && !numInputActive && !nameInputActive && !btnInputActive) {
                int reqDir = activeMoveDir();
                if (reqDir != 0) {
                    playerDir = reqDir;
                    if (!tryStepPlayer(reqDir, true)) {
                        // Rigidly snap to idle frame when facing a wall
                        playerPattern = 0;
                        playerStepFrame = 0;
                    }
                } else {
                    playerPattern = 0;
                    playerStepFrame = 0;
                }
            } else {
                playerPattern = 0;
                playerStepFrame = 0;
            }
        }

        if (playerMoving) {
            int sp = playerSpeed;
            if (playerPx < playerTargetX) {
                playerPx += sp; if (playerPx > playerTargetX) playerPx = playerTargetX;
            } else if (playerPx > playerTargetX) {
                playerPx -= sp; if (playerPx < playerTargetX) playerPx = playerTargetX;
            }
            if (playerPy < playerTargetY) {
                playerPy += sp; if (playerPy > playerTargetY) playerPy = playerTargetY;
            } else if (playerPy > playerTargetY) {
                playerPy -= sp; if (playerPy < playerTargetY) playerPy = playerTargetY;
            }
            playerStepFrame++;
            if (playerPx == playerTargetX && playerPy == playerTargetY) {
                playerMoving = false;
                playerTileX = playerPx / TILE;
                playerTileY = playerPy / TILE;
                playerPattern = 0;
                stepCount++;
                if (!mainVm.active) {
                    checkTouchAt(playerTileX, playerTileY);
                }
            } else {
                int stepDiv = (sp >= 4) ? 1 : 2;
                playerPattern = ((playerStepFrame / stepDiv) & 3);
            }
        }
    }

    private boolean tryStepPlayer(int dir, boolean checkTouch) {
        int nx = playerTileX + dirDx(dir);
        int ny = playerTileY + dirDy(dir);
        if (playerThrough || map.canStep(playerTileX, playerTileY, dir, -1, false)) {
            if (nx >= 0 && ny >= 0 && nx < map.width && ny < map.height) {
                playerTargetX = nx * TILE;
                playerTargetY = ny * TILE;
                playerMoving = true;
                return true;
            }
        }
        if (checkTouch) {
            checkTouchAt(nx, ny);
        }
        return false;
    }

    private boolean isHostileOni(MapEvent ev) {
        if (ev == null || !ev.hasActivePage() || ev.opacity == 0) return false;
        if (ev.oniType == MapEvent.ONI_NONE) return false;
        if (playerTransparent || data.getSwitch(150)) return false;
        return data.getSwitch(49)
            || ev.moveType == 2
            || ev.moveType == 3
            || ev.getActiveTrigger() == 2
            || ev.isRouteActive();
    }

    public boolean isUiBusy() {
        return msgLines != null || choiceLabels != null || numInputActive || nameInputActive || btnInputActive;
    }

    public void startDeathCommonEvent(int ceId) {
        clearHeldDirs();
        clearMessageState();
        stopParallelVms();
        timerActive = false;
        pRouteLen = 0;
        if (map.events != null) {
            for (int i = 0; i < map.events.length; i++) {
                map.events[i].clearMoveRoute();
                map.events[i].dashSteps = 0;
                map.events[i].dashDir = 0;
            }
        }
        int idx = RoomMap.findCommonIndex(ceId);
        if (idx >= 0) {
            mainVm.start(0, idx, RoomMap.commonCodes[idx], RoomMap.commonStrings);
            mainVm.update(this, data, map);
        } else {
            triggerGameOver();
        }
    }

    private void triggerOniCatch(MapEvent ev) {
        if (mode != MODE_PLAY || mainVm.active || data.getSwitch(150)) return;
        if (ev != null) {
            ev.clearMoveRoute();
            ev.dashSteps = 0;
            ev.dashDir = 0;
        }
        int trig = (ev != null) ? ev.getActiveTrigger() : -1;
        byte[] evCode = (ev != null) ? ev.getActiveCode() : null;
        // If this Oni event has its own non-trivial Touch script, run it; otherwise fire Ao Oni's CE 01 ('gameover')
        if ((trig == 1 || trig == 2) && evCode != null && evCode.length > 1) {
            clearHeldDirs();
            stopParallelVms();
            mainVm.start(ev.id, ev.activePage, evCode, map.strings);
            mainVm.update(this, data, map);
            return;
        }
        data.setSwitch(50, true);
        startDeathCommonEvent(1);
    }

    private void checkTouchAt(int tx, int ty) {
        if (map.events == null || mainVm.active) return;
        for (int i = 0; i < map.events.length; i++) {
            MapEvent ev = map.events[i];
            if (!ev.hasActivePage()) continue;
            if (ev.tileX == tx && ev.tileY == ty) {
                if (isHostileOni(ev)) {
                    triggerOniCatch(ev);
                    return;
                }
                int trig = ev.getActiveTrigger();
                if (trig == 1 || trig == 2) {
                    clearHeldDirs();
                    mainVm.start(ev.id, ev.activePage, ev.getActiveCode(), map.strings);
                    return;
                }
            }
        }
    }

    private void stepPlayerRoute() {
        if (pRouteWait > 0) {
            pRouteWait--;
            return;
        }
        int mc = pRouteCodes[pRouteIdx] & 0xFF;
        int ma = pRouteArgs[pRouteIdx];
        boolean advance = true;

        if (mc >= 1 && mc <= 4) {
            int d = mc == 1 ? 2 : (mc == 2 ? 4 : (mc == 3 ? 6 : 8));
            playerDir = d;
            boolean ok = tryStepPlayer(d, false);
            if (!ok && !pRouteSkippable) advance = false;
        } else if (mc == 12) {
            boolean ok = tryStepPlayer(playerDir, false);
            if (!ok && !pRouteSkippable) advance = false;
        } else if (mc == 13) {
            boolean ok = tryStepPlayer(oppositeDir(playerDir), false);
            if (!ok && !pRouteSkippable) advance = false;
        } else if (mc == 15) {
            pRouteWait = (ma + 1) >> 1;
        } else if (mc >= 16 && mc <= 19) {
            playerDir = (mc - 15) * 2;
        } else if (mc == 22) {
            playerDir = oppositeDir(playerDir);
        } else if (mc == 27) {
            data.setSwitch(ma, true); map.refreshPages(data);
        } else if (mc == 28) {
            data.setSwitch(ma, false); map.refreshPages(data);
        } else if (mc == 29) {
            playerSpeed = rmxpSpeedToPx(ma);
        } else if (mc == 37) {
            playerThrough = true;
        } else if (mc == 38) {
            playerThrough = false;
        }

        if (advance) {
            pRouteStuckTicks = 0;
        } else {
            pRouteStuckTicks++;
        }
        if (advance || pRouteStuckTicks >= 8) {
            pRouteStuckTicks = 0;
            pRouteIdx++;
            if (pRouteIdx >= pRouteLen) {
                if (pRouteRepeat) pRouteIdx = 0;
                else pRouteLen = 0;
            }
        }
    }

    private void checkEventOverlapTouch() {
        if (map.events == null || mainVm.active) return;
        for (int i = 0; i < map.events.length; i++) {
            MapEvent ev = map.events[i];
            if (!ev.hasActivePage() || ev.opacity == 0) continue;

            if (isHostileOni(ev)) {
                int pdx = ev.px - playerPx;
                int pdy = ev.py - playerPy;
                if (pdx < 0) pdx = -pdx;
                if (pdy < 0) pdy = -pdy;
                boolean caught = (ev.tileX == playerTileX && ev.tileY == playerTileY)
                    || (pdx < 12 && pdy < 12);
                // Squatto Oni has heavy 1-tile slam reach when actively pursuing
                if (!caught && ev.oniType == MapEvent.ONI_SQUATTO
                        && (ev.moveType == 2 || ev.moveType == 3 || ev.isRouteActive())) {
                    int tdx = ev.tileX - playerTileX;
                    int tdy = ev.tileY - playerTileY;
                    if (tdx < 0) tdx = -tdx;
                    if (tdy < 0) tdy = -tdy;
                    if ((tdx + tdy) == 1) caught = true;
                }
                if (caught) {
                    triggerOniCatch(ev);
                    return;
                }
            }

            int trig = ev.getActiveTrigger();
            if (trig == 2 && ev.tileX == playerTileX && ev.tileY == playerTileY) {
                clearHeldDirs();
                ev.clearMoveRoute();
                mainVm.start(ev.id, ev.activePage, ev.getActiveCode(), map.strings);
                return;
            }
        }
    }

    private int nextRand(int bound) {
        rngSeed = rngSeed * 1103515245 + 12345;
        return ((rngSeed >>> 16) & 0x7FFF) % bound;
    }

    private static int freqToCooldown(int moveFreq, int moveType, int moveSpeed, int oniType) {
        if (moveType == 2 && oniType != MapEvent.ONI_NONE) {
            if (oniType == MapEvent.ONI_FUWATTY) return 0;
            if (oniType == MapEvent.ONI_INFECTED_FAST) return 0;
            if (oniType == MapEvent.ONI_JELLYFISH) return 0;
            if (oniType == MapEvent.ONI_TAKESHI) return 2;
            if (oniType == MapEvent.ONI_SQUATTO) return 3;
            if (oniType == MapEvent.ONI_BLOB) return 5;
        }
        if (moveFreq >= 6) {
            return (moveType == 2 && moveSpeed == 3) ? 1 : 0;
        }
        if (moveFreq == 5) return 3;
        if (moveFreq == 4) return 10;
        if (moveFreq == 3) return 20;
        if (moveFreq == 2) return 36;
        return 56;
    }

    private static int effectiveEventSpeed(MapEvent ev) {
        if (ev.oniType == MapEvent.ONI_FUWATTY && ev.dashSteps > 0) {
            return 8; // 2 frames/tile straight-line lunge (twice Hiroshi's speed)
        }
        if (ev.oniType == MapEvent.ONI_INFECTED_FAST && (ev.moveType == 2 || ev.isRouteActive())) {
            return ev.moveSpeed < 4 ? 4 : ev.moveSpeed;
        }
        if (ev.oniType == MapEvent.ONI_JELLYFISH && (ev.moveType == 2 || ev.isRouteActive())) {
            return 4;
        }
        if (ev.oniType == MapEvent.ONI_SQUATTO) {
            return ev.moveSpeed > 2 ? 2 : ev.moveSpeed;
        }
        if (ev.oniType == MapEvent.ONI_BLOB) {
            return ev.moveSpeed > 2 ? 2 : 1;
        }
        return ev.moveSpeed;
    }

    private void updateMapEvents() {
        if (map.events == null) return;
        int goalX = playerMoving ? (playerTargetX / TILE) : playerTileX;
        int goalY = playerMoving ? (playerTargetY / TILE) : playerTileY;

        for (int i = 0; i < map.events.length; i++) {
            MapEvent ev = map.events[i];
            if (!ev.hasActivePage()) continue;

            ev.aiTicks++;
            if (ev.dashCooldown > 0) ev.dashCooldown--;

            // Squatto Oni keeps its rhythmic stomping/squatting animation even while idle
            if (!ev.moving && ev.oniType == MapEvent.ONI_SQUATTO) {
                ev.pattern = (ev.aiTicks >> 3) & 3;
            }

            if (!ev.moving) {
                if (ev.oniType == MapEvent.ONI_FUWATTY && ev.dashSteps > 0) {
                    continueFuwattyDash(ev);
                } else if (ev.isRouteActive()) {
                    stepEventRoute(ev, goalX, goalY);
                } else if (!mainVm.active && ev.moveType != 0) {
                    ev.stopCount++;
                    int cd = freqToCooldown(ev.moveFreq, ev.moveType, ev.moveSpeed, ev.oniType);
                    if (ev.stopCount >= cd) {
                        ev.stopCount = 0;
                        stepEventAutonomous(ev, goalX, goalY);
                    }
                }
            }

            if (ev.moving) {
                int sp = effectiveEventSpeed(ev);
                if (ev.px < ev.targetX) {
                    ev.px += sp; if (ev.px > ev.targetX) ev.px = ev.targetX;
                } else if (ev.px > ev.targetX) {
                    ev.px -= sp; if (ev.px < ev.targetX) ev.px = ev.targetX;
                }
                if (ev.py < ev.targetY) {
                    ev.py += sp; if (ev.py > ev.targetY) ev.py = ev.targetY;
                } else if (ev.py > ev.targetY) {
                    ev.py -= sp; if (ev.py < ev.targetY) ev.py = ev.targetY;
                }
                ev.stepAnim++;
                if (ev.px == ev.targetX && ev.py == ev.targetY) {
                    ev.moving = false;
                    ev.tileX = ev.px / TILE;
                    ev.tileY = ev.py / TILE;
                    ev.pattern = 0;
                    if (ev.oniType == MapEvent.ONI_FUWATTY && ev.dashSteps > 0) {
                        ev.dashSteps--;
                        if (ev.dashSteps == 0) {
                            ev.dashDir = 0;
                            ev.dashCooldown = 7; // Brief recovery pause after lunge
                        }
                    }
                } else {
                    int stepDiv = (sp >= 4) ? 1 : 2;
                    ev.pattern = ((ev.stepAnim / stepDiv) & 3);
                }
            }
        }
    }

    private void continueFuwattyDash(MapEvent ev) {
        if (ev.dashDir == 0 || ev.dashSteps <= 0) {
            ev.dashDir = 0;
            ev.dashSteps = 0;
            return;
        }
        if (!ev.dirFix) ev.dir = ev.dashDir;
        if (!tryStepEvent(ev, ev.dashDir)) {
            // Slammed into wall/obstacle: end dash and enter recovery window
            ev.dashDir = 0;
            ev.dashSteps = 0;
            ev.dashCooldown = 8;
        }
    }

    /**
     * Unified pursuit dispatcher supporting all late-game Ao Oni variant behaviors.
     */
    private boolean stepOniChase(MapEvent ev, int goalX, int goalY) {
        int ot = ev.oniType;

        // Fuwatty / Blockman: recover briefly after a lunge, or lock a straight-line dash when aligned
        if (ot == MapEvent.ONI_FUWATTY) {
            if (ev.dashCooldown > 0) return true;
            int lineDir = 0;
            if (ev.tileX == goalX && ev.tileY != goalY) {
                lineDir = goalY > ev.tileY ? 2 : 8;
            } else if (ev.tileY == goalY && ev.tileX != goalX) {
                lineDir = goalX > ev.tileX ? 6 : 4;
            }
            if (lineDir != 0 && map.hasClearLine(ev.tileX, ev.tileY, lineDir, 7, goalX, goalY, ev.id)) {
                ev.dashDir = lineDir;
                ev.dashSteps = 5;
                if (!ev.dirFix) ev.dir = lineDir;
                return tryStepEvent(ev, lineDir);
            }
        }

        // Takeshi Oni: occasional brief hesitation
        if (ot == MapEvent.ONI_TAKESHI && (ev.aiTicks % 9) == 0) {
            return true;
        }

        // Jellyfish Oni: frenetic movement with 25% erratic perpendicular veer
        if (ot == MapEvent.ONI_JELLYFISH && nextRand(4) == 0) {
            int bfsD = map.findNextStepToward(ev.tileX, ev.tileY, goalX, goalY, ev.id, ev.through);
            if (bfsD != 0) {
                int alt = (nextRand(2) == 0) ? turnLeft(bfsD) : turnRight(bfsD);
                if (map.canStep(ev.tileX, ev.tileY, alt, ev.id, false)) {
                    if (!ev.dirFix) ev.dir = alt;
                    return tryStepEvent(ev, alt);
                }
            }
        }

        int d = map.findNextStepToward(ev.tileX, ev.tileY, goalX, goalY, ev.id, ev.through);
        if (d == 0) return false;
        if (!ev.dirFix) ev.dir = d;
        return tryStepEvent(ev, d);
    }

    private void stepEventAutonomous(MapEvent ev, int goalX, int goalY) {
        if (ev.moveType == 1) {
            int r = nextRand(4);
            int d = (r + 1) * 2;
            if (!ev.dirFix) ev.dir = d;
            tryStepEvent(ev, d);
        } else if (ev.moveType == 2) {
            stepOniChase(ev, goalX, goalY);
        } else if (ev.moveType == 3 && ev.pageRouteLen > 0) {
            if (ev.pageRouteWait > 0) {
                ev.pageRouteWait--;
                return;
            }
            int mc = ev.pageRouteCodes[ev.pageRouteIdx] & 0xFF;
            int ma = ev.pageRouteArgs[ev.pageRouteIdx];
            boolean ok = execMoveCmd(ev, mc, ma, map.strings, goalX, goalY);
            if (ok || ev.pageRouteSkippable) {
                ev.pageRouteIdx++;
                if (ev.pageRouteIdx >= ev.pageRouteLen) {
                    if (ev.pageRouteRepeat) ev.pageRouteIdx = 0;
                    else ev.pageRouteLen = 0;
                }
            }
        }
    }

    private void stepEventRoute(MapEvent ev, int goalX, int goalY) {
        if (ev.routeWait > 0) {
            ev.routeWait--;
            return;
        }
        int mc = ev.routeCodes[ev.routeIdx] & 0xFF;
        int ma = ev.routeArgs[ev.routeIdx];
        String[] pool = ev.routeStrings != null ? ev.routeStrings : map.strings;
        boolean ok = execMoveCmd(ev, mc, ma, pool, goalX, goalY);

        if (ok) {
            ev.routeStuckTicks = 0;
        } else {
            ev.routeStuckTicks++;
        }

        if (ok || ev.routeSkippable || ev.routeStuckTicks >= 8) {
            ev.routeStuckTicks = 0;
            ev.routeIdx++;
            if (ev.routeIdx >= ev.routeLen) {
                if (ev.routeRepeat) ev.routeIdx = 0;
                else ev.routeLen = 0;
            }
        }
    }

    private boolean execMoveCmd(MapEvent ev, int mc, int ma, String[] pool, int goalX, int goalY) {
        if (mc >= 1 && mc <= 4) {
            int d = mc == 1 ? 2 : (mc == 2 ? 4 : (mc == 3 ? 6 : 8));
            if (!ev.dirFix) ev.dir = d;
            return tryStepEvent(ev, d);
        } else if (mc >= 5 && mc <= 8) {
            int v = (mc == 5 || mc == 6) ? 2 : 8;
            int h = (mc == 5 || mc == 7) ? 4 : 6;
            if (tryStepEvent(ev, v)) { if (!ev.dirFix) ev.dir = v; return true; }
            if (tryStepEvent(ev, h)) { if (!ev.dirFix) ev.dir = h; return true; }
            return false;
        } else if (mc == 9) {
            int d = (nextRand(4) + 1) * 2;
            if (!ev.dirFix) ev.dir = d;
            return tryStepEvent(ev, d);
        } else if (mc == 10) {
            return stepOniChase(ev, goalX, goalY);
        } else if (mc == 11) {
            int d = oppositeDir(stepToward(ev.tileX, ev.tileY, goalX, goalY));
            if (!ev.dirFix) ev.dir = d;
            return tryStepEvent(ev, d);
        } else if (mc == 12) {
            return tryStepEvent(ev, ev.dir);
        } else if (mc == 13) {
            return tryStepEvent(ev, oppositeDir(ev.dir));
        } else if (mc == 15) {
            ev.routeWait = (ma + 1) >> 1;
            return true;
        } else if (mc >= 16 && mc <= 19) {
            ev.dir = (mc - 15) * 2;
            return true;
        } else if (mc == 20) {
            ev.dir = turnRight(ev.dir);
            return true;
        } else if (mc == 21) {
            ev.dir = turnLeft(ev.dir);
            return true;
        } else if (mc == 22) {
            ev.dir = oppositeDir(ev.dir);
            return true;
        } else if (mc == 23) {
            ev.dir = (nextRand(2) == 0) ? turnRight(ev.dir) : turnLeft(ev.dir);
            return true;
        } else if (mc == 24) {
            ev.dir = (nextRand(4) + 1) * 2;
            return true;
        } else if (mc == 25) {
            ev.dir = stepToward(ev.tileX, ev.tileY, playerTileX, playerTileY);
            return true;
        } else if (mc == 26) {
            ev.dir = oppositeDir(stepToward(ev.tileX, ev.tileY, playerTileX, playerTileY));
            return true;
        } else if (mc == 27) {
            data.setSwitch(ma, true); map.refreshPages(data);
            return true;
        } else if (mc == 28) {
            data.setSwitch(ma, false); map.refreshPages(data);
            return true;
        } else if (mc == 29) {
            ev.moveSpeed = RoomMap.rmxpEventSpeedToPx(ma);
            return true;
        } else if (mc == 30) {
            ev.moveFreq = ma;
            return true;
        } else if (mc == 35) {
            ev.dirFix = true;
            return true;
        } else if (mc == 36) {
            ev.dirFix = false;
            return true;
        } else if (mc == 37) {
            ev.through = true;
            return true;
        } else if (mc == 38) {
            ev.through = false;
            return true;
        } else if (mc == 39) {
            ev.alwaysOnTop = true;
            return true;
        } else if (mc == 40) {
            ev.alwaysOnTop = false;
            return true;
        } else if (mc == 41) {
            if (ma < 0 || pool == null || ma >= pool.length) {
                ev.charId = -1;
            } else {
                ev.charId = data.resolveCharId(pool[ma]);
            }
            ev.oniType = data.getOniType(ev.charId);
            return true;
        } else if (mc == 42) {
            ev.opacity = ma & 0xFF;
            return true;
        }
        return true;
    }

    private boolean tryStepEvent(MapEvent ev, int dir) {
        int nx = ev.tileX + dirDx(dir);
        int ny = ev.tileY + dirDy(dir);
        boolean hitsPlayer = (nx == playerTileX && ny == playerTileY)
            || (playerMoving && nx == (playerTargetX / TILE) && ny == (playerTargetY / TILE));
        if (hitsPlayer) {
            if (isHostileOni(ev) && !mainVm.active) {
                triggerOniCatch(ev);
                return false;
            }
            int trig = ev.getActiveTrigger();
            if ((trig == 1 || trig == 2) && !mainVm.active) {
                clearHeldDirs();
                ev.clearMoveRoute();
                ev.dashSteps = 0;
                ev.dashDir = 0;
                mainVm.start(ev.id, ev.activePage, ev.getActiveCode(), map.strings);
            }
            if (!ev.through) return false;
        }
        if (ev.through || map.canStep(ev.tileX, ev.tileY, dir, ev.id, false)) {
            if (nx >= 0 && ny >= 0 && nx < map.width && ny < map.height) {
                ev.targetX = nx * TILE;
                ev.targetY = ny * TILE;
                ev.moving = true;
                return true;
            }
        }
        return false;
    }

    private void updateCamera() {
        int sw = getWidth();
        int sh = getHeight();
        int mapPxW = map.width * TILE;
        int mapPxH = map.height * TILE;

        if (mapPxW <= sw) {
            camX = -((sw - mapPxW) >> 1);
        } else {
            camX = playerPx + (TILE >> 1) - (sw >> 1);
            if (camX < 0) camX = 0;
            if (camX > mapPxW - sw) camX = mapPxW - sw;
        }

        if (mapPxH <= sh) {
            camY = -((sh - mapPxH) >> 1);
        } else {
            camY = playerPy + (TILE >> 1) - (sh >> 1);
            if (camY < 0) camY = 0;
            if (camY > mapPxH - sh) camY = mapPxH - sh;
        }
    }

    // Callbacks from EventVM
    public int getCharProp(int targetId, int selfEvId, int prop) {
        if (targetId == -1) {
            if (prop == 0) return playerTileX;
            if (prop == 1) return playerTileY;
            if (prop == 2) return playerDir;
            if (prop == 3) return playerPx - camX;
            if (prop == 4) return playerPy - camY;
            return 0;
        }
        MapEvent ev = map.getEventById(targetId == 0 ? selfEvId : targetId);
        if (ev == null) return 0;
        if (prop == 0) return ev.tileX;
        if (prop == 1) return ev.tileY;
        if (prop == 2) return ev.dir;
        if (prop == 3) return ev.px - camX;
        if (prop == 4) return ev.py - camY;
        return 0;
    }

    public void assignMoveRoute(
        int targetId, int selfEvId, byte[] codes, short[] args, String[] pool,
        int len, boolean repeat, boolean skippable
    ) {
        if (targetId == -1) {
            pRouteCodes = codes;
            pRouteArgs = args;
            pRouteLen = len;
            pRouteIdx = 0;
            pRouteRepeat = repeat;
            pRouteSkippable = skippable;
            pRouteWait = 0;
        } else {
            MapEvent ev = map.getEventById(targetId == 0 ? selfEvId : targetId);
            if (ev != null) {
                ev.setMoveRoute(codes, args, pool, len, repeat, skippable);
            }
        }
    }

    public boolean isAnyMoveRouteActive() {
        if (pRouteLen > 0 && !pRouteRepeat) return true;
        if (map.events != null) {
            for (int i = 0; i < map.events.length; i++) {
                MapEvent ev = map.events[i];
                if (ev.isRouteActive() && !ev.routeRepeat) return true;
            }
        }
        return false;
    }

    public void startTimer(int sec) {
        timerFrames = sec * 20;
        timerActive = true;
    }

    public void stopTimer() {
        timerActive = false;
    }

    public int getTimerSeconds() {
        return timerActive ? ((timerFrames + 19) / 20) : 0;
    }

    public void handleAudio(int kind, String name, int vol) {
        // Hook ready for JSR-135 MIDI/WAV audio manager
    }

    public void handleScreenFx(int kind, int a1, int a2) {
        if (kind == 0) fadedOut = true;
        else if (kind == 1) fadedOut = false;
        else if (kind == 2) flashFrames = (a1 + 1) >> 1;
        else if (kind == 3) { shakePower = a1; shakeFrames = (a2 + 1) >> 1; }
    }

    public void showPicture(int picId, String name, int x, int y) {
        int slot = picId - 1;
        if (slot < 0 || slot >= MAX_PICS) return;
        Image img = data.loadPictureImage(name);
        picImgs[slot] = img;
        picVisible[slot] = (img != null);
        if (img != null && img.getWidth() >= 200) {
            picX[slot] = (getWidth() - img.getWidth()) >> 1;
            picY[slot] = (getHeight() - img.getHeight()) >> 1;
        } else {
            picX[slot] = (x * getWidth()) / 640;
            picY[slot] = (y * getHeight()) / 480;
        }
    }

    public void movePicture(int picId, int dur, int x, int y, int opacity) {
        int slot = picId - 1;
        if (slot < 0 || slot >= MAX_PICS) return;
        if (opacity == 0) {
            picVisible[slot] = false;
            return;
        }
        if (picImgs[slot] != null && picImgs[slot].getWidth() < 200) {
            picX[slot] = (x * getWidth()) / 640;
            picY[slot] = (y * getHeight()) / 480;
        }
        picVisible[slot] = (picImgs[slot] != null);
    }

    public void erasePicture(int picId) {
        int slot = picId - 1;
        if (slot < 0 || slot >= MAX_PICS) return;
        picImgs[slot] = null;
        picVisible[slot] = false;
        if (msgDockedPicSlot == slot) {
            msgDockedPicSlot = -1;
        }
    }

    // -----------------------------------------------------------------------
    // Clean RMXP / AMS Script Parser & Attached Face Resolution
    // -----------------------------------------------------------------------
    public void openMessage(String rawText, int ownerEvId) {
        clearHeldDirs();
        msgPageStart = 0;
        msgColor = 0xFFFFFF;
        msgFaceImg = null;
        msgDockedPicSlot = -1;

        String[] metaOut = new String[2]; // [0]=faceTag, [1]=speakerNameTag
        int[] colorOut = new int[] { 0xFFFFFF };
        String cleaned = sanitizeScriptText(rawText, metaOut, colorOut);
        msgColor = colorOut[0];

        if (metaOut[1] != null && metaOut[1].length() > 0) {
            cleaned = metaOut[1] + ":\n" + cleaned;
        }

        // Resolve face graphic: 1) explicit tag, 2) active portrait picture, 3) talking character sprite
        if (metaOut[0] != null && metaOut[0].length() > 0) {
            Image f = data.loadFaceImage(metaOut[0]);
            if (f != null) {
                msgFaceImg = makeFaceThumbnail(f, FACE_BOX_SIZE - 2);
            } else {
                int cid = data.resolveCharId(metaOut[0]);
                if (cid >= 0) {
                    msgFaceImg = makeSpriteHeadPortrait(data.getCharImage(cid), FACE_BOX_SIZE - 2);
                }
            }
        }
        if (msgFaceImg == null) {
            // Check for an active square/portrait Show Picture (used by Ao Oni for character faces)
            for (int i = MAX_PICS - 1; i >= 0; i--) {
                if (picVisible[i] && picImgs[i] != null) {
                    int pw = picImgs[i].getWidth();
                    int ph = picImgs[i].getHeight();
                    if (pw >= 24 && pw <= 120 && ph >= 24 && ph <= 120) {
                        msgFaceImg = makeFaceThumbnail(picImgs[i], FACE_BOX_SIZE - 2);
                        msgDockedPicSlot = i;
                        break;
                    }
                }
            }
        }
        if (msgFaceImg == null && ownerEvId > 0) {
            MapEvent ev = map.getEventById(ownerEvId);
            if (ev != null && ev.charId >= 0) {
                msgFaceImg = makeSpriteHeadPortrait(data.getCharImage(ev.charId), FACE_BOX_SIZE - 2);
            }
        }

        msgLines = wrapText(cleaned, getWidth() - 18);
    }

    private static int findMatchingBracket(String s, int openIdx) {
        int depth = 0;
        int len = s.length();
        for (int k = openIdx; k < len; k++) {
            char ch = s.charAt(k);
            if (ch == '[') {
                depth++;
            } else if (ch == ']') {
                depth--;
                if (depth == 0) return k;
            }
        }
        return -1;
    }

    private String sanitizeScriptText(String raw, String[] metaOut, int[] colorOut) {
        if (raw == null || raw.length() == 0) return "";
        StringBuffer sb = new StringBuffer(raw.length());
        int len = raw.length();
        int i = 0;

        while (i < len) {
            char c = raw.charAt(i);

            if (c == '\r') {
                i++;
                continue;
            }
            if (c < 0x20 && c != '\n') {
                i++;
                continue;
            }
            if (c == 0x7F || c == '\uFEFF') {
                i++;
                continue;
            }

            // RMXP / AMS / KGC escape sequences: \, yen (0x00A5), fullwidth yen (0xFFE5)
            if (c == '\\' || c == '\u00A5' || c == '\uFFE5') {
                if (i + 1 >= len) {
                    i++;
                    continue;
                }
                char next = raw.charAt(i + 1);
                if (next == '\\' || next == '\u00A5' || next == '\uFFE5') {
                    sb.append('\\');
                    i += 2;
                    continue;
                }
                // Direct \[name] face tag syntax with balanced brackets
                if (next == '[') {
                    int close = findMatchingBracket(raw, i + 1);
                    if (close > i + 1) {
                        String arg = raw.substring(i + 2, close).trim();
                        if (metaOut != null && arg.length() > 0) metaOut[0] = arg;
                        i = close + 1;
                        continue;
                    }
                }
                // Single-char RMXP/AMS timing & formatting escapes (and bare \0..\9)
                if ((next >= '0' && next <= '9')
                        || next == '.' || next == '|' || next == '!' || next == '^'
                        || next == '>' || next == '<' || next == '$' || next == '/'
                        || next == '_' || next == '%' || next == '+' || next == '-'
                        || next == '=') {
                    i += 2;
                    continue;
                }

                // Read alpha tag name (e.g. c, n, N, v, f, face, nm, name, nb, s, w, p, af, z)
                int tagStart = i + 1;
                int j = tagStart;
                while (j < len && isAsciiLetter(raw.charAt(j))) {
                    j++;
                }
                if (j > tagStart) {
                    String rawTag = raw.substring(tagStart, j);
                    String tag = rawTag.toLowerCase();
                    if (j < len && raw.charAt(j) == '[') {
                        int close = findMatchingBracket(raw, j);
                        if (close > j) {
                            String arg = raw.substring(j + 1, close).trim();
                            handleBracketTag(rawTag, tag, arg, sb, metaOut, colorOut);
                            i = close + 1;
                            continue;
                        }
                    } else {
                        if (tag.equals("g") || tag.equals("b") || tag.equals("i")
                                || tag.equals("a") || tag.equals("k")) {
                            i = j;
                            continue;
                        }
                    }
                }
                i++;
                continue;
            }

            // Angle-bracket style patch tags if present (e.g. <face:hiroshi>)
            if (c == '<') {
                int close = raw.indexOf('>', i + 1);
                if (close > i && (close - i) <= 32) {
                    String inside = raw.substring(i + 1, close).trim();
                    String low = inside.toLowerCase();
                    if (low.startsWith("face:") || low.startsWith("f:")) {
                        int col = inside.indexOf(':');
                        if (metaOut != null) metaOut[0] = inside.substring(col + 1).trim();
                        i = close + 1;
                        continue;
                    }
                    if (low.startsWith("c:") || low.startsWith("/c") || low.startsWith("w:")) {
                        i = close + 1;
                        continue;
                    }
                }
            }

            sb.append(c);
            i++;
        }

        String s = sb.toString();
        int start = 0;
        int end = s.length();
        while (start < end && (s.charAt(start) == '\n' || s.charAt(start) == ' ')) start++;
        while (end > start && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == ' ')) end--;
        return s.substring(start, end);
    }

    private void handleBracketTag(
        String rawTag, String tag, String arg, StringBuffer sb, String[] metaOut, int[] colorOut
    ) {
        if (tag.equals("n")) {
            int id = parseIntSafe(arg, -1);
            if (id >= 0) {
                sb.append(data.getActorName(id));
            } else {
                String resolved = sanitizeScriptText(arg, null, null);
                if (metaOut != null && resolved.length() > 0) {
                    metaOut[1] = resolved;
                }
            }
        } else if (tag.equals("nm") || tag.equals("name") || tag.equals("nb")) {
            String resolved = sanitizeScriptText(arg, null, null);
            if (metaOut != null && resolved.length() > 0) {
                metaOut[1] = resolved;
            }
        } else if (tag.equals("v")) {
            int id = parseIntSafe(arg, -1);
            if (id >= 0) sb.append(data.getVar(id));
        } else if (tag.equals("c")) {
            int ci = parseIntSafe(arg, 0);
            if (colorOut != null && ci >= 0 && ci < RMXP_COLORS.length) {
                colorOut[0] = RMXP_COLORS[ci];
            }
        } else if (tag.equals("f") || tag.equals("face") || tag.equals("pic")
                   || tag.equals("z") || tag.equals("l")) {
            String resolved = sanitizeScriptText(arg, null, null);
            if (metaOut != null && resolved.length() > 0
                    && parseIntSafe(resolved, Integer.MIN_VALUE) == Integer.MIN_VALUE) {
                metaOut[0] = resolved;
            }
        } else if (tag.equals("p")) {
            // Numeric \p[n] is AMS event-position bubble; only non-numeric is a portrait
            String resolved = sanitizeScriptText(arg, null, null);
            if (metaOut != null && resolved.length() > 0
                    && parseIntSafe(resolved, Integer.MIN_VALUE) == Integer.MIN_VALUE) {
                metaOut[0] = resolved;
            }
        } else if (tag.equals("af")) {
            int aid = parseIntSafe(arg, -1);
            if (metaOut != null && aid > 0) metaOut[0] = data.getActorName(aid);
        }
        // All other bracketed AMS/RMXP tags (\s[n], \w[n], \o[n], etc.) are cleanly stripped
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static int parseIntSafe(String s, int fallback) {
        if (s == null || s.length() == 0) return fallback;
        int val = 0;
        int i = 0;
        boolean neg = false;
        if (s.charAt(0) == '-') { neg = true; i = 1; }
        if (i >= s.length()) return fallback;
        for (; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < '0' || ch > '9') return fallback;
            val = val * 10 + (ch - '0');
        }
        return neg ? -val : val;
    }

    // Nearest-neighbor aspect-fit thumbnail for dedicated face/picture PNGs
    private static Image makeFaceThumbnail(Image src, int box) {
        if (src == null) return null;
        int sw = src.getWidth();
        int sh = src.getHeight();
        if (sw <= box && sh <= box) return src;
        int dw, dh;
        if (sw >= sh) {
            dw = box;
            dh = (sh * box) / sw;
        } else {
            dh = box;
            dw = (sw * box) / sh;
        }
        if (dw < 1) dw = 1;
        if (dh < 1) dh = 1;
        try {
            int[] in = new int[sw * sh];
            src.getRGB(in, 0, sw, 0, 0, sw, sh);
            int[] out = new int[dw * dh];
            for (int y = 0; y < dh; y++) {
                int sy = (y * sh) / dh;
                int sRow = sy * sw;
                int dRow = y * dw;
                for (int x = 0; x < dw; x++) {
                    out[dRow + x] = in[sRow + ((x * sw) / dw)];
                }
            }
            return Image.createRGBImage(out, dw, dh, true);
        } catch (Exception e) {
            return src;
        }
    }

    // Crops the front-facing head/shoulders of a 4x4 RMXP sprite sheet and scales cleanly to box
    private static Image makeSpriteHeadPortrait(Image sheet, int box) {
        if (sheet == null) return null;
        int fw = sheet.getWidth() >> 2;
        int fh = sheet.getHeight() >> 2;
        if (fw <= 0 || fh <= 0) return null;
        int cropH = fh > fw ? fw : fh;
        if (cropH > fh) cropH = fh;
        try {
            int[] in = new int[fw * cropH];
            sheet.getRGB(in, 0, fw, 0, 0, fw, cropH);
            int scale = box / fw;
            if (scale < 1) scale = 1;
            if (scale > 2) scale = 2;
            int dw = fw * scale;
            int dh = cropH * scale;
            if (dw > box) dw = box;
            if (dh > box) dh = box;
            int[] out = new int[dw * dh];
            for (int y = 0; y < dh; y++) {
                int sy = (y * cropH) / dh;
                int sRow = sy * fw;
                int dRow = y * dw;
                for (int x = 0; x < dw; x++) {
                    out[dRow + x] = in[sRow + ((x * fw) / dw)];
                }
            }
            return Image.createRGBImage(out, dw, dh, true);
        } catch (Exception e) {
            return null;
        }
    }

    public void openChoices(String[] labels, int cancelType) {
        clearHeldDirs();
        String[] clean = new String[labels.length];
        for (int i = 0; i < labels.length; i++) {
            clean[i] = sanitizeScriptText(labels[i], null, null);
        }
        choiceLabels = clean;
        choiceSel = 0;
        choiceCancelType = cancelType;
    }

    public void openNumberInput(int digits) {
        clearHeldDirs();
        numDigits = digits < 1 ? 1 : (digits > 8 ? 8 : digits);
        for (int i = 0; i < numDigits; i++) numBuf[i] = 0;
        numCursor = 0;
        numInputActive = true;
    }

    public void openNameInput(int actorId, int maxChars) {
        clearHeldDirs();
        nameActorId = actorId;
        nameMaxLen = maxChars < 1 ? 1 : (maxChars > 12 ? 12 : maxChars);
        String cur = data.getActorName(actorId);
        if (cur == null || cur.length() == 0) {
            cur = data.getDefaultActorName(actorId);
        }
        nameLen = cur.length();
        if (nameLen > nameMaxLen) nameLen = nameMaxLen;
        for (int i = 0; i < nameLen; i++) {
            nameBuf[i] = cur.charAt(i);
        }
        // Start cursor on OK so players who want the default name can press 5 immediately
        nameRow = 7;
        nameAct = 2;
        nameCol = 8;
        nameInputActive = true;
    }

    private void submitNameInput() {
        String entered = (nameLen > 0) ? new String(nameBuf, 0, nameLen).trim() : "";
        if (entered.length() == 0) {
            entered = data.getDefaultActorName(nameActorId);
        }
        nameInputActive = false;
        mainVm.onNameEntered(data, map, entered);
    }

    public void openButtonInput() {
        clearHeldDirs();
        btnInputActive = true;
    }

    public void triggerGameOver() {
        clearHeldDirs();
        clearMessageState();
        clearPictures();
        mainVm.stop();
        stopParallelVms();
        data.setSwitch(50, false);
        timerActive = false;
        fadedOut = false;
        if (gameOverImg == null) {
            gameOverImg = data.loadGameOverImage();
        }
        gameOverSel = 0;
        gameOverTicks = 0;
        mode = MODE_GAMEOVER;
    }

    public void returnToTitle() {
        clearHeldDirs();
        clearMessageState();
        clearPictures();
        mainVm.stop();
        stopParallelVms();
        timerActive = false;
        fadedOut = false;
        gameOverImg = null;
        titleSel = 0;
        mode = MODE_TITLE;
    }

    // -----------------------------------------------------------------------
    // Input Handling (D-Pad Move, Key 7 Inventory, 5/OK Confirm)
    // -----------------------------------------------------------------------
    protected void keyReleased(int keyCode) {
        int d = mapDpadDir(keyCode);
        if (d != 0) {
            removeHeldDir(d);
        }
    }

    protected void keyPressed(int keyCode) {
        int ga = 0;
        try { ga = getGameAction(keyCode); } catch (Exception e) {}
        int dpad = mapDpadDir(keyCode);
        boolean isConfirm = (ga == FIRE || keyCode == KEY_NUM5 || keyCode == -6 || keyCode == -5);

        if (mode == MODE_ERROR) {
            if (keyCode == -7 || isConfirm) midlet.notifyDestroyed();
            return;
        }

        if (mode == MODE_TITLE) {
            if (dpad == 8) {
                titleSel = (titleSel + TITLE_ITEMS.length - 1) % TITLE_ITEMS.length;
            } else if (dpad == 2) {
                titleSel = (titleSel + 1) % TITLE_ITEMS.length;
            } else if (isConfirm) {
                if (titleSel == 0) startNewGame();
                else if (titleSel == 1) continueGame();
                else if (titleSel == 2) midlet.notifyDestroyed();
            } else if (keyCode == -7) {
                midlet.notifyDestroyed();
            }
            return;
        }

        if (mode == MODE_GAMEOVER) {
            if (gameOverTicks < 6) return;
            if (dpad == 8 || dpad == 2 || dpad == 4 || dpad == 6
                    || keyCode == KEY_NUM2 || keyCode == KEY_NUM8) {
                gameOverSel = 1 - gameOverSel;
            } else if (isConfirm || keyCode == KEY_NUM0 || keyCode == KEY_POUND) {
                gameOverImg = null;
                if (gameOverSel == 0) {
                    if (!continueIfSaved()) {
                        startNewGame();
                    }
                } else {
                    returnToTitle();
                }
            } else if (keyCode == -7 || keyCode == KEY_NUM7 || keyCode == KEY_STAR) {
                returnToTitle();
            }
            return;
        }

        if (mode == MODE_MENU) {
            handleMenuKey(keyCode, dpad, isConfirm);
            return;
        }

        // Button Input Processing (OP_BUTTON_INPUT)
        if (btnInputActive) {
            int code = 0;
            if (dpad == 2) code = 2;
            else if (dpad == 4) code = 4;
            else if (dpad == 6) code = 6;
            else if (dpad == 8) code = 8;
            else if (keyCode == -7 || keyCode == KEY_STAR) code = 12;
            else if (isConfirm) code = 13;
            if (code != 0) {
                btnInputActive = false;
                mainVm.onButtonPressed(data, map, code);
            }
            return;
        }

        // Character Name Input Screen (OP_NAME_INPUT / RMXP 303)
        if (nameInputActive) {
            handleNameInputKey(keyCode, dpad, isConfirm);
            return;
        }

        // Number Input Keypad (OP_INPUT_NUM)
        if (numInputActive) {
            if (keyCode >= KEY_NUM0 && keyCode <= KEY_NUM9) {
                numBuf[numCursor] = keyCode - KEY_NUM0;
                if (numCursor < numDigits - 1) numCursor++;
            } else if (dpad == 4 && numCursor > 0) {
                numCursor--;
            } else if (dpad == 6 && numCursor < numDigits - 1) {
                numCursor++;
            } else if (dpad == 8) {
                numBuf[numCursor] = (numBuf[numCursor] + 1) % 10;
            } else if (dpad == 2) {
                numBuf[numCursor] = (numBuf[numCursor] + 9) % 10;
            } else if (isConfirm) {
                int val = 0;
                for (int i = 0; i < numDigits; i++) val = val * 10 + numBuf[i];
                numInputActive = false;
                mainVm.onNumberEntered(data, map, val);
            }
            return;
        }

        // Choices UI
        if (choiceLabels != null) {
            if (dpad == 8) {
                choiceSel = (choiceSel + choiceLabels.length - 1) % choiceLabels.length;
            } else if (dpad == 2) {
                choiceSel = (choiceSel + 1) % choiceLabels.length;
            } else if (isConfirm) {
                int pick = choiceSel;
                choiceLabels = null;
                mainVm.onChoicePicked(pick);
            } else if ((keyCode == -7 || keyCode == KEY_STAR) && choiceCancelType != 0) {
                choiceLabels = null;
                mainVm.onChoicePicked(-1);
            }
            return;
        }

        // Dialogue Box (paginated cleanly across 4-line chunks)
        if (msgLines != null) {
            if (isConfirm) {
                if (msgPageStart + MAX_VISIBLE_MSG_LINES < msgLines.length) {
                    msgPageStart += MAX_VISIBLE_MSG_LINES;
                } else {
                    msgLines = null;
                    msgPageStart = 0;
                    msgFaceImg = null;
                    msgDockedPicSlot = -1;
                    mainVm.onMessageClosed();
                    for (int i = 0; i < MAX_PAR_VMS; i++) {
                        if (parVms[i].isWaitingMessage()) {
                            parVms[i].onMessageClosed();
                        }
                    }
                }
            }
            return;
        }

        // Normal Exploration Controls: D-pad to move, 7 for inventory, 5/OK to interact
        if (dpad != 0) {
            pushHeldDir(dpad);
            return;
        }
        if (keyCode == KEY_NUM7 && menuEnabled && !mainVm.active && !playerMoving) {
            clearHeldDirs();
            menuSel = 0;
            menuSubState = 0;
            menuActionSel = 0;
            mergeFirstItemId = -1;
            menuStatus = "";
            mode = MODE_MENU;
            return;
        }
        if (isConfirm && !mainVm.active && !playerMoving) {
            tryInteract();
        }
    }

    private int getOwnedItemIdAt(int rowIdx) {
        int row = 0;
        for (int id = 1; id < data.itemCounts.length; id++) {
            if (data.getItemCount(id) > 0) {
                if (row == rowIdx) return id;
                row++;
            }
        }
        return -1;
    }

    private int countOwnedItems() {
        int c = 0;
        for (int id = 1; id < data.itemCounts.length; id++) {
            if (data.getItemCount(id) > 0) c++;
        }
        return c;
    }

    private boolean runItemCommonEvent(int itemId) {
        int ceId = data.getItemCommonEvent(itemId);
        if (ceId <= 0) return false;
        int ceIdx = RoomMap.findCommonIndex(ceId);
        if (ceIdx < 0) return false;
        if (data.isItemConsumable(itemId)) {
            data.addItem(itemId, -1);
            map.refreshPages(data);
        }
        menuSubState = 0;
        mergeFirstItemId = -1;
        mode = MODE_PLAY;
        clearHeldDirs();
        mainVm.start(0, ceIdx, RoomMap.commonCodes[ceIdx], RoomMap.commonStrings);
        mainVm.update(this, data, map);
        return true;
    }

    private void executeUseItem(int itemId) {
        if (itemId <= 0 || data.getItemCount(itemId) <= 0) return;
        if (data.isItemMenuUsable(itemId) && runItemCommonEvent(itemId)) {
            return;
        }
        if (runItemCommonEvent(itemId)) {
            return;
        }
        String desc = sanitizeScriptText(data.getItemDesc(itemId), null, null);
        menuStatus = (desc.length() > 0) ? desc : "Cannot use here.";
        menuSubState = 0;
    }

    private void executeMergeItems(int idA, int idB) {
        if (idA <= 0 || idB <= 0 || idA == idB) {
            menuStatus = "Pick a different item.";
            return;
        }
        // 1. Prefer authentic RMXP CommonEvent if either item's CE references the other
        int ceA = data.getItemCommonEvent(idA);
        int ceB = data.getItemCommonEvent(idB);
        if (ceA > 0 && RoomMap.commonReferencesItem(ceA, idB)) {
            runItemCommonEvent(idA);
            return;
        }
        if (ceB > 0 && RoomMap.commonReferencesItem(ceB, idA)) {
            runItemCommonEvent(idB);
            return;
        }
        // 2. Direct recipe merge fallback (Soap+Handkerchief, Disc+Metal Stick, Lighter+Fluid, Vinegar+Red Key, Screwdrivers)
        String mergedMsg = data.tryDirectRecipeMerge(idA, idB);
        if (mergedMsg != null) {
            map.refreshPages(data);
            menuStatus = mergedMsg;
            menuSubState = 0;
            mergeFirstItemId = -1;
            int newCount = countOwnedItems();
            if (menuSel >= newCount + 2) menuSel = 0;
            return;
        }
        menuStatus = "Cannot combine those.";
        menuSubState = 0;
        mergeFirstItemId = -1;
    }

    private void handleNameInputKey(int keyCode, int dpad, boolean isConfirm) {
        // Quick Backspace: RSK (-7), '*', or PC Backspace (8)
        if (keyCode == -7 || keyCode == KEY_STAR || keyCode == 8) {
            if (nameLen > 0) nameLen--;
            return;
        }
        // Instant Confirm: '#' or PC Enter (10/13)
        if (keyCode == KEY_POUND || keyCode == 10 || keyCode == 13) {
            submitNameInput();
            return;
        }
        // Direct PC keyboard typing convenience in WTK / KEmulator (letters & space only)
        if ((keyCode >= 'A' && keyCode <= 'Z')
                || (keyCode >= 'a' && keyCode <= 'z')
                || keyCode == ' ') {
            if (nameLen < nameMaxLen) {
                nameBuf[nameLen++] = (char) keyCode;
            }
            return;
        }

        if (dpad == 8) { // UP
            if (nameRow == 7) {
                nameRow = 6;
                nameCol = (nameAct == 0) ? 1 : (nameAct == 1 ? 4 : 8);
            } else if (nameRow == 0) {
                nameRow = 7;
                nameAct = (nameCol <= 2) ? 0 : (nameCol <= 6 ? 1 : 2);
            } else {
                nameRow--;
            }
            return;
        }
        if (dpad == 2) { // DOWN
            if (nameRow == 6) {
                nameRow = 7;
                nameAct = (nameCol <= 2) ? 0 : (nameCol <= 6 ? 1 : 2);
            } else if (nameRow == 7) {
                nameRow = 0;
                nameCol = (nameAct == 0) ? 1 : (nameAct == 1 ? 4 : 8);
            } else {
                nameRow++;
            }
            return;
        }
        if (dpad == 4) { // LEFT
            if (nameRow == 7) {
                nameAct = (nameAct + 2) % 3;
            } else {
                nameCol = (nameCol + 9) % 10;
            }
            return;
        }
        if (dpad == 6) { // RIGHT
            if (nameRow == 7) {
                nameAct = (nameAct + 1) % 3;
            } else {
                nameCol = (nameCol + 1) % 10;
            }
            return;
        }
        if (isConfirm) {
            if (nameRow == 7) {
                if (nameAct == 0) {
                    if (nameLen > 0) nameLen--;
                } else if (nameAct == 1) {
                    nameLen = 0;
                } else {
                    submitNameInput();
                }
            } else {
                char ch = NAME_GRID[nameRow].charAt(nameCol);
                if (nameLen < nameMaxLen) {
                    nameBuf[nameLen++] = ch;
                }
            }
        }
    }

    private void handleMenuKey(int keyCode, int dpad, boolean isConfirm) {
        boolean isCancel = (keyCode == -7 || keyCode == KEY_STAR);

        // Sub-state 1: Inline [USE] / [MERGE] action prompt on selected item
        if (menuSubState == 1) {
            if (isCancel || keyCode == KEY_NUM7) {
                menuSubState = 0;
                return;
            }
            if (dpad == 4 || dpad == 6 || dpad == 8 || dpad == 2) {
                menuActionSel = 1 - menuActionSel;
                return;
            }
            if (isConfirm) {
                int selId = getOwnedItemIdAt(menuSel);
                if (selId < 0) {
                    menuSubState = 0;
                    return;
                }
                if (menuActionSel == 0) {
                    executeUseItem(selId);
                } else {
                    if (countOwnedItems() < 2) {
                        menuStatus = "Need 2 items to merge.";
                        menuSubState = 0;
                    } else {
                        mergeFirstItemId = selId;
                        menuSubState = 2;
                        menuStatus = "Pick 2nd item to merge.";
                    }
                }
            }
            return;
        }

        // Sub-state 2: Picking the second item to merge with mergeFirstItemId
        if (menuSubState == 2) {
            if (isCancel || keyCode == KEY_NUM7) {
                menuSubState = 0;
                mergeFirstItemId = -1;
                menuStatus = "";
                return;
            }
            int itemCount = countOwnedItems();
            if (itemCount <= 0) {
                menuSubState = 0;
                mergeFirstItemId = -1;
                return;
            }
            if (dpad == 8) {
                menuSel = (menuSel + itemCount - 1) % itemCount;
            } else if (dpad == 2) {
                menuSel = (menuSel + 1) % itemCount;
            } else if (isConfirm) {
                int secondId = getOwnedItemIdAt(menuSel);
                executeMergeItems(mergeFirstItemId, secondId);
            }
            return;
        }

        // Sub-state 0: Main inventory list
        if (keyCode == KEY_NUM7 || isCancel) {
            mode = MODE_PLAY;
            return;
        }
        int itemCount = countOwnedItems();
        int totalRows = itemCount + 2; // owned items + SAVE + TITLE
        if (menuSel >= totalRows) menuSel = 0;

        if (dpad == 8) {
            menuSel = (menuSel + totalRows - 1) % totalRows;
            menuStatus = "";
        } else if (dpad == 2) {
            menuSel = (menuSel + 1) % totalRows;
            menuStatus = "";
        } else if (isConfirm) {
            if (menuSel < itemCount) {
                menuActionSel = 0;
                menuSubState = 1;
                menuStatus = "";
            } else if (menuSel == itemCount) {
                boolean ok = data.saveState(map.mapId, playerTileX, playerTileY, playerDir, frameCount);
                if (ok) saveCount++;
                menuStatus = ok ? "SAVED." : "SAVE FAILED.";
            } else if (menuSel == itemCount + 1) {
                returnToTitle();
            }
        }
    }

    private void tryInteract() {
        if (map.events == null) return;
        int fx = playerTileX + dirDx(playerDir);
        int fy = playerTileY + dirDy(playerDir);

        for (int pass = 0; pass < 2; pass++) {
            int tx = (pass == 0) ? fx : playerTileX;
            int ty = (pass == 0) ? fy : playerTileY;
            for (int i = 0; i < map.events.length; i++) {
                MapEvent ev = map.events[i];
                if (ev.tileX == tx && ev.tileY == ty && ev.getActiveTrigger() == 0) {
                    if (!ev.dirFix && ev.charId >= 0) {
                        ev.dir = oppositeDir(playerDir);
                    }
                    clearHeldDirs();
                    mainVm.start(ev.id, ev.activePage, ev.getActiveCode(), map.strings);
                    return;
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Rendering
    // -----------------------------------------------------------------------
    private void render(Graphics g) {
        int sw = getWidth();
        int sh = getHeight();
        g.setClip(0, 0, sw, sh);
        g.setFont(uiFont);

        if (mode == MODE_ERROR) {
            g.setColor(0x000000);
            g.fillRect(0, 0, sw, sh);
            g.setColor(0xFFFFFF);
            g.drawString(errorText, 6, 20, Graphics.TOP | Graphics.LEFT);
            return;
        }

        if (mode == MODE_TITLE) {
            drawTitleScreen(g, sw, sh);
            return;
        }

        if (mode == MODE_GAMEOVER) {
            drawGameOverScreen(g, sw, sh);
            return;
        }

        g.setColor(0x000000);
        g.fillRect(0, 0, sw, sh);

        if (!fadedOut) {
            int shakeOff = (shakeFrames > 0) ? (((shakeFrames & 1) == 0 ? 1 : -1) * shakePower) : 0;
            int ox = camX + shakeOff;
            int oy = camY;
            Image tsImg = data.getTilesetImage(map.tsId);
            int tsCols = data.getTilesetCols(map.tsId);

            // Ground pass (split-point aware so lower layers never overwrite upper layers)
            drawTilePass(g, tsImg, tsCols, ox, oy, false);

            // Ground-level events and Hiroshi
            drawEntities(g, tsImg, tsCols, ox, oy, false);

            // Overhead pass
            drawTilePass(g, tsImg, tsCols, ox, oy, true);

            // Always-on-top events
            drawEntities(g, tsImg, tsCols, ox, oy, true);
        }

        // Pictures (skip the portrait currently docked in the dialogue face box)
        for (int i = 0; i < MAX_PICS; i++) {
            if (picVisible[i] && picImgs[i] != null) {
                if (msgLines != null && i == msgDockedPicSlot) continue;
                g.drawImage(picImgs[i], picX[i], picY[i], Graphics.TOP | Graphics.LEFT);
            }
        }

        // Flash overlay
        if (flashFrames > 0) {
            g.setColor(0xFFFFFF);
            g.fillRect(0, 0, sw, sh);
        }

        // Countdown timer HUD
        if (timerActive) {
            int sec = getTimerSeconds();
            int mm = sec / 60;
            int ss = sec % 60;
            String ts = (mm < 10 ? "0" : "") + mm + ":" + (ss < 10 ? "0" : "") + ss;
            g.setColor(0x000000);
            g.fillRect(sw - 48, 4, 44, 16);
            g.setColor(0xFF6666);
            g.drawString(ts, sw - 26, 5, Graphics.TOP | Graphics.HCENTER);
        }

        // Overlays
        if (msgLines != null) drawMessageBox(g, sw, sh);
        if (choiceLabels != null) drawChoiceBox(g, sw, sh);
        if (numInputActive) drawNumberBox(g, sw, sh);
        if (nameInputActive) drawNameInputBox(g, sw, sh);
        if (mode == MODE_MENU) drawInventoryMenu(g, sw, sh);
    }

    // Full-screen 240x320 death screen with RETRY and TITLE options
    private void drawGameOverScreen(Graphics g, int sw, int sh) {
        g.setColor(0x000000);
        g.fillRect(0, 0, sw, sh);

        if (gameOverImg != null) {
            int ix = (sw - gameOverImg.getWidth()) >> 1;
            int iy = (sh - gameOverImg.getHeight()) >> 1;
            g.drawImage(gameOverImg, ix, iy, Graphics.TOP | Graphics.LEFT);
        } else {
            // Full-bleed dark crimson fallback if gameover.png is absent
            g.setColor(0x280000);
            g.fillRect(0, 0, sw, sh);
            g.setColor(0xCC1111);
            g.drawString("GAME OVER", sw >> 1, (sh >> 1) - 24, Graphics.TOP | Graphics.HCENTER);
        }

        if (gameOverTicks >= 6) {
            int fh = uiFont.getHeight();
            int step = fh + 6;
            int bw = 104;
            int bh = step * 2 + 10;
            int bx = (sw - bw) >> 1;
            int by = sh - bh - 12;

            g.setColor(0x000000);
            g.fillRect(bx, by, bw, bh);
            g.setColor(0xFFFFFF);
            g.drawRect(bx, by, bw - 1, bh - 1);

            String[] opts = { "RETRY", "TITLE" };
            int x = bx + 28;
            for (int i = 0; i < 2; i++) {
                int ry = by + 7 + i * step;
                if (i == gameOverSel) {
                    int cy = ry + (fh >> 1);
                    int ax = x - 8;
                    g.setColor(0xFFFFFF);
                    g.drawLine(ax, cy - 2, ax, cy + 2);
                    g.drawLine(ax + 1, cy - 1, ax + 1, cy + 1);
                    g.drawLine(ax + 2, cy, ax + 2, cy);
                }
                g.setColor(0xFFFFFF);
                g.drawString(opts[i], x, ry, Graphics.TOP | Graphics.LEFT);
            }
        }
    }

    // Minimal stark black title menu matching the classic Ao Oni layout
    private void drawTitleScreen(Graphics g, int sw, int sh) {
        g.setColor(0x000000);
        g.fillRect(0, 0, sw, sh);

        int fh = uiFont.getHeight();
        int step = fh + 6;
        int maxW = 0;
        for (int i = 0; i < TITLE_ITEMS.length; i++) {
            int w = uiFont.stringWidth(TITLE_ITEMS[i]);
            if (w > maxW) maxW = w;
        }
        int x = (sw - maxW) >> 1;
        int y0 = (sh >> 1) - ((TITLE_ITEMS.length * step) >> 1);

        g.setColor(0xFFFFFF);
        for (int i = 0; i < TITLE_ITEMS.length; i++) {
            int y = y0 + i * step;
            if (i == titleSel) {
                int cy = y + (fh >> 1);
                int ax = x - 7;
                // Crisp 4px right-pointing pixel arrow
                g.drawLine(ax, cy - 2, ax, cy + 2);
                g.drawLine(ax + 1, cy - 1, ax + 1, cy + 1);
                g.drawLine(ax + 2, cy, ax + 2, cy);
            }
            g.drawString(TITLE_ITEMS[i], x, y, Graphics.TOP | Graphics.LEFT);
        }
    }

    private void drawTilePass(Graphics g, Image tsImg, int tsCols, int ox, int oy, boolean overhead) {
        if (tsImg == null || map.layer0 == null) return;
        int sw = getWidth();
        int sh = getHeight();
        int c0 = ox / TILE;
        int c1 = (ox + sw) / TILE + 1;
        int r0 = oy / TILE;
        int r1 = (oy + sh) / TILE + 1;
        if (c0 < 0) c0 = 0;
        if (r0 < 0) r0 = 0;
        if (c1 > map.width) c1 = map.width;
        if (r1 > map.height) r1 = map.height;

        for (int r = r0; r < r1; r++) {
            int rowBase = r * map.width;
            int sy = r * TILE - oy;
            for (int c = c0; c < c1; c++) {
                int idx = rowBase + c;
                int sx = c * TILE - ox;
                int v0 = map.layer0[idx] & 0xFFFF;
                int v1 = map.layer1[idx] & 0xFFFF;
                int v2 = map.layer2[idx] & 0xFFFF;

                int split = 3;
                if ((v0 & 0x7FFF) != 0 && (v0 & 0x8000) != 0) split = 0;
                else if ((v1 & 0x7FFF) != 0 && (v1 & 0x8000) != 0) split = 1;
                else if ((v2 & 0x7FFF) != 0 && (v2 & 0x8000) != 0) split = 2;

                if (!overhead) {
                    if (split > 0) drawSlot(g, tsImg, tsCols, v0 & 0x7FFF, sx, sy);
                    if (split > 1) drawSlot(g, tsImg, tsCols, v1 & 0x7FFF, sx, sy);
                    if (split > 2) drawSlot(g, tsImg, tsCols, v2 & 0x7FFF, sx, sy);
                } else {
                    if (split <= 0) drawSlot(g, tsImg, tsCols, v0 & 0x7FFF, sx, sy);
                    if (split <= 1) drawSlot(g, tsImg, tsCols, v1 & 0x7FFF, sx, sy);
                    if (split <= 2) drawSlot(g, tsImg, tsCols, v2 & 0x7FFF, sx, sy);
                }
            }
        }
    }

    private static void drawSlot(Graphics g, Image tsImg, int tsCols, int slot, int sx, int sy) {
        if (slot == 0) return;
        int srcX = (slot % tsCols) * TILE;
        int srcY = (slot / tsCols) * TILE;
        g.drawRegion(tsImg, srcX, srcY, TILE, TILE, 0, sx, sy, Graphics.TOP | Graphics.LEFT);
    }

    private void drawEntities(Graphics g, Image tsImg, int tsCols, int ox, int oy, boolean onTopPass) {
        if (!onTopPass && !playerTransparent) {
            int leadCid = data.getLeadCharId();
            if (leadCid >= 0) {
                drawCharacter(g, data.getCharImage(leadCid), playerPx - ox, playerPy - oy, playerDir, playerPattern);
            }
        }
        if (map.events == null) return;
        for (int i = 0; i < map.events.length; i++) {
            MapEvent ev = map.events[i];
            if (!ev.hasActivePage()) continue;
            if (ev.opacity == 0 || ev.alwaysOnTop != onTopPass) continue;

            int sx = ev.px - ox;
            int sy = ev.py - oy;
            if (ev.charId >= 0) {
                drawCharacter(g, data.getCharImage(ev.charId), sx, sy, ev.dir, ev.pattern);
            } else if (ev.tileSlot > 0 && tsImg != null) {
                int srcX = (ev.tileSlot % tsCols) * TILE;
                int srcY = (ev.tileSlot / tsCols) * TILE;
                g.drawRegion(tsImg, srcX, srcY, TILE, TILE, 0, sx, sy, Graphics.TOP | Graphics.LEFT);
            }
        }
    }

    private static void drawCharacter(Graphics g, Image sheet, int sx, int sy, int dir, int pat) {
        if (sheet == null) return;
        int fw = sheet.getWidth() >> 2;
        int fh = sheet.getHeight() >> 2;
        int row = (dir >> 1) - 1;
        if (row < 0) row = 0; else if (row > 3) row = 3;
        int col = pat & 3;
        int dx = sx + ((TILE - fw) >> 1);
        int dy = sy + TILE - fh;
        g.drawRegion(sheet, col * fw, row * fh, fw, fh, 0, dx, dy, Graphics.TOP | Graphics.LEFT);
    }

    // Text box with attached top-left square face box
    private void drawMessageBox(Graphics g, int sw, int sh) {
        int lh = uiFont.getHeight() + 2;
        int rem = msgLines.length - msgPageStart;
        int vis = rem > MAX_VISIBLE_MSG_LINES ? MAX_VISIBLE_MSG_LINES : rem;
        int bh = MAX_VISIBLE_MSG_LINES * lh + 12;
        int bx = 4;
        int bw = sw - 8;
        int by;

        if (textPos == 0) {
            by = (msgFaceImg != null) ? (FACE_BOX_SIZE + 4) : 6;
        } else if (textPos == 1) {
            by = (sh - bh) >> 1;
        } else {
            by = sh - bh - 6;
        }

        // Attached top-left square face box sitting directly on top of the text box
        if (msgFaceImg != null) {
            int fx = bx;
            int fy = by - FACE_BOX_SIZE + 1;
            if (fy < 2) fy = 2;
            g.setColor(0x000000);
            g.fillRect(fx, fy, FACE_BOX_SIZE, FACE_BOX_SIZE);
            g.setColor(0xFFFFFF);
            g.drawRect(fx, fy, FACE_BOX_SIZE - 1, FACE_BOX_SIZE - 1);

            g.setClip(fx + 1, fy + 1, FACE_BOX_SIZE - 2, FACE_BOX_SIZE - 2);
            int ix = fx + ((FACE_BOX_SIZE - msgFaceImg.getWidth()) >> 1);
            int iy = fy + ((FACE_BOX_SIZE - msgFaceImg.getHeight()) >> 1);
            g.drawImage(msgFaceImg, ix, iy, Graphics.TOP | Graphics.LEFT);
            g.setClip(0, 0, sw, sh);
        }

        if (textFrame == 0) {
            g.setColor(0x000000);
            g.fillRect(bx, by, bw, bh);
            g.setColor(0xFFFFFF);
            g.drawRect(bx, by, bw - 1, bh - 1);
        }

        g.setClip(bx + 4, by + 4, bw - 8, bh - 8);
        g.setColor(msgColor);
        for (int i = 0; i < vis; i++) {
            g.drawString(msgLines[msgPageStart + i], bx + 6, by + 6 + i * lh, Graphics.TOP | Graphics.LEFT);
        }
        g.setClip(0, 0, sw, sh);

        // Small page-continue indicator at bottom-right if more lines remain
        if (msgPageStart + MAX_VISIBLE_MSG_LINES < msgLines.length) {
            int cx = bx + bw - 9;
            int cy = by + bh - 6;
            g.setColor(0xFFFFFF);
            g.drawLine(cx - 2, cy - 1, cx + 2, cy - 1);
            g.drawLine(cx - 1, cy, cx + 1, cy);
            g.drawLine(cx, cy + 1, cx, cy + 1);
        }
    }

    private void drawChoiceBox(Graphics g, int sw, int sh) {
        int lh = uiFont.getHeight() + 4;
        int bh = choiceLabels.length * lh + 10;
        int bw = sw - 40;
        int bx = 20;
        int by = (sh - bh) >> 1;

        g.setColor(0x000000);
        g.fillRect(bx, by, bw, bh);
        g.setColor(0xFFFFFF);
        g.drawRect(bx, by, bw - 1, bh - 1);

        for (int i = 0; i < choiceLabels.length; i++) {
            int ry = by + 5 + i * lh;
            if (i == choiceSel) {
                g.setColor(0x303048);
                g.fillRect(bx + 3, ry - 1, bw - 6, lh);
            }
            g.setColor(0xFFFFFF);
            g.drawString(choiceLabels[i], bx + 10, ry + 1, Graphics.TOP | Graphics.LEFT);
        }
    }

    // Full-screen RMXP-style Name Input UI sized cleanly for 240x320 Nokia 6300
    private void drawNameInputBox(Graphics g, int sw, int sh) {
        g.setColor(0x000000);
        g.fillRect(0, 0, sw, sh);

        int fh = uiFont.getHeight();
        int bx = 8;
        int bw = sw - 16;

        // 1. Top Header Box: Character sprite preview + Name slots
        int topY = 10;
        int topH = 52;
        g.setColor(0x000000);
        g.fillRect(bx, topY, bw, topH);
        g.setColor(0xFFFFFF);
        g.drawRect(bx, topY, bw - 1, topH - 1);

        int cid = data.getActorCharId(nameActorId);
        Image spr = (cid >= 0) ? data.getCharImage(cid) : null;
        int textX = bx + 10;
        if (spr != null) {
            drawCharacter(g, spr, bx + 12, topY + 28, 2, 0);
            textX = bx + 38;
        }

        g.setColor(0xAAAAAA);
        g.drawString("NAME", textX, topY + 6, Graphics.TOP | Graphics.LEFT);
        g.drawString(nameLen + "/" + nameMaxLen, bx + bw - 8, topY + 6, Graphics.TOP | Graphics.RIGHT);

        // Character slots (_ _ _ _ ...)
        int availW = (bx + bw - 8) - textX;
        int slotW = availW / nameMaxLen;
        if (slotW > 16) slotW = 16;
        if (slotW < 10) slotW = 10;
        int slotY = topY + 26;
        for (int i = 0; i < nameMaxLen; i++) {
            int sx = textX + i * slotW;
            boolean isCurSlot = (i == nameLen) || (nameLen == nameMaxLen && i == nameMaxLen - 1);
            if (i < nameLen) {
                g.setColor(0xFFFFFF);
                g.drawChar(nameBuf[i], sx + (slotW >> 1), slotY, Graphics.TOP | Graphics.HCENTER);
            }
            g.setColor(isCurSlot ? 0xFFE860 : 0x666666);
            g.drawLine(sx + 1, slotY + fh + 1, sx + slotW - 2, slotY + fh + 1);
        }

        // 2. Character Grid Box (7 rows x 10 cols + action bar)
        int gridY = topY + topH + 8;
        int cellW = (bw - 12) / 10;
        if (cellW < 16) cellW = 16;
        int cellH = fh + 6;
        int actH = fh + 8;
        int gridH = 7 * cellH + actH + 14;
        if (gridY + gridH > sh - 10) {
            cellH = fh + 3;
            actH = fh + 5;
            gridH = 7 * cellH + actH + 10;
        }

        g.setColor(0x000000);
        g.fillRect(bx, gridY, bw, gridH);
        g.setColor(0xFFFFFF);
        g.drawRect(bx, gridY, bw - 1, gridH - 1);

        int startX = bx + ((bw - (10 * cellW)) >> 1);
        int startY = gridY + 5;

        for (int r = 0; r < 7; r++) {
            String rowStr = NAME_GRID[r];
            int cy = startY + r * cellH;
            for (int c = 0; c < 10; c++) {
                int cx = startX + c * cellW;
                boolean sel = (nameRow == r && nameCol == c);
                if (sel) {
                    g.setColor(0x303048);
                    g.fillRect(cx, cy, cellW, cellH - 1);
                    g.setColor(0xFFFFFF);
                    g.drawRect(cx, cy, cellW - 1, cellH - 2);
                }
                char ch = rowStr.charAt(c);
                g.setColor(sel ? 0xFFE860 : 0xFFFFFF);
                if (ch == ' ') {
                    g.drawString("_", cx + (cellW >> 1), cy + 2, Graphics.TOP | Graphics.HCENTER);
                } else {
                    g.drawChar(ch, cx + (cellW >> 1), cy + 2, Graphics.TOP | Graphics.HCENTER);
                }
            }
        }

        // 3. Action row: [ DEL ] [ CLR ] [ OK ]
        int ay = startY + 7 * cellH + 4;
        int gap = 6;
        int actW = (bw - 16 - gap * 2) / 3;
        String[] acts = { "DEL", "CLR", "OK" };
        for (int a = 0; a < 3; a++) {
            int ax = bx + 8 + a * (actW + gap);
            boolean sel = (nameRow == 7 && nameAct == a);
            g.setColor(sel ? 0x303048 : 0x101018);
            g.fillRect(ax, ay, actW, actH);
            g.setColor(sel ? 0xFFE860 : 0x888888);
            g.drawRect(ax, ay, actW - 1, actH - 1);
            g.setColor(sel ? 0xFFE860 : 0xFFFFFF);
            g.drawString(acts[a], ax + (actW >> 1), ay + ((actH - fh) >> 1), Graphics.TOP | Graphics.HCENTER);
        }

    }

    private void drawNumberBox(Graphics g, int sw, int sh) {
        int bw = numDigits * 22 + 20;
        int bh = 44;
        int bx = (sw - bw) >> 1;
        int by = (sh - bh) >> 1;

        g.setColor(0x000000);
        g.fillRect(bx, by, bw, bh);
        g.setColor(0xFFFFFF);
        g.drawRect(bx, by, bw - 1, bh - 1);

        for (int i = 0; i < numDigits; i++) {
            int dx = bx + 10 + i * 22;
            if (i == numCursor) {
                g.setColor(0x303048);
                g.fillRect(dx, by + 10, 18, 22);
            }
            g.setColor(0xFFFFFF);
            g.drawString(String.valueOf(numBuf[i]), dx + 9, by + 13, Graphics.TOP | Graphics.HCENTER);
        }
    }

    private void drawInventoryMenu(Graphics g, int sw, int sh) {
        g.setColor(0x000000);
        g.fillRect(8, 12, sw - 16, sh - 24);
        g.setColor(0xFFFFFF);
        g.drawRect(8, 12, sw - 17, sh - 25);

        if (menuSubState == 2 && mergeFirstItemId > 0) {
            String firstN = sanitizeScriptText(data.getItemName(mergeFirstItemId), null, null);
            g.setClip(14, 16, sw - 28, 18);
            g.setColor(0xFFE860);
            g.drawString("MERGE: " + firstN + " + ?", 16, 18, Graphics.TOP | Graphics.LEFT);
            g.setClip(0, 0, sw, sh);
        } else {
            g.drawString("INVENTORY", 16, 18, Graphics.TOP | Graphics.LEFT);
        }

        int lh = uiFont.getHeight() + 3;
        int listTop = 38;
        int listBottom = sh - 46;
        int maxVis = (listBottom - listTop) / lh;
        if (maxVis < 3) maxVis = 3;

        int itemCount = countOwnedItems();
        int totalRows = (menuSubState == 2) ? itemCount : (itemCount + 2);
        if (menuSel >= totalRows && totalRows > 0) menuSel = totalRows - 1;

        int scroll = 0;
        if (menuSel >= maxVis) scroll = menuSel - maxVis + 1;

        int y = listTop;
        String selDesc = "";
        int drawn = 0;

        for (int row = scroll; row < totalRows && drawn < maxVis; row++) {
            boolean isSel = (row == menuSel);
            if (row < itemCount) {
                int id = getOwnedItemIdAt(row);
                int cnt = data.getItemCount(id);
                boolean isFirstMerge = (menuSubState == 2 && id == mergeFirstItemId);
                if (isSel) {
                    g.setColor(0x303048);
                    g.fillRect(12, y - 1, sw - 24, lh);
                    selDesc = sanitizeScriptText(data.getItemDesc(id), null, null);
                }
                g.setColor(isFirstMerge ? 0xFFE860 : 0xFFFFFF);
                String name = sanitizeScriptText(data.getItemName(id), null, null);
                String prefix = isFirstMerge ? "* " : "";
                g.setClip(14, y - 1, sw - 28, lh);
                g.drawString(prefix + name + (cnt > 1 ? (" x" + cnt) : ""), 16, y, Graphics.TOP | Graphics.LEFT);
                g.setClip(0, 0, sw, sh);
            } else {
                String label = (row == itemCount) ? "SAVE" : "TITLE";
                if (isSel) {
                    g.setColor(0x303048);
                    g.fillRect(12, y - 1, sw - 24, lh);
                }
                g.setColor(0xCCCCCC);
                g.drawString(label, 16, y, Graphics.TOP | Graphics.LEFT);
            }
            y += lh;
            drawn++;
        }

        // Inline [USE] / [MERGE] action bar when an item is clicked
        if (menuSubState == 1) {
            int by = sh - 40;
            g.setColor(0x000000);
            g.fillRect(14, by, sw - 28, 22);
            g.setColor(0xFFFFFF);
            g.drawRect(14, by, sw - 29, 21);

            int half = (sw - 34) >> 1;
            if (menuActionSel == 0) {
                g.setColor(0x303048);
                g.fillRect(16, by + 2, half, 18);
            } else {
                g.setColor(0x303048);
                g.fillRect(18 + half, by + 2, half, 18);
            }
            g.setColor(0xFFFFFF);
            g.drawString("USE", 16 + (half >> 1), by + 4, Graphics.TOP | Graphics.HCENTER);
            g.drawString("MERGE", 18 + half + (half >> 1), by + 4, Graphics.TOP | Graphics.HCENTER);
        } else {
            String footer = menuStatus.length() > 0 ? menuStatus : selDesc;
            if (footer.length() > 0) {
                g.setClip(14, sh - 36, sw - 28, 18);
                g.setColor(0xCCCCCC);
                g.drawString(footer, 16, sh - 34, Graphics.TOP | Graphics.LEFT);
                g.setClip(0, 0, sw, sh);
            }
        }
    }

    private String[] wrapText(String text, int maxW) {
        if (text == null || text.length() == 0) return new String[] { "" };
        String[] tmp = new String[24];
        int count = 0;
        int start = 0;
        int len = text.length();
        while (start <= len && count < tmp.length) {
            int nl = text.indexOf('\n', start);
            String seg = (nl < 0) ? text.substring(start) : text.substring(start, nl);
            if (uiFont.stringWidth(seg) <= maxW) {
                tmp[count++] = seg;
            } else {
                int p = 0;
                while (p < seg.length() && count < tmp.length) {
                    int cut = p;
                    int lastSpace = -1;
                    while (cut < seg.length()) {
                        char ch = seg.charAt(cut);
                        if (ch == ' ') lastSpace = cut;
                        if (uiFont.stringWidth(seg.substring(p, cut + 1)) > maxW) break;
                        cut++;
                    }
                    if (cut == p) cut = p + 1;
                    else if (cut < seg.length() && lastSpace > p) cut = lastSpace;
                    tmp[count++] = seg.substring(p, cut).trim();
                    p = (cut < seg.length() && seg.charAt(cut) == ' ') ? cut + 1 : cut;
                }
            }
            if (nl < 0) break;
            start = nl + 1;
        }
        String[] res = new String[count];
        System.arraycopy(tmp, 0, res, 0, count);
        return res;
    }

    private static int dirDx(int d) { return d == 4 ? -1 : (d == 6 ? 1 : 0); }
    private static int dirDy(int d) { return d == 8 ? -1 : (d == 2 ? 1 : 0); }
    private static int oppositeDir(int d) {
        if (d == 2) return 8;
        if (d == 8) return 2;
        if (d == 4) return 6;
        return 4;
    }

    private static int turnRight(int d) {
        if (d == 2) return 4;
        if (d == 4) return 8;
        if (d == 8) return 6;
        return 2;
    }

    private static int turnLeft(int d) {
        if (d == 2) return 6;
        if (d == 6) return 8;
        if (d == 8) return 4;
        return 2;
    }

    private static int stepToward(int fx, int fy, int tx, int ty) {
        int dx = tx - fx;
        int dy = ty - fy;
        int adx = dx < 0 ? -dx : dx;
        int ady = dy < 0 ? -dy : dy;
        if (adx >= ady) return dx < 0 ? 4 : 6;
        return dy < 0 ? 8 : 2;
    }

    private static int rmxpSpeedToPx(int rmxpSpeed) {
        if (rmxpSpeed <= 2) return 1;
        if (rmxpSpeed == 3) return 2;
        if (rmxpSpeed == 4) return 4;
        return 8;
    }
}
