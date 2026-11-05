package net.kdt.pojavlaunch.volt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pins featured servers at the top of Minecraft's servers.dat (NBT), keeping the player's own servers. */
final class ServerList {
    private ServerList() {}

    private static final String MANAGED = ".volt-servers";

    // ---- tiny NBT model: every tag is {type, value} ----
    static final class Tag { final byte type; Object value; Tag(byte t, Object v) { type = t; value = v; } }
    static final class NbtList { byte elemType; List<Object> items = new ArrayList<>(); }

    static void pin(File gameDir, List<String[]> featured) throws Exception {
        File file = new File(gameDir, "servers.dat");
        Map<String, Tag> root;
        if (file.exists()) {
            try { root = read(readFile(file)); } catch (Exception e) { root = new LinkedHashMap<>(); }
        } else root = new LinkedHashMap<>();

        Tag serversTag = root.get("servers");
        NbtList list;
        if (serversTag == null || serversTag.type != 9) { list = new NbtList(); list.elemType = 10; root.put("servers", new Tag((byte) 9, list)); }
        else list = (NbtList) serversTag.value;
        list.elemType = 10;

        Set<String> managed = new HashSet<>();
        File mf = new File(gameDir, MANAGED);
        if (mf.exists()) for (String s : new String(readFile(mf), StandardCharsets.UTF_8).split("\n")) if (!s.isEmpty()) managed.add(s);
        Set<String> ours = new HashSet<>();
        for (String[] f : featured) ours.add(f[1].toLowerCase());

        List<Object> kept = new ArrayList<>();
        for (Object o : list.items) {
            @SuppressWarnings("unchecked") Map<String, Tag> c = (Map<String, Tag>) o;
            Tag ip = c.get("ip");
            String key = ip == null ? "" : String.valueOf(ip.value).toLowerCase();
            if (!ours.contains(key) && !managed.contains(key)) kept.add(c);
        }
        List<Object> items = new ArrayList<>();
        StringBuilder mark = new StringBuilder();
        for (String[] f : featured) {
            Map<String, Tag> c = new LinkedHashMap<>();
            c.put("name", new Tag((byte) 8, f[0]));
            c.put("ip", new Tag((byte) 8, f[1]));
            c.put("acceptTextures", new Tag((byte) 1, (byte) 1));
            items.add(c);
            mark.append(f[1].toLowerCase()).append('\n');
        }
        items.addAll(kept);
        list.items = items;
        writeFile(file, write(root));
        writeFile(mf, mark.toString().getBytes(StandardCharsets.UTF_8));
    }

    // Android 5-7 friendly file helpers (java.nio.file needs Android 8+)
    static byte[] readFile(File f) throws Exception {
        try (InputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
    static void writeFile(File f, byte[] data) throws Exception {
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(data); }
    }

    // ---------------- reader ----------------
    static Map<String, Tag> read(byte[] data) throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        if (in.readByte() != 10) throw new Exception("not a compound");
        in.readUTF();
        @SuppressWarnings("unchecked") Map<String, Tag> m = (Map<String, Tag>) payload(in, (byte) 10);
        return m;
    }

    private static Object payload(DataInputStream in, byte t) throws Exception {
        switch (t) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: { byte[] b = new byte[in.readInt()]; in.readFully(b); return b; }
            case 8: return in.readUTF();
            case 9: { NbtList l = new NbtList(); l.elemType = in.readByte(); int n = in.readInt(); for (int i = 0; i < n; i++) l.items.add(payload(in, l.elemType)); return l; }
            case 10: {
                Map<String, Tag> c = new LinkedHashMap<>();
                for (;;) { byte ct = in.readByte(); if (ct == 0) break; String name = in.readUTF(); c.put(name, new Tag(ct, payload(in, ct))); }
                return c;
            }
            case 11: { int[] a = new int[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readInt(); return a; }
            case 12: { long[] a = new long[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readLong(); return a; }
            default: throw new Exception("bad tag " + t);
        }
    }

    // ---------------- writer ----------------
    static byte[] write(Map<String, Tag> root) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bo);
        out.writeByte(10); out.writeUTF("");
        writePayload(out, (byte) 10, root);
        out.flush();
        return bo.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static void writePayload(DataOutputStream out, byte t, Object v) throws Exception {
        switch (t) {
            case 1: out.writeByte((Byte) v); break;
            case 2: out.writeShort((Short) v); break;
            case 3: out.writeInt((Integer) v); break;
            case 4: out.writeLong((Long) v); break;
            case 5: out.writeFloat((Float) v); break;
            case 6: out.writeDouble((Double) v); break;
            case 7: { byte[] b = (byte[]) v; out.writeInt(b.length); out.write(b); break; }
            case 8: out.writeUTF((String) v); break;
            case 9: { NbtList l = (NbtList) v; out.writeByte(l.items.isEmpty() ? 0 : l.elemType); out.writeInt(l.items.size()); for (Object o : l.items) writePayload(out, l.elemType, o); break; }
            case 10: { for (Map.Entry<String, Tag> e : ((Map<String, Tag>) v).entrySet()) { out.writeByte(e.getValue().type); out.writeUTF(e.getKey()); writePayload(out, e.getValue().type, e.getValue().value); } out.writeByte(0); break; }
            case 11: { int[] a = (int[]) v; out.writeInt(a.length); for (int x : a) out.writeInt(x); break; }
            case 12: { long[] a = (long[]) v; out.writeInt(a.length); for (long x : a) out.writeLong(x); break; }
            default: throw new Exception("bad tag " + t);
        }
    }
}
