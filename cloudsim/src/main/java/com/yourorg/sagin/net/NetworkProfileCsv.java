package com.yourorg.sagin.net;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 从 CSV 加载网络画像，并支持：
 *  1) link(u,v,t) 按时间查询（最近不超过 t 的一条）
 *  2) 运行期覆盖 put(u,v, metrics) 作为“当前快照”
 *
 * CSV 列：t_start, src, dst, rtt_ms, up_mbps, down_mbps, loss, up_flag
 * 例：
 * 0, 101, 201, 35,  300,  900, 0.01, 1
 * 5, 101, 201, 40,  250,  700, 0.02, 1
 * 0, 201, 101, 50,  100,  100, 0.10, 0
 */
public class NetworkProfileCsv extends NetworkProfile {

    /** 有向边 (u,v) 的键（int 版本，与你的抽象类一致） */
    private static final class Key {
        final int u, v;
        Key(int u, int v){ this.u = u; this.v = v; }
        @Override public boolean equals(Object o){
            if(this == o) return true;
            if(!(o instanceof Key k)) return false;
            return u == k.u && v == k.v;
        }
        @Override public int hashCode(){ return 31 * u + v; }
    }

    /** 时间序列：每条 {t, rtt, up, down, loss, upFlag(1/0)}，按 t 升序保存 */
    private final Map<Key, List<double[]>> series = new HashMap<>();

    /** 运行期覆盖的“当前快照” */
    private final Map<Key, LinkMetrics> overrides = new HashMap<>();

    /* ------------------ 实现抽象方法 ------------------ */

    @Override
    public LinkMetrics link(int u, int v, double t){
        // 1) 运行期覆盖优先
        LinkMetrics ov = overrides.get(new Key(u, v));
        if(ov != null) return ov;

        // 2) 时间序列查询：最近不超过 t 的一条
        List<double[]> lst = series.get(new Key(u, v));
        if(lst == null || lst.isEmpty()) return defaults();

        int i = Collections.binarySearch(
                lst,
                new double[]{t,0,0,0,0,1},
                Comparator.comparingDouble(a -> a[0])
        );
        if(i < 0) i = -i - 2;     // 插入点的左侧 = 最后一个 <= t
        if(i < 0) return defaults();

        double[] a = lst.get(i);
        double rtt  = a[1];
        double up   = a[2];
        double down = a[3];
        double loss = a[4];
        boolean ok  = a.length > 5 ? a[5] >= 0.5 : true;
        return new LinkMetrics(rtt, up, down, loss, ok);
    }

    /** 按时间写入（实现抽象方法）：把该时间点的指标加入时间序列 */
    @Override
    public void put(int src, int dst, double t, LinkMetrics m){
        final Key k = new Key(src, dst);
        final double[] row = new double[]{
                t,
                m.getRttMs(),
                m.getBwUpMbps(),
                m.getBwDownMbps(),
                m.getLoss(),
                m.isUp() ? 1.0 : 0.0
        };
        series.computeIfAbsent(k, __ -> new ArrayList<>()).add(row);
        // 为防止调用顺序无序，这里保持 series 有序性（量小影响可忽略；量大可延迟到 load 完成后 sort）
        series.get(k).sort(Comparator.comparingDouble(a -> a[0]));
    }

    /** 运行期覆盖写入：把“当前快照”打补丁（不带时间） */
    public void put(int src, int dst, LinkMetrics m){
        overrides.put(new Key(src, dst), m);
    }

    /* ------------------ CSV 读入 ------------------ */

    public static NetworkProfileCsv loadCsv(Path csv) throws IOException {
        NetworkProfileCsv np = new NetworkProfileCsv();

        try(var lines = Files.lines(csv)){
            lines.map(String::trim)
                    .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                    .forEach(s -> {
                        String[] f = s.split(",");
                        if(f.length < 7) return;      // 列不足跳过

                        double t  = parseDouble(f[0], 0.0);
                        int src   = parseInt(f[1], 0);
                        int dst   = parseInt(f[2], 0);
                        double r  = parseDouble(f[3], 0.0);
                        double up = parseDouble(f[4], 0.0);
                        double dn = parseDouble(f[5], 0.0);
                        double ls = parseDouble(f[6], 0.0);
                        boolean ok = (f.length >= 8) ? !"0".equals(f[7].trim()) : true;

                        np.put(src, dst, t, new LinkMetrics(r, up, dn, ls, ok));
                    });
        }
        return np;
    }

    /* ------------------ 小工具 ------------------ */

    private static double parseDouble(String s, double def){
        try { return Double.parseDouble(s.trim()); }
        catch (Exception ignore){ return def; }
    }

    private static int parseInt(String s, int def){
        try { return Integer.parseInt(s.trim()); }
        catch (Exception ignore){ return def; }
    }

    private static LinkMetrics defaults(){
        // 兜底：不可用、0 带宽、0 RTT/LOSS
        return new LinkMetrics(0.0, 0.0, 0.0, 0.0, false);
    }
}
