package com.loyalteams.http;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;

import com.loyalteams.http.core.DnsProxy;
import com.loyalteams.http.core.HttpHostHeaderParser;
import com.loyalteams.http.core.NatSession;
import com.loyalteams.http.core.NatSessionManager;
import com.loyalteams.http.core.ProxyConfig;
import com.loyalteams.http.core.TcpProxyServer;
import com.loyalteams.http.dns.DnsPacket;
import com.loyalteams.http.tcpip.CommonMethods;
import com.loyalteams.http.tcpip.IPHeader;
import com.loyalteams.http.tcpip.TCPHeader;
import com.loyalteams.http.tcpip.UDPHeader;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import de.blinkt.openvpn.core.CIDRIP;
import de.blinkt.openvpn.core.NetworkSpace;


public class SquidService extends VpnService implements Runnable {

    private PendingIntent pi;
    public static SquidService Instance;
    public static boolean IsRunning = false;

    private static int LOCAL_IP;
    private Thread m_VPNThread;
    private ParcelFileDescriptor m_VPNInterface;
    private TcpProxyServer m_TcpProxyServer;
    private DnsProxy m_DnsProxy;
    private FileOutputStream m_VPNOutputStream;

    private byte[] m_Packet;
    private IPHeader m_IPHeader;
    private TCPHeader m_TCPHeader;
    private UDPHeader m_UDPHeader;
    private ByteBuffer m_DNSBuffer;
    public static long m_SentBytes;
    public static long m_ReceivedBytes;

    public SquidService() {
        m_Packet = new byte[20000];
        m_IPHeader = new IPHeader(m_Packet, 0);
        m_TCPHeader = new TCPHeader(m_Packet, 20);
        m_UDPHeader = new UDPHeader(m_Packet, 20);
        m_DNSBuffer = ((ByteBuffer) ByteBuffer.wrap(m_Packet).position(28)).slice();
        Instance = this;

    }

    @Override
    public void onDestroy() {
        if (m_VPNThread != null) {
            m_VPNThread.interrupt();
        }
    }

    @Override
    public void onCreate() {
        // Start a new session by creating a new thread.
        pi = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class).addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT);
        notifyAlert(0);
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!intent.getBooleanExtra("start", false)) {
            writeLog("disconnecting...");
            dispose();
        } else {
            writeLog("connecting...");
            IsRunning = true;
            m_VPNThread = new Thread(this, "VPNServiceThread");
            m_VPNThread.setDaemon(true);
            m_VPNThread.start();
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        return START_REDELIVER_INTENT;
    }

    public String writeLog(String s) {
        sendBroadcast(new Intent("log").putExtra("msg", s));
        return s;
    }

    public void sendUDPPacket(IPHeader ipHeader, UDPHeader udpHeader) {
        try {
            CommonMethods.ComputeUDPChecksum(ipHeader, udpHeader);
            this.m_VPNOutputStream.write(ipHeader.m_Data, ipHeader.m_Offset, ipHeader.getTotalLength());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        try {
            waitUntilPreapred();
            m_TcpProxyServer = new TcpProxyServer(0);
            m_TcpProxyServer.start();

            m_DnsProxy = new DnsProxy();
            m_DnsProxy.start();

            while (true) {
                if (IsRunning) {
                    try {
                        ProxyConfig.Instance.m_ProxyList.clear();
                        String[] sa = new String[]{"username", "password", "remote_addr", "remote_port"};
                        for (int i = 0; i < sa.length; i++) {
                            if (TextUtils.equals(LogReceiver.getConfigValue(sa[0]).toString(), "") || TextUtils.equals(LogReceiver.getJsonArrayValue(sa[1]).getString(0), "")) {
                                ProxyConfig.Instance.addProxyToList((String) LogReceiver.getConfigValue(sa[2]).toString() + ":" + LogReceiver.getConfigValue(sa[3]).toString());
                            } else if (TextUtils.equals(LogReceiver.getConfigValue(sa[2]).toString(), "") || TextUtils.equals(LogReceiver.getConfigValue(sa[3]).toString(), "")) {
                                writeLog("upstream_format_error");
                                throw new Exception("upstream_format_error");
                            } else {
                                ProxyConfig.Instance.addProxyToList((String) LogReceiver.getConfigValue(sa[0]) + ":" + LogReceiver.getJsonArrayValue((String) sa[1]).getString(0) + "@" + (String) LogReceiver.getConfigValue(sa[2]) + ":" + (String) LogReceiver.getConfigValue(sa[3]));
                            }
                        }
                        //ProxyConfig.Instance.addProxyToList("dzebb_handler:dzebb123456handler@144.168.171.231:12716".trim());
                    } catch (Exception e) {
                        e.printStackTrace();
                        IsRunning = false;
                        continue;
                    }
                    runVPN();
                } else {
                    Thread.sleep(100);
                    break;
                }
            }
        } catch (Exception e) {
            StringWriter s9 = new StringWriter();
            PrintWriter p1 = new PrintWriter(s9);
            e.printStackTrace(p1);
            System.out.println("Fatal error: " + s9.toString());
        } finally {
//            writeLog("App terminated.");
            dispose();
        }
    }

    private void runVPN() throws Exception {
        this.m_VPNInterface = establishVPN();
        writeLog("VPN Established.");
        this.m_VPNOutputStream = new FileOutputStream(m_VPNInterface.getFileDescriptor());
        FileInputStream in = new FileInputStream(m_VPNInterface.getFileDescriptor());
        int size = 0;
        while (size != -1 && IsRunning) {
            while ((size = in.read(m_Packet)) > 0 && IsRunning) {
                if (m_DnsProxy.Stopped || m_TcpProxyServer.Stopped) {
                    in.close();
                    throw new Exception("LocalServer stopped.");
                }
                onIPPacketReceived(m_IPHeader, size);
            }
            Thread.sleep(100);
        }
        in.close();
        disconnectVPN();
    }

    void onIPPacketReceived(IPHeader ipHeader, int size) throws IOException {
        switch (ipHeader.getProtocol()) {
            case IPHeader.TCP:
                TCPHeader tcpHeader = m_TCPHeader;
                tcpHeader.m_Offset = ipHeader.getHeaderLength();
                if (ipHeader.getSourceIP() == LOCAL_IP) {
                    if (tcpHeader.getSourcePort() == m_TcpProxyServer.Port) {
                        NatSession session = NatSessionManager.getSession(tcpHeader.getDestinationPort());
                        if (session != null) {
                            ipHeader.setSourceIP(ipHeader.getDestinationIP());
                            tcpHeader.setSourcePort(session.RemotePort);
                            ipHeader.setDestinationIP(LOCAL_IP);

                            CommonMethods.ComputeTCPChecksum(ipHeader, tcpHeader);
                            m_VPNOutputStream.write(ipHeader.m_Data, ipHeader.m_Offset, size);
                            m_ReceivedBytes += size;
                        } else {
                            System.out.printf("NoSession: %s %s\n", ipHeader.toString(), tcpHeader.toString());
                        }
                    } else {
                        int portKey = tcpHeader.getSourcePort();
                        NatSession session = NatSessionManager.getSession(portKey);
                        if (session == null || session.RemoteIP != ipHeader.getDestinationIP() || session.RemotePort != tcpHeader.getDestinationPort()) {
                            session = NatSessionManager.createSession(portKey, ipHeader.getDestinationIP(), tcpHeader.getDestinationPort());
                        }

                        session.LastNanoTime = System.nanoTime();
                        session.PacketSent++;

                        int tcpDataSize = ipHeader.getDataLength() - tcpHeader.getHeaderLength();
                        if (session.PacketSent == 2 && tcpDataSize == 0) {
                            return;
                        }
                        if (session.BytesSent == 0 && tcpDataSize > 10) {
                            int dataOffset = tcpHeader.m_Offset + tcpHeader.getHeaderLength();
                            String host = HttpHostHeaderParser.parseHost(tcpHeader.m_Data, dataOffset, tcpDataSize);
                            if (host != null) {
                                session.RemoteHost = host;
                            } else {
                                System.out.printf("No host name found: %s ", session.RemoteHost);
                            }
                        }
                        ipHeader.setSourceIP(ipHeader.getDestinationIP());
                        ipHeader.setDestinationIP(LOCAL_IP);
                        tcpHeader.setDestinationPort(m_TcpProxyServer.Port);

                        CommonMethods.ComputeTCPChecksum(ipHeader, tcpHeader);
                        m_VPNOutputStream.write(ipHeader.m_Data, ipHeader.m_Offset, size);
                        session.BytesSent += tcpDataSize;
                        m_SentBytes += size;
                    }
                }
                break;
            case IPHeader.UDP:
                // 转发DNS数据包：
                UDPHeader udpHeader = m_UDPHeader;
                udpHeader.m_Offset = ipHeader.getHeaderLength();
                if (ipHeader.getSourceIP() == LOCAL_IP && udpHeader.getDestinationPort() == 53) {
                    m_DNSBuffer.clear();
                    m_DNSBuffer.limit(ipHeader.getDataLength() - 8);
                    DnsPacket dnsPacket = DnsPacket.FromBytes(m_DNSBuffer);
                    if (dnsPacket != null && dnsPacket.Header.QuestionCount > 0) {
                        m_DnsProxy.onDnsRequestReceived(ipHeader, udpHeader, dnsPacket);
                    }
                }
                break;
        }
    }

    private void waitUntilPreapred() {
        while (prepare(this) != null) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private ParcelFileDescriptor establishVPN() throws Exception {
        StringBuilder sb = new StringBuilder();
        NetworkSpace mRoutes = new NetworkSpace();
        mRoutes.clear();
        Builder builder = new Builder();
        builder.setMtu(1500);
        ProxyConfig.IPAddress ipAddress = ProxyConfig.Instance.getDefaultLocalIP();
        LOCAL_IP = CommonMethods.ipStringToInt(ipAddress.Address);
        builder.addAddress(ipAddress.Address, ipAddress.PrefixLength);

        String c = (String) LogReceiver.getConfigValue("include");
        for (String r : c.split(" ")) {
            String[] s1 = r.split("/");
            mRoutes.addIP(new CIDRIP(s1[0], Integer.parseInt(s1[1])), true);
        }
        String e = (String) LogReceiver.getConfigValue("exclude");
        for (String r : e.split(" ")) {
            String[] s1 = r.split("/");
            mRoutes.addIP(new CIDRIP(s1[0], Integer.parseInt(s1[1])), false);
        }
/*
        String[] is = {(String) LogReceiver.getConfigValue("remote_addr")};

        for (String s1 : is) {
            if (!s1.equals("")) {
                InetAddress inetAddr = InetAddress.getByName(s1);
                byte[] addr = inetAddr.getAddress();
                StringBuilder ipAddr = new StringBuilder();
                for (int o = 0; o < addr.length; o++) {
                    if (o > 0) {
                        ipAddr.append(".");
                    }
                    ipAddr.append(addr[o] & 0xFF);
                }
                mRoutes.addIP(new CIDRIP(ipAddr.toString(), 32), true);
            }
        }

 */

        mRoutes.addIP(new CIDRIP(getIPAddress(), 32), false);

        if (ProxyConfig.IS_DEBUG)
            System.out.printf("addAddress: %s/%d\n", ipAddress.Address, ipAddress.PrefixLength);

        for (String dns : new String[]{"8.8.8.8", "8.8.4.4"}) {
            builder.addDnsServer("8.8.8.8");
            mRoutes.addIP(new CIDRIP(dns, 32), false);
        }


/*
        if (ProxyConfig.Instance.getRouteList().size() > 0) {
            for (ProxyConfig.IPAddress routeAddress : ProxyConfig.Instance.getRouteList()) {
                builder.addRoute(routeAddress.Address, routeAddress.PrefixLength);
                if (ProxyConfig.IS_DEBUG)
                    System.out.printf("addRoute: %s/%d\n", routeAddress.Address, routeAddress.PrefixLength);
            }
            builder.addRoute(CommonMethods.ipIntToString(ProxyConfig.FAKE_NETWORK_IP), 16);

            if (ProxyConfig.IS_DEBUG)
                System.out.printf("addRoute for FAKE_NETWORK: %s/%d\n", CommonMethods.ipIntToString(ProxyConfig.FAKE_NETWORK_IP), 16);
        } else {
            builder.addRoute("0.0.0.0", 0);
            if (ProxyConfig.IS_DEBUG)
                System.out.print("addDefaultRoute: 0.0.0.0/0\n");
        }
        ArrayList<String> servers = new ArrayList<String>();
        for (String name : new String[]{"net.dns1", "net.dns2", "net.dns3", "net.dns4",}) {
            String value = (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, name);
            if (value != null && !"".equals(value) && !servers.contains(value)) {
                servers.add(value);
                builder.addRoute(value, 32);
                if (ProxyConfig.IS_DEBUG)
                    System.out.printf("%s=%s\n", name, value);
            }
        }
 */

        Collection<NetworkSpace.ipAddress> positiveIPv4Routes = mRoutes.getPositiveIPList();
        NetworkSpace.ipAddress multicastRange = new NetworkSpace.ipAddress(new CIDRIP("224.0.0.0", 3), true);
        sb.delete(0, sb.length());
        for (NetworkSpace.ipAddress route : positiveIPv4Routes) {
            sb.append(route.getIPv4Address()).append(", ");
            if (!multicastRange.containsNet(route)) {
                builder.addRoute(route.getIPv4Address(), route.networkMask);
            }
        }
        sb.delete(sb.lastIndexOf(", "), sb.length());
        sendBroadcast(new Intent("log").putExtra("msg", "[included multi-cast routes: " + sb.toString() + "]"));
        for (boolean bo : new Boolean[]{true, false}) {
            sb.delete(0, sb.length());
            for (NetworkSpace.ipAddress p : mRoutes.getNetworks(bo)) {
                sb.append(p.getIPv4Address()).append(", ");
            }
            sb.delete(sb.lastIndexOf(", "), sb.length());
            switch (!bo ? 0 : 1) {
                case 0:
                    sendBroadcast(new Intent("log").putExtra("msg", "[excluded routes: " + sb.toString() + "]"));
                    break;
                case 1:
                    sendBroadcast(new Intent("log").putExtra("msg", "[included routes: " + sb.toString() + "]"));
                    break;
            }
        }

        builder.setSession(getApplicationInfo().loadLabel(getPackageManager()).toString());
        return builder.establish();
    }

    public void disconnectVPN() {
        try {
            if (m_VPNInterface != null) {
                m_VPNInterface.close();
                m_VPNInterface = null;
            }
        } catch (Exception e) {
            // ignore
        }
        this.m_VPNOutputStream = null;
    }

    public void dispose() {
        disconnectVPN();
        if (m_TcpProxyServer != null) {
            m_TcpProxyServer.stop();
            m_TcpProxyServer = null;
        }
        if (m_DnsProxy != null) {
            m_DnsProxy.stop();
            m_DnsProxy = null;
        }
        notifyAlert(1);
        stopSelf();
        IsRunning = false;
        android.os.Process.killProcess(android.os.Process.myPid());
        writeLog("VPN Disconnected.");
    }

    private void notifyAlert(int flags) {
        try {
            String CHANNEL_ONE_ID = getApplicationInfo().loadLabel(getPackageManager()).toString();
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            Notification.Builder n = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new Notification.Builder(this, CHANNEL_ONE_ID) : new Notification.Builder(this);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL_ONE_ID, CHANNEL_ONE_ID, NotificationManager.IMPORTANCE_HIGH));
            } else {
                n.setDefaults(Notification.DEFAULT_ALL);
            }
//            n.setShowWhen(true);
            n.setUsesChronometer(true);
            n.setOnlyAlertOnce(true);
            n.setContentTitle(CHANNEL_ONE_ID);
            n.setContentText("Running in Foreground");
            n.setSmallIcon(android.R.drawable.ic_lock_idle_lock);
            n.setContentIntent(pi);
            n.setAutoCancel(false);
            n.setOngoing(true);
            Notification n1 = n.build();
            switch (flags) {
                case 0:
                    n1.flags |= (Notification.FLAG_NO_CLEAR | Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE);
                    if (nm != null) {
                        nm.notify(1, n1);
                    }
                    startForeground(1, n1);
                    break;
                case 1:
                    if (nm != null) {
                        nm.cancel(1);
                    }
                    stopForeground(true);
                    break;
                case 2:
                    n1.flags |= 0;
                    if (nm != null) {
                        nm.cancel(1);
                    }
                    stopForeground(true);
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String getIPAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress()) {
                        String sAddr = addr.getHostAddress();
                        boolean isIPv4 = sAddr.indexOf(':') < 0;
                        if (isIPv4)
                            return sAddr;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }
}
