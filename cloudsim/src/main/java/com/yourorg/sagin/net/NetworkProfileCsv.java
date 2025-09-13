package com.yourorg.sagin.net;

import java.nio.file.*;
import java.util.*;

public class NetworkProfileCsv extends NetworkProfile {
    private static class Key { final int u,v; Key(int u,int v){this.u=u;this.v=v;}
        public int hashCode(){return Objects.hash(u,v);}
        public boolean equals(Object o){return o instanceof Key k && k.u==u && k.v==v;}
    }
    /** 每对(u,v)按时间升序的片段列表 */
    private final Map<Key, List<double[]>> series = new HashMap<>();

    public static NetworkProfileCsv load(Path csv) throws Exception {
        var np = new NetworkProfileCsv();
        for (var line: Files.readAllLines(csv)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            var f = line.split(",");
            double t = Double.parseDouble(f[0].trim());
            int u = Integer.parseInt(f[1].trim());
            int v = Integer.parseInt(f[2].trim());
            double rtt = Double.parseDouble(f[3].trim());
            double bw  = Double.parseDouble(f[4].trim());
            double loss= Double.parseDouble(f[5].trim());
            var key = new Key(u,v);
            np.series.computeIfAbsent(key,k->new ArrayList<>()).add(new double[]{t,rtt,bw,loss});
        }
        np.series.values().forEach(lst -> lst.sort(Comparator.comparingDouble(a->a[0])));
        return np;
    }

    @Override
    public LinkMetrics link(int u, int v, double t) {
        var lst = series.get(new Key(u, v));
        if (lst == null || lst.isEmpty()) return null;

        int i = Collections.binarySearch(lst, new double[]{t,0,0,0,0,0},
                Comparator.comparingDouble(a -> a[0]));
        if (i < 0) i = -i - 2;
        if (i < 0) i = 0;

        double[] a = lst.get(i);     // 统一为: [t, rtt, up, dn, loss, upFlag]
        return new LinkMetrics(
                a[1],        // rttMs
                a[2],        // bwUpMbps
                a[3],        // bwDownMbps
                a[4],        // loss
                a.length >= 6 ? a[5] > 0.5 : true  // upFlag
        );
    }

}

