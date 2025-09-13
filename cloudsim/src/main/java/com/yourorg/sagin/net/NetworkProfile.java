package com.yourorg.sagin.net;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网络画像（支持时间轴 & 上/下行 & 丢包 & 可用性 & 并发共享带宽）。
 * 兼容你原来的 put/get/calcUpSeconds/calcDownSeconds，但也支持按时间查询。
 */
public abstract class NetworkProfile {

    public abstract void put(int u, int v, double tStart, LinkMetrics m);

    public abstract LinkMetrics link(int u, int v, double t);

    /** 键：src->dst */
    private static final class Key {
        final String s, d;
        Key(String s, String d){ this.s = s; this.d = d; }
        @Override public int hashCode(){ return Objects.hash(s,d); }
        @Override public boolean equals(Object o){ return (o instanceof Key k) && k.s.equals(s) && k.d.equals(d); }
    }

    /** 每条边是一条时间线：t_start → LinkMetrics */
    private final Map<Key, NavigableMap<Double, LinkMetrics>> table = new ConcurrentHashMap<>();

    /** 默认链路（没命中时兜底） */
    private final LinkMetrics DEFAULT = new LinkMetrics(40, 50, 50, 0.01, true);

    /** 兼容旧 API：不带时间的 put，相当于 t=0 覆盖 */
    public void put(String src, String dst, LinkMetrics m){ put(src, dst, 0.0, m); }

    /** 新 API：带时间轴的 put，在 tStart 之后生效（直到下一段覆盖） */
    public void put(String src, String dst, double tStart, LinkMetrics m){
        table.computeIfAbsent(new Key(src,dst), k -> new TreeMap<>()).put(tStart, m);
    }

    /** 兼容旧 API：不带时间的 get（取 <= +∞ 的最后一段） */
    public LinkMetrics get(String src, String dst){
        return getAt(src, dst, Double.POSITIVE_INFINITY);
    }

    /** 带时间的 get：返回 t 时刻的 LinkMetrics（找 floorEntry） */
    public LinkMetrics getAt(String src, String dst, double t){
        var map = table.get(new Key(src,dst));
        if (map == null || map.isEmpty()) return DEFAULT;
        var e = map.floorEntry(t);
        return (e != null ? e.getValue() : map.firstEntry().getValue());
    }

    /* =============== 传输时间估算（保持原方法签名，同时提供时间/并发重载） =============== */

    /** 旧：上行（bytes）——不带时间，不带并发（=1） */
    public double calcUpSeconds(long bytes, String src, String dst){
        return calcUpSeconds(bytes, src, dst, Double.POSITIVE_INFINITY, 1);
    }

    /** 旧：下行（bytes）——不带时间，不带并发（=1） */
    public double calcDownSeconds(long bytes, String src, String dst){
        return calcDownSeconds(bytes, src, dst, Double.POSITIVE_INFINITY, 1);
    }

    /** 新：上行（bytes，时间 t，并发 flows） */
    public double calcUpSeconds(long bytes, String src, String dst, double t, int flows){
        LinkMetrics m = getAt(src, dst, t);
        if (!m.available()) return Double.POSITIVE_INFINITY;
        double effMbps = Math.max(m.effUpMbps(), 1e-6) / Math.max(flows, 1);
        double txSec   = (bytes * 8.0) / (effMbps * 1e6);
        double propSec = m.getRttMs()/2000.0;          // 半个 RTT
        return txSec + propSec;
    }

    /** 新：下行（bytes，时间 t，并发 flows） */
    public double calcDownSeconds(long bytes, String src, String dst, double t, int flows){
        LinkMetrics m = getAt(src, dst, t);
        if (!m.available()) return Double.POSITIVE_INFINITY;
        double effMbps = Math.max(m.effDownMbps(), 1e-6) / Math.max(flows, 1);
        double txSec   = (bytes * 8.0) / (effMbps * 1e6);
        double propSec = m.getRttMs()/2000.0;
        return txSec + propSec;
    }

    /* =============== 便捷装载：CSV（可选） =============== */
    /**
     * CSV 列格式：t_start, src, dst, rtt_ms, up_mbps, down_mbps, loss, up_flag
     * 例：
     * 0, 101, 201, 25, 300, 300, 0.02, 1
     * 60,101, 201, 35, 150, 150, 0.05, 1
     * 75,101, 201,  0,   0,   0, 1.00, 0   # 链路断开
     */
    public static NetworkProfile loadCsv(Path csv) throws IOException {
        NetworkProfile np = new NetworkProfileCsv();
        try (var lines = Files.lines(csv)) {
            lines.filter(s -> !s.isBlank() && !s.startsWith("#")).forEach(s -> {
                try {
                    String[] f = s.split(",");
                    int n = f.length;

                    if (n < 5) {
                        // 列数太少，忽略该行
                        System.err.println("[NetworkProfile] skip line (too few cols): " + s);
                        return;
                    }

                    double t   = Double.parseDouble(f[0].trim());
                    String u   = f[1].trim();
                    String v   = f[2].trim();
                    double rtt = Double.parseDouble(f[3].trim());

                    double upMbps, dnMbps, loss = 0.0;
                    boolean ok = true;

                    if (n >= 8) {
                        // 新格式: t,u,v,rtt,up_mbps,down_mbps,loss,up_flag
                        upMbps = Double.parseDouble(f[4].trim());
                        dnMbps = Double.parseDouble(f[5].trim());
                        loss   = Double.parseDouble(f[6].trim());
                        ok     = !"0".equals(f[7].trim());
                    } else if (n >= 6) {
                        // 旧格式A: t,u,v,rtt,bw_mbps,loss  → 上下行对称
                        upMbps = dnMbps = Double.parseDouble(f[4].trim());
                        loss   = Double.parseDouble(f[5].trim());
                    } else {
                        // 旧格式B: t,u,v,rtt,bw_mbps      → 上下行对称 + loss=0 + 可用
                        upMbps = dnMbps = Double.parseDouble(f[4].trim());
                    }

                    np.put(u, v, t, new LinkMetrics(rtt, upMbps, dnMbps, loss, ok));
                } catch (Exception ex) {
                    // 单行容错，不影响整体加载
                    System.err.println("[NetworkProfile] bad line, skip: " + s + "  |  err=" + ex.getMessage());
                }
            });
        }
        return np;
    }

}
