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

package org.jboss.as.process;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jboss.as.communication.InitialSocketRequestException;
import org.jboss.as.communication.SocketConnection;
import org.jboss.as.communication.SocketListener;
import org.jboss.as.communication.SocketListener.SocketHandler;
import org.jboss.as.process.ManagedProcess.ProcessHandler;
import org.jboss.as.process.ManagedProcess.RealProcessHandler;
import org.jboss.as.process.ManagedProcess.StopProcessListener;
import org.jboss.logging.Logger;

/**
 * Process manager main entry point.  The thin process manager process is implemented here.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
public class ProcessManagerMaster implements ProcessOutputStreamHandler.Master{

    public static final String SERVER_MANAGER_PROCESS_NAME = "ServerManager";

    private final SocketListener socketListener;

    private final Logger log = Logger.getLogger(ProcessManagerMaster.class);

    private final Map<String, ManagedProcess> processes = new HashMap<String, ManagedProcess>();

    private final AtomicBoolean shutdown = new AtomicBoolean();

    private final ProcessHandlerFactory processHandlerFactory;

    protected ProcessManagerMaster(InetAddress addr, int port) throws IOException{
        this(null, addr, port);
    }

    protected ProcessManagerMaster(ProcessHandlerFactory processHandlerFactory, InetAddress addr, int port) throws IOException {
        socketListener = SocketListener.createSocketListener("PM", new ProcessAcceptor(), addr, port, 20);
        this.processHandlerFactory = processHandlerFactory == null ? new RealProcessHandlerFactory() : processHandlerFactory;
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {

            @Override
            public void run() {
                shutdown();
            }
        }, "Shutdown Hook"));
    }

    public static void main(String[] args) throws Exception{
        ParsedArgs parsedArgs = ParsedArgs.parse(args);
        if (parsedArgs == null) {
            return;
        }
        final ProcessManagerMaster master = new ProcessManagerMaster(parsedArgs.pmAddress, parsedArgs.pmPort);
        master.start();

        List<String> command = new ArrayList<String>(parsedArgs.command);
        command.add(CommandLineConstants.INTERPROCESS_PM_ADDRESS);
        command.add(master.getInetAddress().getHostAddress());
        command.add(CommandLineConstants.INTERPROCESS_PM_PORT);
        command.add(String.valueOf(master.getPort()));
        command.add(CommandLineConstants.INTERPROCESS_NAME);
        command.add(SERVER_MANAGER_PROCESS_NAME);
        command.add(CommandLineConstants.INTERPROCESS_SM_ADDRESS);
        command.add(parsedArgs.smAddress.getHostAddress());
        command.add(CommandLineConstants.INTERPROCESS_SM_PORT);
        command.add(String.valueOf(parsedArgs.smPort));

        master.addProcess(SERVER_MANAGER_PROCESS_NAME, command, System.getenv(), parsedArgs.workingDir, RespawnPolicy.DefaultRespawnPolicy.INSTANCE);
        master.startProcess(SERVER_MANAGER_PROCESS_NAME);
    }

    protected synchronized void start() {
        try {
            socketListener.start();
        } catch (IOException e) {
            // AutoGenerated
            throw new RuntimeException(e);
        }
    }

    protected void shutdown() {
        boolean isShutdown = shutdown.getAndSet(true);
        if (isShutdown)
            return;

        log.info("Initiating shutdown of ProcessManager");

        ManagedProcess serverManager = null;
        synchronized (processes) {
            serverManager = processes.get(SERVER_MANAGER_PROCESS_NAME);
        }
        if (serverManager != null) {
            try {
                log.info("Stopping ServerManager");
                serverManager.stop();
            } catch (IOException e) {
                log.error("Error sending SHUTDOWN to ServerManager");
            }
        }

        synchronized (processes) {
            for (ManagedProcess proc : processes.values()) {
                try {
                    log.info("Stopping " + proc.getProcessName());
                    proc.stop();
                } catch (IOException e) {
                    log.error("Error sending SHUTDOWN to " + proc.getProcessName());
                }
            }
            processes.clear();
        }

        socketListener.shutdown();
        log.info("Shutdown ProcessManager");
    }

    public boolean isShutdown() {
        return shutdown.get();
    }

    ProcessHandlerFactory getProcessHandlerFactory() {
        return processHandlerFactory;
    }

    public InetAddress getInetAddress() {
        return socketListener.getAddress();
    }

    public Integer getPort() {
        return socketListener.getPort();
    }

    public void addProcess(final String processName, final List<String> command, final Map<String, String> env, final String workingDirectory) {
        addProcess(processName, command, env, workingDirectory, null);
    }

    public void addProcess(final String processName, final List<String> command, final Map<String, String> env, final String workingDirectory, RespawnPolicy respawnPolicy) {
        if (shutdown.get()) {
            return;
        }
        final Map<String, ManagedProcess> processes = this.processes;
        synchronized (processes) {
            if (processes.containsKey(processName)) {
                log.debugf("already have process %s", processName);
                // ignore
                return;
            }

            final ManagedProcess process = new ManagedProcess(this, processName, command, env, workingDirectory, respawnPolicy);
            processes.put(processName, process);
        }
    }

    public void startProcess(final String processName) {
        if (shutdown.get()) {
            return;
        }
        final Map<String, ManagedProcess> processes = this.processes;
        synchronized (processes) {
            final ManagedProcess process = processes.get(processName);
            if (process == null) {
                // ignore
                return;
            }
            try {
                process.start();
            } catch (IOException e) {
                // todo log it
            }
        }
    }

    public void stopProcess(final String processName) {
        if (shutdown.get()) {
            return;
        }
        final Map<String, ManagedProcess> processes = this.processes;
        synchronized (processes) {
            final ManagedProcess process = processes.get(processName);
            if (process == null) {
                // ignore
                return;
            }
            try {
                process.stop();
            } catch (IOException e) {
                // todo log it
            }
        }
    }

    public void removeProcess(final String processName) {
        if (shutdown.get()) {
            return;
        }
        final Map<String, ManagedProcess> processes = this.processes;
        synchronized (processes) {
            final ManagedProcess process = processes.get(processName);
            if (process == null) {
                // ignore
                return;
            }
            synchronized (process) {
                if (process.isStart()) {
                    log.debugf("Ignoring remove request for running process %s", processName);
                    return;
                }
                processes.remove(processName);
            }
        }
    }

    public void sendStdin(final String recipient, final byte[] msg) {
        if (shutdown.get()) {
            return;
        }
        final Map<String, ManagedProcess> processes = this.processes;
        synchronized (processes) {
            final ManagedProcess process = processes.get(recipient);
            if (process == null) {
                // ignore
                return;
            }
            synchronized (process) {
                if (! process.isStart()) {
                    // ignore
                    return;
                }
                try {
                    process.sendStdin( msg);
                } catch (IOException e) {
                    // todo log it
                }
            }
        }

    }

    @Override
    public void downServer(String serverName) {
        if (shutdown.get()) {
            return;
        }

        ManagedProcess serverManagerProcess;
        synchronized (processes) {
            try {
                serverManagerProcess = processes.get(SERVER_MANAGER_PROCESS_NAME);
                if (serverManagerProcess != null)
                    serverManagerProcess.down(serverName);
            } catch (IOException e) {
                log.error("Problem notifying ServerManager of down process " + serverName);
            }
        }
    }

    @Override
    public void reconnectServersToServerManager(String smAddress, String smPort) {
        if (shutdown.get()) {
            return;
        }
        try {
            InetAddress.getLocalHost();
        } catch (UnknownHostException e) {
            log.error("Invalid address " + smAddress, e);
            return;
        }
        int port;
        try {
             port = Integer.valueOf(smPort);
        } catch (NumberFormatException e) {
            log.error("Port should be a number " + smPort);
             return;
        }
        synchronized (processes) {
            for (ManagedProcess process : processes.values()) {
                if (!process.getProcessName().equals(SERVER_MANAGER_PROCESS_NAME)) {
                    try {
                        process.reconnectToServerManager(smAddress, port);
                    } catch (IOException e) {
                        log.warnf("Could not send RECONNECT_SERVER_MANAGER command to " + process.getProcessName());
                    }
                }
            }
        }
    }

    @Override
    public void reconnectProcessToServerManager(String server, String smAddress, String smPort) {
        if (shutdown.get()) {
            return;
        }
        if (SERVER_MANAGER_PROCESS_NAME.equals(server)) {
            return;
        }
        try {
            InetAddress.getLocalHost();
        } catch (UnknownHostException e) {
            log.errorf(e, "Invalid address %s", smAddress);
            return;
        }
        int port;
        try {
             port = Integer.valueOf(smPort);
        } catch (NumberFormatException e) {
            log.errorf("Port should be a number %d", smPort);
             return;
        }

        ManagedProcess process;
        synchronized (processes) {
            process = processes.get(server);
            if (process == null) {
                return;
            }
        }

        try {
            process.reconnectToServerManager(smAddress, port);
        } catch (IOException e) {
            log.warnf("Could not send RECONNECT_SERVER_MANAGER command to " + process.getProcessName());
        }
    }


    void registerStopProcessListener(final String name, final StopProcessListener listener) {
        final Map<String, ManagedProcess> processes = this.processes;
        synchronized (processes) {
            ManagedProcess process = processes.get(name);
            if (process == null)
                return;
            process.registerStopProcessListener(listener);
        }
    }

    List<String> getProcessNames(final boolean onlyStarted) {
        final Map<String, ManagedProcess> processes = this.processes;

        synchronized (processes) {
            if (onlyStarted) {
                List<String> started = new ArrayList<String>();
                for (Map.Entry<String, ManagedProcess> entry : processes.entrySet()) {
                    if (entry.getValue().isStart()) {
                        started.add(entry.getKey());
                    }
                }
                return started;
            }
            else
                return new ArrayList<String>(processes.keySet());
        }
    }

    protected void acceptedConnection(String processName, SocketConnection connection) {
        //Hook for tests
    }

    private static class ParsedArgs {
        final Integer pmPort;
        final InetAddress pmAddress;
        final Integer smPort;
        final InetAddress smAddress;
        final String workingDir;
        final List<String> command;

        ParsedArgs(Integer pmPort, InetAddress pmAddress, Integer smPort, InetAddress smAddress, String workingDir, List<String> command){
            this.pmPort = pmPort;
            this.pmAddress = pmAddress;
            this.smPort = smPort;
            this.smAddress = smAddress;
            this.workingDir = workingDir;
            this.command = command;
        }

        static ParsedArgs parse(String[] args) {
            Integer pmPort = null;
            InetAddress pmAddress = null;
            Integer smPort = null;
            InetAddress smAddress = null;
            String workingDir = args[0];
            int i = 1;
            for (; i < args.length ; i++) {
                String arg = args[i];
                if (arg.startsWith("-")) {
                    if (arg.equals(CommandLineConstants.INTERPROCESS_PM_PORT)) {
                        try {
                            pmPort = Integer.valueOf(args[++i]);
                        } catch (NumberFormatException e) {
                            System.err.printf("Value for %s is not an Integer -- %s\n", CommandLineConstants.INTERPROCESS_PM_PORT, args[i]);
                            return null;
                        }

                    }
                    else if (arg.equals(CommandLineConstants.INTERPROCESS_PM_ADDRESS)) {
                        try {
                            pmAddress = InetAddress.getByName(args[++i]);
                        } catch (UnknownHostException e) {
                            System.err.printf("Value for %s-interprocess-address is not a known host -- %s\n", CommandLineConstants.INTERPROCESS_PM_ADDRESS, args[i]);
                            return null;
                        }
                    } else if (arg.equals(CommandLineConstants.INTERPROCESS_SM_PORT)) {
                        try {
                            smPort = Integer.valueOf(args[++i]);
                        } catch (NumberFormatException e) {
                            System.err.printf("Value for %s is not an Integer -- %s\n", CommandLineConstants.INTERPROCESS_SM_PORT, args[i]);
                            return null;
                        }

                    }
                    else if (arg.equals(CommandLineConstants.INTERPROCESS_SM_ADDRESS)) {
                        try {
                            smAddress = InetAddress.getByName(args[++i]);
                        } catch (UnknownHostException e) {
                            System.err.printf("Value for %s-interprocess-address is not a known host -- %s\n", CommandLineConstants.INTERPROCESS_SM_ADDRESS, args[i]);
                            return null;
                        }
                    }
                }
                else {
                    break;
                }
            }
            List<String> command = new ArrayList<String>(Arrays.asList(args).subList(i, args.length));


            pmPort = pmPort != null ? pmPort : Integer.valueOf(0);
            smPort = smPort != null ? smPort : Integer.valueOf(0);
            if (pmAddress == null) {
                pmAddress = getLocalHost();
                if (pmAddress == null)
                    return null;
            }
            if (smAddress == null) {
                smAddress = getLocalHost();
                if (smAddress == null)
                    return null;
            }
            return new ParsedArgs(pmPort, pmAddress, smPort, smAddress, workingDir, command);
        }

        private static InetAddress getLocalHost() {
            try {
                return InetAddress.getLocalHost();
            } catch (UnknownHostException e) {
                System.err.printf("Could not determine local host");
                return null;
            }
        }
    }

    class ProcessAcceptor implements SocketHandler {

        @Override
        public void initializeConnection(Socket socket) throws IOException, InitialSocketRequestException {
            InputStream in = socket.getInputStream();
            StringBuilder sb = new StringBuilder();

            socket.setSoTimeout(10000);
            Status status;
            String processName;
            try {
                status = StreamUtils.readWord(in, sb);
                if (status != Status.MORE) {
                    throw new InitialSocketRequestException("Process acceptor: received '" + sb.toString() + "' but no more");
                }
                if (!sb.toString().equals("CONNECTED")) {
                    throw new InitialSocketRequestException("Process acceptor: received unknown start command '" + sb.toString() + "'");
                }
                sb = new StringBuilder();
                while (status == Status.MORE) {
                    status = StreamUtils.readWord(in, sb);
                }
                processName = sb.toString();
            } catch (SocketTimeoutException e) {
                throw new InitialSocketRequestException("Process acceptor: did not receive any data on socket within 10 seconds");
            }

            final Map<String, ManagedProcess> processes = ProcessManagerMaster.this.processes;
            ManagedProcess process = null;
            synchronized (processes) {
                process = processes.get(processName);
                if (process == null) {
                    throw new InitialSocketRequestException("Process acceptor: received connect command for unknown process '" + processName + "' (" +  processes.keySet() + ")");
                }
            }
            socket.setSoTimeout(0);
            SocketConnection connection = SocketConnection.accepted(socket);
            process.setSocket(connection);
            acceptedConnection(processName, connection);
        }
    }

    /**
     * Create instances of {@link ProcessHandler}. The default implementation
     * returns an implementation of ProcessHandler that creates real processes.
     * Tests may provide a different implementation
     */
    public interface ProcessHandlerFactory {
        ProcessHandler createHandler();
    }

    /**
     * ProcessHandler implementation that creates real processes.
     */
    private static class RealProcessHandlerFactory implements ProcessHandlerFactory {

        @Override
        public ProcessHandler createHandler() {
            return new RealProcessHandler();
        }
    }
}
