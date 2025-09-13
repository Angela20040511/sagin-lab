package com.yourorg.sagin.net;

/** 单向/往返都能用；我们提供“有效带宽”辅助方法，考虑丢包和可用性 */
public final class LinkMetrics {
    private final double rttMs;        // RTT (毫秒)
    private final double bwUpMbps;     // 上行原始带宽 (Mbps)
    private final double bwDownMbps;   // 下行原始带宽 (Mbps)
    private final double loss;         // 丢包率 [0,1]
    private final boolean up;          // 链路是否可用

    public LinkMetrics(double rttMs, double bwUpMbps, double bwDownMbps, double loss, boolean up) {
        this.rttMs = rttMs;
        this.bwUpMbps = Math.max(0.0, bwUpMbps);
        this.bwDownMbps = Math.max(0.0, bwDownMbps);
        this.loss = Math.min(1.0, Math.max(0.0, loss));
        this.up = up;
    }

    public double getRttMs()      { return rttMs; }
    public double getBwUpMbps()   { return bwUpMbps; }
    public double getBwDownMbps() { return bwDownMbps; }
    public double getLoss()       { return loss; }
    public boolean isUp()         { return up; }

    /** 有效带宽（已考虑丢包） */
    public double effUpMbps()     { return bwUpMbps   * (1.0 - loss); }
    public double effDownMbps()   { return bwDownMbps * (1.0 - loss); }

    /** 可用性判定（丢包导致的 0 带宽也视为不可用） */
    public boolean available()    { return up && (effUpMbps() > 0 || effDownMbps() > 0); }

    /** 便捷改写器（可选） */
    public LinkMetrics withUp(boolean newUp){ return new LinkMetrics(rttMs,bwUpMbps,bwDownMbps,loss,newUp); }
    public LinkMetrics withLoss(double newLoss){ return new LinkMetrics(rttMs,bwUpMbps,bwDownMbps,newLoss,up); }

    @Override public String toString() {
        return "LinkMetrics{rttMs=" + rttMs + ", upMbps=" + bwUpMbps + ", downMbps=" + bwDownMbps +
                ", loss=" + loss + ", up=" + up + "}";
    }
}
