package com.mattring.streamvisserver;

import com.mattring.marketdata.Point;
import com.mattring.marketdata.PointsFn;
import com.mattring.marketdata.PointsFnFromCsv;
import com.mattring.marketdata.PointsFnFromDb;
import com.mattring.stockmarketticker.Tick;
import com.mattring.stockmarketticker.fake.BiasedRandomWalkTickSupplier;
import com.mattring.streams.InterleavedSupplier;
import com.mattring.streams.InterleavedSuppliers;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.nats.Connection;
import static spark.Spark.*;

public class ServerMain {

    static final LinkedBlockingQueue<String> tickAndPLQueue = new LinkedBlockingQueue<>();
    static volatile boolean running = true;
    static List<String> symbolsOfInterest;
    static List<List<Point>> eodPointsOfInterest;

    private static final Runnable fakeTicker = () -> {
        while (running) {
            String owner = Math.random() > 0.5d ? "TQQQ" : "FAS";
            double price = Math.random() * 100d;
            String exchange = Math.random() > 0.5d ? "FASDAQ" : "MATS";
            String tick = String.format("T|%s|%.2f|%s", owner, price, exchange);
            try {
                tickAndPLQueue.put(tick);
                Thread.sleep(300L);
            } catch (InterruptedException intex) {
                // don't care
            }
            Thread.yield();
        }
    };

    private static final Runnable realTicker = () -> {

        final int numTicksPerDay = 10;
        final String[] exchanges = new String[]{"NJSE", "FASDAQ", "MATS"};
        final List<List<Point>> eodPoints = new ArrayList<>(eodPointsOfInterest);
        Connection natsConnection = null;
        try {
            natsConnection = Connection.connect(new Properties());
        } catch (IOException | InterruptedException ex) {
            throw new RuntimeException(ex);
        }

        InterleavedSupplier<Point> eodData = new InterleavedSupplier<>(eodPoints);
        Point curr = null;
        while (true) {
            List<Point> day = new ArrayList<>();
            if (curr != null) {
                day.add(curr);
            }
            Point prev = null;
            curr = null;
            while (true) {
                curr = eodData.get();
                if (curr == null) {
                    break;
                } else if (prev == null || curr.getDate() == prev.getDate()) {
                    day.add(curr);
                    prev = curr;
                } else {
                    break;
                }
            }
            if (!day.isEmpty()) {
                final PointMapper pm = new PointMapper();
                List<Supplier<Tick>> tickSuppliers = day.stream().map(pm).map(ep -> {
                    System.out.println(ep);
                    return new BiasedRandomWalkTickSupplier(
                            ep, numTicksPerDay, new Random(), exchanges);
                }).collect(Collectors.toList());
                System.out.println("tickSuppliers.len="+tickSuppliers.size());
                InterleavedSuppliers<Tick> ticks = new InterleavedSuppliers<>(tickSuppliers);
                while (true) {
                    Tick tick = ticks.get();
                    if (tick != null) {
                        System.out.println(tick);
                        // tick: T|sym|last|exchange
                        String tickMsg = String.format("%s|%s|%.2f|%s", MsgPrefix.T.name(), tick.symbol, tick.last, tick.exchange);
                        try {
                            tickAndPLQueue.put(tickMsg);
                            try {
                                natsConnection.publish("ticks", tickMsg);
                            } catch (IOException ex) {
                                throw new RuntimeException(ex);
                            }
                            Thread.sleep(100L);
                        } catch (InterruptedException intex) {
                            // don't care
                        }
                        Thread.yield();
                    } else {
                        break;
                    }
                }
                
            } else {
                break;
            }
        }
    };

    private static final Runnable fakePnLUpdater = () -> {
        while (running) {
            String owner = Math.random() > 0.5d ? "Emily" : "Maurice";
            double pnl = Math.random() * 100d;
            String pnlUpdate = String.format("PL|%s|%.2f", owner, pnl);
            try {
                tickAndPLQueue.put(pnlUpdate);
                Thread.sleep(300L);
            } catch (InterruptedException intex) {
                // don't care
            }
            Thread.yield();
        }
    };

    public static void main(String[] args) throws IOException {

        List<String> tradeableSyms
                = Files.readAllLines(Paths.get("/var/data/tradeable-syms.txt"));
        Collections.shuffle(tradeableSyms);
        symbolsOfInterest = tradeableSyms.subList(0, 5);
        System.out.println("symbolsOfInterest=" + symbolsOfInterest);

        final PointsFn pfn = new PointsFnFromCsv("/var/data");
        final int startDate = 20150101;
        eodPointsOfInterest
                = symbolsOfInterest.stream()
                .map(s -> pfn.getAllPointsForSym(s, startDate))
                .collect(Collectors.toList());

        // Spark Java
        webSocket("/marketdata", TradingVisDataSource.class);
        externalStaticFileLocation("/var/www/public");
        get("/hello", (req, res) -> {
            return "Hello World!";
        });

        // Market Data
        Thread t1 = new Thread(realTicker, "Ticker");
        t1.setDaemon(true);

//        // PnL Updates
//        Thread t2 = new Thread(fakePnLUpdater, "PnL_Updater");
//        t2.setDaemon(true);
        
        // Traders (covers PnL updates)
        Traders traders = new Traders(tickAndPLQueue, "Emily", "Maurice", "Veena");
        Thread t3 = new Thread(traders, "Traders");

        t1.start();
//        t2.start();
        t3.start();
    }

}
