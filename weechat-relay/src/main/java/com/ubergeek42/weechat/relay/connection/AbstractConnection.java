package com.ubergeek42.weechat.relay.connection;

import com.ubergeek42.weechat.relay.RelayConnectionHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.Socket;
import java.net.SocketException;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;

public abstract class AbstractConnection implements IConnection {
    final private static boolean DEBUG = false;
    private static Logger logger = LoggerFactory.getLogger("AbstractConnection");

    String server = null;
    int port = 0;

    Socket sock = null;
    OutputStream out_stream = null;
    InputStream in_stream = null;
    volatile boolean connected = false;

    ArrayList<RelayConnectionHandler> connectionHandlers = new ArrayList<RelayConnectionHandler>();
    Thread connector = null;

    @Override
    public boolean isConnected() {
        Socket s = sock;
        return (s != null && !s.isClosed() && connected);
    }

    @Override
    public int read(byte[] bytes, int off, int len) throws IOException {
        InputStream in = in_stream;
        if (in == null)
            return -1;
        return in.read(bytes, off, len);
    }

    @Override
    public void write(byte[] bytes) {
        OutputStream out = out_stream;
        if (out == null)
            return;
        try {
            out.write(bytes);
        } catch (IOException e) {
            // TODO: better this part
            e.printStackTrace();
        }
    }

    @Override
    public void connect() {
        notifyHandlers(STATE.CONNECTING);

        if (connector.isAlive()) {
            // do nothing
            return;
        }
        connector.start();
    }

    /**
     * Disconnects from the server, and cleans up
     */
    @Override
    public void disconnect() {
        // If we're in the process of connecting, kill the thread and let us die
        if (connector.isAlive()) {
            connector.interrupt();
        }

        if (!connected) {
            return;
        }

            // If we're connected, tell weechat we're going away
        try {
            out_stream.write("quit\n".getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Close all of our streams/sockets
        connected = false;
        if (in_stream != null) {
            try {
                in_stream.close();
            } catch (IOException e) {}
            in_stream = null;
        }
        if (out_stream != null) {
            try {
                out_stream.close();
            } catch (IOException e) {}
            out_stream = null;
        }
        if (sock != null) {
            try {
                sock.close();
            } catch (IOException e) {}
            sock = null;
        }

        // Call any registered disconnect handlers
        notifyHandlers(STATE.DISCONNECTED);
    }
    /**
     * Register a connection handler to receive onConnected/onDisconnected events
     *
     * @param rch - The connection handler
     */
    @Override
    public void addConnectionHandler(RelayConnectionHandler rch) {
        connectionHandlers.add(rch);
    }

    @Override
    public void notifyHandlers(IConnection.STATE s) {
        for (RelayConnectionHandler rch: connectionHandlers) {
            switch (s) {
                case CONNECTING:
                    rch.onConnecting();
                    break;
                case CONNECTED:
                    rch.onConnect();
                    break;
                case AUTHENTICATED:
                    rch.onAuthenticated();
                    break;
                case DISCONNECTED:
                    rch.onDisconnect();
                    break;
            }
        }
    }

    @Override
    public void notifyHandlersOfError(Exception e) {
        for (RelayConnectionHandler rch : connectionHandlers) {
            rch.onError(e.getMessage(), e);
        }
    }

    /* These definitions are only valid on Linux */
    private static final int IPPROTO_TCP = 6;
    private static final int TCP_KEEPIDLE = 4;
    private static final int TCP_KEEPINTVL = 5;
    private static final int TCP_KEEPCNT = 6;

    private static Object os;
    private static Method setsockoptInt;

    static {
        try {
            String osName =  System.getProperty("os.name");
            if (osName.equals("Linux")) {
                Class libcore = Class.forName("libcore.io.Libcore");
                Field osField = libcore.getDeclaredField("os");

                os = osField.get(null);
                setsockoptInt = osField.getType().getDeclaredMethod("setsockoptInt", FileDescriptor.class, int.class, int.class, int.class);
            } else {
                logger.warn("OS={}", osName);
            }
        } catch (Throwable t) {
            logger.warn("Unable to get setsockoptInt()", t);
        }
    }

    private static FileDescriptor getFileDescriptor(SocketChannel channel) {
        try {
            Method getFileDescriptor = channel.getClass().getDeclaredMethod("getFD");
            return (FileDescriptor)getFileDescriptor.invoke(channel);

        } catch (Throwable t) {
            logger.warn("Unable to get FileDescriptor using getFD()", t);
        }

        logger.warn("Unable to get FileDescriptor for {}", channel.getClass());
        return null;
    }

    protected void configureKeepAlive(SocketChannel channel) {
        try {
            sock.setKeepAlive(true);
        } catch (SocketException e) {
            logger.error("Unable to enable TCP keepalive on socket {}", sock, e);
            return;
        }

        if (setsockoptInt == null)
            return;

        FileDescriptor fd = getFileDescriptor(channel);
        if (fd == null)
            return;

        try {
            setsockoptInt.invoke(os, fd, IPPROTO_TCP, TCP_KEEPIDLE, 60);
            setsockoptInt.invoke(os, fd, IPPROTO_TCP, TCP_KEEPINTVL, 15);
            setsockoptInt.invoke(os, fd, IPPROTO_TCP, TCP_KEEPCNT, 12);
            logger.info("Configured TCP keepalive on socket {}", sock);
        } catch (Throwable t) {
            logger.error("Unable to configure TCP keepalive on socket {}", sock, t);
        }
    }
}
