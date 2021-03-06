/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.as.communication;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jboss.logging.Logger;

/**
 * Manages a server socket and hands off incoming connections to a {@link SocketHandler}
 *
 * @author <a href="kabir.khan@jboss.com">Kabir Khan</a>
 * @version $Revision: 1.1 $
 */
public final class SocketListener {

    private final Logger log = Logger.getLogger(SocketListener.class);
    private final String name;
    private final SocketHandler socketHandler;
    private final InetAddress address;
    private final int port;
    private final int backlog;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private volatile ServerSocketListener serverSocketListener;

    private SocketListener(String name, SocketHandler socketHandler, InetAddress address, int port, int backlog) {
        if (name == null) {
            throw new IllegalArgumentException("Null name");
        }
        if (socketHandler == null) {
            throw new IllegalArgumentException("Null socketHandler");
        }
        if (address == null) {
            throw new IllegalArgumentException("Null address");
        }
        this.name = name;
        this.socketHandler = socketHandler;
        this.address = address;
        this.port = port;
        this.backlog = backlog;
    }

    public static SocketListener createSocketListener(String name, SocketHandler socketHandler, InetAddress address, int port, int backlog) throws IOException {
        return new SocketListener(name, socketHandler, address, port, backlog);
    }

    /**
     * Start listening for requests
     */
    public void start() throws IOException{
        serverSocketListener = new ServerSocketListener();
        Thread t = new Thread(serverSocketListener, "Socket Listener " + name);
        t.start();
    }

    public void shutdown(){
        executor.shutdown();
        if (serverSocketListener != null) {
            serverSocketListener.shutdown();
        }
    }

    public InetAddress getAddress() {
        ServerSocketListener listener = this.serverSocketListener;
        if (listener == null)
            throw new IllegalArgumentException(name + " not started ");
        return listener.getAddress();
    }

    public Integer getPort() {
        ServerSocketListener listener = this.serverSocketListener;
        if (listener == null)
            throw new IllegalArgumentException(name + " not started");
        return listener.getPort();
    }


    /**
     * Contains the server socket that is listening for requests from client processes.
     * When a request is accepted, the new socket is handed off to ProcessAcceptorTask
     * which is executed in a separate thread.
     *
     */
    private class ServerSocketListener implements Runnable {

        private final ServerSocket serverSocket;
        private final AtomicBoolean shutdown = new AtomicBoolean(false);

        private ServerSocketListener() throws IOException{
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(address, port), backlog);
            } catch (Exception e) {
                // AutoGenerated
                throw new RuntimeException("Error starting listener '" + name + "' on " + address + ":" + port, e);
            }
        }

        int getPort() {
            return serverSocket.getLocalPort();
        }

        InetAddress getAddress() {
            return serverSocket.getInetAddress();
        }

        @Override
        public void run() {
            log.infof("%s listening on %d", name, serverSocket.getLocalPort());
                try {
                    while (!shutdown.get()) {
                        Socket socket = serverSocket.accept();
                        executor.execute(new AcceptorTask(socket));
                    }
                } catch (SocketException e) {
                    log.infof("%s server socket was closed", name);
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    shutdown();
                }
        }

        private void shutdown() {
            if (shutdown.getAndSet(true))
                return;
            try {
                log.infof("%s closing server socket %d", name, getPort());
                serverSocket.close();
                log.infof("%s closed server socket %d", name, getPort());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Takes a newly created socket and listens for the first request.
     * If the request is valid, the socket is handed off to the ManagedProcess
     * that has that name.
     * <p>
     * If the request is not valid, or there is no ManagedProcess with
     * that name the socket is closed.
     */
    private class AcceptorTask implements Runnable {
        private final Socket socket;

        public AcceptorTask(Socket socket) {
            this.socket = socket;
            log.debugf("%s got new connection on %d", name, socket.getLocalPort());
        }

        @Override
        public void run() {
            boolean ok = false;
            try {
                log.infof("%s checking for socket handler %b", name, socketHandler != null);
                if (socketHandler != null) {
                    socketHandler.initializeConnection(socket);
                }
                ok = true;
            } catch (InitialSocketRequestException e) {
                log.errorf("%s process acceptor: Invalid initial request: %s", name, e.getMessage());
            } catch (IOException e) {
                log.errorf("%s process acceptor: error reading from socket: %s", name, e.getMessage());
            }
            finally {
                if (!ok){
                    try {
                        socket.close();
                    } catch (IOException ignore) {
                    }
                }
            }
        }
    }

    public interface SocketHandler {

        /**
         * Used to verify the initial request of the socket and to start a thread to
         * read data from the socket
         *
         * @param socket
         * @throws IOException if there were some problems with the socket
         * @throws InitialSocketRequestException if there were some problems with the initial request
         */
        void initializeConnection(Socket socket) throws IOException, InitialSocketRequestException;

    }
}
