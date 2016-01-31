package com.mattring.streamvisserver;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Matthew
 */
@WebSocket
public class TradingVisDataSource implements Runnable {

    private final Logger log = LoggerFactory.getLogger(TradingVisDataSource.class);
    private Session session;
    private volatile boolean running;
    private final BlockingQueue<String> tickAndPLQueue;

    public TradingVisDataSource() {
        tickAndPLQueue = ServerMain.tickAndPLQueue;
    }

    private void send(String s) {
        try {
            session.getRemote().sendString(s);
        } catch (IOException ioex) {
            log.error("Websocket IO Issue", ioex);
        }
    }

    @Override
    public void run() {
        System.err.println("running");
        while (running) {
            List<String> items = new LinkedList<>();
            tickAndPLQueue.drainTo(items, 100);
            items.forEach(item -> send(item));
            Thread.yield();
        }
        System.err.println("stopping");
    }

    @OnWebSocketConnect
    public void connected(Session session) {
        if (!this.running) {
            this.session = session;
            this.running = true;
            System.err.println("connected");
            Thread t = new Thread(this, "TradingServer-Websocket");
            t.setDaemon(true);
            t.start();
        }
    }

    @OnWebSocketClose
    public void closed(Session session, int statusCode, String reason) {
        if (this.running) {
            this.running = false;
            this.session = null;
            System.err.println("disconnected");
        }
    }

    @OnWebSocketMessage
    public void message(Session session, String message) throws IOException {
        System.out.println("Got: " + message);   // Print message
    }

}
