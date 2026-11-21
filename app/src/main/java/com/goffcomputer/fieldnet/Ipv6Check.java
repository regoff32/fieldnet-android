package com.goffcomputer.fieldnet;

import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FieldNet - IPv6 check (added in 1.3).
 *
 * Answers: Is IPv6 on? Does it work? Is DNS filtering (OpenDNS) being
 * bypassed? What's out there?
 *
 * Tests the network the phone uses for internet: Ethernet if it has
 * internet, otherwise Wi-Fi, otherwise cellular. Every step fails
 * gracefully and says "Not available on this device" when Android blocks it.
 */
class Ipv6Check {

    static final String OPENDNS_V6 = "2620:119:35::35 and 2620:119:53::53";

    final MainActivity a;

    Ipv6Check(MainActivity a) { this.a = a; }

    // ============================================================== helpers
    /** global (internet, 2.../3...) | ula (private fd..) | linklocal (fe80::) | other */
    static String kind(InetAddress ad) {
        if (!(ad instanceof Inet6Address)) return "other";
        byte[] b = ad.getAddress();
        int b0 = b[0] & 0xff, b1 = b[1] & 0xff;
        if (b0 == 0xfe && (b1 & 0xc0) == 0x80) return "linklocal";
        if ((b0 & 0xfe) == 0xfc) return "ula";
        if ((b0 & 0xe0) == 0x20) return "global";
        return "other";
    }

    /** Address as text without the %wlan0 scope part. */
    static String txt(InetAddress ad) {
        String s = ad.getHostAddress();
        int p = s.indexOf('%');
        return p >= 0 ? s.substring(0, p) : s;
    }

    static String short6(String s) {
        return s.length() > 22 ? s.substring(0, 19) + "..." : s;
    }

    static boolean isOpenDns(String s) {
        s = s.toLowerCase(Locale.US);
        return s.startsWith("208.67.222.") || s.startsWith("208.67.220.") || s.startsWith("2620:119:");
    }

    /** Quick IPv6 state from the address list only (no traffic). */
    static String quick(LinkProperties lp) {
        if (lp == null) return "not connected";
        String ula = null;
        for (LinkAddress la : lp.getLinkAddresses()) {
            String k = kind(la.getAddress());
            if (k.equals("global")) return "ON (" + txt(la.getAddress()) + ")";
            if (k.equals("ula")) ula = txt(la.getAddress());
        }
        return ula != null ? "OFF for internet (private fd.. only)" : "OFF (local only)";
    }

    static class Pick {
        Network net;
        String kind;
        LinkProperties lp;
        boolean validated;
    }

    /** The network to test: the one the customer's internet comes through. */
    Pick pickNetwork() {
        Pick best = null;
        int bestScore = -1;
        for (Network n : a.cm.getAllNetworks()) {
            NetworkCapabilities nc = a.cm.getNetworkCapabilities(n);
            LinkProperties lp = a.cm.getLinkProperties(n);
            if (nc == null || lp == null || nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            boolean v = nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            String k;
            int score;
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) { k = "Ethernet"; score = v ? 5 : 1; }
            else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) { k = "Wi-Fi"; score = v ? 4 : 2; }
            else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) { k = "Cellular"; score = v ? 3 : 0; }
            else continue;
            if (score > bestScore) {
                bestScore = score;
                best = new Pick();
                best.net = n;
                best.kind = k;
                best.lp = lp;
                best.validated = v;
            }
        }
        return best;
    }

    /** The IPv6 source address this network would use to reach the internet.
     *  UDP "connect" sends nothing; null = no IPv6 route. */
    static InetAddress sourceFor(Network net) {
        try (DatagramSocket ds = new DatagramSocket()) {
            net.bindSocket(ds);
            ds.connect(InetAddress.getByName("2001:4860:4860::8888"), 53);
            InetAddress la = ds.getLocalAddress();
            if (la == null || la.isAnyLocalAddress() || !(la instanceof Inet6Address)) return null;
            return la;
        } catch (Exception e) {
            return null;
        }
    }

    static String httpGet(Network net, String url, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) net.openConnection(new URL(url));
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setUseCaches(false);
        c.setRequestProperty("Connection", "close");
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) return "";
            try (InputStream i = in) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = i.read(buf)) > 0 && bo.size() < 65536) bo.write(buf, 0, n);
                return bo.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    /** TCP connect over this network. Returns ms, or -1 on failure. */
    static long tcpMs(Network net, String ip, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            net.bindSocket(s);
            long t0 = System.nanoTime();
            s.connect(new InetSocketAddress(InetAddress.getByName(ip), port), timeoutMs);
            return (System.nanoTime() - t0) / 1_000_000;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Ask one DNS server directly for debug.opendns.com TXT.
     *  1 = OpenDNS (it answers), 0 = not OpenDNS (no such name), -1 = no answer. */
    static int askOpenDns(Network net, InetAddress server) {
        try (DatagramSocket ds = new DatagramSocket()) {
            net.bindSocket(ds);
            ds.setSoTimeout(2000);
            ByteArrayOutputStream q = new ByteArrayOutputStream();
            int id = new Random().nextInt(0xffff);
            q.write(id >> 8); q.write(id & 0xff);
            q.write(0x01); q.write(0x00);            // recursion desired
            q.write(0); q.write(1);                  // 1 question
            for (int k = 0; k < 6; k++) q.write(0);  // no answers/authority/extra
            for (String label : "debug.opendns.com".split("\\.")) {
                q.write(label.length());
                byte[] lb = label.getBytes(StandardCharsets.US_ASCII);
                q.write(lb, 0, lb.length);
            }
            q.write(0);
            q.write(0); q.write(16);                 // TXT
            q.write(0); q.write(1);                  // IN
            byte[] out = q.toByteArray();
            ds.send(new DatagramPacket(out, out.length, server, 53));
            byte[] buf = new byte[1500];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            ds.receive(p);
            if (p.getLength() < 12) return -1;
            int rcode = buf[3] & 0x0f;
            int answers = ((buf[6] & 0xff) << 8) | (buf[7] & 0xff);
            return (rcode == 0 && answers > 0) ? 1 : 0;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Run a command (ping, ip). Returns its output, or null if Android blocks it. */
    static String exec(String[] cmd, int secs) {
        try {
            Process pr = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            if (!pr.waitFor(secs, TimeUnit.SECONDS)) pr.destroy();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (InputStream in = pr.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0 && bo.size() < 65536) bo.write(buf, 0, n);
            }
            return bo.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    void rep(String label, String value, int color) {
        a.say(String.format(Locale.US, "  %-12s %s", label, value), color);
    }

    void step(String s) {
        a.say("", 0);
        a.say("  " + s, MainActivity.BLUE);
    }

    // ================================================================= check
    void run() {
        a.hdr("IPv6 check");
        a.say("  Is IPv6 on? Does it work? Is DNS filtering", 0);
        a.say("  being bypassed? What's out there?", 0);

        Pick p = pickNetwork();
        if (p == null) {
            a.err("No network to test.");
            a.hint("Join the customer's Wi-Fi, or plug into their router/switch.");
            a.setV6Line("IPv6: no network to test", MainActivity.GRAY);
            return;
        }
        Network net = p.net;
        LinkProperties lp = p.lp;
        String ifn = lp.getInterfaceName();
        boolean cell = p.kind.equals("Cellular");
        a.say("  Testing: " + p.kind + (ifn != null ? " (" + ifn + ")" : ""), 0);
        if (cell) {
            a.warn("This is cellular data, not a customer network.");
            a.hint("Join the customer's Wi-Fi to test their network.");
        } else if (p.kind.equals("Ethernet") && !p.validated) {
            a.hint("Ethernet has no internet (normal on a direct hookup).");
            a.hint("Internet tests will fail. Join their Wi-Fi to test it.");
        }
        if (a.vpnOn()) a.hint("A VPN is on. FieldNet tests underneath it.");

        // ---- 1) Is IPv6 on?
        step("1) Is IPv6 on?");
        List<String> gl = new ArrayList<>(), ula = new ArrayList<>();
        boolean hasV4 = false;
        for (LinkAddress la : lp.getLinkAddresses()) {
            InetAddress ad = la.getAddress();
            if (ad instanceof Inet4Address) hasV4 = true;
            String k = kind(ad);
            if (k.equals("global")) gl.add(txt(ad));
            else if (k.equals("ula")) ula.add(txt(ad));
        }
        InetAddress srcAd = sourceFor(net);
        String src = srcAd != null ? txt(srcAd) : null;
        boolean v6on;
        String st;
        if (src != null && kind(srcAd).equals("global")) {
            v6on = true;
            st = "ON  (this phone: " + src + ")";
            a.ok("IPv6 is ON. This phone has an internet IPv6 address:");
            a.say("      " + src, 0);
        } else if (!gl.isEmpty()) {
            v6on = true;
            st = "ON  (" + gl.get(0) + ", no IPv6 route out)";
            a.warn("There's an internet IPv6 address, but no route out.");
        } else if (!ula.isEmpty()) {
            v6on = false;
            st = "OFF for internet (private fd.. IPv6 only)";
            a.say("  IPv6 is OFF for the internet. Private (fd..) only.", 0);
        } else {
            v6on = false;
            st = "OFF (local only)";
            a.say("  IPv6 is OFF (only fe80:: local addresses).", 0);
        }
        if (v6on && !ula.isEmpty()) a.hint("Private IPv6 (fd..) also in use: " + ula.get(0));
        if (cell && v6on && !hasV4) {
            a.hint("Cellular is IPv6-only here; IPv4 runs through");
            a.hint("464XLAT. That's normal for the carrier.");
            st += " · cellular IPv6-only/464XLAT";
        } else if (cell) {
            st += " · cellular";
        }

        // ---- 2) Public IPv6 and 3) internet over IPv6
        String pub = "None", pubNote = "", netLine = "Skipped (IPv6 is off)";
        long ms = -1;
        boolean broken = false;
        if (v6on) {
            step("2) Public IPv6 address");
            try {
                String body = httpGet(net, "https://api6.ipify.org", 6000).trim();
                if (body.contains(":") && body.matches("[0-9a-fA-F:]+")) pub = body;
            } catch (Exception ignored) { }
            if (pub.equals("None")) {
                a.say("  No answer (normal if IPv6 doesn't reach the", 0);
                a.say("  internet - see step 3).", 0);
            } else {
                a.say("  " + pub, 0);
                boolean mine = pub.equalsIgnoreCase(src != null ? src : "");
                for (String g : gl) if (g.equalsIgnoreCase(pub)) mine = true;
                if (mine) {
                    pubNote = "this phone's own address";
                    a.ok("It's this phone's own address: no NAT.");
                    a.hint("Normal for IPv6. Every device gets its own public");
                    a.hint("address, so the router's IPv6 firewall protects it.");
                } else {
                    pubNote = "differs from this phone - NAT, VPN or proxy?";
                    a.warn("Differs from this phone's address. IPv6 NAT, a VPN");
                    a.hint("or a proxy is in the path.");
                }
            }

            step("3) Does IPv6 reach the internet?");
            a.say("  TCP connect to Cloudflare [2606:4700:4700::1111]:443", 0);
            ms = tcpMs(net, "2606:4700:4700::1111", 443, 3000);
            if (ms < 0 && !a.stop) {
                a.say("  No answer. Trying Google [2001:4860:4860::8888]:53", 0);
                ms = tcpMs(net, "2001:4860:4860::8888", 53, 3000);
            }
            if (ms >= 0) {
                netLine = "OK · " + ms + " ms over IPv6";
                a.ok("IPv6 reaches the internet (" + ms + " ms).");
            } else {
                broken = true;
                netLine = "FAILED - no internet over IPv6";
                a.err("IPv6 is turned on but doesn't reach the internet.");
            }
        }
        if (a.stop) { a.warn("Stopped."); return; }

        // ---- 4) Router advertisement (Android shows this without root)
        step("4) What the router advertises over IPv6");
        String pfx = null, router = null;
        try {
            for (RouteInfo r : lp.getRoutes()) {
                IpPrefix d = r.getDestination();
                if (d == null || !(d.getAddress() instanceof Inet6Address)) continue;
                InetAddress gw = r.getGateway();
                if (d.getPrefixLength() == 0 && gw != null && !gw.isAnyLocalAddress()) {
                    if (router == null) router = txt(gw);
                } else if (pfx == null && kind(d.getAddress()).equals("global")
                        && d.getPrefixLength() >= 48 && d.getPrefixLength() <= 64) {
                    pfx = txt(d.getAddress()) + "/" + d.getPrefixLength();
                }
            }
        } catch (Exception e) {
            a.hint("Route details not available on this device.");
        }
        String rtrLine = (pfx != null || router != null)
                ? "prefix " + (pfx != null ? pfx : "none") + " · router " + (router != null ? router : "none")
                : (cell ? "n/a on cellular" : "No IPv6 router advertisement seen");
        a.say("  " + rtrLine, 0);
        String pstem = pfx != null ? pfx.split("::")[0].split("/")[0].toLowerCase(Locale.US) : null;

        List<InetAddress> dns4 = new ArrayList<>(), dns6 = new ArrayList<>();
        try {
            for (InetAddress d : lp.getDnsServers()) {
                if (d instanceof Inet6Address) dns6.add(d);
                else dns4.add(d);
            }
        } catch (Exception ignored) { }
        boolean openDnsWanted = false;
        StringBuilder v4s = new StringBuilder();
        for (InetAddress d : dns4) {
            v4s.append(txt(d)).append(" ");
            if (isOpenDns(txt(d))) openDnsWanted = true;
        }
        a.say("  IPv4 DNS: " + (dns4.isEmpty() ? "none shown" : v4s.toString().trim()), 0);
        List<String> bad6 = new ArrayList<>();
        StringBuilder v6s = new StringBuilder();
        for (InetAddress d : dns6) {
            String s = txt(d).toLowerCase(Locale.US);
            String desc;
            if (s.startsWith("2620:119:")) desc = "OpenDNS";
            else if (kind(d).equals("linklocal") || kind(d).equals("ula")
                    || (pstem != null && s.startsWith(pstem + ":"))) desc = "the router itself";
            else { desc = "NOT OpenDNS"; bad6.add(s); }
            v6s.append(s).append(" ");
            a.say("  IPv6 DNS " + s + " -> " + desc, 0);
        }
        if (dns6.isEmpty()) a.say("  IPv6 DNS: none advertised", 0);

        boolean privateDns = false;
        String privateName = null;
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                privateDns = lp.isPrivateDnsActive();
                privateName = lp.getPrivateDnsServerName();
            } catch (Exception ignored) { }
        }

        // ---- 5) DNS filtering
        step("5) Is DNS filtering (OpenDNS) being bypassed?");
        if (privateName != null) {
            a.warn("This phone uses Private DNS (" + privateName + "),");
            a.hint("which skips the router's DNS. These results are for");
            a.hint("this phone only. Settings > Connections > More");
            a.hint("connection settings > Private DNS.");
        }
        int w = 0, o = 0;
        for (int n = 1; n <= 3 && !a.stop; n++) {
            String r;
            try {
                String body = httpGet(net, "https://welcome.opendns.com", 6000);
                r = body.contains("Welcome to OpenDNS") ? "Welcome to OpenDNS"
                        : body.contains("Oops") ? "Oops" : "no answer";
            } catch (Exception e) {
                r = "no answer";
            }
            if (r.startsWith("Welcome")) w++;
            else if (r.equals("Oops")) o++;
            a.say("  Check " + n + " of 3: " + r, r.startsWith("Welcome") ? MainActivity.GREEN : 0);
            if (n < 3) {
                try { Thread.sleep(1000); } catch (InterruptedException ie) { return; }
            }
        }
        int tot = w + o;
        if (w > 0) openDnsWanted = true;
        // Ask each DNS server directly (debug.opendns.com only answers on OpenDNS)
        List<InetAddress> all = new ArrayList<>(dns4);
        all.addAll(dns6);
        for (InetAddress d : all) {
            if (a.stop) break;
            int r = askOpenDns(net, d);
            String s = txt(d);
            if (r == 1) {
                a.ok(s + " -> OpenDNS");
                openDnsWanted = true;
            } else if (r == 0) {
                a.say("  " + s + " -> NOT OpenDNS", 0);
                if (d instanceof Inet6Address && !bad6.contains(s.toLowerCase(Locale.US))) bad6.add(s.toLowerCase(Locale.US));
            } else {
                a.say("  " + s + " -> no answer", MainActivity.GRAY);
            }
        }
        String aaaa = "no";
        try {
            for (InetAddress ad : net.getAllByName("www.google.com")) {
                if (ad instanceof Inet6Address && !txt(ad).startsWith("::ffff:")) aaaa = "yes";
            }
        } catch (Exception e) {
            aaaa = "lookup failed";
        }
        a.say("  IPv6 (AAAA) records come back: " + aaaa, 0);

        String dnsLine;
        if (tot == 0) dnsLine = "Couldn't test (no internet?)";
        else if (w == tot) dnsLine = "OpenDNS in use (" + w + " of " + tot + " checks)";
        else if (o == tot) dnsLine = "Not using OpenDNS";
        else dnsLine = "MIXED - OpenDNS on " + w + " of " + tot + " checks";
        if (!bad6.isEmpty() && openDnsWanted) dnsLine += " · IPv6 DNS " + String.join(" ", bad6) + " is NOT OpenDNS";
        if (privateName != null) dnsLine += " · Private DNS " + privateName;
        boolean dnsWarn = v6on && ((w > 0 && o > 0) || (!bad6.isEmpty() && openDnsWanted));
        if (dnsWarn) {
            a.warn("IPv6 is on and this device isn't always using");
            a.warn("OpenDNS. Filtering may be bypassed.");
            a.hint("Set OpenDNS IPv6 servers on the router:");
            a.hint(OPENDNS_V6);
        } else if (tot > 0 && o == tot) {
            a.say("  Not using OpenDNS.", 0);
            if (v6on) {
                a.hint("Only matters if this customer is supposed to be on");
                a.hint("OpenDNS. If so, set it on the router for IPv4 AND");
                a.hint("IPv6 (" + OPENDNS_V6 + ").");
            }
        } else if (tot > 0 && w == tot) {
            a.ok("OpenDNS answered every check.");
        } else if (w > 0 && o > 0) {
            a.warn("Mixed results: one DNS server in use isn't OpenDNS.");
        }

        // ---- 6) Neighbors
        String neighLine = null;
        List<String> globals = new ArrayList<>();
        if (cell) {
            neighLine = "n/a on cellular";
        } else if (ifn == null) {
            neighLine = "Not available on this device";
        } else if (!a.stop) {
            step("6) Who's on the network over IPv6");
            a.hint("IPv6 can't be swept like a /24 (a /64 has billions");
            a.hint("of addresses), so we ping everyone at once (ff02::1).");
            Set<String> mine = new LinkedHashSet<>();
            for (LinkAddress la : lp.getLinkAddresses()) mine.add(txt(la.getAddress()).toLowerCase(Locale.US));
            StringBuilder out = new StringBuilder();
            boolean pingOk = false;
            String[][] tries = {
                    {"ping6", "-c", "2", "-w", "3", "-I", ifn, "ff02::1"},
                    {"ping", "-6", "-c", "2", "-w", "3", "-I", ifn, "ff02::1"},
            };
            for (String[] t : tries) {
                String r = exec(t, 6);
                if (r != null && r.contains("from ")) {
                    out.append(r);
                    pingOk = true;
                    if (v6on && src != null && kind(srcAd).equals("global")) {
                        // from our internet address, so devices answer from THEIR internet address
                        String[] t2 = t.clone();
                        t2[t2.length - 2] = src;
                        t2[t2.length - 1] = "ff02::1%" + ifn;
                        String r2 = exec(t2, 6);
                        if (r2 != null) out.append("\n").append(r2);
                    }
                    break;
                }
            }
            Map<String, String> found = new LinkedHashMap<>();   // address -> "router" or ""
            Matcher m = Pattern.compile("from ([0-9a-fA-F:]+)").matcher(out);
            while (m.find()) {
                String ad = m.group(1).toLowerCase(Locale.US);
                if (!mine.contains(ad)) found.put(ad, "");
            }
            // Neighbor table: blocked for apps on Android 10+, try anyway
            String nt = exec(new String[]{"ip", "-6", "neigh", "show", "dev", ifn}, 3);
            boolean tableOk = nt != null && nt.contains("lladdr");
            Map<String, List<String>> byMac = new LinkedHashMap<>();
            if (tableOk) {
                for (String line : nt.split("\n")) {
                    String[] f = line.trim().split("\\s+");
                    for (int k = 0; k + 1 < f.length; k++) {
                        if (f[k].equals("lladdr")) {
                            String mac = f[k + 1].toLowerCase(Locale.US);
                            List<String> l = byMac.get(mac);
                            if (l == null) { l = new ArrayList<>(); byMac.put(mac, l); }
                            l.add(f[0].toLowerCase(Locale.US) + (line.contains(" router ") ? " (router)" : ""));
                            found.remove(f[0].toLowerCase(Locale.US));
                        }
                    }
                }
            }
            if (router != null) found.computeIfPresent(router.toLowerCase(Locale.US), (k, v) -> "router");
            int count = byMac.size() + found.size();
            if (!pingOk && !tableOk) {
                neighLine = "Not available on this device";
                a.say("  " + neighLine, 0);
                a.hint("Android doesn't let apps see this here.");
            } else {
                neighLine = count + " device(s) answered over IPv6";
                a.say("  " + neighLine + ":", 0);
                for (Map.Entry<String, List<String>> e : byMac.entrySet()) {
                    a.say("    " + e.getKey() + "  " + String.join("  ", e.getValue()), 0);
                    for (String ad : e.getValue()) if (ad.startsWith("2") || ad.startsWith("3")) globals.add(ad.split(" ")[0]);
                }
                for (Map.Entry<String, String> e : found.entrySet()) {
                    a.say("    " + e.getKey() + (e.getValue().isEmpty() ? "" : "  (" + e.getValue() + ")"), 0);
                    try {
                        if (kind(InetAddress.getByName(e.getKey())).equals("global")) globals.add(e.getKey());
                    } catch (Exception ignored) { }
                }
                if (!tableOk) a.hint("MAC details not available on this device (Android 10+).");
                if (!globals.isEmpty()) {
                    a.say("", 0);
                    a.warn("Devices with their own INTERNET IPv6 address:");
                    for (String g : globals) {
                        a.say("    IPv6: " + g, 0);
                        a.hint("  global - make sure the router firewall blocks inbound IPv6");
                    }
                }
            }
        }

        // ---- The report
        a.say("", 0);
        a.say("IPV6", MainActivity.BLUE);
        rep("Status", st, 0);
        rep("Public IPv6", pub + (pubNote.isEmpty() ? "" : "  (" + pubNote + ")"), 0);
        rep("Internet", netLine, broken ? MainActivity.RED : 0);
        rep("DNS filter", dnsLine, dnsWarn ? MainActivity.ORANGE : 0);
        rep("AAAA lookups", aaaa, 0);
        rep("Router", rtrLine, 0);
        if (v6s.length() > 0) rep("IPv6 DNS", v6s.toString().trim(), 0);
        if (neighLine != null) rep("Neighbors", neighLine, 0);
        if (broken) {
            a.say("  > IPv6 is turned on but doesn't reach the internet.", MainActivity.RED);
            a.say("    Devices may be slow or flaky while they fall back", MainActivity.RED);
            a.say("    to IPv4. Fix IPv6 on the router or turn it off.", MainActivity.RED);
        }
        if (dnsWarn) {
            a.say("  > IPv6 is on and this device isn't always using", MainActivity.ORANGE);
            a.say("    OpenDNS. Filtering may be bypassed. Set OpenDNS", MainActivity.ORANGE);
            a.say("    IPv6 servers on the router:", MainActivity.ORANGE);
            a.say("    " + OPENDNS_V6, MainActivity.ORANGE);
        }
        if (v6on && !broken) {
            a.say("  > IPv6 is working. Devices get their own public IPv6", 0);
            a.say("    addresses, so the router's IPv6 firewall matters -", 0);
            a.say("    inbound IPv6 should be blocked.", 0);
        } else if (!v6on) {
            a.say("  > IPv6 is off on this network. Nothing to fix for IPv6.", 0);
        }

        String summ;
        int color = 0;
        if (v6on) {
            if (broken) {
                summ = "ON · BROKEN (no internet over IPv6)";
                color = MainActivity.RED;
            } else {
                summ = "ON · works (" + ms + " ms) · public " + short6(pub);
                if (dnsWarn) { summ += " · DNS filter BYPASSED?"; color = MainActivity.ORANGE; }
                else if (tot > 0 && w == tot) summ += " · DNS filter OK";
                else if (tot > 0 && o == tot) summ += " · not OpenDNS";
            }
        } else {
            summ = st;
        }
        summ = "IPv6 (" + p.kind + "): " + summ;
        rep("Summary", summ, color);
        a.setV6Line(summ, color);
        a.hint("Share log saves this report.");
    }

}
