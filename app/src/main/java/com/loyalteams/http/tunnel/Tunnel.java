package com.loyalteams.http.tunnel;


import android.annotation.SuppressLint;

import com.loyalteams.http.SquidService;
import com.loyalteams.http.core.ProxyConfig;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

import static java.nio.channels.SelectionKey.OP_READ;
import static java.nio.channels.SelectionKey.OP_WRITE;

public abstract class Tunnel {
    final static ByteBuffer GL_BUFFER = ByteBuffer.allocate(20000);
    public static long SessionCount;

    protected abstract void onConnected(ByteBuffer buffer) throws Exception;

    protected abstract boolean isTunnelEstablished();

    protected abstract void beforeSend(ByteBuffer buffer) throws Exception;

    protected abstract void afterReceived(ByteBuffer buffer) throws Exception;

    protected abstract void onDispose();

    private SocketChannel m_InnerChannel;
    private ByteBuffer m_SendRemainBuffer;
    private Selector m_Selector;
    private Tunnel m_BrotherTunnel;
    private boolean m_Disposed;
    private InetSocketAddress m_ServerEP;
    protected InetSocketAddress m_DestAddress;

    public Tunnel(SocketChannel innerChannel, Selector selector) {
        this.m_InnerChannel = innerChannel;
        this.m_Selector = selector;
        SessionCount++;
    }

    public Tunnel(InetSocketAddress serverAddress, Selector selector) throws IOException {
        SocketChannel innerChannel = SocketChannel.open();
        innerChannel.configureBlocking(true);
        this.m_InnerChannel = innerChannel;
        this.m_Selector = selector;
        this.m_ServerEP = serverAddress;
        SessionCount++;
    }

    public void setBrotherTunnel(Tunnel brotherTunnel) {
        m_BrotherTunnel = brotherTunnel;
    }

    public void connect(InetSocketAddress destAddress) throws Exception {
        if (m_InnerChannel.isBlocking()) {
            m_InnerChannel.configureBlocking(false);
        }
        if (SquidService.Instance.protect(m_InnerChannel.socket())) {
            m_DestAddress = destAddress;
            m_InnerChannel.register(m_Selector, SelectionKey.OP_CONNECT, this);
            m_InnerChannel.socket().setKeepAlive(true);
            m_InnerChannel.connect(m_ServerEP);//连接目标
//            m_InnerChannel.connect(new InetSocketAddress(8484));//连接目标
        } else {
            throw new Exception("VPN protect socket failed.");
        }
    }

    public SocketChannel beginReceive() throws Exception {
        if (m_InnerChannel.isBlocking()) {
            m_InnerChannel.configureBlocking(false);
        }
        m_InnerChannel.register(m_Selector, OP_READ, this);
        return m_InnerChannel;
    }

    protected boolean write(ByteBuffer buffer, boolean copyRemainData) throws Exception {
        if (m_InnerChannel.isBlocking()) {
            m_InnerChannel.configureBlocking(false);
        }
        int bytesSent;
        while (buffer.hasRemaining()) {
            bytesSent = m_InnerChannel.write(buffer);
            if (bytesSent == 0) {
                break;
            }
        }
        if (buffer.hasRemaining()) {
            if (copyRemainData) {
                if (m_SendRemainBuffer == null) {
                    m_SendRemainBuffer = ByteBuffer.allocate(buffer.capacity());
                }
                m_SendRemainBuffer.clear();
                m_SendRemainBuffer.put(buffer);
                m_SendRemainBuffer.flip();
                m_InnerChannel.register(m_Selector, OP_WRITE, this);//注册写事件
            }
            return false;
        } else {//发送完毕了
            return true;
        }
    }

    protected void onTunnelEstablished() throws Exception {
        this.beginReceive();
        m_BrotherTunnel.beginReceive();
    }

    @SuppressLint("DefaultLocale")
    public void onConnectable() {
        try {
            if (m_InnerChannel.finishConnect()) {
                onConnected(GL_BUFFER);
            } else {
                this.dispose();
            }
        } catch (Exception e) {
            StringWriter s9 = new StringWriter();
            PrintWriter p1 = new PrintWriter(s9);
            e.printStackTrace(p1);
            this.dispose();
        }
    }

    public void onReadable(SelectionKey key) {
        try {
            ByteBuffer buffer = GL_BUFFER;
            buffer.clear();
            int bytesRead = m_InnerChannel.read(buffer);
            if (bytesRead > 0) {
                buffer.flip();
                afterReceived(buffer);
                if (isTunnelEstablished() && buffer.hasRemaining()) {
                    m_BrotherTunnel.beforeSend(buffer);
                    if (!m_BrotherTunnel.write(buffer, true)) {
                        key.cancel();
                        if (ProxyConfig.IS_DEBUG)
                            System.out.printf("%s can not read more.\n", m_ServerEP);
                    }
                }
            } else if (bytesRead < 0) {
                this.dispose();
            }
        } catch (Exception e) {
            e.printStackTrace();
            this.dispose();
        }
    }

    public void onWritable(SelectionKey key) {
        try {
            this.beforeSend(m_SendRemainBuffer);
            if (this.write(m_SendRemainBuffer, false)) {
                key.cancel();
                if (isTunnelEstablished()) {
                    m_BrotherTunnel.beginReceive();
                } else {
                    this.beginReceive();
                }
            }
        } catch (Exception e) {
            this.dispose();
        }
    }

    public void dispose() {
        disposeInternal(true);
    }

    void disposeInternal(boolean disposeBrother) {
        if (m_Disposed) {
        } else {
            try {
                m_InnerChannel.close();
            } catch (Exception e) {
            }

            if (m_BrotherTunnel != null && disposeBrother) {
                m_BrotherTunnel.disposeInternal(false);
            }

            m_InnerChannel = null;
            m_SendRemainBuffer = null;
            m_Selector = null;
            m_BrotherTunnel = null;
            m_Disposed = true;
            SessionCount--;

            onDispose();
        }
    }
}