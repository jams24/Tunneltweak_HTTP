package com.loyalteams.http.core;

import com.loyalteams.http.tunnel.Config;
import com.loyalteams.http.tunnel.RawTunnel;
import com.loyalteams.http.tunnel.Tunnel;
import com.loyalteams.http.tunnel.httpconnect.HttpConnectConfig;
import com.loyalteams.http.tunnel.httpconnect.HttpConnectTunnel;

import java.net.InetSocketAddress;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

public class TunnelFactory {

    public static Tunnel wrap(SocketChannel channel, Selector selector) {
        return new RawTunnel(channel, selector);
    }

    public static Tunnel createTunnelByConfig(InetSocketAddress destAddress, Selector selector) throws Exception {
        if (destAddress.isUnresolved()) {
            Config config = ProxyConfig.Instance.getDefaultTunnelConfig(destAddress);
            if (config instanceof HttpConnectConfig) {
                return new HttpConnectTunnel((HttpConnectConfig) config, selector);
            } else
                throw new Exception("The config is unknown.");
        } else {
            return new RawTunnel(destAddress, selector);
        }
    }

}
