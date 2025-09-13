package com.yourorg.sagin.net;

import java.util.List;
import java.util.Map;

public class LinkPatchApplier {

    /** 持有可变的网络画像；默认给一个 CSV 版实现 */
    public final NetworkProfile profile;

    /** 默认：内部自己 new 一个具体实现 */
    public LinkPatchApplier() {
        this(new NetworkProfileCsv());
    }

    /** 可选：从外面传入（例如在 Broker 里复用同一个 profile 实例） */
    public LinkPatchApplier(NetworkProfile profile) {
        this.profile = profile;
    }

    @SuppressWarnings("unchecked")
    public void applyFromAction(Map<String, Object> action) {
        if (action == null) return;
        Object lp = action.get("link_patch");
        if (!(lp instanceof List<?>)) return;

        for (Map<String, Object> e : (List<Map<String, Object>>) lp) {
            String src = String.valueOf(e.get("src"));
            String dst = String.valueOf(e.get("dst"));

            double rtt  = toD(e.get("rtt_ms"),       25.0);
            double up   = toD(e.get("bw_up_mbps"),  300.0);
            double dn   = toD(e.get("bw_down_mbps"),300.0);
            double loss = toD(e.get("loss"),          0.01);

            // up_flag / up: 1/true 表示可用；0/false 表示断链
            boolean ok;
            Object upFlag = e.get("up");
            if (upFlag == null) ok = true;
            else {
                String s = String.valueOf(upFlag).trim().toLowerCase();
                ok = !(s.equals("0") || s.equals("false"));
            }

            // 支持 t_start 或 t；缺省为 0
            double t = e.containsKey("t_start") ? toD(e.get("t_start"), 0.0)
                    : toD(e.get("t"),       0.0);

            profile.put(src, dst, t, new LinkMetrics(rtt, up, dn, loss, ok));
        }
    }

    private static double toD(Object o, double def) {
        if (o == null) return def;
        try { return Double.parseDouble(o.toString()); }
        catch (Exception ignore) { return def; }
    }
}
