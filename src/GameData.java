import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Hashtable;
import javax.microedition.lcdui.Image;
import javax.microedition.rms.RecordStore;

public class GameData {
    private static final String SAVE_STORE = "aooni_save";

    // Starting state from meta.bin (AOG2)
    public int startMap = 1;
    public int startX = 0;
    public int startY = 0;

    // Actor metadata from Actors.rxdata (indexed by RMXP actorId 1..N)
    private int[] initParty = new int[] { 1 };
    private int[] actorCharIds = new int[16];
    private String[] defaultActorNames = new String[16];

    // Items table (AOG3)
    public int[] itemIds;
    public int[] itemCommonEvents;
    public byte[] itemFlags; // bit 0 = consumable, bit 1 = menuUsable
    public String[] itemNames;
    public String[] itemDescs;

    // Pre-classified Oni variant type per baked character ID (O(1) runtime lookup)
    private byte[] charOniTypes = new byte[256];

    // Tileset remap tables (rawTileId sorted ascending -> slot = index + 1)
    private int[] tsIds;
    private int[] tsCols;
    private short[][] tsRawTables;

    // Name -> numeric asset ID tables (lowercase keys)
    private Hashtable charNameToId = new Hashtable();
    private Hashtable picNameToId = new Hashtable();
    private Hashtable faceNameToId = new Hashtable();

    // AOPK directory for /rooms.pak
    private int[] pakIds;
    private int[] pakOffsets;
    private int[] pakLengths;

    // Runtime GameState
    public boolean[] switches = new boolean[512];
    public int[] vars = new int[256];
    public byte[] itemCounts = new byte[128];
    public int[] partyOrder = new int[16];
    public int partyCount = 0;
    public String[] actorNames = new String[16];

    // Self-switches stored as packed int keys: (mapId << 18) | (evId << 2) | ch
    private int[] selfSwKeys = new int[256];
    private int selfSwCount = 0;

    // Asset caches (bounded to stay safely inside Nokia 6300 2 MB heap)
    private int loadedTsId = -1;
    private Image loadedTsImg = null;

    private static final int MAX_CHAR_CACHE = 16;
    private int[] charCacheIds = new int[MAX_CHAR_CACHE];
    private Image[] charCacheImgs = new Image[MAX_CHAR_CACHE];
    private int charCacheCursor = 0;

    public GameData() {
        for (int i = 0; i < MAX_CHAR_CACHE; i++) {
            charCacheIds[i] = -1;
        }
        for (int i = 0; i < actorCharIds.length; i++) {
            actorCharIds[i] = -1;
            defaultActorNames[i] = "";
            actorNames[i] = "";
        }
        resetState();
    }

    public void resetState() {
        for (int i = 0; i < switches.length; i++) switches[i] = false;
        for (int i = 0; i < vars.length; i++) vars[i] = 0;
        for (int i = 0; i < itemCounts.length; i++) itemCounts[i] = 0;
        partyCount = 0;
        for (int i = 0; i < initParty.length && i < partyOrder.length; i++) {
            partyOrder[partyCount++] = initParty[i];
        }
        for (int i = 0; i < actorNames.length; i++) {
            actorNames[i] = defaultActorNames[i] != null ? defaultActorNames[i] : "";
        }
        selfSwCount = 0;
    }

    public int getLeadCharId() {
        if (partyCount <= 0) return -1;
        int aid = partyOrder[0];
        if (aid < 0 || aid >= actorCharIds.length) return -1;
        return actorCharIds[aid];
    }

    public boolean saveState(int mapId, int tx, int ty, int dir, int frameCount) {
        RecordStore rs = null;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeInt(0x414F5333); // AOS3
            dos.writeShort(mapId);
            dos.writeShort(tx);
            dos.writeShort(ty);
            dos.writeByte(dir);
            dos.writeInt(frameCount);

            for (int i = 0; i < switches.length; i += 8) {
                int b = 0;
                for (int k = 0; k < 8 && (i + k) < switches.length; k++) {
                    if (switches[i + k]) b |= (1 << k);
                }
                dos.writeByte(b);
            }

            dos.writeShort(vars.length);
            for (int i = 0; i < vars.length; i++) {
                dos.writeInt(vars[i]);
            }

            dos.writeShort(itemCounts.length);
            dos.write(itemCounts);

            dos.writeByte(partyCount);
            for (int i = 0; i < partyCount; i++) {
                dos.writeShort(partyOrder[i]);
            }

            dos.writeShort(selfSwCount);
            for (int i = 0; i < selfSwCount; i++) {
                dos.writeInt(selfSwKeys[i]);
            }

            dos.writeByte(actorNames.length);
            for (int i = 0; i < actorNames.length; i++) {
                dos.writeUTF(actorNames[i] != null ? actorNames[i] : "");
            }
            dos.flush();

            byte[] blob = baos.toByteArray();
            rs = RecordStore.openRecordStore(SAVE_STORE, true);
            if (rs.getNumRecords() == 0) {
                rs.addRecord(blob, 0, blob.length);
            } else {
                rs.setRecord(1, blob, 0, blob.length);
            }
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (rs != null) {
                try { rs.closeRecordStore(); } catch (Exception e) {}
            }
        }
    }

    public boolean loadState(int[] outMeta) {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(SAVE_STORE, false);
            if (rs.getNumRecords() == 0) return false;
            byte[] blob = rs.getRecord(1);
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(blob));
            int saveMagic = dis.readInt();
            if (saveMagic != 0x414F5332 && saveMagic != 0x414F5333) return false;

            resetState();
            outMeta[0] = dis.readUnsignedShort();
            outMeta[1] = dis.readUnsignedShort();
            outMeta[2] = dis.readUnsignedShort();
            outMeta[3] = dis.readUnsignedByte();
            outMeta[4] = dis.readInt();

            for (int i = 0; i < switches.length; i += 8) {
                int b = dis.readUnsignedByte();
                for (int k = 0; k < 8 && (i + k) < switches.length; k++) {
                    switches[i + k] = (b & (1 << k)) != 0;
                }
            }

            int vLen = dis.readUnsignedShort();
            for (int i = 0; i < vLen; i++) {
                int v = dis.readInt();
                if (i < vars.length) vars[i] = v;
            }

            int iLen = dis.readUnsignedShort();
            for (int i = 0; i < iLen; i++) {
                byte c = dis.readByte();
                if (i < itemCounts.length) itemCounts[i] = c;
            }

            int pc = dis.readUnsignedByte();
            partyCount = 0;
            for (int i = 0; i < pc; i++) {
                int aid = dis.readUnsignedShort();
                if (partyCount < partyOrder.length) partyOrder[partyCount++] = aid;
            }

            int sc = dis.readUnsignedShort();
            if (sc > selfSwKeys.length) {
                selfSwKeys = new int[sc + 32];
            }
            selfSwCount = sc;
            for (int i = 0; i < sc; i++) {
                selfSwKeys[i] = dis.readInt();
            }
            if (saveMagic == 0x414F5333) {
                int ac = dis.readUnsignedByte();
                for (int i = 0; i < ac; i++) {
                    String an = dis.readUTF();
                    if (i < actorNames.length) actorNames[i] = an;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (rs != null) {
                try { rs.closeRecordStore(); } catch (Exception e) {}
            }
        }
    }

    public void loadMetadata() throws IOException {
        InputStream is = getClass().getResourceAsStream("/meta.bin");
        if (is == null) {
            throw new IOException("Missing /meta.bin");
        }
        DataInputStream dis = new DataInputStream(is);
        try {
            int m0 = dis.readUnsignedByte();
            int m1 = dis.readUnsignedByte();
            int m2 = dis.readUnsignedByte();
            int m3 = dis.readUnsignedByte();
            if (m0 != 'A' || m1 != 'O' || m2 != 'G' || (m3 != '2' && m3 != '3')) {
                throw new IOException("Re-run bake_assets.py for AOG3");
            }
            boolean isV3 = (m3 == '3');
            startMap = dis.readUnsignedShort();
            startX = dis.readUnsignedShort();
            startY = dis.readUnsignedShort();

            // Initial party
            int pc = dis.readUnsignedByte();
            initParty = new int[pc];
            for (int i = 0; i < pc; i++) {
                initParty[i] = dis.readUnsignedShort();
            }

            // Actors table
            int ac = dis.readUnsignedShort();
            for (int i = 0; i < ac; i++) {
                int aid = dis.readUnsignedShort();
                int cid = dis.readUnsignedShort();
                String aname = dis.readUTF();
                if (aid >= 0 && aid < actorCharIds.length) {
                    actorCharIds[aid] = (cid == 0xFFFF) ? -1 : cid;
                    defaultActorNames[aid] = aname;
                }
            }

            // Items
            int ic = dis.readUnsignedShort();
            itemIds = new int[ic];
            itemCommonEvents = new int[ic];
            itemFlags = new byte[ic];
            itemNames = new String[ic];
            itemDescs = new String[ic];
            for (int i = 0; i < ic; i++) {
                itemIds[i] = dis.readUnsignedShort();
                if (isV3) {
                    itemCommonEvents[i] = dis.readUnsignedShort();
                    itemFlags[i] = dis.readByte();
                } else {
                    itemCommonEvents[i] = 0;
                    itemFlags[i] = 2;
                }
                itemNames[i] = dis.readUTF();
                itemDescs[i] = dis.readUTF();
            }

            // Tileset remap tables
            int tc = dis.readUnsignedShort();
            tsIds = new int[tc];
            tsCols = new int[tc];
            tsRawTables = new short[tc][];
            for (int i = 0; i < tc; i++) {
                tsIds[i] = dis.readUnsignedShort();
                tsCols[i] = dis.readUnsignedShort();
                int count = dis.readUnsignedShort();
                short[] tbl = new short[count];
                for (int j = 0; j < count; j++) {
                    tbl[j] = (short) dis.readUnsignedShort();
                }
                tsRawTables[i] = tbl;
            }

            // Character name -> ID + Oni variant classification
            int cc = dis.readUnsignedShort();
            if (cc + 8 > charOniTypes.length) {
                charOniTypes = new byte[cc + 32];
            }
            for (int i = 0; i < cc; i++) {
                int cid = dis.readUnsignedShort();
                String cname = dis.readUTF();
                String low = cname.toLowerCase();
                charNameToId.put(low, new Integer(cid));
                if (cid >= 0 && cid < charOniTypes.length) {
                    charOniTypes[cid] = (byte) classifyOniSpriteName(low);
                }
            }

            // Picture name -> ID
            int picCount = dis.readUnsignedShort();
            for (int i = 0; i < picCount; i++) {
                int pid = dis.readUnsignedShort();
                String pname = dis.readUTF();
                picNameToId.put(pname.toLowerCase(), new Integer(pid));
            }

            // Face name -> ID
            int fc = dis.readUnsignedShort();
            for (int i = 0; i < fc; i++) {
                int fid = dis.readUnsignedShort();
                String fname = dis.readUTF();
                faceNameToId.put(fname.toLowerCase(), new Integer(fid));
            }
        } finally {
            try { dis.close(); } catch (Exception e) {}
        }
        resetState();
    }

    public void loadPakIndex() throws IOException {
        InputStream is = getClass().getResourceAsStream("/rooms.pak");
        if (is == null) {
            throw new IOException("Missing /rooms.pak");
        }
        DataInputStream dis = new DataInputStream(is);
        try {
            int m0 = dis.readUnsignedByte();
            int m1 = dis.readUnsignedByte();
            int m2 = dis.readUnsignedByte();
            int m3 = dis.readUnsignedByte();
            if (m0 != 'A' || m1 != 'O' || m2 != 'P' || m3 != 'K') {
                throw new IOException("Bad rooms.pak magic");
            }
            int count = dis.readUnsignedShort();
            pakIds = new int[count];
            pakOffsets = new int[count];
            pakLengths = new int[count];
            for (int i = 0; i < count; i++) {
                pakIds[i] = dis.readUnsignedShort();
                pakOffsets[i] = dis.readInt();
                pakLengths[i] = dis.readInt();
            }
        } finally {
            try { dis.close(); } catch (Exception e) {}
        }
    }

    public DataInputStream openPakEntry(int entryId) throws IOException {
        if (pakIds == null) return null;
        int lo = 0, hi = pakIds.length - 1;
        int found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >> 1;
            int id = pakIds[mid];
            if (id == entryId) {
                found = mid;
                break;
            } else if (id < entryId) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (found < 0) return null;

        InputStream is = getClass().getResourceAsStream("/rooms.pak");
        if (is == null) return null;
        skipFully(is, pakOffsets[found]);
        return new DataInputStream(is);
    }

    private static void skipFully(InputStream is, int bytes) throws IOException {
        int rem = bytes;
        byte[] scratch = null;
        while (rem > 0) {
            long skipped = is.skip(rem);
            if (skipped > 0) {
                rem -= (int) skipped;
            } else {
                if (scratch == null) scratch = new byte[256];
                int n = is.read(scratch, 0, rem < 256 ? rem : 256);
                if (n < 0) throw new IOException("Unexpected EOF in rooms.pak");
                rem -= n;
            }
        }
    }

    public int remapTile(int tsId, int rawTileId) {
        if (rawTileId <= 0 || tsIds == null) return 0;
        short[] tbl = null;
        for (int i = 0; i < tsIds.length; i++) {
            if (tsIds[i] == tsId) {
                tbl = tsRawTables[i];
                break;
            }
        }
        if (tbl == null) return 0;
        int lo = 0, hi = tbl.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >> 1;
            int v = tbl[mid] & 0xFFFF;
            if (v == rawTileId) return mid + 1;
            if (v < rawTileId) lo = mid + 1;
            else hi = mid - 1;
        }
        return 0;
    }

    public int getTilesetCols(int tsId) {
        if (tsIds != null) {
            for (int i = 0; i < tsIds.length; i++) {
                if (tsIds[i] == tsId) return tsCols[i];
            }
        }
        return 16;
    }

    public Image getTilesetImage(int tsId) {
        if (loadedTsId == tsId && loadedTsImg != null) {
            return loadedTsImg;
        }
        loadedTsImg = null;
        loadedTsId = -1;
        System.gc();
        try {
            String p = "/gfx/t" + pad2(tsId) + ".png";
            loadedTsImg = Image.createImage(p);
            loadedTsId = tsId;
        } catch (Exception e) {
            loadedTsImg = null;
        }
        return loadedTsImg;
    }

    public void clearCharCache() {
        for (int i = 0; i < MAX_CHAR_CACHE; i++) {
            charCacheIds[i] = -1;
            charCacheImgs[i] = null;
        }
        charCacheCursor = 0;
    }

    public int resolveCharId(String name) {
        if (name == null || name.length() == 0) return -1;
        Integer v = (Integer) charNameToId.get(name.toLowerCase());
        return v == null ? -1 : v.intValue();
    }

    public Image getCharImage(int charId) {
        if (charId < 0 || charId == 0xFFFF) return null;
        for (int i = 0; i < MAX_CHAR_CACHE; i++) {
            if (charCacheIds[i] == charId && charCacheImgs[i] != null) {
                return charCacheImgs[i];
            }
        }
        try {
            Image img = Image.createImage("/gfx/c" + pad3(charId) + ".png");
            int slot = charCacheCursor;
            charCacheCursor = (charCacheCursor + 1) % MAX_CHAR_CACHE;
            charCacheIds[slot] = charId;
            charCacheImgs[slot] = img;
            return img;
        } catch (Exception e) {
            return null;
        }
    }

    public Image loadPictureImage(String name) {
        if (name == null || name.length() == 0) return null;
        String key = stripExtLower(name);
        Integer v = (Integer) picNameToId.get(key);
        if (v == null) return null;
        try {
            return Image.createImage("/gfx/p" + pad3(v.intValue()) + ".png");
        } catch (Exception e) {
            return null;
        }
    }

    public Image loadFaceImage(String name) {
        if (name == null || name.length() == 0) return null;
        String key = stripExtLower(name);
        Integer fv = (Integer) faceNameToId.get(key);
        if (fv != null) {
            try {
                return Image.createImage("/gfx/f" + pad3(fv.intValue()) + ".png");
            } catch (Exception e) {}
        }
        return loadPictureImage(key);
    }

    public Image loadGameOverImage() {
        try {
            return Image.createImage("/gfx/gameover.png");
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripExtLower(String name) {
        String key = name.trim().toLowerCase();
        int dot = key.lastIndexOf('.');
        return dot > 0 ? key.substring(0, dot) : key;
    }

    // State helpers
    public boolean getSwitch(int id) {
        return (id >= 0 && id < switches.length) && switches[id];
    }

    public void setSwitch(int id, boolean val) {
        if (id >= 0 && id < switches.length) switches[id] = val;
    }

    public int getVar(int id) {
        return (id >= 0 && id < vars.length) ? vars[id] : 0;
    }

    public void setVar(int id, int val) {
        if (id >= 0 && id < vars.length) vars[id] = val;
    }

    public int getItemCount(int id) {
        return (id >= 0 && id < itemCounts.length) ? (itemCounts[id] & 0xFF) : 0;
    }

    public void addItem(int id, int delta) {
        if (id < 0 || id >= itemCounts.length) return;
        int nv = (itemCounts[id] & 0xFF) + delta;
        if (nv < 0) nv = 0;
        if (nv > 99) nv = 99;
        itemCounts[id] = (byte) nv;
    }

    public String getItemName(int id) {
        if (itemIds != null) {
            for (int i = 0; i < itemIds.length; i++) {
                if (itemIds[i] == id) return itemNames[i];
            }
        }
        return "Item #" + id;
    }

    public String getItemDesc(int id) {
        if (itemIds != null) {
            for (int i = 0; i < itemIds.length; i++) {
                if (itemIds[i] == id) return itemDescs[i];
            }
        }
        return "";
    }

    public boolean isInParty(int actorId) {
        for (int i = 0; i < partyCount; i++) {
            if (partyOrder[i] == actorId) return true;
        }
        return false;
    }

    public void setInParty(int actorId, boolean add) {
        if (add) {
            if (!isInParty(actorId) && partyCount < partyOrder.length) {
                partyOrder[partyCount++] = actorId;
            }
        } else {
            for (int i = 0; i < partyCount; i++) {
                if (partyOrder[i] == actorId) {
                    for (int j = i; j < partyCount - 1; j++) {
                        partyOrder[j] = partyOrder[j + 1];
                    }
                    partyCount--;
                    return;
                }
            }
        }
    }

    public int getPartySize() {
        return partyCount;
    }

    public String getActorName(int actorId) {
        if (actorId >= 0 && actorId < actorNames.length && actorNames[actorId] != null) {
            return actorNames[actorId];
        }
        return "";
    }

    public String getDefaultActorName(int actorId) {
        if (actorId >= 0 && actorId < defaultActorNames.length && defaultActorNames[actorId] != null) {
            return defaultActorNames[actorId];
        }
        return "Hiroshi";
    }

    public void setActorName(int actorId, String name) {
        if (actorId >= 0 && actorId < actorNames.length) {
            actorNames[actorId] = (name != null) ? name : "";
        }
    }

    public int getActorCharId(int actorId) {
        if (actorId >= 0 && actorId < actorCharIds.length) {
            return actorCharIds[actorId];
        }
        return -1;
    }

    public boolean getSelfSwitch(int mapId, int evId, int ch) {
        int key = (mapId << 18) | ((evId & 0xFFFF) << 2) | (ch & 3);
        for (int i = 0; i < selfSwCount; i++) {
            if (selfSwKeys[i] == key) return true;
        }
        return false;
    }

    public void setSelfSwitch(int mapId, int evId, int ch, boolean val) {
        int key = (mapId << 18) | ((evId & 0xFFFF) << 2) | (ch & 3);
        for (int i = 0; i < selfSwCount; i++) {
            if (selfSwKeys[i] == key) {
                if (!val) {
                    selfSwKeys[i] = selfSwKeys[--selfSwCount];
                }
                return;
            }
        }
        if (val) {
            if (selfSwCount >= selfSwKeys.length) {
                int[] next = new int[selfSwKeys.length * 2];
                System.arraycopy(selfSwKeys, 0, next, 0, selfSwCount);
                selfSwKeys = next;
            }
            selfSwKeys[selfSwCount++] = key;
        }
    }

    public int getItemIndex(int id) {
        if (itemIds != null) {
            for (int i = 0; i < itemIds.length; i++) {
                if (itemIds[i] == id) return i;
            }
        }
        return -1;
    }

    public int getItemCommonEvent(int id) {
        int idx = getItemIndex(id);
        return (idx >= 0 && itemCommonEvents != null) ? itemCommonEvents[idx] : 0;
    }

    public boolean isItemConsumable(int id) {
        int idx = getItemIndex(id);
        return (idx >= 0 && itemFlags != null) && ((itemFlags[idx] & 1) != 0);
    }

    public boolean isItemMenuUsable(int id) {
        int idx = getItemIndex(id);
        return (idx >= 0 && itemFlags != null) && ((itemFlags[idx] & 2) != 0);
    }

    public int getOniType(int charId) {
        if (charId < 0 || charId >= charOniTypes.length) return MapEvent.ONI_NONE;
        return charOniTypes[charId] & 0xFF;
    }

    private static int classifyOniSpriteName(String low) {
        if (low == null || low.length() == 0) return MapEvent.ONI_NONE;
        // Fuwatty / Blockman (straight-line high-speed dasher)
        if (low.indexOf("fuwa") >= 0 || low.indexOf("huwa") >= 0
                || low.indexOf("block") >= 0 || low.indexOf("kaku") >= 0) {
            return MapEvent.ONI_FUWATTY;
        }
        // Infected friends (Mika / Takuro / Takeshi Oni)
        if (low.indexOf("ao_mika") >= 0 || low.indexOf("mika_ao") >= 0
                || low.indexOf("ao_taku") >= 0 || low.indexOf("takurou_ao") >= 0
                || low.indexOf("takuro_ao") >= 0) {
            return MapEvent.ONI_INFECTED_FAST;
        }
        if (low.indexOf("ao_take") >= 0 || low.indexOf("takesi_ao") >= 0
                || low.indexOf("takeshi_ao") >= 0) {
            return MapEvent.ONI_TAKESHI;
        }
        // Squatto Oni (heavy stomping variant)
        if (low.indexOf("squat") >= 0 || low.indexOf("suku") >= 0
                || low.indexOf("gori") >= 0 || low.indexOf("debu") >= 0) {
            return MapEvent.ONI_SQUATTO;
        }
        // Jellyfish Oni (frenetic erratic bursts)
        if (low.indexOf("kurage") >= 0 || low.indexOf("jelly") >= 0
                || low.indexOf("ika") >= 0 || low.indexOf("tako") >= 0) {
            return MapEvent.ONI_JELLYFISH;
        }
        // Hunchback Oni (Blueberry Farm basement chase)
        if (low.indexOf("hunch") >= 0 || low.indexOf("sebone") >= 0
                || low.indexOf("kagamu") >= 0 || low.indexOf("nekoze") >= 0) {
            return MapEvent.ONI_HUNCHBACK;
        }
        // Blob / Sludge / Slug / Amoeba / Flat Oni (slow relentless ooze)
        if (low.indexOf("blob") >= 0 || low.indexOf("sludge") >= 0
                || low.indexOf("slime") >= 0 || low.indexOf("doro") >= 0
                || low.indexOf("ameba") >= 0 || low.indexOf("slug") >= 0
                || low.indexOf("namekuji") >= 0 || low.indexOf("flat") >= 0) {
            return MapEvent.ONI_BLOB;
        }
        // Classic Ao Oni (ao, ao2, ao_s, aooni, oni, etc. — excluding non-oni props)
        if (low.equals("ao") || low.startsWith("ao_") || low.startsWith("ao-")
                || low.indexOf("aooni") >= 0 || low.startsWith("oni")) {
            return MapEvent.ONI_CLASSIC;
        }
        return MapEvent.ONI_NONE;
    }

    private boolean nameHas(int itemId, String tok) {
        String n = getItemName(itemId).toLowerCase();
        return n.indexOf(tok) >= 0;
    }

    private int findItemIdByTokens(String req1, String req2, String exclude) {
        if (itemIds == null) return -1;
        for (int i = 0; i < itemIds.length; i++) {
            String n = itemNames[i].toLowerCase();
            if (req1 != null && n.indexOf(req1) < 0) continue;
            if (req2 != null && n.indexOf(req2) < 0) continue;
            if (exclude != null && n.indexOf(exclude) >= 0) continue;
            return itemIds[i];
        }
        return -1;
    }

    /**
     * Direct inventory combination fallback when neither item's RMXP CommonEvent
     * directly references the other item. Returns a short status message on success,
     * or null if the two items cannot be merged.
     */
    public String tryDirectRecipeMerge(int idA, int idB) {
        if (idA == idB || getItemCount(idA) <= 0 || getItemCount(idB) <= 0) return null;

        // 1. Soap + Handkerchief -> Soapy Handkerchief
        boolean soapA = nameHas(idA, "soap") || nameHas(idA, "\u77f3\u9e78");
        boolean soapB = nameHas(idB, "soap") || nameHas(idB, "\u77f3\u9e78");
        boolean hankA = (nameHas(idA, "handkerchief") || nameHas(idA, "\u30cf\u30f3\u30ab\u30c1")) && !soapA;
        boolean hankB = (nameHas(idB, "handkerchief") || nameHas(idB, "\u30cf\u30f3\u30ab\u30c1")) && !soapB;
        if ((soapA && hankB) || (soapB && hankA)) {
            int res = findItemIdByTokens("soap", "handkerchief", null);
            if (res < 0) res = findItemIdByTokens("wet", "handkerchief", null);
            if (res < 0) res = findItemIdByTokens("\u77f3\u9e78", "\u30cf\u30f3\u30ab\u30c1", null);
            if (res > 0) {
                addItem(idA, -1);
                addItem(idB, -1);
                addItem(res, 1);
                return "Made: " + getItemName(res);
            }
        }

        // 2. Disc + Metal Stick -> Hidden Door Key
        boolean discA = nameHas(idA, "disc") || nameHas(idA, "disk") || nameHas(idA, "\u5186\u76e4");
        boolean discB = nameHas(idB, "disc") || nameHas(idB, "disk") || nameHas(idB, "\u5186\u76e4");
        boolean stickA = (nameHas(idA, "metal") && nameHas(idA, "stick")) || nameHas(idA, "rod")
                || nameHas(idA, "\u91d1\u5c5e");
        boolean stickB = (nameHas(idB, "metal") && nameHas(idB, "stick")) || nameHas(idB, "rod")
                || nameHas(idB, "\u91d1\u5c5e");
        if ((discA && stickB) || (discB && stickA)) {
            int res = findItemIdByTokens("hidden", "key", null);
            if (res < 0) res = findItemIdByTokens("door", "key", null);
            if (res < 0) res = findItemIdByTokens("\u96a0\u3057", "\u9375", null);
            if (res > 0) {
                addItem(idA, -1);
                addItem(idB, -1);
                addItem(res, 1);
                return "Made: " + getItemName(res);
            }
        }

        // 3. Lighter + Lighter Fluid -> Refilled Lighter
        boolean fluidA = nameHas(idA, "fluid") || nameHas(idA, "oil") || nameHas(idA, "\u30aa\u30a4\u30eb");
        boolean fluidB = nameHas(idB, "fluid") || nameHas(idB, "oil") || nameHas(idB, "\u30aa\u30a4\u30eb");
        boolean lightA = (nameHas(idA, "lighter") || nameHas(idA, "\u30e9\u30a4\u30bf\u30fc")) && !fluidA;
        boolean lightB = (nameHas(idB, "lighter") || nameHas(idB, "\u30e9\u30a4\u30bf\u30fc")) && !fluidB;
        if ((lightA && fluidB) || (lightB && fluidA)) {
            int fluidId = fluidA ? idA : idB;
            int lightId = lightA ? idA : idB;
            int res = findItemIdByTokens("lighter", "fill", "fluid");
            if (res < 0) res = findItemIdByTokens("lighter", "oil", "fluid");
            addItem(fluidId, -1);
            if (res > 0 && res != lightId) {
                addItem(lightId, -1);
                addItem(res, 1);
                return "Refilled: " + getItemName(res);
            }
            return "Refilled Lighter!";
        }

        // 4. Vinegar + Red Key -> Cleaned Red Key
        boolean vinA = nameHas(idA, "vinegar") || nameHas(idA, "\u9162");
        boolean vinB = nameHas(idB, "vinegar") || nameHas(idB, "\u9162");
        boolean rkeyA = (nameHas(idA, "red") && nameHas(idA, "key")) || nameHas(idA, "\u8d64");
        boolean rkeyB = (nameHas(idB, "red") && nameHas(idB, "key")) || nameHas(idB, "\u8d64");
        if ((vinA && rkeyB) || (vinB && rkeyA)) {
            int vinId = vinA ? idA : idB;
            int keyId = rkeyA ? idA : idB;
            int cleanKey = -1;
            if (itemIds != null) {
                for (int i = 0; i < itemIds.length; i++) {
                    int cand = itemIds[i];
                    if (cand == keyId) continue;
                    String n = itemNames[i].toLowerCase();
                    if (n.indexOf("key") >= 0 && (n.indexOf("clean") >= 0 || n.indexOf("silver") >= 0
                            || n.indexOf("gold") >= 0 || n.indexOf("annex") >= 0 || n.indexOf("cell") >= 0)) {
                        cleanKey = cand;
                        break;
                    }
                }
            }
            addItem(vinId, -1);
            if (cleanKey > 0) {
                addItem(keyId, -1);
                addItem(cleanKey, 1);
                return "Cleaned: " + getItemName(cleanKey);
            }
            return "Cleaned Red Key!";
        }

        // 5. Phillips Screwdriver + Flat Head -> Flat Head Screwdriver
        boolean philA = nameHas(idA, "phillip") || nameHas(idA, "plus") || nameHas(idA, "\u30d7\u30e9\u30b9");
        boolean philB = nameHas(idB, "phillip") || nameHas(idB, "plus") || nameHas(idB, "\u30d7\u30e9\u30b9");
        boolean flatA = nameHas(idA, "flat") || nameHas(idA, "minus") || nameHas(idA, "\u30de\u30a4\u30ca\u30b9");
        boolean flatB = nameHas(idB, "flat") || nameHas(idB, "minus") || nameHas(idB, "\u30de\u30a4\u30ca\u30b9");
        if ((philA && flatB) || (philB && flatA)) {
            int res = findItemIdByTokens("flat", "screwdriver", null);
            if (res < 0) res = findItemIdByTokens("flat", "driver", null);
            if (res > 0 && res != idA && res != idB) {
                addItem(idA, -1);
                addItem(idB, -1);
                addItem(res, 1);
                return "Swapped: " + getItemName(res);
            }
        }

        return null;
    }

    private static String pad2(int n) {
        return n < 10 ? "0" + n : String.valueOf(n);
    }

    private static String pad3(int n) {
        if (n < 10) return "00" + n;
        if (n < 100) return "0" + n;
        return String.valueOf(n);
    }
}
