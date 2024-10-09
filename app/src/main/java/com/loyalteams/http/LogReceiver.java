package com.loyalteams.http;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;

public class LogReceiver extends BroadcastReceiver implements Runnable {

    private Intent n;
    private Context x;

    public LogReceiver() {
    }

    @Override
    public void onReceive(Context c, Intent i) {
        try {
            if (TextUtils.equals(i.getAction(), Intent.ACTION_BOOT_COMPLETED) && (Boolean) getConfigValue("start_on_boot")) {
                Intent vpn = VpnService.prepare(c), sv = new Intent(c, SquidService.class).putExtra("start", true);
                if (vpn != null) {
                    ((MainActivity) c).startActivityForResult(vpn, 0);
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        c.startForegroundService(sv);
                    } else {
                        c.startService(sv);
                    }
                }
            } else if (TextUtils.equals(i.getAction(), "refresh")) {
                ((MainActivity) c).UpdateUI();
            } else if (TextUtils.equals(i.getAction(), "test")) {
                this.n = i;
                this.x = c;
                Thread t = new Thread(this);
                if (t.isAlive()) {
                    t.interrupt();
                }
                t.start();
            } else if (TextUtils.equals(i.getAction(), "log")) {
                ((MainActivity) c).TunnelLog(i.getStringExtra("msg"), 0);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        try {
            boolean a = n.getBooleanExtra("ip", false), f = true, g = true;
            if (a) {
                do {
                    if (!((MainActivity) x).isOnline()) {
                        a = n.getBooleanExtra("ip", false);
                        g = true;
                    } else {
                        f = true;
                    }
                    while (f) {
                        if (!((MainActivity) x).isOnline()) {
                            ((MainActivity) x).TunnelLog(null, 3);//ui setter
                            f = false;
                            break;
                        }
                    }
                    while (g) {
                        if (((MainActivity) x).isOnline()) {
                            ((MainActivity) x).TunnelLog(null, 3);//ui setter
                            g = false;
                            break;
                        }
                    }
                } while (a); //if true, loop forever
            }

            boolean b = n.getBooleanExtra("start", false), b1 = (Boolean) getObjectValue("ssl", "force_handshake");
//                String s1 = String.format("%s", SquidService.c((String) l.get(0), (String) l.get(1)));
//                ((MainActivity) x).TunnelLog(!b ? s1 : b1 ? "Forcing handshake..." : s1, 0);
            if (b) {
                if (b1) {
                    ((MainActivity) x).SwitchVPN();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SuppressLint("PrivateApi")
    public static WeakReference<Context> getContext() {
        try {
            return new WeakReference<>((Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null, (Object[]) null));
        } catch (final Exception e) {
            try {
                return new WeakReference<>((Context) Class.forName("android.app.AppGlobals").getMethod("getInitialApplication").invoke(null, (Object[]) null));
            } catch (final Exception e1) {
                throw new RuntimeException("Failed to get application instance");
            }
        }
    }


    public static String getConfigPath() {
        String s = "";

        Context c = getContext().get();
        if (c != null) {
            s = c.getFilesDir().getAbsolutePath();
        }
        return combine(s, "config.qcfg");
    }

    public static void setConfigValue(String k, Object v) throws Exception {
        File f = new File(getConfigPath());
        try (FileInputStream fis = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] c = new byte[(int) f.length()];
            int r;
            while ((r = fis.read(c)) != -1) {
                bos.write(c, 0, r);
                bos.flush();
            }
            JSONObject jo1 = new JSONObject(new String(bos.toByteArray()));
            try (FileOutputStream fos = new FileOutputStream(f)) {
                jo1.put(k, v);
                fos.write(jo1.toString().getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
        }
    }

    public static JSONArray getJsonArrayValue(String key) throws Exception {
        try {
            File f = new File(getConfigPath());
            try (FileInputStream fis = new FileInputStream(f)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] c = new byte[(int) f.length()];
                int r;
                while ((r = fis.read(c)) != -1) {
                    bos.write(c, 0, r);
                    bos.flush();
                }
                JSONObject jo = new JSONObject(new String(bos.toByteArray()));
                return jo.getJSONArray(key);
            }
        } catch (Exception e) {
            return defaultConfig().getJSONArray(key);
        }
    }

    public static void setObjectValue(String key, String key1, Object val) throws Exception {
        File f = new File(getConfigPath());
        try (FileInputStream fis = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] c = new byte[(int) f.length()];
            int r;
            while ((r = fis.read(c)) != -1) {
                bos.write(c, 0, r);
                bos.flush();
            }
            JSONObject jo = new JSONObject(new String(bos.toByteArray()));
            try (FileOutputStream fos = new FileOutputStream(f)) {
                jo.getJSONObject(key).put(key1, val);
                fos.write(jo.toString().getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
        }
    }

    public static Object getObjectValue(String key, String key1) throws Exception {
        try {
            File f = new File(getConfigPath());
            try (FileInputStream fis = new FileInputStream(f)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] c = new byte[(int) f.length()];
                int r;
                while ((r = fis.read(c)) != -1) {
                    bos.write(c, 0, r);
                    bos.flush();
                }
                JSONObject jo = new JSONObject(new String(bos.toByteArray()));
                return jo.getJSONObject(key).get(key1);
            }
        } catch (Exception e) {
            return defaultConfig().getJSONObject(key).get(key1);
        }
    }

    public static JSONObject defaultConfig() throws Exception {
        return new JSONObject()
                .put("remote_addr", "144.168.171.231")
                .put("remote_port", 12716)
                .put("username", "dzebb_handler")
                .put("password", new JSONArray().put("dzebb123456handler"))
                .put("ssl", new JSONObject()
                        .put("sni", "")
                        .put("reuse_session", true)
                        .put("session_ticket", false)
                        .put("compression", false)
                        .put("force_handshake", true)
                        .put("curves", "")
                        .put("alpn", new JSONArray().put("h2").put("spdy/3.1").put("http/1.1")
                        ))
                .put("tcp", new JSONObject()
                        .put("prefer_ipv4", true)
                        .put("no_delay", true)
                        .put("keep_alive", true)
                        .put("fast_open", false)
                        .put("fast_open_qlen", 20)
                )
                .put("udp_timeout", 0)
                .put("include", "0.0.0.0/0")
                .put("exclude", "10.0.0.0/8 172.16.0.0/12 192.168.0.0/16")
                .put("dns", "1.1.1.1")
                .put("forward", true)
                .put("theme", R.style.Theme_AppCompat_Light)
                .put("build", BuildConfig.VERSION_CODE)
                .put("start_on_boot", false)
                .put("message", "CONNECT [host:port] HTTP/1.0[crlf]Proxy-Connection: keep-alive[crlf][proxy_auth][crlf][crlf]")
                .put("dark", false)
                .put("proxy_auth", true)
                .put("exempt", new JSONArray()
                        .put("package:" + BuildConfig.APPLICATION_ID));
    }

    public static Object getConfigValue(String key) throws Exception {
        try {
            File f = new File(getConfigPath());
            if (!f.exists()) {
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    JSONObject jo = new JSONObject(defaultConfig().toString());
                    fos.write(jo.toString().getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                }
            }
            try (FileInputStream fis = new FileInputStream(f)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] c = new byte[(int) f.length()];
                int r;
                while ((r = fis.read(c)) != -1) {
                    bos.write(c, 0, r);
                    bos.flush();
                }
                JSONObject jo = new JSONObject(new String(bos.toByteArray()));
                return jo.get(key);
            }
        } catch (Exception e) {
            return defaultConfig().get(key);
        }
    }

    private static String combine(String... paths) {
        File file = new File(paths[0]);

        for (int i = 1; i < paths.length; i++) {
            file = new File(file, paths[i]);
        }

        return file.getPath();
    }
}
