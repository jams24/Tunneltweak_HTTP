package com.loyalteams.http;

import androidx.appcompat.app.AppCompatActivity;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.text.Html;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class MainActivity extends AppCompatActivity implements Runnable, DialogInterface.OnClickListener, View.OnClickListener, CompoundButton.OnCheckedChangeListener {
    private int cmd = 0;
    private final StringBuilder log = new StringBuilder();
    private final LogReceiver cr = new LogReceiver();
    ScrollView ds;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            for (String s : new String[]{"log", "test", "path"}) {
                IntentFilter filter = new IntentFilter();
                filter.addAction(s);
                registerReceiver(cr, filter);
            }
            setContentView(R.layout.activity_main);
            ((EditText) findViewById(R.id.et1)).setText((CharSequence) LogReceiver.getConfigValue("remote_addr"));
            ((EditText) findViewById(R.id.et2)).setText(String.valueOf(LogReceiver.getConfigValue("remote_port")));
            ((EditText) findViewById(R.id.et3)).setText((CharSequence) LogReceiver.getConfigValue("username"));
            ((EditText) findViewById(R.id.et4)).setText(String.valueOf(LogReceiver.getJsonArrayValue("password").getString(0)));
            ((EditText) findViewById(R.id.et5)).setText((CharSequence) LogReceiver.getConfigValue("message"));
            ((Switch) findViewById(R.id.switch1)).setChecked((Boolean) LogReceiver.getConfigValue("proxy_auth"));
            ((Switch) findViewById(R.id.switch1)).setOnCheckedChangeListener(this);
            ((EditText) findViewById(R.id.et3)).setVisibility((Boolean) LogReceiver.getConfigValue("proxy_auth") ? View.VISIBLE : View.GONE);
            ((EditText) findViewById(R.id.et4)).setVisibility((Boolean) LogReceiver.getConfigValue("proxy_auth") ? View.VISIBLE : View.GONE);
//                et.setVisibility((Boolean) LogReceiver.getConfigValue("proxy_auth") ? View.VISIBLE : View.GONE);
            ((Button) findViewById(R.id.button)).setOnClickListener(this);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void setTheme(int t) {
        try {
            super.setTheme(((Integer) LogReceiver.getConfigValue("theme")));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(cr);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        try {
            switch (resultCode) {
                case Activity.RESULT_CANCELED:
                    switch (requestCode) {
                        case 0:
                            if (data == null) {
                                TunnelLog("VPN is not prepared.", 0);
                                ScrollView sv = (ScrollView) findViewById(R.id.sv);
                                if (sv != null) {
                                    for (int h = 0; h < sv.getChildCount(); h++) {
                                        View v = sv.getChildAt(h);
                                        if (v instanceof LinearLayout) {
                                            for (int i = 0; i < ((LinearLayout) v).getChildCount(); i++) {
                                                View w = ((LinearLayout) v).getChildAt(i);
                                                if (w instanceof Button && TextUtils.equals(((Button) w).getText(), "stop")) {
                                                    ((Button) w).setText(MessageFormat.format("{0}", "start"));
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            break;
                        case 1: //export canceled
                        case 2: //import canceled
                            break;
                    }
                    break;
                case Activity.RESULT_OK:
                    switch (requestCode) {
                        case 0:
                            if (data == null) {
                                Intent intent = new Intent(this, SquidService.class).putExtra("start", true);
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    startForegroundService(intent);
                                } else {
                                    startService(intent);
                                }
                                TunnelLog("VPN is prepared.", 0);
                            }
                            break;
                        case 1:
                            if (data != null) {
                                //SaveAccount(false);
                                Uri u = data.getData();
                                if (u != null) {
                                    Cursor cursor;
                                    cursor = getContentResolver().query(u, null, null, null, null);
                                    if (cursor != null) {
                                        cursor.moveToFirst();
                                        int nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                                        if (nameIndex >= 0) {
                                            boolean b = cursor.getString(nameIndex).endsWith(".qcfg");
                                            if (b) {
                                                File file = new File(LogReceiver.getConfigPath());
                                                if (file.exists()) {
                                                    try (ByteArrayOutputStream byteOut = new ByteArrayOutputStream()) {
                                                        try (ObjectOutputStream out = new ObjectOutputStream(byteOut)) {
                                                            try (FileInputStream fis = new FileInputStream(file)) {
                                                                byte[] c = new byte[(int) file.length()];
                                                                while (fis.read(c) != -1) {
                                                                    out.writeObject(new String(c, StandardCharsets.UTF_8));
                                                                    out.flush();
                                                                }
                                                            }
                                                            byte[] outputBytes = crypt(byteOut.toByteArray(), 'e');
                                                            try (ObjectOutputStream oo1 = new ObjectOutputStream(getContentResolver().openOutputStream(u))) {
                                                                oo1.writeObject(outputBytes);
                                                                oo1.flush();
                                                            }
                                                        }
                                                    }
                                                }
                                            } else
                                                DocumentsContract.deleteDocument(getContentResolver(), u);
                                            Toast.makeText(this, TunnelLog(!b ? "Export Failed. Suffix should be .qcfg file extension, or the file name already exists. Select the file to be overwritten before you export." : "Config file exported.", 0), Toast.LENGTH_LONG).show();
                                            cursor.close();
                                        }
                                    }
                                }
                            }
                            break;
                        case 2:
                            if (data != null) {
                                Uri u = data.getData();
                                if (u != null) {
                                    Cursor cursor;
                                    cursor = getContentResolver().query(u, null, null, null, null);
                                    if (cursor != null) {
                                        cursor.moveToFirst();
                                        int nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                                        if (nameIndex >= 0) {
                                            String s = cursor.getString(nameIndex);
                                            boolean b = s.endsWith(".qcfg");
                                            if (b) {
                                                //importConfig(u);
                                                cursor.close();
                                                UpdateUI();
                                            } else
                                                Toast.makeText(this, TunnelLog("Import failed. Suffix should be .qcfg file extension.", 0), Toast.LENGTH_LONG).show();
                                        }
                                    }
                                }
                            }
                            break;
                    }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    @Override
    public void onClick(View v) {
        try {
            boolean serviceInstance = isMyServiceRunning(), online = isOnline();
            if (v instanceof Button) {
                if (!online) {
                    Toast.makeText(this, "No network.", Toast.LENGTH_LONG).show();
                    return;
                }
                if (TextUtils.equals(((Button) v).getText(), "Start")) {
                    if (SaveAccount(true)) {
                        sendBroadcast(new Intent("test").putExtra("start", true));
                        ((Button) v).setText(MessageFormat.format("{0}", "stop"));
                    }
                    ShowDialog('l');
                } else if (TextUtils.equals(((Button) v).getText(), "Stop")) {
                    ((Button) v).setText(MessageFormat.format("{0}", "Start"));
                    if (serviceInstance) {
                        Intent intent = new Intent(this, SquidService.class).putExtra("start", false);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent);
                        } else {
                            startService(intent);
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        try {
            if (cmd == 3) {
                UpdateUI();
            } else if (ds != null) {
                for (int h = 0; h < ds.getChildCount(); h++) {
                    View v = ds.getChildAt(h);
                    if (v instanceof LinearLayout) {
                        for (int i = 0; i < ((LinearLayout) v).getChildCount(); i++) {
                            View w = ((LinearLayout) v).getChildAt(i);
                            if (w instanceof TextView) {
                                if (TextUtils.equals((String) w.getTag(), "Log")) {
                                    switch (cmd) {
                                        case 0:
                                            ((TextView) w).setText(Html.fromHtml(log.toString()).toString());
                                            UpdateUI();
                                            break;
                                        case 1:
                                            ((TextView) w).setText(log.delete(0, log.length()).toString());
                                            break;
                                        case 2:
                                            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                                            ClipData clip = ClipData.newPlainText(getApplicationInfo().loadLabel(getPackageManager()).toString(), ((TextView) w).getText().toString());
                                            if (clipboard != null) {
                                                clipboard.setPrimaryClip(clip);
                                            }
                                            break;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu m) {
        return super.onPrepareOptionsMenu(m);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem m) {
        if (m != null)
            if (TextUtils.equals("Log", m.getTitle())) {
                ShowDialog('l');
            }
        return super.onOptionsItemSelected(m);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        //menu group ID = 1
        String[] s = {"Log"}; //{"Export", "Import", "Settings", "Exit"};
        for (int i = 0; i < s.length; i++) {
/*
            if (i == 0) {
                m.addSubMenu(1, i, i, s[i]);
                continue;
            }
*/
            m.add(1, i, i, s[i]); // group ID = 1
        }
        return super.onCreateOptionsMenu(m);
    }

    @Override
    public void onCheckedChanged(CompoundButton b, boolean isChecked) {
        try {
            if (b.getId() == R.id.switch1) {
                String sv = String.valueOf(LogReceiver.getConfigValue(("message")));
                LogReceiver.setConfigValue("message", isChecked ? sv.replace("[crlf][crlf]", "[crlf][proxy_auth][crlf][crlf]") : sv.replace("[proxy_auth][crlf]", ""));
                LogReceiver.setConfigValue("proxy_auth", isChecked);
            } else if (TextUtils.equals((CharSequence) b.getTag(), "dark")) {
                Class<?> wrapper = Context.class;
                for (Method m : wrapper.getDeclaredMethods()) {
                    if (TextUtils.equals(m.getName(), "getThemeResId")) {
                        m.setAccessible(true);
                        Object i = m.invoke(this);
                        if (i instanceof Integer) {
                            switch ((Integer) i) {
                                case R.style.Theme_AppCompat_Light:
                                    i = R.style.Theme_AppCompat;
                                    break;
                                case R.style.Theme_AppCompat:
                                    i = R.style.Theme_AppCompat_Light;
                                    break;
                            }
                            LogReceiver.setConfigValue("theme", i);
                            LogReceiver.setConfigValue((String) b.getTag(), isChecked);
                        }
                        recreate();
                    }
                }
                SaveAccount(false);
                return;
            }
            UpdateUI();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean SaveAccount(boolean c) {
        try {
            ScrollView sv = (ScrollView) findViewById(R.id.sv);
            if (sv != null) {
                for (int h = 0; h < sv.getChildCount(); h++) {
                    View v = sv.getChildAt(h);
                    if (v instanceof LinearLayout) {
                        for (int i = 0; i < ((LinearLayout) v).getChildCount(); i++) {
                            View m = ((LinearLayout) v).getChildAt(i);
                            if (m instanceof EditText) {
                                if (TextUtils.equals(((EditText) m).getText().toString(), "") && c) {
                                    ((EditText) m).setError("Field is empty!");
                                    m.requestFocus();
                                    return false;
                                }
                                String s = ((EditText) m.findViewWithTag(m.getTag())).getText().toString();
                                if (m.getTag().equals("password")) {
                                    LogReceiver.setConfigValue((String) m.getTag(), new JSONArray().put(s));
                                } else
                                    LogReceiver.setConfigValue((String) m.getTag(), s);
                            } else if (m instanceof Switch) {
                                LogReceiver.setConfigValue((String) m.getTag(), ((Switch) m).isChecked());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return true;
    }

    public void SwitchVPN() {
        Intent vpn = VpnService.prepare(getApplicationContext());
        if (vpn != null) {
            startActivityForResult(vpn, 0);
        } else {
            onActivityResult(0, Activity.RESULT_OK, VpnService.prepare(getApplicationContext()));
        }
    }

    private void ShowDialog(char c) {
        try {
            AlertDialog.Builder bm = new AlertDialog.Builder(this);
            boolean b = isMyServiceRunning();
            AlertDialog dm;
            ds = new ScrollView(this);
            ds.setSaveEnabled(true);
            ds.setSaveFromParentEnabled(true);
            ds.setScrollbarFadingEnabled(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            LinearLayout ll = new LinearLayout(this);
            ll.setOrientation(LinearLayout.VERTICAL);
            ll.setLayoutParams(lp);

            switch (c) {
                case 'l':
                    TextView t1 = new TextView(this);
                    t1.setTag("Log");
                    if (!TextUtils.equals(log.toString(), null)) {
                        t1.setText(Html.fromHtml(log.toString()).toString());
                    }
                    ll.addView(t1);
                    bm.setNegativeButton(android.R.string.cancel, this)
                            .setPositiveButton(android.R.string.copy, this)
                            .setNeutralButton(android.R.string.cut, this)
                            .setTitle((String) t1.getTag()).setCancelable(true);
                    break;
            }
            ds.addView(ll);
            bm.setView(ds);
            dm = bm.create();
            dm.setCanceledOnTouchOutside(c == '1');
            dm.show();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private byte[] crypt(byte[] clean, char m) throws Exception {
        String key = getApplicationInfo().loadLabel(getPackageManager()).toString();
        // Generating IV.
        int ivSize = 16;
        byte[] iv = new byte[ivSize];
        IvParameterSpec ivParameterSpec = new IvParameterSpec(iv);
        SecretKeySpec secretKeySpec = new SecretKeySpec(iv, "AES");
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(key.getBytes(StandardCharsets.UTF_8));
        switch (m) {
            case 'e':
                SecureRandom random = new SecureRandom();
                random.nextBytes(iv);
                // Hashing key.
                System.arraycopy(md.digest(), 0, iv, 0, iv.length);
                // Encrypt.
                Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivParameterSpec);
                byte[] encrypted = cipher.doFinal(clean);
                // Combine IV and encrypted part.
                byte[] encryptedIVAndText = new byte[ivSize + encrypted.length];
                System.arraycopy(iv, 0, encryptedIVAndText, 0, ivSize);
                System.arraycopy(encrypted, 0, encryptedIVAndText, ivSize, encrypted.length);
                return encryptedIVAndText;
            case 'd':
                System.arraycopy(clean, 0, iv, 0, iv.length);
                // Extract encrypted part.
                int encryptedSize = clean.length - ivSize;
                byte[] encryptedBytes = new byte[encryptedSize];
                System.arraycopy(clean, ivSize, encryptedBytes, 0, encryptedSize);
                // Hash key.
                System.arraycopy(md.digest(), 0, iv, 0, iv.length);
                // Decrypt.
                Cipher cipherDecrypt = Cipher.getInstance("AES/CBC/PKCS5Padding");
                cipherDecrypt.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec);
                return cipherDecrypt.doFinal(encryptedBytes);
        }
        return null;
    }

    public String TunnelLog(String s, int i) {
        cmd = i;
        if (i != 3) {
            log.append(android.text.format.DateFormat.format("h:m:ss a", new java.util.Date()));
            log.append("<br>");
            log.append(s);
            log.append("<br>");
        }
        runOnUiThread(this);
        return s;
    }

    public boolean isOnline() {
        ConnectivityManager manager = (ConnectivityManager) this.getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = null;
        if (manager != null) {
            networkInfo = manager.getActiveNetworkInfo();
        }
        return networkInfo != null;
    }

    private static boolean isTunOnline() {
        List<String> networkList = new ArrayList<>();
        try {
            ArrayList<NetworkInterface> al = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface n : al) {
                if (n.isUp())
                    networkList.add(n.getName());
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return !networkList.contains("tun0");
    }

    private boolean isMyServiceRunning() {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
                if (TextUtils.equals(SquidService.class.getName(), service.service.getClassName())) {
                    return true;
                }
            }
        }
        return false;
    }

    public void UpdateUI() {
        try {
            ScrollView sv = (ScrollView) findViewById(R.id.sv);
            if (sv != null) {
                for (int h = 0; h < sv.getChildCount(); h++) {
                    View v = sv.getChildAt(h);
                    if (v instanceof LinearLayout) {
                        for (int i = 0; i < ((LinearLayout) v).getChildCount(); i++) {
                            View w = ((LinearLayout) v).getChildAt(i);
                            if (w instanceof Switch) {
//                                ((Switch) w).setChecked(isTunOnline() && ((Switch) w).isChecked());
                                w.setEnabled(!isMyServiceRunning());
                            } else if (w instanceof Button) {
                                ((Button) w).setText(isMyServiceRunning() ? "Stop" : "Start");
                            } else if (w instanceof EditText) {
                                ((EditText) findViewById(R.id.et3)).setVisibility((Boolean) LogReceiver.getConfigValue("proxy_auth") ? View.VISIBLE : View.GONE);
                                ((EditText) findViewById(R.id.et4)).setVisibility((Boolean) LogReceiver.getConfigValue("proxy_auth") ? View.VISIBLE : View.GONE);
                                w.setEnabled(!isMyServiceRunning());
                                ((EditText) w).setText(w.getTag().equals("password") ? String.valueOf(LogReceiver.getJsonArrayValue((String) w.getTag()).getString(0)) : String.valueOf(LogReceiver.getConfigValue((String) w.getTag())));
                            } else if (w instanceof TextView) {
//                                if (TextUtils.equals((CharSequence) w.getTag(), "username") || TextUtils.equals((CharSequence) w.getTag(), "password")) {
//                                    w.setVisibility((Boolean) LogReceiver.getConfigValue("proxy_auth") ? View.VISIBLE : View.GONE);
//                                }
                            }
                        }
                    }
                }
            }
            SaveAccount(false);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onClick(DialogInterface di, int i) {
        switch (i) {
            case DialogInterface.BUTTON_NEGATIVE:
                di.cancel();
                break;
            case DialogInterface.BUTTON_POSITIVE:
                TunnelLog(null, 2);
                break;
            case DialogInterface.BUTTON_NEUTRAL:
                TunnelLog(null, 1);
                break;
        }

    }
}