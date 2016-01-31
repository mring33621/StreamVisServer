package com.mattring.streamvisserver;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.nats.Connection;

/**
 *
 * @author mring
 */
public class Traders implements Runnable {


    private final Map<String, TradingState> traderStateMap = new ConcurrentHashMap<>();
    private org.nats.Connection natsConnection;
    private Integer recoSID;
    private Integer execSID;
    private volatile boolean running;
    private final BlockingQueue<String> pnlUpdateQueue;

    public Traders(BlockingQueue<String> pnlUpdateQueue, String... names) {
        this.pnlUpdateQueue = pnlUpdateQueue;
        for (String name : names) {
            traderStateMap.put(name, new TradingState(name));
        }
    }

    @Override
    public void run() {
        running = false;
        try {
            natsConnection = Connection.connect(new Properties());
            recoSID = natsConnection.subscribe("recos", new org.nats.MsgHandler() {
                @Override
                public void execute(String msg) {
                    handleRecommendation(msg);
                }
            });
            execSID = natsConnection.subscribe("execs", new org.nats.MsgHandler() {
                @Override
                public void execute(String msg) {
                    handleExecution(msg);
                }
            });
            running = true;
        } catch (IOException | InterruptedException ex) {
            throw new RuntimeException(ex);
        }
        while (running) {
            Thread.yield();
        }
    }

    public void handleRecommendation(String reco) {
        // reco: [RB or RS]|Sym|recoPrice
        if (reco.startsWith(MsgPrefix.RB.name()) || reco.startsWith(MsgPrefix.RS.name())) {
            final String[] recoParts = reco.split("\\|");
            traderStateMap
                    .values()
                    .parallelStream()
                    .map(ts -> ts.placePossibleOrder(recoParts))
                    .filter(po -> po.isPresent())
                    .map(po -> po.get())
                    .forEach(this::sendOrder);
        }
    }

    void sendOrder(String order) {
        try {
            natsConnection.publish("orders", order);
        } catch (IOException ex) {
            Logger.getLogger(Traders.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    public void handleExecution(String exec) {
        if (exec.startsWith(MsgPrefix.EB.name()) || exec.startsWith(MsgPrefix.ES.name())) {
            // exec: [EB or ES]|Sym|Trader|Price
            final String[] execParts = exec.split("\\|");
            final String trader = execParts[2];
            Optional<Double> pnlUpdate = traderStateMap.get(trader).handleExecution(execParts);
            if (pnlUpdate.isPresent()) {
                // pnl: PL|Trader|amt
                final String update = String.format("%s|%s|%.2f", MsgPrefix.PL.name(), trader, pnlUpdate.get());
                sendPnLUpdate(update);
            }
        }

    }

    void sendPnLUpdate(String pnlUpdate) {
        try {
            // stay local, no NATs needed
            pnlUpdateQueue.put(pnlUpdate);
        } catch (InterruptedException ex) {
            // don't care
        }
    }

}
