/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.mattring.streamvisserver;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.AtomicDouble;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 *
 * @author mring
 */
class TradingState {
    
    final Set<String> positions = Sets.newConcurrentHashSet();
    final Map<String, Double> costs = Maps.newConcurrentMap();
    final AtomicDouble pnl = new AtomicDouble();
    final String name;

    public TradingState(String name) {
        this.name = name;
    }

    boolean addPosition(String[] recoParts) {
        // reco: [RB or RS]|Sym|recoPrice
        final String sym = recoParts[1];
        // 50/50 chance of going w/ reco if not already have position
        return !positions.contains(sym) && Math.random() > 0.5d;
    }

    boolean sellPosition(String[] recoParts) {
        // reco: [RB or RS]|Sym|recoPrice
        final String sym = recoParts[1];
        boolean goForIt = false;
        if (positions.contains(sym)) {
            final double recoPrice = Double.parseDouble(recoParts[2]);
            final double cost = costs.get(sym);
            if (recoPrice > cost) {
                // in the black
                goForIt = true;
            } else if (Math.random() > 0.5d) {
                // 50/50 chance of being willing to take a recommended loss
                goForIt = true;
            }
        }
        return goForIt;
    }

    public Optional<String> placePossibleOrder(String[] recoParts) {
        // reco: [RB or RS]|Sym|recoPrice
        Optional<String> possibleOrder = Optional.empty();
        final String prefix = recoParts[0];
        final String sym = recoParts[1];
        final String recoPrice = recoParts[2];
        final boolean isContrarian = Math.random() > 0.75d;
        final boolean isBuy = MsgPrefix.RB.name().equals(prefix) && !isContrarian;
        boolean doReco = false;
        String orderPrefix;
        if (isBuy) {
            doReco = addPosition(recoParts);
            orderPrefix = MsgPrefix.OB.name();
        } else {
            doReco = sellPosition(recoParts);
            orderPrefix = MsgPrefix.OS.name();
        }
        if (doReco) {
            // order: [OB or OS]|Sym|Trader|recoPrice
            final String order = String.format("%s|%s|%s|%s", orderPrefix, sym, name, recoPrice);
            possibleOrder = Optional.of(order);
        }
        return possibleOrder;
    }

    public Optional<Double> handleExecution(String[] execParts) {
        Optional<Double> pnlUpdate = Optional.empty();
        // exec: [EB or ES]|Sym|Trader|Price
        final String prefix = execParts[0];
        final String sym = execParts[1];
        final String trader = execParts[2];
        final double price = Double.parseDouble(execParts[3]);
        Preconditions.checkArgument(name.equals(trader), "Execution to Wrong Trader: " + trader);
        if (MsgPrefix.EB.name().equals(prefix)) {
            // BUY EXEC
            // TODO: check if sym already there?
            costs.put(sym, price);
            positions.add(sym);
        } else {
            // SELL EXEC
            // TODO: check if sym not there?
            final double cost = costs.remove(sym);
            positions.remove(sym);
            final double gain = price - cost;
            final double totalPnl = pnl.addAndGet(gain);
            pnlUpdate = Optional.of(totalPnl);
        }
        return pnlUpdate;
    }
    
}
