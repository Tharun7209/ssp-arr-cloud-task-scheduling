package com.cloudscheduling.simulation;

import com.cloudscheduling.scheduler.GlobalSchedulers;
import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.schedulers.vm.VmSchedulerTimeShared;
import org.cloudsimplus.vms.Vm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * MultiGlobalComparisonSimulation:
 *
 * Compares Classical Round Robin (RR) vs. SSP-ARR across 5 Global Schedulers:
 *   1. Dynamic Max-Min (Dy. MM)
 *   2. DRALBA
 *   3. PSSLB
 *   4. PSSELB
 *   5. DeRALBA
 *
 * Evaluated Metrics:
 *   - AWT: Average Waiting Time (ms)
 *   - ATAT: Average Turnaround Time (s)
 *   - ACT (TCT): Average Completion Time / Total Completion Time (s)
 *   - Throughput: Tasks completed per second (tasks/s)
 *   - Energy Consumption: Host power model integration in kWh
 */
public class MultiGlobalComparisonSimulation {

    // Infrastructure setup (matching MAADRR paper Section IV-A)
    private static final int    HOST_COUNT   = 30;
    private static final int    PES_PER_HOST = 4;
    private static final double HOST_PE_MIPS = 4000.0;
    private static final int    VM_COUNT     = 80;

    // Power model parameters (SPECpower benchmark standard server: P_idle=150W, P_max=250W)
    private static final double P_IDLE_W = 150.0;
    private static final double P_MAX_W  = 250.0;

    private static final double RR_QUANTUM  = 1.0;   // 1 second fixed quantum for RR
    private static final double CS_OVERHEAD = 0.005; // 5ms context switch penalty per preemption
    private static final double STABILITY_THRESHOLD = 0.50; // CV threshold for SSP-ARR
    private static final double MAX_NORM_PREDICTION_ERROR = 0.20; // Confidence gate threshold
    private static final double AGING_FACTOR = 0.01; // Aging bonus to prevent task starvation
    private static final int    RUNS        = 3;     // Runs per workload size for statistical stability
    private static final int[]  WORKLOADS   = { 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000, 1500, 2000 };

    public static void main(String[] args) {
        Locale.setDefault(Locale.US);
        WorkspaceInfo.print();
        System.out.println();
        System.out.println("=================================================================================================================");
        System.out.println(" MULTI-GLOBAL SCHEDULER EVALUATION: Classical RR vs. SSP-ARR");
        System.out.println(" Global Schedulers: [1] Dy. MM  [2] DRALBA  [3] PSSLB  [4] PSSELB  [5] DeRALBA");
        System.out.println(" Metrics: AWT (ms), ATAT (s), ACT/TCT (s), Throughput (tasks/s), Energy Consumption (kWh)");
        System.out.println("=================================================================================================================");
        System.out.println();

        for (GlobalSchedulers.Policy policy : GlobalSchedulers.Policy.values()) {
            System.out.println("=================================================================================================================");
            System.out.printf(" GLOBAL SCHEDULER: %s%n", policy.getDisplayName());
            System.out.println("=================================================================================================================");
            System.out.printf("%-5s | %-17s | %-17s | %-17s | %-15s | %-15s | %-15s%n",
                "Tasks",
                "AWT (ms) [RR/SSP]",
                "ATAT (s) [RR/SSP]",
                "MS (s) [RR/SSP]",
                "Energy (kWh)",
                "JFI [RR/SSP]",
                "Preempt [RR/SSP]");
            System.out.println("-".repeat(116));

            double sumAwtImp = 0, sumAtatImp = 0, sumMsImp = 0, sumTpImp = 0, sumEnergyImp = 0, sumJfiImp = 0, sumPreemptImp = 0;

            for (int size : WORKLOADS) {
                ComprehensiveResult rrAgg = new ComprehensiveResult();
                ComprehensiveResult sspAgg = new ComprehensiveResult();

                for (int r = 0; r < RUNS; r++) {
                    rrAgg.add(runSimulation(policy, size, true));
                    sspAgg.add(runSimulation(policy, size, false));
                }
                rrAgg.divide(RUNS);
                sspAgg.divide(RUNS);

                double awtImp     = pctImprove(rrAgg.awtMs,              sspAgg.awtMs,              false);
                double atatImp    = pctImprove(rrAgg.atatS,              sspAgg.atatS,              false);
                double msImp      = pctImprove(rrAgg.makespan,           sspAgg.makespan,           false);
                double tpImp      = pctImprove(rrAgg.throughput,         sspAgg.throughput,         true);
                double energyImp  = pctImprove(rrAgg.energyKWh,          sspAgg.energyKWh,          false);
                double jfiImp     = pctImprove(rrAgg.jainsFairnessIndex, sspAgg.jainsFairnessIndex, true);
                double preemptImp = pctImprove(rrAgg.totalPreemptions,   sspAgg.totalPreemptions,   false);

                sumAwtImp     += awtImp;
                sumAtatImp    += atatImp;
                sumMsImp      += msImp;
                sumTpImp      += tpImp;
                sumEnergyImp  += energyImp;
                sumJfiImp     += jfiImp;
                sumPreemptImp += preemptImp;

                System.out.printf("%-5d | %7.1f / %7.1f | %7.2f / %7.2f | %7.2f / %7.2f | %6.4f / %6.4f | %5.3f / %5.3f | %6d / %5d%n",
                    size,
                    rrAgg.awtMs, sspAgg.awtMs,
                    rrAgg.atatS, sspAgg.atatS,
                    rrAgg.makespan, sspAgg.makespan,
                    rrAgg.energyKWh, sspAgg.energyKWh,
                    rrAgg.jainsFairnessIndex, sspAgg.jainsFairnessIndex,
                    rrAgg.totalPreemptions, sspAgg.totalPreemptions
                );
                System.out.printf("      | AWT: %+5.1f%%        | ATAT: %+5.1f%%       | MS: %+5.1f%%         | Energy: %+5.1f%%     | JFI: %+5.2f%%   | Preempt: %+5.1f%%%n",
                    awtImp, atatImp, msImp, energyImp, jfiImp, preemptImp
                );
                System.out.println("-".repeat(116));
            }

            int n = WORKLOADS.length;
            System.out.printf(" >>> %s Mean Improvement: AWT=%+5.1f%% | ATAT=%+5.1f%% | MS=%+5.1f%% | Energy=%+5.1f%% | JFI=%+5.2f%% | Preempt=%+5.1f%%%n%n",
                policy.getDisplayName(),
                sumAwtImp / n, sumAtatImp / n, sumMsImp / n, sumEnergyImp / n, sumJfiImp / n, sumPreemptImp / n);
        }

        System.out.println("=================================================================================================================");
        System.out.println(" MULTI-GLOBAL SCHEDULER SIMULATION COMPLETED SUCCESSFULLY");
        System.out.println("=================================================================================================================");
    }

    public static ComprehensiveResult runSimulation(GlobalSchedulers.Policy policy, int workloadSize, boolean isRR) {
        CloudSimPlus simulation = new CloudSimPlus();

        List<Host> hostList = new ArrayList<>();
        for (int h = 0; h < HOST_COUNT; h++) {
            List<Pe> peList = new ArrayList<>();
            for (int p = 0; p < PES_PER_HOST; p++) peList.add(new PeSimple(HOST_PE_MIPS));
            Host host = new HostSimple(32768, 100000, 1_000_000, peList);
            host.setVmScheduler(new VmSchedulerTimeShared());
            hostList.add(host);
        }
        new DatacenterSimple(simulation, hostList);

        DatacenterBrokerSimple broker = new DatacenterBrokerSimple(simulation);
        List<Vm> vmList = MAADRRSimulation.createVms();
        broker.submitVmList(vmList);

        List<Cloudlet> cloudlets = MAADRRSimulation.loadGoCJCloudlets(workloadSize);
        int[] assignment = GlobalSchedulers.assign(policy, cloudlets, vmList);

        List<VMQueue> vmQueues = new ArrayList<>();
        for (Vm vm : vmList) vmQueues.add(new VMQueue(vm));

        for (int i = 0; i < cloudlets.size(); i++) {
            Cloudlet cl = cloudlets.get(i);
            int vmIdx = assignment[i];
            double burst = cl.getLength() / vmList.get(vmIdx).getMips();
            vmQueues.get(vmIdx).tasks.add(new TaskState(cl.getId(), cl, burst));
        }

        if (isRR) {
            return simulateRR(vmQueues);
        } else {
            // SSP-ARR orders by SRPT burst rank
            for (VMQueue vq : vmQueues) {
                List<TaskState> sorted = new ArrayList<>(vq.tasks);
                sorted.sort(Comparator.comparingDouble((TaskState t) -> t.originalBurst).thenComparingLong(t -> t.cloudletId));
                vq.tasks.clear();
                vq.tasks.addAll(sorted);
            }
            return simulateSSPARR(vmQueues);
        }
    }

    // =========================================================================
    // LOCAL RR SCHEDULER
    // =========================================================================
    private static ComprehensiveResult simulateRR(List<VMQueue> vmQueues) {
        ComprehensiveResult res = new ComprehensiveResult();
        double globalMakespan = 0.0;
        double totalCpuBusyTime = 0.0;
        double sumEfficiency = 0.0;
        double sumEfficiencySq = 0.0;
        double[] vmFinishTimes = new double[vmQueues.size()];

        for (int vIdx = 0; vIdx < vmQueues.size(); vIdx++) {
            VMQueue vq = vmQueues.get(vIdx);
            double currentTime = 0.0;
            while (!vq.tasks.isEmpty()) {
                TaskState t = vq.tasks.pollFirst();
                double slice = Math.min(RR_QUANTUM, t.remainingBurst);
                t.remainingBurst -= slice;
                currentTime += slice;
                totalCpuBusyTime += slice;

                if (t.remainingBurst <= 1e-9) {
                    t.finishTime = currentTime;
                    double wait = Math.max(0.0, t.finishTime - t.originalBurst);
                    res.totalAwt += wait;
                    res.totalAtat += t.finishTime;
                    res.totalAct += t.finishTime;
                    res.completed++;
                    globalMakespan = Math.max(globalMakespan, t.finishTime);

                    double eff = t.originalBurst / Math.max(t.finishTime, 1e-9);
                    sumEfficiency += eff;
                    sumEfficiencySq += eff * eff;
                } else {
                    currentTime += CS_OVERHEAD;
                    totalCpuBusyTime += CS_OVERHEAD;
                    res.totalPreemptions++;
                    vq.tasks.addLast(t);
                }
            }
            vmFinishTimes[vIdx] = currentTime;
        }

        calculateFinalMetrics(res, globalMakespan, totalCpuBusyTime, sumEfficiency, sumEfficiencySq, vmFinishTimes);
        return res;
    }

    // =========================================================================
    // LOCAL SSP-ARR SCHEDULER (v3 with Adaptive Alpha, Gate, Aging)
    // =========================================================================
    private static ComprehensiveResult simulateSSPARR(List<VMQueue> vmQueues) {
        ComprehensiveResult res = new ComprehensiveResult();
        double globalMakespan = 0.0;
        double totalCpuBusyTime = 0.0;
        double sumEfficiency = 0.0;
        double sumEfficiencySq = 0.0;
        double[] vmFinishTimes = new double[vmQueues.size()];

        for (int vIdx = 0; vIdx < vmQueues.size(); vIdx++) {
            VMQueue vq = vmQueues.get(vIdx);
            double currentTime = 0.0;
            Deque<Double> burstHistory = new ArrayDeque<>();
            Deque<Double> errorHistory = new ArrayDeque<>();
            double ewma = 0.0;
            boolean ewmaInit = false;

            while (!vq.tasks.isEmpty()) {
                TaskState t = vq.tasks.pollFirst();

                // Aging update for remaining tasks in this queue
                for (TaskState waitingTask : vq.tasks) {
                    waitingTask.waitingPasses++;
                }

                // Reactive quantum = median of remaining bursts
                double reactiveQ = calculateMedian(vq, t);
                boolean stable = isStable(burstHistory);

                // Prediction only generated when volatile
                double predictiveEstimate = 0.0;
                boolean predictionGenerated = false;
                if (!stable) {
                    predictiveEstimate = makeEwmaPrediction(ewma, ewmaInit, burstHistory);
                    predictionGenerated = true;
                    t.predictedBurst = predictiveEstimate;
                    t.predictionEvaluated = true;
                }

                // Confidence gate
                boolean reliable = predictionGenerated && isPredictionReliable(errorHistory);
                boolean predictiveMode = !stable && reliable;

                double q;
                if (predictiveMode) {
                    double taskBound = Math.min(predictiveEstimate, t.remainingBurst);
                    q = Math.max(0.001, Math.min(60.0, taskBound));
                } else {
                    double taskBound = Math.min(reactiveQ, t.remainingBurst);
                    q = Math.max(0.001, Math.min(60.0, taskBound));
                }

                double slice = Math.min(q, t.remainingBurst);
                t.remainingBurst -= slice;
                currentTime += slice;
                totalCpuBusyTime += slice;

                if (t.remainingBurst <= 1e-9) {
                    t.finishTime = currentTime;
                    double wait = Math.max(0.0, t.finishTime - t.originalBurst);
                    res.totalAwt += wait;
                    res.totalAtat += t.finishTime;
                    res.totalAct += t.finishTime;
                    res.completed++;
                    globalMakespan = Math.max(globalMakespan, t.finishTime);

                    double eff = t.originalBurst / Math.max(t.finishTime, 1e-9);
                    sumEfficiency += eff;
                    sumEfficiencySq += eff * eff;

                    // Update prediction error history
                    if (t.predictionEvaluated && t.predictedBurst > 0.0) {
                        double absErr = Math.abs(t.originalBurst - t.predictedBurst);
                        double normErr = absErr / Math.max(t.originalBurst, 1e-9);
                        errorHistory.addLast(normErr);
                        while (errorHistory.size() > 5) errorHistory.removeFirst();
                    }

                    // Adaptive EWMA update
                    double actual = t.originalBurst;
                    if (!ewmaInit) {
                        ewma = actual;
                        ewmaInit = true;
                    } else {
                        double alpha = calculateAdaptiveAlpha(errorHistory);
                        ewma = alpha * actual + (1.0 - alpha) * ewma;
                    }

                    burstHistory.addLast(actual);
                    while (burstHistory.size() > 5) burstHistory.removeFirst();
                } else {
                    currentTime += CS_OVERHEAD;
                    totalCpuBusyTime += CS_OVERHEAD;
                    res.totalPreemptions++;
                    insertSorted(vq.tasks, t);
                }
            }
            vmFinishTimes[vIdx] = currentTime;
        }

        calculateFinalMetrics(res, globalMakespan, totalCpuBusyTime, sumEfficiency, sumEfficiencySq, vmFinishTimes);
        return res;
    }

    private static void calculateFinalMetrics(ComprehensiveResult res, double makespan, double totalCpuBusyTime,
                                              double sumEfficiency, double sumEfficiencySq, double[] vmFinishTimes) {
        if (res.completed > 0) {
            res.awtMs = (res.totalAwt / res.completed) * 1000.0;
            res.atatS = res.totalAtat / res.completed;
            res.actS  = res.totalAct / res.completed;
            res.tctS  = res.totalAct;
            res.jainsFairnessIndex = (sumEfficiencySq > 0)
                ? (sumEfficiency * sumEfficiency) / (res.completed * sumEfficiencySq)
                : 1.0;
        }
        res.makespan = makespan;
        res.throughput = makespan > 0 ? (double) res.completed / makespan : 0.0;

        // Load Imbalance Factor (LIF) across all VMs
        double maxFT = 0.0;
        double minFT = Double.MAX_VALUE;
        double sumFT = 0.0;
        for (double ft : vmFinishTimes) {
            if (ft > maxFT) maxFT = ft;
            if (ft < minFT) minFT = ft;
            sumFT += ft;
        }
        double avgFT = vmFinishTimes.length > 0 ? (sumFT / vmFinishTimes.length) : 0.0;
        res.loadImbalanceFactor = (avgFT > 0) ? (maxFT - minFT) / avgFT : 0.0;

        // Energy consumption model based on CloudSimPlus / SPECpower:
        double avgUtilization = (HOST_COUNT * makespan > 0)
            ? Math.min(1.0, totalCpuBusyTime / (HOST_COUNT * PES_PER_HOST * makespan))
            : 0.0;
        double powerWatts = HOST_COUNT * (P_IDLE_W + (P_MAX_W - P_IDLE_W) * avgUtilization);
        res.energyKWh = (powerWatts * makespan) / (3_600_000.0);
    }

    private static double calculateMedian(VMQueue vq, TaskState current) {
        List<Double> bursts = new ArrayList<>();
        bursts.add(current.remainingBurst);
        for (TaskState t : vq.tasks) bursts.add(t.remainingBurst);
        bursts.sort(Double::compareTo);
        int sz = bursts.size();
        return (sz % 2 == 0) ? (bursts.get(sz / 2 - 1) + bursts.get(sz / 2)) / 2.0 : bursts.get(sz / 2);
    }

    private static boolean isStable(Deque<Double> hist) {
        if (hist.size() < 5) return true;
        double mean = hist.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        if (mean <= 1e-9) return true;
        double var = 0.0;
        for (double v : hist) var += (v - mean) * (v - mean);
        var /= hist.size();
        return (Math.sqrt(var) / mean) <= STABILITY_THRESHOLD;
    }

    private static double makeEwmaPrediction(double ewma, boolean initialized, Deque<Double> history) {
        if (!initialized) {
            if (history.isEmpty()) return 0.001;
            List<Double> vals = new ArrayList<>(history);
            vals.sort(Double::compareTo);
            int mid = vals.size() / 2;
            return (vals.size() % 2 == 0)
                ? (vals.get(mid - 1) + vals.get(mid)) / 2.0
                : vals.get(mid);
        }
        return Math.max(0.001, Math.min(60.0, ewma));
    }

    private static double calculateAdaptiveAlpha(Deque<Double> errorHistory) {
        if (errorHistory.isEmpty()) return 0.20;
        double mean = errorHistory.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        if (mean < 0.10) return 0.20;
        if (mean < 0.20) return 0.40;
        if (mean < 0.30) return 0.60;
        return 0.80;
    }

    private static boolean isPredictionReliable(Deque<Double> errorHistory) {
        if (errorHistory.size() < 5) return false;
        double mean = errorHistory.stream().mapToDouble(Double::doubleValue).average()
            .orElse(Double.POSITIVE_INFINITY);
        return mean <= MAX_NORM_PREDICTION_ERROR;
    }

    private static void insertSorted(Deque<TaskState> deque, TaskState t) {
        List<TaskState> list = new ArrayList<>(deque);
        deque.clear();
        boolean placed = false;
        double tEff = t.effectiveBurst();
        for (TaskState item : list) {
            if (!placed && item.effectiveBurst() > tEff) {
                deque.addLast(t);
                placed = true;
            }
            deque.addLast(item);
        }
        if (!placed) deque.addLast(t);
    }

    private static double pctImprove(double base, double prop, boolean higherBetter) {
        if (base == 0) return 0;
        return higherBetter ? (prop - base) / base * 100.0 : (base - prop) / base * 100.0;
    }

    // =========================================================================
    // DATA STRUCTURES
    // =========================================================================
    private static class VMQueue {
        @SuppressWarnings("unused")
        final Vm vm;
        final Deque<TaskState> tasks = new ArrayDeque<>();
        VMQueue(Vm vm) { this.vm = vm; }
    }

    private static class TaskState {
        @SuppressWarnings("unused")
        final long cloudletId;
        @SuppressWarnings("unused")
        final Cloudlet cloudlet;
        final double originalBurst;
        double remainingBurst;
        double finishTime;
        double predictedBurst;
        boolean predictionEvaluated;
        int waitingPasses;

        TaskState(long id, Cloudlet cl, double burst) {
            this.cloudletId = id;
            this.cloudlet = cl;
            this.originalBurst = burst;
            this.remainingBurst = burst;
            this.waitingPasses = 0;
        }

        double effectiveBurst() {
            return Math.max(0.001, remainingBurst - (AGING_FACTOR * waitingPasses));
        }
    }

    public static class ComprehensiveResult {
        public double totalAwt, totalAtat, totalAct;
        public double awtMs, atatS, actS, tctS, makespan, throughput, energyKWh;
        public double jainsFairnessIndex, loadImbalanceFactor;
        public long totalPreemptions;
        public long completed;

        public void add(ComprehensiveResult o) {
            totalAwt += o.totalAwt; totalAtat += o.totalAtat; totalAct += o.totalAct;
            awtMs += o.awtMs; atatS += o.atatS; actS += o.actS; tctS += o.tctS;
            makespan += o.makespan; throughput += o.throughput; energyKWh += o.energyKWh;
            jainsFairnessIndex += o.jainsFairnessIndex;
            loadImbalanceFactor += o.loadImbalanceFactor;
            totalPreemptions += o.totalPreemptions;
            completed += o.completed;
        }

        public void divide(int d) {
            if (d <= 0) return;
            totalAwt /= d; totalAtat /= d; totalAct /= d;
            awtMs /= d; atatS /= d; actS /= d; tctS /= d;
            makespan /= d; throughput /= d; energyKWh /= d;
            jainsFairnessIndex /= d;
            loadImbalanceFactor /= d;
            totalPreemptions /= d;
            completed /= d;
        }
    }
}

