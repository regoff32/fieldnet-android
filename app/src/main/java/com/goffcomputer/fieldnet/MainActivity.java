package com.goffcomputer.fieldnet;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.io.RandomAccessFile;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FieldNet for Android - on-site network helper for Robert's Galaxy S22
 * with a USB-C Ethernet adapter.
 *
 * The app can't change the phone's IP (Android only lets system apps do
 * that). The IP is set in Settings > Connections > More connection
 * settings > Ethernet. This app forces its own traffic onto the Ethernet
 * adapter, checks the link, pings, finds devices, checks ports, opens a
 * device's web page, and nags while the phone is left on a static IP.
 */
public class MainActivity extends Activity {

    static final String VERSION = "1.2";
    static final String CHANNEL = "static_ip";
    static final int NOTIF_ID = 1;

    static final int GREEN = Color.rgb(46, 158, 68);
    static final int RED = Color.rgb(211, 47, 47);
    static final int ORANGE = Color.rgb(224, 138, 0);
    static final int BLUE = Color.rgb(63, 127, 208);
    static final int GRAY = Color.rgb(128, 128, 128);

    static final int[] PORTS = {21, 22, 23, 53, 80, 81, 443, 445, 554, 631,
            3389, 5000, 8000, 8080, 8081, 8291, 8443, 8728, 9100, 37777};
    static final String[] PORT_NAMES = {"FTP", "SSH", "Telnet", "DNS", "Web (http)",
            "Web (alt)", "Web (https)", "Windows file sharing", "Camera video (RTSP)",
            "Printer (IPP)", "Remote Desktop", "NAS / UPnP", "Web (alt)", "Web (alt)",
            "Web (alt)", "MikroTik Winbox", "Web (https alt)", "MikroTik API",
            "Printer (raw)", "Dahua DVR"};
    static final int[] SCAN_PORTS = {80, 443, 22, 23, 8080, 554};

    ConnectivityManager cm;
    ConnectivityManager.NetworkCallback cb;
    volatile Network eth;
    volatile boolean bound;

    final ExecutorService worker = Executors.newSingleThreadExecutor();
    volatile boolean stop;
    volatile boolean busy;
    final Handler ui = new Handler(Looper.getMainLooper());

    TextView out, banner, ethLine;
    EditText devIp;
    ScrollView outScroll;
    SharedPreferences prefs;
    File logFile;

    // ================================================================ setup
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("fieldnet", MODE_PRIVATE);
        File dir = getExternalFilesDir(null);
        logFile = new File(dir != null ? dir : getFilesDir(), "fieldnet-log.txt");
        cm = getSystemService(ConnectivityManager.class);

        buildUi();
        makeChannel();
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }

        log("=== FieldNet " + VERSION + " started ===");
        say("FieldNet " + VERSION + " for Android", BLUE);
        say("Set the phone's IP in Ethernet settings (button below).", 0);
        say("This app keeps its own traffic on the Ethernet adapter.", 0);
        say("", 0);

        watchEthernet();
        refreshHeader();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(rebindLoop);
        ui.post(rebindLoop);
        refreshHeader();   // user may be coming back from Ethernet settings
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(rebindLoop);
        super.onPause();
    }

    /** Every 3 seconds, if Ethernet is up but the app isn't locked onto it
     *  (usually a VPN in the way), try again. Turning the VPN off is then
     *  enough; no need to close the app or replug the cable. */
    final Runnable rebindLoop = new Runnable() {
        @Override
        public void run() {
            ensureBound();
            ui.postDelayed(this, 3000);
        }
    };

    /** Try to lock this app's traffic onto Ethernet. Returns true if locked. */
    boolean ensureBound() {
        Network n = eth;
        if (n == null) return false;
        if (bound && n.equals(cm.getBoundNetworkForProcess())) return true;
        boolean was = bound;
        bound = cm.bindProcessToNetwork(n);
        if (bound != was) {
            log("App traffic bound to Ethernet: " + bound);
            refreshHeader();
        }
        return bound;
    }

    @Override
    protected void onDestroy() {
        stop = true;
        try { if (cb != null) cm.unregisterNetworkCallback(cb); } catch (Exception ignored) { }
        try { cm.bindProcessToNetwork(null); } catch (Exception ignored) { }
        worker.shutdownNow();
        super.onDestroy();
    }

    int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(8));

        TextView title = new TextView(this);
        title.setText("FieldNet " + VERSION);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        ethLine = new TextView(this);
        ethLine.setTextSize(14);
        ethLine.setPadding(0, dp(2), 0, dp(4));
        root.addView(ethLine);

        banner = new TextView(this);
        banner.setTextSize(14);
        banner.setTypeface(Typeface.DEFAULT_BOLD);
        banner.setTextColor(Color.WHITE);
        banner.setPadding(dp(10), dp(8), dp(10), dp(8));
        banner.setVisibility(View.GONE);
        banner.setOnClickListener(v -> {
            if (eth != null && !bound && vpnOn()) openVpnSettings();
            else openEthernetSettings();
        });
        root.addView(banner);

        LinearLayout ipRow = new LinearLayout(this);
        ipRow.setOrientation(LinearLayout.HORIZONTAL);
        ipRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView ipLbl = new TextView(this);
        ipLbl.setText("Device IP  ");
        ipLbl.setTextSize(15);
        ipRow.addView(ipLbl);
        devIp = new EditText(this);
        devIp.setSingleLine(true);
        devIp.setHint("192.168.1.20");
        devIp.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        devIp.setText(prefs.getString("lastIp", ""));
        ipRow.addView(devIp, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(ipRow);

        addRow(root, btn("Status", v -> start(this::doStatus)),
                btn("Ping x4", v -> withIp(ip -> start(() -> doPing(ip, false)))));
        addRow(root, btn("Ping nonstop", v -> withIp(ip -> start(() -> doPing(ip, true)))),
                btn("STOP", v -> { stop = true; toast("Stopping..."); }));
        addRow(root, btn("Find devices", v -> start(this::doScan)),
                btn("Check ports", v -> withIp(ip -> start(() -> doPorts(ip)))));
        addRow(root, btn("Open web page", v -> withIp(this::askWebPage)),
                btn("Ethernet settings", v -> openEthernetSettings()));
        addRow(root, btn("Listen for radios", v -> start(this::doListen)),
                btn("Common defaults", v -> start(this::doDefaults)));
        addRow(root, btn("Share log", v -> shareLog()),
                btn("Clear screen", v -> out.setText("", TextView.BufferType.EDITABLE)));

        outScroll = new ScrollView(this);
        out = new TextView(this);
        out.setTypeface(Typeface.MONOSPACE);
        out.setTextSize(12.5f);
        out.setTextIsSelectable(true);
        out.setPadding(0, dp(6), 0, dp(6));
        out.setText("", TextView.BufferType.EDITABLE);
        outScroll.addView(out);
        root.addView(outScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    Button btn(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    void addRow(LinearLayout root, Button a, Button c) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(a, lp);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(c, lp2);
        root.addView(row);
    }

    // ================================================================ output
    void say(String s, int color) {
        log(s);
        ui.post(() -> {
            SpannableString sp = new SpannableString(s + "\n");
            if (color != 0) {
                sp.setSpan(new ForegroundColorSpan(color), 0, sp.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            out.append(sp);
            CharSequence t = out.getText();
            if (t.length() > 60000) {
                out.setText(t.subSequence(t.length() - 40000, t.length()),
                        TextView.BufferType.EDITABLE);
            }
            outScroll.post(() -> outScroll.scrollTo(0, out.getHeight()));
        });
    }

    void ok(String s) { say("  OK  " + s, GREEN); }
    void err(String s) { say("  !!  " + s, RED); }
    void warn(String s) { say("  ->  " + s, ORANGE); }
    void hint(String s) { say("      " + s, GRAY); }
    void hdr(String s) { say("", 0); say("== " + s + " ==", BLUE); }

    void toast(String s) { ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show()); }

    synchronized void log(String s) {
        try (FileWriter w = new FileWriter(logFile, true)) {
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            w.write(ts + "  " + s + "\n");
        } catch (Exception ignored) { }
    }

    // ============================================================ task runner
    void start(Runnable r) {
        ensureBound();
        if (busy) {
            toast("Still working. Tap STOP first.");
            return;
        }
        stop = false;
        busy = true;
        worker.submit(() -> {
            try {
                r.run();
            } catch (Exception e) {
                err("Error: " + e);
            } finally {
                busy = false;
            }
        });
    }

    interface IpAction { void run(String ip); }

    /** Read and check the Device IP box, then run the action. */
    void withIp(IpAction a) {
        String ip = devIp.getText().toString().trim();
        if (!validIp(ip)) {
            toast("Type a device IP first, like 192.168.1.20");
            devIp.requestFocus();
            return;
        }
        ip = normIp(ip);
        devIp.setText(ip);
        prefs.edit().putString("lastIp", ip).apply();
        a.run(ip);
    }

    static boolean validIp(String s) {
        if (!s.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return false;
        for (String p : s.split("\\.")) if (Integer.parseInt(p) > 255) return false;
        return true;
    }

    static String normIp(String s) {
        String[] p = s.split("\\.");
        return Integer.parseInt(p[0]) + "." + Integer.parseInt(p[1]) + "."
                + Integer.parseInt(p[2]) + "." + Integer.parseInt(p[3]);
    }

    static int ipToInt(String s) {
        String[] p = s.split("\\.");
        return (Integer.parseInt(p[0]) << 24) | (Integer.parseInt(p[1]) << 16)
                | (Integer.parseInt(p[2]) << 8) | Integer.parseInt(p[3]);
    }

    static String intToIp(int v) {
        return ((v >>> 24) & 255) + "." + ((v >>> 16) & 255) + "."
                + ((v >>> 8) & 255) + "." + (v & 255);
    }

    // ===================================================== Ethernet tracking
    void watchEthernet() {
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        cb = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network n) {
                eth = n;
                bound = false;
                log("Ethernet connected");
                ensureBound();
                refreshHeader();
            }

            @Override
            public void onLinkPropertiesChanged(Network n, LinkProperties lp) {
                if (n.equals(eth)) refreshHeader();
            }

            @Override
            public void onLost(Network n) {
                if (n.equals(eth)) {
                    eth = null;
                    bound = false;
                    cm.bindProcessToNetwork(null);
                    log("Ethernet disconnected");
                    refreshHeader();
                }
            }
        };
        try {
            cm.registerNetworkCallback(req, cb, ui);
        } catch (Exception e) {
            err("Couldn't watch the Ethernet adapter: " + e);
        }
    }

    static class EthInfo {
        String iface, cidr, ip, gateway;
        int prefix;
        Inet4Address dhcpServer;
        List<String> dns = new ArrayList<>();
        boolean validated;
    }

    EthInfo ethInfo() {
        Network n = eth;
        if (n == null) return null;
        LinkProperties lp = cm.getLinkProperties(n);
        if (lp == null) return null;
        EthInfo e = new EthInfo();
        e.iface = lp.getInterfaceName();
        for (LinkAddress la : lp.getLinkAddresses()) {
            if (la.getAddress() instanceof Inet4Address) {
                e.ip = la.getAddress().getHostAddress();
                e.prefix = la.getPrefixLength();
                e.cidr = e.ip + "/" + e.prefix;
                break;
            }
        }
        for (RouteInfo r : lp.getRoutes()) {
            if (r.getDestination() != null && r.getDestination().getPrefixLength() == 0
                    && r.getGateway() instanceof Inet4Address
                    && !r.getGateway().isAnyLocalAddress()) {
                e.gateway = r.getGateway().getHostAddress();
            }
        }
        for (InetAddress d : lp.getDnsServers()) e.dns.add(d.getHostAddress());
        e.dhcpServer = lp.getDhcpServerAddress();
        NetworkCapabilities nc = cm.getNetworkCapabilities(n);
        e.validated = nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        return e;
    }

    /** Is this an IP the phone's Ethernet looks like it got by typing it in? */
    static boolean looksStatic(EthInfo e) {
        return e != null && e.ip != null && e.dhcpServer == null;
    }

    void refreshHeader() {
        ui.post(() -> {
            EthInfo e = ethInfo();
            if (e == null) {
                ethLine.setText("Ethernet: not connected (plug in adapter and cable)");
                if (prefs.getBoolean("wasStatic", false)) {
                    showBanner("Last time, the phone's Ethernet was on STATIC IP. "
                            + "Plug in and make sure it's back on DHCP. Tap here for Ethernet settings.",
                            ORANGE);
                } else {
                    banner.setVisibility(View.GONE);
                }
                return;
            }
            String mode = e.ip == null ? "waiting for an IP"
                    : (e.dhcpServer != null ? "DHCP" : "STATIC");
            ethLine.setText("Ethernet: " + (e.ip != null ? e.cidr : "no IP yet") + "   (" + mode + ")"
                    + (bound ? "" : "   [NOT locked onto Ethernet]"));
            boolean isStatic = looksStatic(e);
            if (isStatic) {
                prefs.edit().putBoolean("wasStatic", true).apply();
                staticNotification(true, e.cidr);
            } else if (e.dhcpServer != null) {
                prefs.edit().putBoolean("wasStatic", false).apply();
                staticNotification(false, null);
            }
            if (!bound) {
                String msg = vpnOn()
                        ? "A VPN IS BLOCKING FIELDNET. Pause or turn off the VPN (tap here). "
                          + "FieldNet locks onto Ethernet by itself within a few seconds."
                        : "FIELDNET COULDN'T LOCK ONTO ETHERNET. Turn on Airplane mode "
                          + "(Ethernet keeps working). It retries by itself.";
                if (isStatic) msg += "\nAlso: phone Ethernet is on STATIC IP (" + e.cidr
                        + "). Switch back to DHCP when done.";
                showBanner(msg, isStatic ? RED : ORANGE);
            } else if (isStatic) {
                showBanner("PHONE ETHERNET IS ON STATIC IP (" + e.cidr + "). "
                        + "Switch it back to DHCP when the job is done. Tap here for Ethernet settings.",
                        RED);
            } else {
                banner.setVisibility(View.GONE);
            }
        });
    }

    void showBanner(String text, int color) {
        banner.setText(text);
        banner.setBackgroundColor(color);
        banner.setVisibility(View.VISIBLE);
    }

    void makeChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL,
                "Static IP reminder", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("Reminds you when the phone's Ethernet is left on a static IP");
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    void staticNotification(boolean show, String cidr) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (!show) {
            nm.cancel(NOTIF_ID);
            return;
        }
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Phone Ethernet is on Static IP")
                .setContentText(cidr + " - switch back to DHCP when done")
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
        try {
            nm.notify(NOTIF_ID, n);
        } catch (Exception ignored) { }   // notifications turned off
    }

    void openVpnSettings() {
        toast("Pause or turn off the VPN, then come back");
        try {
            startActivity(new Intent(Settings.ACTION_VPN_SETTINGS));
        } catch (Exception e) {
            openEthernetSettings();
        }
    }

    /** Try to open Samsung's Ethernet page directly. Android has no official
     *  shortcut for it, so try the known ones and fall back to Connections. */
    void openEthernetSettings() {
        Intent[] tries = {
                new Intent("android.settings.ETHERNET_SETTINGS"),
                new Intent().setClassName("com.android.settings",
                        "com.android.settings.Settings$EthernetSettingsActivity"),
                new Intent().setClassName("com.android.settings",
                        "com.samsung.android.settings.ethernet.EthernetSettingsActivity"),
                new Intent().setClassName("com.android.settings",
                        "com.android.settings.Settings$MoreConnectionsSettingsActivity"),
        };
        for (int k = 0; k < tries.length; k++) {
            try {
                startActivity(tries[k]);
                log("Opened settings with shortcut " + (k + 1));
                if (k == 3) toast("Tap Ethernet");
                return;
            } catch (Exception ignored) { }
        }
        toast("Go to: More connection settings > Ethernet");
        try {
            startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    // ================================================================ checks
    boolean needEth() {
        if (eth == null) {
            err("Ethernet is not connected.");
            hint("Check the adapter is plugged in, the cable is in, and the other end");
            hint("has power (PoE injector LAN port, switch, router or device).");
            return false;
        }
        return true;
    }

    void subnetWarning(String ip) {
        EthInfo e = ethInfo();
        if (e == null || e.ip == null) return;
        int mask = e.prefix == 0 ? 0 : (int) (0xFFFFFFFFL << (32 - e.prefix));
        if ((ipToInt(ip) & mask) != (ipToInt(e.ip) & mask)) {
            warn(ip + " is NOT on the phone's subnet (" + e.cidr + ").");
            hint("Set a static IP on the device's subnet in Ethernet settings,");
            hint("for example " + suggestIp(ip) + " with prefix 24.");
        }
    }

    static String suggestIp(String devIp) {
        String[] p = devIp.split("\\.");
        int last = Integer.parseInt(p[3]) == 250 ? 249 : 250;
        return p[0] + "." + p[1] + "." + p[2] + "." + last;
    }

    boolean vpnOn() {
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
        }
        return false;
    }

    /** New TCP socket, tied to the Ethernet adapter when possible. */
    Socket ethSocket() {
        Socket s = new Socket();
        Network n = eth;
        if (n != null) {
            try { n.bindSocket(s); } catch (Exception ignored) { }
        }
        return s;
    }

    /** 1 = open, 0 = closed (device answered "no"), -1 = no answer. */
    int tcpCheck(String ip, int port, int timeoutMs) {
        try (Socket s = ethSocket()) {
            s.connect(new InetSocketAddress(ip, port), timeoutMs);
            return 1;
        } catch (ConnectException e) {
            String m = String.valueOf(e.getMessage());
            return m.contains("ECONNREFUSED") || m.toLowerCase(Locale.US).contains("refused") ? 0 : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    // ================================================================ actions
    void doStatus() {
        hdr("Status");
        ensureBound();
        EthInfo e = ethInfo();
        if (e == null) {
            needEth();
        } else {
            ok("Ethernet connected" + (e.iface != null ? " (" + e.iface + ")" : ""));
            if (e.ip == null) {
                err("No IP address yet.");
                hint("On DHCP: the other end may not hand out addresses. Set a static IP.");
            } else {
                say("  IP address ...... " + e.cidr, 0);
                if (e.dhcpServer != null) {
                    say("  Mode ............ DHCP (server " + e.dhcpServer.getHostAddress() + ")", 0);
                } else {
                    say("  Mode ............ STATIC (no DHCP server)", RED);
                }
                say("  Gateway ......... " + (e.gateway != null ? e.gateway : "none"), 0);
                say("  DNS ............. " + (e.dns.isEmpty() ? "none" : String.join(", ", e.dns)), 0);
                say("  Internet on it .. " + (e.validated ? "yes" : "no (normal for a direct hookup)"), 0);
                if (e.ip.startsWith("169.254.")) {
                    warn("169.254.x.x means DHCP failed. Nothing gave the phone an address.");
                }
            }
            if (bound) ok("This app's traffic is forced onto Ethernet.");
            else if (vpnOn()) err("A VPN is blocking FieldNet. Pause the VPN; it retries by itself.");
            else err("Couldn't force this app onto Ethernet. Try Airplane mode.");
        }
        say("", 0);
        say("  Other networks:", 0);
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            LinkProperties lp = cm.getLinkProperties(n);
            if (nc == null) continue;
            String kind = nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "Ethernet"
                    : nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi"
                    : nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "Cellular"
                    : nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? "VPN" : "Other";
            if (kind.equals("Ethernet")) continue;
            say("    " + kind + (lp != null && lp.getInterfaceName() != null
                    ? " (" + lp.getInterfaceName() + ")" : ""), 0);
        }
        if (vpnOn()) {
            warn("A VPN is on. If devices don't answer, turn on \"Allow LAN");
            hint("connections\" in the VPN app, or pause the VPN for the job.");
        }
    }

    void doPing(String ip, boolean nonstop) {
        hdr("Ping " + ip + (nonstop ? " (nonstop - tap STOP to end)" : ""));
        if (!needEth()) hint("Trying anyway over the phone's other connection...");
        subnetWarning(ip);
        int sent = 0, got = 0;
        long best = Long.MAX_VALUE, worst = 0;
        try {
            InetAddress a = InetAddress.getByName(ip);
            while (!stop && (nonstop || sent < 4)) {
                sent++;
                long t0 = System.nanoTime();
                boolean r = a.isReachable(1000);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                if (r) {
                    got++;
                    best = Math.min(best, ms);
                    worst = Math.max(worst, ms);
                    say("  Reply from " + ip + "   time=" + ms + " ms", GREEN);
                } else {
                    say("  No reply (" + sent + ")", RED);
                }
                long wait = 1000 - ms;
                if (wait > 0 && !stop) Thread.sleep(wait);
            }
        } catch (InterruptedException ie) {
            return;
        } catch (Exception e) {
            err("Ping failed: " + e.getMessage());
            return;
        }
        int lost = sent == 0 ? 0 : (sent - got) * 100 / sent;
        say("  Sent " + sent + ", replies " + got + ", lost " + lost + "%"
                + (got > 0 ? ", fastest " + best + " ms, slowest " + worst + " ms" : ""), 0);
        if (got == sent && sent > 0) ok(ip + " answers.");
        else if (got > 0) warn("Some replies lost. Check the cable, the connectors, or signal.");
        else {
            err("No answer from " + ip + ".");
            hint("Some devices block ping. Try Check ports before giving up.");
            hint("Make sure you're in the PoE injector's LAN port, not the PoE port.");
        }
    }

    void doScan() {
        hdr("Find devices");
        if (!needEth()) return;
        EthInfo e = ethInfo();
        if (e == null || e.ip == null) {
            err("The phone has no IP on Ethernet yet.");
            hint("Set a static IP in Ethernet settings, or wait for DHCP.");
            return;
        }
        int prefix = e.prefix;
        if (prefix < 24) {
            warn("Subnet /" + prefix + " is big. Only scanning the /24 around the phone.");
            prefix = 24;
        }
        int mask = (int) (0xFFFFFFFFL << (32 - prefix));
        int me = ipToInt(e.ip);
        int net = me & mask;
        int count = (int) ((1L << (32 - prefix)));
        say("  Scanning " + intToIp(net) + "/" + prefix + " (about " + Math.max(count - 2, 1)
                + " addresses, 10-30 seconds)...", 0);

        ConcurrentSkipListMap<Long, String> found = new ConcurrentSkipListMap<>();
        AtomicInteger done = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(48);
        List<Future<?>> jobs = new ArrayList<>();
        for (int k = 1; k < count - 1 || (count <= 2 && k < count); k++) {
            int host = net + k;
            if (host == me) continue;
            String ip = intToIp(host);
            jobs.add(pool.submit(() -> {
                if (stop) return;
                String how = probe(ip);
                if (how != null) {
                    found.put(ipToInt(ip) & 0xFFFFFFFFL, how);
                    say("  found " + ip + "   (" + how + ")", GREEN);
                }
                int d = done.incrementAndGet();
                if (d % 64 == 0) say("  ..." + d + " checked", GRAY);
            }));
        }
        pool.shutdown();
        try {
            pool.awaitTermination(5, TimeUnit.MINUTES);
        } catch (InterruptedException ie) {
            pool.shutdownNow();
            return;
        }
        if (stop) warn("Stopped early.");
        say("", 0);
        if (found.isEmpty()) {
            err("Nothing answered on " + intToIp(net) + "/" + prefix + ".");
            hint("The device may be on a different subnet. Common defaults:");
            hint("192.168.1.x, 192.168.0.x, 192.168.88.1 (MikroTik), 10.0.0.x.");
            hint("Set a static IP on that subnet and scan again.");
        } else {
            ok(found.size() + " device(s) found:");
            for (Map.Entry<Long, String> en : found.entrySet()) {
                String ip = intToIp((int) (long) en.getKey());
                say("    " + ip + (ip.equals(e.gateway) ? "   <- gateway" : "")
                        + "   " + en.getValue(), 0);
            }
            hint("Copy one into Device IP, then Check ports or Open web page.");
        }
        say("  (Your phone is " + e.ip + ", not listed.)", GRAY);
    }

    /** Returns how the host answered, or null. */
    String probe(String ip) {
        try {
            if (InetAddress.getByName(ip).isReachable(600)) return "answers ping";
        } catch (Exception ignored) { }
        for (int p : SCAN_PORTS) {
            if (stop) return null;
            int r = tcpCheck(ip, p, 300);
            if (r == 1) return "port " + p + " open";
            if (r == 0) return "answered on port " + p;
        }
        return null;
    }

    void doPorts(String ip) {
        hdr("Check ports on " + ip);
        if (!needEth()) hint("Trying anyway over the phone's other connection...");
        subnetWarning(ip);
        say("  Checking " + PORTS.length + " common ports...", 0);
        int[] res = new int[PORTS.length];
        ExecutorService pool = Executors.newFixedThreadPool(10);
        for (int k = 0; k < PORTS.length; k++) {
            final int idx = k;
            pool.submit(() -> { res[idx] = stop ? -1 : tcpCheck(ip, PORTS[idx], 1000); });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(2, TimeUnit.MINUTES);
        } catch (InterruptedException ie) {
            pool.shutdownNow();
            return;
        }
        int open = 0, closed = 0, silent = 0;
        boolean web = false;
        for (int k = 0; k < PORTS.length; k++) {
            if (res[k] == 1) {
                open++;
                say(String.format(Locale.US, "  OPEN   %-5d %s", PORTS[k], PORT_NAMES[k]), GREEN);
                if (PORT_NAMES[k].startsWith("Web")) web = true;
            } else if (res[k] == 0) closed++;
            else silent++;
        }
        say("  Closed: " + closed + "   No answer: " + silent, 0);
        if (open > 0) {
            ok(ip + " is there.");
            if (web) hint("It has a web page. Tap Open web page.");
        } else if (closed > 0) {
            warn(ip + " is there, but none of these ports are open.");
        } else {
            err("No answer on any port. The device is off, on another subnet,");
            hint("or blocking everything. Try Find devices.");
        }
    }

    // ======================================================= v1.2: Listen
    /** One thing heard on the cable. */
    static class Heard {
        final String ip, what;
        final int rank;   // higher = more useful description
        Heard(String ip, String what, int rank) { this.ip = ip; this.what = what; this.rank = rank; }
    }

    final java.util.concurrent.ConcurrentHashMap<String, Heard> heard =
            new java.util.concurrent.ConcurrentHashMap<>();
    volatile String firstRadioIp;

    void record(String ip, String what, int rank) {
        if (ip == null || ip.equals("0.0.0.0")) return;
        Heard old = heard.get(ip);
        if (old != null && old.rank >= rank) return;
        heard.put(ip, new Heard(ip, what, rank));
        say("  heard " + ip + "   " + what, GREEN);
        if (rank >= 2 && firstRadioIp == null) {
            firstRadioIp = ip;
            ui.post(() -> devIp.setText(ip));
        }
    }

    static String u16str(byte[] b, int off, int len) {
        return new String(b, off, len, java.nio.charset.StandardCharsets.UTF_8).trim();
    }

    static String ip4(byte[] b, int off) {
        return (b[off] & 255) + "." + (b[off + 1] & 255) + "." + (b[off + 2] & 255) + "." + (b[off + 3] & 255);
    }

    /** MikroTik Neighbor Discovery packet (UDP 5678). */
    void parseMndp(byte[] b, int len, String src) {
        if (len <= 4) return;                       // our own 4-byte refresh request
        String ident = null, board = null, ver = null, ip = null;
        int i = 4;
        while (i + 4 <= len) {
            int t = ((b[i] & 255) << 8) | (b[i + 1] & 255);
            int l = ((b[i + 2] & 255) << 8) | (b[i + 3] & 255);
            i += 4;
            if (l < 0 || i + l > len) break;
            if (t == 5) ident = u16str(b, i, l);
            else if (t == 7) ver = u16str(b, i, l);
            else if (t == 12) board = u16str(b, i, l);
            else if (t == 17 && l == 4) ip = ip4(b, i);
            i += l;
        }
        StringBuilder d = new StringBuilder("MikroTik");
        if (board != null && !board.isEmpty()) d.append(" ").append(board);
        if (ident != null && !ident.isEmpty()) d.append("  name: ").append(ident);
        if (ver != null && !ver.isEmpty()) d.append("  RouterOS ").append(ver.split(" ")[0]);
        record(ip != null ? ip : src, d.toString(), 3);
        if (ip != null && !ip.equals(src) && !src.equals("0.0.0.0")) record(src, "MikroTik (sent from this IP)", 2);
    }

    /** Ubiquiti discovery reply (UDP 10001). */
    void parseUbnt(byte[] b, int len, String src) {
        if (len <= 4 || b[0] != 1) return;          // our own 4-byte request
        String host = null, model = null, fw = null, ssid = null;
        List<String> ips = new ArrayList<>();
        int i = 4;
        while (i + 3 <= len) {
            int t = b[i] & 255;
            int l = ((b[i + 1] & 255) << 8) | (b[i + 2] & 255);
            i += 3;
            if (i + l > len) break;
            if (t == 0x02 && l == 10) ips.add(ip4(b, i + 6));
            else if (t == 0x0B) host = u16str(b, i, l);
            else if (t == 0x14 || (t == 0x0C && model == null)) model = u16str(b, i, l);
            else if (t == 0x03) fw = u16str(b, i, l);
            else if (t == 0x0D) ssid = u16str(b, i, l);
            i += l;
        }
        StringBuilder d = new StringBuilder("Ubiquiti");
        if (model != null && !model.isEmpty()) d.append(" ").append(model);
        if (host != null && !host.isEmpty()) d.append("  name: ").append(host);
        if (ssid != null && !ssid.isEmpty()) d.append("  SSID: ").append(ssid);
        if (fw != null && !fw.isEmpty()) d.append("  fw ").append(fw);
        if (ips.isEmpty()) ips.add(src);
        for (String ip : ips) record(ip, d.toString(), 3);
    }

    /** SSDP / UPnP announcement (UDP 1900): routers, cameras, NVRs, printers. */
    void parseSsdp(byte[] b, int len, String src) {
        String t = new String(b, 0, len, java.nio.charset.StandardCharsets.ISO_8859_1);
        if (t.startsWith("M-SEARCH")) {
            record(src, "something searching for UPnP devices", 1);
            return;
        }
        String server = null;
        for (String line : t.split("\r?\n")) {
            String lo = line.toLowerCase(Locale.US);
            if (lo.startsWith("server:")) server = line.substring(7).trim();
        }
        record(src, "UPnP device" + (server != null && !server.isEmpty() ? ": " + server : ""), 2);
    }

    interface PacketHandler { void handle(byte[] b, int len, String src); }

    /** Receive on a socket until the deadline or STOP. */
    Thread receiver(java.net.DatagramSocket s, long deadline, PacketHandler h) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[2048];
            String me = myEthIp();
            while (!stop && System.currentTimeMillis() < deadline) {
                java.net.DatagramPacket p = new java.net.DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (java.net.SocketTimeoutException te) {
                    continue;
                } catch (Exception e) {
                    break;
                }
                String src = p.getAddress() instanceof Inet4Address
                        ? p.getAddress().getHostAddress() : "0.0.0.0";
                if (src.equals(me)) continue;       // our own broadcast echoing back
                try { h.handle(p.getData(), p.getLength(), src); } catch (Exception ignored) { }
            }
        });
        t.start();
        return t;
    }

    String myEthIp() {
        EthInfo e = ethInfo();
        return e != null && e.ip != null ? e.ip : "";
    }

    java.net.DatagramSocket udp(int port) throws Exception {
        java.net.DatagramSocket s = new java.net.DatagramSocket(null);
        s.setReuseAddress(true);
        s.setBroadcast(true);
        s.bind(new InetSocketAddress(port));
        Network n = eth;
        if (n != null) {
            try { n.bindSocket(s); } catch (Exception ignored) { }
        }
        s.setSoTimeout(500);
        return s;
    }

    void send(java.net.DatagramSocket s, byte[] data, String ip, int port) {
        try {
            s.send(new java.net.DatagramPacket(data, data.length, InetAddress.getByName(ip), port));
        } catch (Exception ignored) { }
    }

    void doListen() {
        hdr("Listen for radios (60 seconds - tap STOP to end early)");
        if (!needEth()) return;
        say("  Listening for MikroTik and Ubiquiti radios, and UPnP devices", 0);
        say("  (routers, cameras, NVRs). Tip: power-cycle the radio now;", 0);
        say("  they announce themselves while booting.", 0);
        heard.clear();
        firstRadioIp = null;
        long deadline = System.currentTimeMillis() + 60_000;
        List<java.net.DatagramSocket> socks = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        java.net.DatagramSocket mndp = null, ubntListen = null, ubntAsk = null;
        java.net.MulticastSocket ssdp = null;
        try {
            try {
                mndp = udp(5678);
                socks.add(mndp);
                threads.add(receiver(mndp, deadline, this::parseMndp));
            } catch (Exception e) { warn("Couldn't listen for MikroTik: " + e.getMessage()); }
            try {
                ubntListen = udp(10001);
                socks.add(ubntListen);
                threads.add(receiver(ubntListen, deadline, this::parseUbnt));
            } catch (Exception e) { hint("(Ubiquiti announcements port busy; asking only.)"); }
            try {
                ubntAsk = udp(0);
                socks.add(ubntAsk);
                threads.add(receiver(ubntAsk, deadline, this::parseUbnt));
            } catch (Exception e) { warn("Couldn't ask Ubiquiti radios: " + e.getMessage()); }
            try {
                ssdp = new java.net.MulticastSocket(null);
                ssdp.setReuseAddress(true);
                ssdp.bind(new InetSocketAddress(1900));
                EthInfo e = ethInfo();
                java.net.NetworkInterface ni = e != null && e.iface != null
                        ? java.net.NetworkInterface.getByName(e.iface) : null;
                InetSocketAddress grp = new InetSocketAddress(InetAddress.getByName("239.255.255.250"), 1900);
                if (ni != null) ssdp.joinGroup(grp, ni);
                else ssdp.joinGroup(InetAddress.getByName("239.255.255.250"));
                ssdp.setSoTimeout(500);
                socks.add(ssdp);
                threads.add(receiver(ssdp, deadline, this::parseSsdp));
            } catch (Exception e) { hint("(UPnP listening not available here.)"); }

            byte[] mndpAsk = {0, 0, 0, 0};
            byte[] ubntV1 = {1, 0, 0, 0};
            byte[] ubntV2 = {2, 8, 0, 0};
            byte[] msearch = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n"
                    + "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            int round = 0;
            while (!stop && System.currentTimeMillis() < deadline) {
                if (mndp != null) send(mndp, mndpAsk, "255.255.255.255", 5678);
                if (ubntAsk != null) {
                    send(ubntAsk, ubntV1, "255.255.255.255", 10001);
                    send(ubntAsk, ubntV2, "255.255.255.255", 10001);
                }
                if (ssdp != null && round % 3 == 0) send(ssdp, msearch, "239.255.255.250", 1900);
                round++;
                for (int k = 0; k < 20 && !stop && System.currentTimeMillis() < deadline; k++) {
                    Thread.sleep(250);
                }
                if (round % 4 == 0) {
                    long left = (deadline - System.currentTimeMillis()) / 1000;
                    if (left > 0) say("  ..." + left + " seconds left, " + heard.size() + " heard", GRAY);
                }
            }
        } catch (InterruptedException ie) {
            // stopping
        } finally {
            for (java.net.DatagramSocket s : socks) {
                try { s.close(); } catch (Exception ignored) { }
            }
            for (Thread t : threads) {
                try { t.join(1500); } catch (InterruptedException ignored) { }
            }
        }

        say("", 0);
        if (stop) warn("Stopped early.");
        if (heard.isEmpty()) {
            err("Nothing announced itself.");
            hint("Power-cycle the radio while listening, and try again.");
            hint("Other brands stay quiet. Tap Common defaults and try those subnets,");
            hint("or use the laptop's FieldNet listen option, which hears everything.");
            return;
        }
        ok(heard.size() + " heard:");
        EthInfo e = ethInfo();
        for (Heard h : new java.util.TreeMap<>(heard).values()) {
            say("    " + h.ip + "   " + h.what, 0);
        }
        String pick = firstRadioIp;
        if (pick == null) pick = heard.keySet().iterator().next();
        say("", 0);
        if (e != null && e.ip != null) {
            int mask = e.prefix == 0 ? 0 : (int) (0xFFFFFFFFL << (32 - e.prefix));
            if ((ipToInt(pick) & mask) == (ipToInt(e.ip) & mask)) {
                ok(pick + " is on the phone's subnet. It's in Device IP now.");
                hint("Try Ping x4, Check ports, or Open web page.");
                return;
            }
        }
        warn(pick + " is on a different subnet than the phone.");
        hint("Tap Ethernet settings and set Static IP:");
        hint("  IP address ...... " + suggestIp(pick));
        hint("  Netmask ......... 255.255.255.0");
        hint("  DNS ............. 8.8.8.8");
        hint("  Gateway ......... " + pick);
        hint("Type every box. Then come back and tap Ping x4.");
    }

    void doDefaults() {
        hdr("Common default IPs");
        say("  Ubiquiti airMAX / LTU ..... 192.168.1.20", 0);
        say("  Mimosa .................... 192.168.1.20", 0);
        say("  MikroTik .................. 192.168.88.1", 0);
        say("  Cambium ePMP .............. 192.168.0.1", 0);
        say("  Cambium PMP 450 ........... 169.254.1.1", 0);
        say("  TP-Link CPE / Pharos ...... 192.168.0.254", 0);
        say("  Most home routers ......... 192.168.1.1 or 192.168.0.1", 0);
        say("  Some ISP gear ............. 10.0.0.1 or 192.168.100.1", 0);
        say("", 0);
        hint("To try one: put it in Device IP, set the phone to a static IP on");
        hint("that subnet (Ethernet settings), then Ping x4 or Find devices.");
        hint("Defaults are only true if nobody changed the radio's settings.");
    }

    void askWebPage(String ip) {
        String[] opts = {"http://" + ip, "https://" + ip, "http://" + ip + ":8080",
                "https://" + ip + ":8443", "http://" + ip + ":81"};
        new AlertDialog.Builder(this)
                .setTitle("Open web page")
                .setItems(opts, (d, which) -> {
                    log("Open web page " + opts[which]);
                    Intent i = new Intent(this, WebActivity.class);
                    i.putExtra("url", opts[which]);
                    startActivity(i);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    void shareLog() {
        String text;
        try (RandomAccessFile f = new RandomAccessFile(logFile, "r")) {
            long len = f.length();
            long startAt = Math.max(0, len - 150_000);
            f.seek(startAt);
            byte[] buf = new byte[(int) (len - startAt)];
            f.readFully(buf);
            text = new String(buf, "UTF-8");
        } catch (Exception e) {
            toast("No log yet");
            return;
        }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "FieldNet log");
        i.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(i, "Share FieldNet log"));
    }
}
