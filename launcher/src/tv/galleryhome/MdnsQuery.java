package tv.galleryhome;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A tiny one-shot mDNS "legacy unicast" query (RFC 6762 §6.7): ask 224.0.0.251:5353 for a service type from an
 * ordinary UDP port, and the speakers answer straight back to us with their SRV, TXT and IPv4 (A) records.
 * Used because the TV's own NsdManager only hands back an unscoped IPv6 link-local address, which can't be dialled.
 * No multicast lock is needed: the answers arrive as plain unicast.
 */
final class MdnsQuery {
    private MdnsQuery() { }

    static String groupHost = "224.0.0.251";       // tests point these at a fake responder
    static int groupPort = 5353;

    static final class Service {
        String instance = "", target = "", ipv4, model = "", name = "";
        int port;
        @Override public String toString() { return name + " [" + model + "] " + ipv4 + ":" + port; }
    }

    private static final int A = 1, PTR = 12, TXT = 16, SRV = 33;

    static List<Service> query(String type, int timeoutMs) throws Exception {
        String q = type.endsWith(".local") ? type : type + ".local";
        Map<String, Service> byInstance = new HashMap<>();
        Map<String, String> addr = new HashMap<>();
        DatagramSocket sock = new DatagramSocket();
        try {
            InetAddress group = InetAddress.getByName(groupHost);
            byte[] ptrQuery = build(q, PTR, 0x4d51);
            long end = System.currentTimeMillis() + timeoutMs;
            long resendAt = System.currentTimeMillis() + timeoutMs / 3;
            sock.send(new DatagramPacket(ptrQuery, ptrQuery.length, group, groupPort));
            byte[] buf = new byte[9000];
            boolean askedForA = false;
            while (true) {
                long now = System.currentTimeMillis();
                if (now >= end) break;
                if (resendAt > 0 && now >= resendAt) {          // UDP: ask once more in case the first got lost
                    resendAt = 0;
                    sock.send(new DatagramPacket(ptrQuery, ptrQuery.length, group, groupPort));
                    askedForA = askForMissingA(sock, group, byInstance, addr) || askedForA;
                }
                sock.setSoTimeout((int) Math.max(50, Math.min(end - now, resendAt > 0 ? resendAt - now : end - now)));
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try { sock.receive(p); } catch (SocketTimeoutException e) { continue; }
                try { parse(p.getData(), p.getLength(), q, byInstance, addr); } catch (RuntimeException ignored) { }
            }
        } finally {
            sock.close();
        }
        List<Service> out = new ArrayList<>();
        for (Service s : byInstance.values()) {
            if (s.port == 0) continue;
            if (s.ipv4 == null) s.ipv4 = addr.get(s.target);
            if (s.ipv4 != null) out.add(s);
        }
        return out;
    }

    /** If a speaker answered with SRV but left out its A record, ask for the A record directly. */
    private static boolean askForMissingA(DatagramSocket sock, InetAddress group, Map<String, Service> svcs, Map<String, String> addr) {
        boolean asked = false;
        for (Service s : svcs.values()) {
            if (s.target.isEmpty() || addr.containsKey(s.target)) continue;
            try {
                byte[] b = build(s.target, A, 0x4d52);
                sock.send(new DatagramPacket(b, b.length, group, groupPort));
                asked = true;
            } catch (Exception ignored) { }
        }
        return asked;
    }

    static byte[] build(String name, int type, int id) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int[] header = {id, 0, 1, 0, 0, 0};
        for (int h : header) { b.write(h >> 8); b.write(h); }
        for (String label : name.split("\\.")) {
            byte[] l = label.getBytes();
            b.write(l.length);
            b.write(l, 0, l.length);
        }
        b.write(0);
        b.write(type >> 8); b.write(type);
        b.write(0); b.write(1);                      // class IN
        return b.toByteArray();
    }

    static void parse(byte[] d, int len, String type, Map<String, Service> svcs, Map<String, String> addr) {
        int[] p = {12};
        int qd = u16(d, 4), an = u16(d, 6), ns = u16(d, 8), ar = u16(d, 10);
        for (int i = 0; i < qd; i++) { name(d, p, len); p[0] += 4; }
        String t = type.toLowerCase(Locale.US);
        for (int i = 0; i < an + ns + ar && p[0] < len; i++) {
            String owner = name(d, p, len).toLowerCase(Locale.US);
            int rt = u16(d, p[0]); int rdlen = u16(d, p[0] + 8);
            int rd = p[0] + 10;
            p[0] = rd + rdlen;
            if (p[0] > len) break;
            if (rt == PTR && owner.equals(t)) {
                int[] q = {rd};
                String inst = name(d, q, len).toLowerCase(Locale.US);
                if (!svcs.containsKey(inst)) { Service s = new Service(); s.instance = inst; svcs.put(inst, s); }
            } else if (rt == SRV) {
                Service s = get(svcs, owner, t);
                if (s == null) continue;
                s.port = u16(d, rd + 4);
                int[] q = {rd + 6};
                s.target = name(d, q, len).toLowerCase(Locale.US);
            } else if (rt == TXT) {
                Service s = get(svcs, owner, t);
                if (s == null) continue;
                int q = rd;
                while (q < rd + rdlen) {
                    int n = d[q] & 0xFF;
                    String kv = new String(d, q + 1, Math.min(n, rd + rdlen - q - 1), java.nio.charset.Charset.forName("UTF-8"));
                    q += n + 1;
                    int eq = kv.indexOf('=');
                    if (eq < 0) continue;
                    String k = kv.substring(0, eq), v = kv.substring(eq + 1);
                    if (k.equals("md")) s.model = v;
                    else if (k.equals("fn")) s.name = v;
                }
            } else if (rt == A && rdlen == 4) {
                addr.put(owner, (d[rd] & 0xFF) + "." + (d[rd + 1] & 0xFF) + "." + (d[rd + 2] & 0xFF) + "." + (d[rd + 3] & 0xFF));
            }
        }
        for (Service s : svcs.values()) if (s.ipv4 == null && !s.target.isEmpty()) s.ipv4 = addr.get(s.target);
    }

    /** SRV/TXT records belong to an instance of our type; create it if the PTR came in another packet. */
    private static Service get(Map<String, Service> svcs, String owner, String type) {
        Service s = svcs.get(owner);
        if (s == null && owner.endsWith("." + type)) { s = new Service(); s.instance = owner; svcs.put(owner, s); }
        return s;
    }

    private static int u16(byte[] d, int i) { return ((d[i] & 0xFF) << 8) | (d[i + 1] & 0xFF); }

    /** Reads a (possibly compressed) DNS name. */
    private static String name(byte[] d, int[] p, int len) {
        StringBuilder sb = new StringBuilder();
        int i = p[0], jumps = 0;
        boolean jumped = false;
        while (i < len) {
            int n = d[i] & 0xFF;
            if (n == 0) { i++; break; }
            if ((n & 0xC0) == 0xC0) {
                int ptr = ((n & 0x3F) << 8) | (d[i + 1] & 0xFF);
                if (!jumped) p[0] = i + 2;
                jumped = true;
                i = ptr;
                if (++jumps > 20) break;
                continue;
            }
            if (sb.length() > 0) sb.append('.');
            sb.append(new String(d, i + 1, Math.min(n, len - i - 1), java.nio.charset.Charset.forName("UTF-8")));
            i += n + 1;
        }
        if (!jumped) p[0] = i;
        return sb.toString();
    }
}
