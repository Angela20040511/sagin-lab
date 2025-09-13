package com.yourorg.sagin;

import com.yourorg.sagin.broker.STHGNNBroker;
import com.yourorg.sagin.gen.PoissonTaskGenerator;
import com.yourorg.sagin.net.NetworkProfile;
import com.yourorg.sagin.net.NetworkProfileCsv;

import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.core.Simulation;

import org.cloudsimplus.allocationpolicies.VmAllocationPolicySimple;
import org.cloudsimplus.datacenters.DatacenterSimple;

import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.power.models.PowerModelHostSimple;

import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;

import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerSpaceShared;

import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletSimple;

import org.cloudsimplus.utilizationmodels.UtilizationModelFull;
import org.cloudsimplus.utilizationmodels.UtilizationModelDynamic;

import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class MainRun {

    // ------------ 主机/VM 基本参数（与你仓库一致） ------------
    private static final int    HOST_PES         = 8;        // 主机核数
    private static final long   HOST_MIPS_PER_PE = 10_000;   // 每核 MIPS
    private static final long   HOST_RAM         = 128_000;  // MB
    private static final long   HOST_BW          = 1_000_000;// B/s
    private static final long   HOST_STO         = 1_000_000;// MB

    private static final long   VM_RAM_MB        = 64_000;
    private static final long   VM_BW            = 500_000;
    private static final long   VM_STO           = 10_000;

    // ------------ Host 电源模型参数（可按需标定） ------------
    private static final double HOST_MAX_POWER_W      = 250.0; // 满载功率(瓦)
    private static final double HOST_IDLE_RATIO       = 0.50;  // 空载功率比例
    private static final double HOST_STATIC_POWER_W   = HOST_MAX_POWER_W * HOST_IDLE_RATIO;

    // ------------ 网络能耗(演示常数) ------------
    private static final double JOULE_PER_BIT = 0.1e-6; // 0.1 微焦/比特，仅演示

    // 任务“软”截止时间预算（秒），供打印展示
    private static final double DEADLINE_BUDGET_SEC = 20.0;

    public static void main(String[] args) {
        // 1) 仿真引擎
        Simulation sim = new CloudSimPlus();
        sim.terminateAt(120); // 2分钟上限，按需调整

        // 2) 数据中心 & 主机
        List<Host> hosts = createHosts(2);
        DatacenterSimple dc = new DatacenterSimple(sim, hosts, new VmAllocationPolicySimple());

        // 3) Broker
        Path bridgeDir = Paths.get("bridge");
        STHGNNBroker broker = new STHGNNBroker(sim, bridgeDir, 1.0);

        // 4) 网络画像（可选 CSV，若不存在则空画像）——加载但不在此直接使用
        loadNetworkProfileCsv(Paths.get("cloudsim", "src", "main", "resources", "network", "network_profile.csv"));

        // 5) VM（mips=10_000 与主机核能力匹配）
        List<Vm> vms = new ArrayList<>();
        Vm vmGs = new VmSimple(/*mips*/10_000, /*pes*/2)
                .setRam(VM_RAM_MB).setBw(VM_BW).setSize(VM_STO)
                .setCloudletScheduler(new CloudletSchedulerSpaceShared());
        vmGs.setId(101);

        Vm vmSat = new VmSimple(/*mips*/10_000, /*pes*/1)
                .setRam(VM_RAM_MB).setBw(VM_BW).setSize(VM_STO)
                .setCloudletScheduler(new CloudletSchedulerSpaceShared());
        vmSat.setId(201);

        vms.add(vmGs);
        vms.add(vmSat);
        broker.submitVmList(vms);

        // 6) 任务生成器（与你仓库一致）
        PoissonTaskGenerator gen = new PoissonTaskGenerator(sim, broker);
        gen.lambdaGs(0.15).lambdaSat(0.10);

        // === 为了立刻看到“任务明细”，追加一批演示 Cloudlet（可随时删除这段） ===
        List<Cloudlet> demo = demoCloudlets(sim);
        Map<Long, Double> deadlineMap = demo.stream()
                .collect(Collectors.toMap(Cloudlet::getId,
                        c -> c.getSubmissionDelay() + DEADLINE_BUDGET_SEC));
        broker.submitCloudletList(demo);
        // ========================================================

        // 7) 能耗统计（CPU + 粗略网络），分主机统计
        final double[] lastT = {0.0};
        Map<Long, Double> hostEnergyJ = new LinkedHashMap<>(); // key=hostId
        for (Host h : hosts) hostEnergyJ.put(h.getId(), 0.0);

        sim.addOnClockTickListener(ev -> {
            double t  = sim.clock();
            double dt = Math.max(0.0, t - lastT[0]); // 秒
            lastT[0]  = t;

            for (Host h: dc.getHostList()){
                double util = h.getCpuPercentUtilization(); // 0~1
                double w    = h.getPowerModel().getPower(util); // W（注意：这里不再强转 HostSimple）
                hostEnergyJ.put(h.getId(), hostEnergyJ.get(h.getId()) + w * dt);
            }
        });

        // 8) 跑
        sim.start();

        // ------- 打印 VM→Host 绑定 -------
        System.out.println("\n===== VM → Host Bindings =====");
        for (Vm vm : vms) {
            Host h = vm.getHost();
            System.out.printf("VM %d -> Host %d | mips=%,.0f x %d PE, RAM=%dMB, BW=%dB/s%n",
                    vm.getId(),
                    h == null ? -1 : h.getId(),
                    vm.getMips(),                     // 修正：使用 getMips()
                    vm.getPesNumber(),                // 修正：8.0.0 用 getPesNumber()
                    vm.getRam().getCapacity(),
                    vm.getBw().getCapacity());
        }

        // ------- 打印 Cloudlet 明细 -------
        var finished = broker.getCloudletFinishedList();
        finished.sort(Comparator.comparingLong(Cloudlet::getId));

        System.out.println("\n===== Cloudlet Execution Summary =====");
        if (finished.isEmpty()){
            System.out.println("(no cloudlets were executed)");
        }else{
            System.out.printf("%-6s %-6s %-6s %8s %8s %8s %8s %8s%n",
                    "CL", "VM", "HOST", "r(sec)", "start", "finish", "run", "deadline");
            for (Cloudlet c : finished){
                Vm   vm = c.getVm();
                Host h  = vm == null ? null : vm.getHost();
                double r   = c.getSubmissionDelay();
                double st  = c.getExecStartTime();
                double ft  = c.getFinishTime();
                double run = Math.max(0.0, ft - st);
                Double ddl = deadlineMap.getOrDefault(c.getId(), Double.NaN);

                System.out.printf("%-6d %-6d %-6d %8.3f %8.3f %8.3f %8.3f %8.3f%n",
                        c.getId(),
                        vm == null ? -1 : vm.getId(),
                        h  == null ? -1 : h.getId(),
                        r, st, ft, run, ddl);
            }
        }

        // ------- 估算网络能耗（按 Cloudlet 的 input+output 字节） -------
        long totalBytes = finished.stream()
                .mapToLong(c -> c.getFileSize() + c.getOutputSize())
                .sum();
        double netEnergyJ = totalBytes * 8.0 * JOULE_PER_BIT;

        // ------- 能耗汇总 -------
        System.out.println("\n===== Energy Summary =====");
        double hostTotalJ = 0.0;
        for (var e : hostEnergyJ.entrySet()){
            hostTotalJ += e.getValue();
            System.out.printf("Host %d energy: %.6f J (%.6f Wh)%n",
                    e.getKey(), e.getValue(), e.getValue()/3600.0);
        }
        System.out.printf("TOTAL host energy: %.6f J (%.6f Wh, %.6f kJ)%n",
                hostTotalJ, hostTotalJ/3600.0, hostTotalJ/1000.0);
        System.out.printf("TOTAL network energy (est.): %.6f J (%.6f Wh, %.6f kJ)%n",
                netEnergyJ, netEnergyJ/3600.0, netEnergyJ/1000.0);
        System.out.printf("TOTAL energy (host + net): %.6f J (%.6f Wh, %.6f kJ)%n",
                hostTotalJ+netEnergyJ, (hostTotalJ+netEnergyJ)/3600.0, (hostTotalJ+netEnergyJ)/1000.0);
    }

    // ---------------- 工具方法 ----------------

    private static List<Host> createHosts(int count){
        List<Host> hs = new ArrayList<>();
        for(int i=0;i<count;i++){
            List<Pe> pes = new ArrayList<>();
            for(int p=0;p<HOST_PES;p++){
                pes.add(new PeSimple(HOST_MIPS_PER_PE));
            }
            HostSimple h = new HostSimple(HOST_RAM, HOST_BW, HOST_STO, pes);
            // 给 Host 绑定线性功耗模型（参数：最大功率W + 静态功率W）
            h.setPowerModel(new PowerModelHostSimple(HOST_MAX_POWER_W, HOST_STATIC_POWER_W));
            h.setId(1 + i);
            hs.add(h);
        }
        return hs;
    }

    private static NetworkProfile loadNetworkProfileCsv(Path csv){
        try{
            if(Files.exists(csv)){
                return NetworkProfileCsv.loadCsv(csv);
            }
        }catch(Exception ignore){}
        return new NetworkProfileCsv(); // 没 CSV 就给一个空实现
    }

    /** 追加一批“演示用”的 Cloudlet，保证你能看到任务明细（随时可以删） */
    private static List<Cloudlet> demoCloudlets(Simulation sim){
        List<Cloudlet> list = new ArrayList<>();
        var uCpu = new UtilizationModelFull();
        var uRam = new UtilizationModelDynamic(0.25); // 25% 平均
        var uBw  = new UtilizationModelDynamic(0.35); // 35% 平均

        int n = 6;                  // 6 个 cloudlet
        long lenMI = 12_000;        // 计算量（MI）
        int  pes   = 1;
        long inB   = 2_000;         // 输入/输出字节
        long outB  = 3_000;

        for (int i=0;i<n;i++){
            CloudletSimple c = new CloudletSimple(lenMI, pes);
            c.setFileSize(inB).setOutputSize(outB);
            c.setUtilizationModelCpu(uCpu);
            c.setUtilizationModelRam(uRam);
            c.setUtilizationModelBw(uBw);
            c.setSubmissionDelay(i * 1.0); // r：每1秒放一个
            c.setId(1000 + i);
            list.add(c);
        }
        return list;
    }
}
