/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.mattring.streamvisserver;

import com.mattring.marketdata.Point;
import com.mattring.stockmarketticker.EodPoint;
import java.util.function.Function;

/**
 *
 * @author mring
 */
public class PointMapper implements Function<Point, EodPoint> {

    @Override
    public EodPoint apply(Point p) {
        EodPoint ep = new EodPoint(p.getSym(), p.getDate(), p.getOpen(), p.getHigh(), p.getLow(), p.getClose(), p.getVol());
        return ep;
    }
    
}
