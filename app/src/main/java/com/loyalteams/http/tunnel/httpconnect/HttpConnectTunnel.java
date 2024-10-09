package com.loyalteams.http.tunnel.httpconnect;

import android.text.TextUtils;

import com.loyalteams.http.LogReceiver;
import com.loyalteams.http.SquidService;
import com.loyalteams.http.core.ProxyConfig;
import com.loyalteams.http.crypto.Base64;
import com.loyalteams.http.tunnel.Tunnel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.util.Locale;


public class HttpConnectTunnel extends Tunnel {

    private boolean m_TunnelEstablished;
    private HttpConnectConfig m_Config;
    private int t = 0;

    public HttpConnectTunnel(HttpConnectConfig config, Selector selector) throws IOException {
        super(config.ServerAddress, selector);
        m_Config = config;
    }

    @Override
    protected void onConnected(ByteBuffer buffer) throws Exception {

        String msg = (String) LogReceiver.getConfigValue("message");
        if (!msg.contains("[host:port]")) {
            throw new Exception(SquidService.Instance.writeLog("Payload format error, missing [host:port]"));
        }else if((Boolean) LogReceiver.getConfigValue("proxy_auth") && !msg.contains("[proxy_auth]")) {
            throw new Exception(SquidService.Instance.writeLog("Payload format error, missing [proxy_auth]"));
        }

        String auth = String.format("%s:%s", m_Config.UserName, m_Config.Password);
        if (!TextUtils.equals(auth, "") || !TextUtils.equals(auth, ":")) {
            auth = String.valueOf(Base64.encode(auth.getBytes(StandardCharsets.UTF_8)));
        }
        String request = String.format(Locale.ENGLISH,
                msg.replace("[crlf]", "\r\n").replace("[host:port]", "%s:%d").replace("[proxy_auth]", "Proxy-Authorization: Basic %s"),
                m_DestAddress.getHostName(),
                m_DestAddress.getPort(),
                auth);

        buffer.clear();
        buffer.put(request.getBytes());
        buffer.flip();

        if (this.write(buffer, true)) {
            this.beginReceive();
        }
    }

    void trySendPartOfHeader(ByteBuffer buffer) throws Exception {

        int bytesSent = 0;
        if (buffer.remaining() > 10) {
            int pos = buffer.position() + buffer.arrayOffset();
            String firString = new String(buffer.array(), pos, 10).toUpperCase();
            if (firString.startsWith("GET /") || firString.startsWith("POST /")) {
                int limit = buffer.limit();
                buffer.limit(buffer.position() + 10);
                super.write(buffer, false);
                bytesSent = 10 - buffer.remaining();
                buffer.limit(limit);
                if (ProxyConfig.IS_DEBUG)
                    System.out.printf("Send %d bytes(%s) to %s\n", bytesSent, firString, m_DestAddress);
            }
        }
    }


    @Override
    protected void beforeSend(ByteBuffer buffer) throws Exception {
        if (ProxyConfig.Instance.isIsolateHttpHostHeader()) {
            trySendPartOfHeader(buffer);
        }
    }

    @Override
    protected void afterReceived(ByteBuffer buffer) throws Exception {
        t++;
        if (!m_TunnelEstablished) {
            String response = new String(buffer.array(), buffer.position(), 12);
            if (response.matches("^HTTP/1.[01] 200$")) {
                buffer.limit(buffer.position());
                /*
                if (t <= 1) {
                    SquidService.Instance.writeLog(String.format("Proxy server responded %s\n", response));
                }
                 */
                SquidService.Instance.writeLog(String.format("Proxy server responded %s\n", response));
            } else {
                throw new Exception(String.format("Proxy server responded an error: %s", response));
            }

            m_TunnelEstablished = true;
            super.onTunnelEstablished();
        }
    }

    @Override
    protected boolean isTunnelEstablished() {
        return m_TunnelEstablished;
    }

    @Override
    protected void onDispose() {
        m_Config = null;
    }
}