package com.yourorg.sagin;

import com.yourorg.sagin.broker.STHGNNBroker;
import com.yourorg.sagin.gen.PoissonTaskGenerator;

import org.cloudsimplus.core.Simulation;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerSpaceShared;
import org.cloudsimplus.utilizationmodels.UtilizationModelFull;
import org.cloudsimplus.allocationpolicies.VmAllocationPolicySimple;


import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class MainRun {
    // 模拟参数
//    private static final long HOST_MIPS_PER_PE = 10_000;
//    private static final int  HOST_PES = 8;
//    private static final long HOST_RAM = 128_000;   // MB
//    private static final long HOST_BW  = 1_000_000; // bps
//    private static final long HOST_STO = 1_000_000; // MB

    // Host 资源（保证 >= 两个 VM 的需求）
    private static final int   HOST_PES          = 4;
    private static final double HOST_MIPS_PER_PE = 10000;
    private static final long HOST_RAM           = 80_000;
    private static final long  HOST_BW           = 2_000_000;
    private static final long  HOST_STO          = 1_000_000_000L; // 1 TB

    // VM 资源（两个 VM 一样，确保能放入一台 Host）
    private static final int   VM_PES            = 2;
    private static final double VM_MIPS_PER_PE   = 5000;
    private static final long  VM_RAM_MB         = 4096;    // 4 GB
    private static final long  VM_BW             = 500_000;
    private static final long  VM_SIZE           = 10_000;  // 10 GB

    public static void main(String[] args) {
        CloudSimPlus sim = new CloudSimPlus();

        // Datacenter
        DatacenterSimple dc = new DatacenterSimple(sim, createHosts(2), new VmAllocationPolicySimple());
        System.out.println("Host count = " + dc.getHostList().size());

        // 自定义 Broker（负责 bridge 交互，每 tick 写 state 读 action）
        Path bridge = Paths.get("bridge"); // 与项目根同级的 bridge 目录
        double tickSeconds = 1.0;
        STHGNNBroker broker = new STHGNNBroker(sim, Paths.get("bridge"), 1.0);
        //broker.setSubmitCloudletsAfterVmCreation(false);

        // VM
        List<Vm> vms = new ArrayList<>();
        Vm vmGs  = new VmSimple(VM_MIPS_PER_PE, VM_PES)
                .setRam(VM_RAM_MB).setBw(VM_BW).setSize(VM_SIZE)
                .setCloudletScheduler(new CloudletSchedulerSpaceShared());
        vmGs.setId(101);

        Vm vmSat = new VmSimple(VM_MIPS_PER_PE, 1)
                .setRam(VM_RAM_MB).setBw(VM_BW).setSize(VM_SIZE)
                .setCloudletScheduler(new CloudletSchedulerSpaceShared());
        vmSat.setId(201);

        vms.add(vmGs); vms.add(vmSat);
        broker.submitVmList(vms);

        System.out.printf("Host PES=%d, MIPS/PE=%.0f, RAM=%dMB, BW=%d%n",
                HOST_PES, HOST_MIPS_PER_PE, HOST_RAM, HOST_BW);
        System.out.printf("VM   PES=%d, MIPS/PE=%.0f, RAM=%dMB, BW=%d%n",
                VM_PES, VM_MIPS_PER_PE, VM_RAM_MB, VM_BW);


        // Poisson 任务生成器（两条流：GS & SAT，各自的到达率）
        PoissonTaskGenerator gen = new PoissonTaskGenerator(sim, broker)
                .lambdaGs(0.5)   // 每秒 0.5 个
                .lambdaSat(0.3)  // 每秒 0.3 个
                .bindRoundRobin(vms)  // 轮询绑定到 VM
                .cloudletTemplate( // 可选：覆盖默认的 Cloudlet 模板
                        (lengthMi) -> {
                            CloudletSimple c = new CloudletSimple(lengthMi, 1);
                            c.setFileSize(2 * 1024 * 1024L);
                            c.setOutputSize(1 * 1024 * 1024L);
                            UtilizationModelFull full = new UtilizationModelFull();
                            c.setUtilizationModelCpu(full);
                            c.setUtilizationModelRam(full);
                            c.setUtilizationModelBw(full);
                            return c;
                        });

        // 限制仿真时间
        sim.terminateAt(60);

        sim.start();

        // 输出结果
        List<Cloudlet> finished = broker.getCloudletFinishedList();
        finished.sort((a,b) -> Long.compare(a.getId(), b.getId()));
        finished.forEach(c -> System.out.printf(
                "Cloudlet %d finished on VM %d: start=%.3f, finish=%.3f%n",
                c.getId(), c.getVm().getId(), c.getExecStartTime(), c.getFinishTime()));
    }

    private static List<Host> createHosts(int count){
        List<Host> hs = new ArrayList<>();
        for(int i=0;i<count;i++){
            List<Pe> pes = new ArrayList<>();
            for(int p=0;p<HOST_PES;p++) pes.add(new PeSimple(HOST_MIPS_PER_PE));
            Host host = new HostSimple(HOST_RAM, HOST_BW, HOST_STO, pes);
            hs.add(host);
        }
        return hs;
    }
}
