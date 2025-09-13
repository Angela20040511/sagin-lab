package com.yourorg.sagin.gen;

import org.cloudsimplus.core.Simulation;
import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.utilizationmodels.UtilizationModelDynamic;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.utilizationmodels.UtilizationModelFull;
import org.cloudsimplus.listeners.EventInfo;

// 事件参数类型
import org.cloudsimplus.listeners.EventInfo;

import java.util.*;
import java.util.function.Function;

public class PoissonTaskGenerator {
    private final Simulation sim;
    private final DatacenterBrokerSimple broker;
    private final Random rng = new Random(42);

    private double lambdaGs = 0.0;
    private double lambdaSat = 0.0;

    private double nextGs = 0.0;
    private double nextSat = 0.0;

    private final List<Vm> bindVms = new ArrayList<>();
    private int rr = 0;


    private Function<Long, CloudletSimple> factory =
            (len) -> {
                CloudletSimple c = new CloudletSimple(len, 1);
                c.setFileSize(1 * 1024 * 1024L);
                c.setOutputSize(1 * 1024 * 1024L);
                UtilizationModelFull full = new UtilizationModelFull();
                c.setUtilizationModelCpu(full);
//                c.setUtilizationModelRam(full);
//                c.setUtilizationModelBw(full);
                c.setUtilizationModelRam(new UtilizationModelDynamic(0.05)); // 用 VM RAM 的 25%
                c.setUtilizationModelBw(new UtilizationModelDynamic(0.10));  // 用 VM BW 的 50%
                return c;
            };

    public PoissonTaskGenerator(Simulation sim, DatacenterBrokerSimple broker) {
        this.sim = sim;
        this.broker = broker;
        sim.addOnClockTickListener(this::onTick);
        this.nextGs = 0.0;
        this.nextSat = 0.0;
    }

    public PoissonTaskGenerator lambdaGs(double l){ this.lambdaGs = l; return this; }
    public PoissonTaskGenerator lambdaSat(double l){ this.lambdaSat = l; return this; }

    public PoissonTaskGenerator bindRoundRobin(List<Vm> vms){
        this.bindVms.clear();
        this.bindVms.addAll(vms);
        this.rr = 0;
        return this;
    }

    public PoissonTaskGenerator cloudletTemplate(Function<Long, CloudletSimple> f){
        this.factory = f;
        return this;
    }

    private void onTick(EventInfo info){
        double time = info.getTime();

        while(lambdaGs > 0 && time >= nextGs){
            submitOne("GS");
            nextGs = time + exp(lambdaGs);
        }
        while(lambdaSat > 0 && time >= nextSat){
            submitOne("SAT");
            nextSat = time + exp(lambdaSat);
        }
    }

    private void submitOne(String src){
        long len = src.equals("GS") ? 40_000L + rng.nextInt(20_000) : 20_000L + rng.nextInt(10_000);
        CloudletSimple c = factory.apply(len);
        c.setId(broker.getCloudletSubmittedList().size() + broker.getCloudletWaitingList().size() + 1);

        if(!bindVms.isEmpty()){
            Vm vm = bindVms.get(rr % bindVms.size());
            rr++;
            // 根据 VM 给出“占比”需求（每个 cloudlet 都不同）
//            double ramFrac = rng.nextDouble(0.15, 0.30);  // 用 VM RAM 的 15%~30%
//            double bwFrac  = rng.nextDouble(0.20, 0.45);  // 用 VM BW 的 20%~45%
//            c.setUtilizationModelRam(new UtilizationModelDynamic(ramFrac));
//            c.setUtilizationModelBw (new UtilizationModelDynamic(bwFrac));
            broker.bindCloudletToVm(c, vm);
        }
        broker.submitCloudlet(c);
    }

    private double exp(double lambda){
        return -Math.log(1 - rng.nextDouble()) / lambda;
    }
}
