package com.yourorg.sagin.broker;

import com.yourorg.sagin.io.ActionReader;
import com.yourorg.sagin.io.StateWriter;
import com.yourorg.sagin.net.LinkMetrics;
import com.yourorg.sagin.net.LinkPatchApplier;
import com.yourorg.sagin.net.NetworkProfile;

import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.core.Simulation;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.vms.Vm;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * STHGNNBroker（签名对齐你的工程）
 * - 每 tick：累计 CPU 能耗；写 state_{k}.json；读 action_{k}.json 并应用
 * - 网络能耗：按 bit 计（常数 J_PER_BIT），上行在提交时累加，下行在 Cloudlet 完成后(在 tick 中扫描)累加
 * - 时延：由 NetworkProfile.link(u,v,t) 获取链路指标后粗算
 */
public class STHGNNBroker extends DatacenterBrokerSimple {

    private final Path bridgeDir;
    private final double tickSeconds;

    private final StateWriter stateWriter;
    private final ActionReader actionReader;

    /** 处理 link_patch；内部自带一个可变的 NetworkProfile 实例 */
    public final LinkPatchApplier linkApplier = new LinkPatchApplier();
    public final NetworkProfile profile = linkApplier.profile;

    private long lastK = -1;

    /** CPU 能耗参数（W） */
    private static final double P_IDLE_W = 10.0;
    private static final double P_MAX_W  = 35.0;

    /** 网络能耗参数（J/bit，可按文献调整） */
    private static final double J_PER_BIT = 5e-9;

    /** VM -> 累计能耗（J） */
    private final Map<Long, Double> vmEnergyJ = new HashMap<>();
    /** 全网累计链路能耗（J） */
    private double netEnergyJ = 0.0;
    /** 已经按“下行能耗”计过账的 Cloudlet 集合，防止重复统计 */
    private final Set<Long> downEnergyAccounted = new HashSet<>();

    public STHGNNBroker(Simulation sim, Path bridgeDir, double tickSeconds) {
        super((CloudSimPlus) sim);
        this.bridgeDir = bridgeDir;
        this.tickSeconds = tickSeconds;

        try { Files.createDirectories(bridgeDir.resolve("tmp")); } catch (Exception ignored) {}

        this.stateWriter = new StateWriter(bridgeDir);
        this.actionReader = new ActionReader(bridgeDir, tickSeconds * 0.9);

        // 每 tick 回调
        sim.addOnClockTickListener(ev -> onTick(ev.getTime()));
    }

    /* ======================= Tick 主循环 ======================= */

    private void onTick(double time){
        long k = (long)Math.floor(time / tickSeconds);
        if (k == lastK) return;
        lastK = k;

        // 1) 累计 CPU 能耗
        accumulateCpuEnergyForTick();

        // 2) 对“刚完成”的 Cloudlet 计下行能耗（只记一次）
        for (Cloudlet c : getCloudletFinishedList()) {
            if (downEnergyAccounted.add(c.getId())) {
                Vm vm = c.getVm();
                if (vm != null) {
                    int src = resolveSrcId(c);
                    int dst = resolveDstId(vm);
                    double bitsDown = bytesToBits(c.getOutputSize());
                    // 这里只按 bit 计能耗；时延用于提交延迟，完成时无需再延迟
                    netEnergyJ += bitsDown * J_PER_BIT;
                }
            }
        }

        // 3) 写 state
        Map<String,Object> state = buildState(k, time);
        stateWriter.write(k, state);

        // 4) 读并应用 action
        Map<String,Object> action = actionReader.read(k);
        applyAssignments(action, time);
        linkApplier.applyFromAction(action); // link_patch
    }

    /* ======================= 构建 state ======================= */

    private Map<String,Object> buildState(long k, double time){
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("k", k);
        m.put("time", time);

        // VM 视图（含能耗）
        List<Map<String,Object>> vms = getVmCreatedList().stream()
                .map(this::vmInfo)
                .collect(Collectors.toList());
        m.put("vms", vms);

        // Cloudlet 视图
        List<Map<String,Object>> cls = new ArrayList<>();
        getCloudletWaitingList().forEach(c -> cls.add(clInfo(c, "WAITING")));
        getCloudletSubmittedList().forEach(c -> { if (!c.isFinished()) cls.add(clInfo(c, "RUNNING")); });
        m.put("cloudlets", cls);

        // 网络累计能耗（J）
        m.put("net_energy_j", netEnergyJ);
        return m;
    }

    private Map<String,Object> vmInfo(Vm vm){
        Map<String,Object> x = new LinkedHashMap<>();
        x.put("id", vm.getId());
        x.put("mips", vm.getMips());
        x.put("pes", vm.getPesNumber());
        x.put("ram", vm.getRam().getCapacity());
        x.put("bw", vm.getBw().getCapacity());
        x.put("size", vm.getStorage().getCapacity());

        double util = vmUtilizationApprox(vm);
        x.put("cpu_util", util);
        x.put("energy_j", vmEnergyJ.getOrDefault(vm.getId(), 0.0));
        return x;
    }

    private Map<String,Object> clInfo(Cloudlet c, String phase){
        Map<String,Object> x = new LinkedHashMap<>();
        x.put("id", c.getId());
        x.put("len", c.getLength());
        x.put("in_bytes",  c.getFileSize());
        x.put("out_bytes", c.getOutputSize());
        x.put("vm",   c.getVm() == null ? -1 : c.getVm().getId());
        x.put("phase", phase);
        x.put("src_id", resolveSrcId(c));  // 方便 Python 侧构图
        return x;
    }

    /* ======================= 应用 action（assign） ======================= */

    @SuppressWarnings("unchecked")
    private void applyAssignments(Map<String,Object> action, double now){
        if (action == null) return;
        Object asg = action.get("assign");
        if (!(asg instanceof List<?>)) return;

        for (Object o : (List<?>)asg) {
            if (!(o instanceof Map)) continue;
            Map<String,Object> e = (Map<String,Object>)o;

            long clId = num(e.getOrDefault("cloudlet_id", e.getOrDefault("id", -1))).longValue();
            long vmId = num(e.get("vm_id")).longValue();

            Cloudlet c = findCloudletInQueues(clId);
            Vm vm = getVmCreatedList().stream().filter(v -> v.getId() == vmId).findFirst().orElse(null);
            if (c == null || vm == null) continue;

            int src = resolveSrcId(c);
            int dst = resolveDstId(vm);

            // 上行：按链路指标粗算时延
            double bitsUp = bytesToBits(c.getFileSize());
            double tUp = calcUpSeconds(src, dst, bitsUp, now);

            // 上行能耗：按 bit 计
            netEnergyJ += bitsUp * J_PER_BIT;

            // 让 Cloudlet 在上行完成后进入执行
            c.setSubmissionDelay(tUp);

            // 绑定 & 提交
            bindCloudletToVm(c, vm);
            submitCloudlet(c);
        }
    }

    /* ======================= 能耗 & 利用率 ======================= */

    private void accumulateCpuEnergyForTick(){
        for (Vm vm : getVmCreatedList()) {
            double util = vmUtilizationApprox(vm); // [0,1]
            double powerW = P_IDLE_W + (P_MAX_W - P_IDLE_W) * util;
            double incJ = powerW * tickSeconds;    // W*s = J
            vmEnergyJ.merge(vm.getId(), incJ, Double::sum);
        }
    }

    /** 优先用 API；否则退化为 (#running / pes) 估算 */
    private double vmUtilizationApprox(Vm vm){
        try {
            double u = vm.getCpuPercentUtilization(); // 期望 [0,1]
            if (!Double.isNaN(u) && u >= 0 && u <= 1) return u;
        } catch (Throwable ignore) { }
        long running = getCloudletSubmittedList().stream()
                .filter(c -> !c.isFinished() && c.getVm() == vm)
                .count();
        return Math.max(0.0, Math.min(1.0, running / Math.max(1.0, (double)vm.getPesNumber())));
    }

    /* ======================= NetworkProfile 辅助 ======================= */

    /** 使用你的接口：link(int u, int v, double t) → LinkMetrics */
    private double calcUpSeconds(int u, int v, double bits, double t){
        LinkMetrics lm = profile.link(u, v, t);
        if (lm == null) return 0.0;
        double bwMbps = Math.max(1e-6, lm.getBwUpMbps());
        double sec = (bits / 1e6) / bwMbps + lm.getRttMs() / 1000.0;
        return sec;
    }

    private double calcDownSeconds(int u, int v, double bits, double t){
        LinkMetrics lm = profile.link(u, v, t);
        if (lm == null) return 0.0;
        double bwMbps = Math.max(1e-6, lm.getBwDownMbps());
        double sec = (bits / 1e6) / bwMbps + lm.getRttMs() / 1000.0;
        return sec;
    }

    /* ======================= 查找/工具 ======================= */

    private Cloudlet findCloudletInQueues(long id){
        for (Cloudlet c : getCloudletWaitingList()) if (c.getId() == id) return c;
        for (Cloudlet c : getCloudletSubmittedList()) if (!c.isFinished() && c.getId() == id) return c;
        return null;
    }

    private static double bytesToBits(long bytes){ return bytes * 8.0; }

    private static Number num(Object o){
        if (o instanceof Number) return (Number)o;
        if (o == null) return 0;
        try { return Double.parseDouble(String.valueOf(o)); }
        catch (Exception e){ return 0; }
    }

    /** 解析源节点 id（占位：先用 cloudletId 映射；后续替换成真实映射，比如从 Cloudlet 自定义字段拿） */
    private int resolveSrcId(Cloudlet c){
        // TODO: 从 Cloudlet 的自定义属性或你的映射表读取真实 srcId
        return (int)(c.getId() % 10_000); // 占位
    }

    /** 解析目的节点 id（占位：先用 vmId 映射；后续替换为 mapping.yaml 中的真实节点 id） */
    private int resolveDstId(Vm vm){
        // TODO: 从你的映射表将 VM 映射到节点 id
        return (int)(vm.getId() % 10_000); // 占位
    }
}
