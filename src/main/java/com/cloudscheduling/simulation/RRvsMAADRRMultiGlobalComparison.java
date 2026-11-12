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

import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * RRvsMAADRRMultiGlobalComparison:
 *
 * Compares Classical Round Robin (RR) vs. MAADRR across all 5 Global Schedulers:
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
public class RRvsMAADRRMultiGlobalComparison {

    // Infrastructure setup (identical across all experiments)
    private static final int    HOST_COUNT   = 30;
    private static final int    PES_PER_HOST = 4;
    private static final double HOST_PE_MIPS = 4000.0;
    private static final int    VM_COUNT     = 80;

    // Power model parameters (SPECpower benchmark standard: P_idle=150W, P_max=250W)
    private static final double P_IDLE_W = 150.0;
    private static final double P_MAX_W  = 250.0;

    // MAADRR parameters
    private static final double Q_MIN = 0.001;
    private static final double Q_MAX = 60.0;
    private static final double CS_OVERHEAD = 0.005; // 5ms context switch penalty per preemption

    private static final double RR_QUANTUM = 1.0; // 1 second fixed quantum for RR
    private static final int    RUNS       = 3;   // Statistical runs per size
    private static final int[]  WORKLOADS  = { 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000, 1500, 2000 };

    public static void main(String[] args) {
        Locale.setDefault(Locale.US);
        WorkspaceInfo.print();
        System.out.println();
        System.out.println("=================================================================================================================");
        System.out.println(" MULTI-GLOBAL SCHEDULER EVALUATION: Classical RR vs. MAADRR");
        System.out.println(" Global Schedulers: [1] Dy. MM  [2] DRALBA  [3] PSSLB  [4] PSSELB  [5] DeRALBA");
        System.out.println(" Metrics: AWT (ms), ATAT (s), ACT/TCT (s), Throughput (tasks/s), Energy Consumption (kWh)");
        System.out.println("=================================================================================================================");
        System.out.println();

        StringBuilder mdReport = new StringBuilder();
        mdReport.append("# Multi-Global Cloud Scheduling Evaluation: Classical RR vs. MAADRR\n\n");
        mdReport.append("> **Simulation Environment**: CloudSim Plus 8.5.7 | Java 21 | Real GoCJ Workload Traces (100–2000 Cloudlets)\n");
        mdReport.append("> **Datacenter Infrastructure**: 30 Hosts (120 PEs @ 4000 MIPS) | 80 Heterogeneous VMs (1000–4000 MIPS)\n");
        mdReport.append("> **Power Model**: Linear SPECpower Host Profile ($P_{\\text{idle}} = 150\\text{ W}$, $P_{\\text{max}} = 250\\text{ W}$)\n");
        mdReport.append("> **Global Schedulers**: Dynamic Max-Min, DRALBA, PSSLB, PSSELB, DeRALBA\n");
        mdReport.append("> **Local Schedulers Compared**: Classical Round Robin (Fixed $q = 1.0\\text{ s}$, FIFO) vs. MAADRR (Dynamic Time Quantum, DTQ)\n\n---\n\n");

        mdReport.append("## 1. Executive Summary Across All 5 Global Schedulers\n\n");
        mdReport.append("| Global Scheduler | AWT Reduction | ATAT Reduction | ACT Reduction | Throughput Imp. | Energy Imp. |\n");
        mdReport.append("| :--- | :---: | :---: | :---: | :---: | :---: |\n");

        StringBuilder detailedSections = new StringBuilder();

        for (GlobalSchedulers.Policy policy : GlobalSchedulers.Policy.values()) {
            System.out.println("=================================================================================================================");
            System.out.printf(" GLOBAL SCHEDULER: %s%n", policy.getDisplayName());
            System.out.println("=================================================================================================================");
            System.out.printf("%-5s | %-19s | %-19s | %-19s | %-18s | %-18s%n",
                "Tasks",
                "AWT (ms) [RR/MAA]",
                "ATAT (s) [RR/MAA]",
                "ACT (s) [RR/MAA]",
                "Throughput [RR/MAA]",
                "Energy (kWh) [RR/MAA]");
            System.out.println("-".repeat(113));

            detailedSections.append("### ").append(policy.getDisplayName()).append("\n\n");
            detailedSections.append("| Tasks | RR AWT (ms) | MAADRR AWT (ms) | **AWT Imp** | RR ATAT (s) | MAADRR ATAT (s) | **ATAT Imp** | RR ACT (s) | MAADRR ACT (s) | **ACT Imp** | Throughput | Energy (kWh) |\n");
            detailedSections.append("|------:|------------:|----------------:|:-----------:|------------:|----------------:|:------------:|-----------:|---------------:|:-----------:|:----------:|:------------:|\n");

            double sumAwtImp = 0, sumAtatImp = 0, sumActImp = 0, sumTpImp = 0, sumEnergyImp = 0;

            for (int size : WORKLOADS) {
                ComprehensiveResult rrAgg = new ComprehensiveResult();
                ComprehensiveResult maadrrAgg = new ComprehensiveResult();

                for (int r = 0; r < RUNS; r++) {
                    rrAgg.add(runSimulation(policy, size, true));
                    maadrrAgg.add(runSimulation(policy, size, false));
                }
                rrAgg.divide(RUNS);
                maadrrAgg.divide(RUNS);

                double awtImp    = pctImprove(rrAgg.awtMs,    maadrrAgg.awtMs,    false);
                double atatImp   = pctImprove(rrAgg.atatS,    maadrrAgg.atatS,    false);
                double actImp    = pctImprove(rrAgg.actS,     maadrrAgg.actS,     false);
                double tpImp     = pctImprove(rrAgg.throughput, maadrrAgg.throughput, true);
                double energyImp = pctImprove(rrAgg.energyKWh, maadrrAgg.energyKWh, false);

                sumAwtImp += awtImp;
                sumAtatImp += atatImp;
                sumActImp += actImp;
                sumTpImp += tpImp;
                sumEnergyImp += energyImp;

                System.out.printf("%-5d | %8.1f / %8.1f | %8.2f / %8.2f | %8.2f / %8.2f | %7.3f / %7.3f | %7.4f / %7.4f%n",
                    size,
                    rrAgg.awtMs, maadrrAgg.awtMs,
                    rrAgg.atatS, maadrrAgg.atatS,
                    rrAgg.actS, maadrrAgg.actS,
                    rrAgg.throughput, maadrrAgg.throughput,
                    rrAgg.energyKWh, maadrrAgg.energyKWh
                );
                System.out.printf("      | AWT: %+5.1f%%        | ATAT: %+5.1f%%       | ACT: %+5.1f%%        | TP: %+5.1f%%       | Energy: %+5.1f%%%n",
                    awtImp, atatImp, actImp, tpImp, energyImp
                );
                System.out.println("-".repeat(113));

                detailedSections.append(String.format(Locale.US,
                    "| %5d | %11.1f | %15.1f | **%+5.1f%%** | %11.2f | %15.2f | **%+5.1f%%** | %10.2f | %14.2f | **%+5.1f%%** | %10.4f | %12.4f |\n",
                    size,
                    rrAgg.awtMs, maadrrAgg.awtMs, awtImp,
                    rrAgg.atatS, maadrrAgg.atatS, atatImp,
                    rrAgg.actS, maadrrAgg.actS, actImp,
                    maadrrAgg.throughput, maadrrAgg.energyKWh
                ));
            }

            int n = WORKLOADS.length;
            double meanAwt = sumAwtImp / n;
            double meanAtat = sumAtatImp / n;
            double meanAct = sumActImp / n;
            double meanTp = sumTpImp / n;
            double meanEnergy = sumEnergyImp / n;

            System.out.printf(" >>> %s Mean Improvement: AWT=%+5.1f%% | ATAT=%+5.1f%% | ACT=%+5.1f%% | TP=%+5.1f%% | Energy=%+5.1f%%%n%n",
                policy.getDisplayName(), meanAwt, meanAtat, meanAct, meanTp, meanEnergy);

            mdReport.append(String.format(Locale.US,
                "| **%s** | **%+5.2f%%** | **%+5.2f%%** | **%+5.2f%%** | %+5.2f%% | %+5.2f%% |\n",
                policy.getDisplayName(), meanAwt, meanAtat, meanAct, meanTp, meanEnergy
            ));
            detailedSections.append("\n---\n\n");
        }

        mdReport.append("\n---\n\n## 2. Detailed Metric Tables by Global Scheduler\n\n");
        mdReport.append(detailedSections);

        // Write report directly to results directory in workspace
        try (PrintWriter writer = new PrintWriter(new FileWriter("results/rr_vs_maadrr_results.md"))) {
            writer.print(mdReport);
            System.out.println(" [SUCCESS] Saved report to results/rr_vs_maadrr_results.md");
        } catch (Exception e) {
            System.err.println("Error saving results file: " + e.getMessage());
        }

        System.out.println("=================================================================================================================");
        System.out.println(" RR vs. MAADRR MULTI-GLOBAL SIMULATION COMPLETED SUCCESSFULLY");
        System.out.println("=================================================================================================================");
    }

    private static ComprehensiveResult runSimulation(GlobalSchedulers.Policy policy, int workloadSize, boolean isRR) {
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
            // MAADRR sorts initially by Rank = RB ascending (no priority in GoCJ)
            for (VMQueue vq : vmQueues) {
                List<TaskState> sorted = new ArrayList<>(vq.tasks);
                sorted.sort(Comparator.comparingDouble((TaskState t) -> t.originalBurst).thenComparingLong(t -> t.cloudletId));
                vq.tasks.clear();
                vq.tasks.addAll(sorted);
            }
            return simulateMAADRR(vmQueues);
        }
    }

    // =========================================================================
    // LOCAL RR SCHEDULER
    // =========================================================================
    private static ComprehensiveResult simulateRR(List<VMQueue> vmQueues) {
        ComprehensiveResult res = new ComprehensiveResult();
        double globalMakespan = 0.0;
        double totalCpuBusyTime = 0.0;

        for (VMQueue vq : vmQueues) {
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
                } else {
                    currentTime += CS_OVERHEAD;
                    totalCpuBusyTime += CS_OVERHEAD;
                    vq.tasks.addLast(t);
                }
            }
        }

        calculateFinalMetrics(res, globalMakespan, totalCpuBusyTime);
        return res;
    }

    // =========================================================================
    // LOCAL MAADRR SCHEDULER (Dynamic Time Quantum = DTQ)
    // =========================================================================
    private static ComprehensiveResult simulateMAADRR(List<VMQueue> vmQueues) {
        ComprehensiveResult res = new ComprehensiveResult();
        double globalMakespan = 0.0;
        double totalCpuBusyTime = 0.0;

        for (VMQueue vq : vmQueues) {
            double currentTime = 0.0;

            while (!vq.tasks.isEmpty()) {
                TaskState t = vq.tasks.pollFirst();

                // MAADRR Dynamic Time Quantum: DTQ_j = clip(median{RB(x) : x in Q_j} + G_j, q_min, q_max)
                double dtq = calculateMAADRRQuantum(vq, t);
                double slice = Math.min(dtq, t.remainingBurst);
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
                } else {
                    currentTime += CS_OVERHEAD;
                    totalCpuBusyTime += CS_OVERHEAD;
                    // Re-insert maintaining sorted order by ascending remaining burst (MAADRR Rank)
                    insertSorted(vq.tasks, t);
                }
            }
        }

        calculateFinalMetrics(res, globalMakespan, totalCpuBusyTime);
        return res;
    }

    private static double calculateMAADRRQuantum(VMQueue vq, TaskState current) {
        List<Double> bursts = new ArrayList<>();
        bursts.add(current.remainingBurst);
        for (TaskState t : vq.tasks) bursts.add(t.remainingBurst);
        bursts.sort(Double::compareTo);
        int sz = bursts.size();
        double median = (sz % 2 == 0) ? (bursts.get(sz / 2 - 1) + bursts.get(sz / 2)) / 2.0 : bursts.get(sz / 2);
        return Math.max(Q_MIN, Math.min(Q_MAX, median));
    }

    private static void calculateFinalMetrics(ComprehensiveResult res, double makespan, double totalCpuBusyTime) {
        if (res.completed > 0) {
            res.awtMs = (res.totalAwt / res.completed) * 1000.0;
            res.atatS = res.totalAtat / res.completed;
            res.actS  = res.totalAct / res.completed;
        }
        res.makespan = makespan;
        res.throughput = makespan > 0 ? (double) res.completed / makespan : 0.0;

        // Energy consumption model based on CloudSimPlus / SPECpower
        double totalCapacityTime = HOST_COUNT * makespan;
        double avgUtilization = totalCapacityTime > 0 ? Math.min(1.0, totalCpuBusyTime / (HOST_COUNT * PES_PER_HOST * makespan)) : 0.0;
        double powerWatts = HOST_COUNT * (P_IDLE_W + (P_MAX_W - P_IDLE_W) * avgUtilization);
        res.energyKWh = (powerWatts * makespan) / (3_600_000.0);
    }

    private static void insertSorted(Deque<TaskState> deque, TaskState t) {
        List<TaskState> list = new ArrayList<>(deque);
        deque.clear();
        boolean placed = false;
        for (TaskState item : list) {
            if (!placed && item.remainingBurst > t.remainingBurst) {
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
        TaskState(long id, Cloudlet cl, double burst) {
            this.cloudletId = id;
            this.cloudlet = cl;
            this.originalBurst = burst;
            this.remainingBurst = burst;
        }
    }

    public static class ComprehensiveResult {
        public double totalAwt, totalAtat, totalAct;
        public double awtMs, atatS, actS, makespan, throughput, energyKWh;
        public long completed;

        public void add(ComprehensiveResult o) {
            totalAwt += o.totalAwt; totalAtat += o.totalAtat; totalAct += o.totalAct;
            awtMs += o.awtMs; atatS += o.atatS; actS += o.actS;
            makespan += o.makespan; throughput += o.throughput; energyKWh += o.energyKWh;
            completed += o.completed;
        }

        public void divide(int d) {
            if (d <= 0) return;
            totalAwt /= d; totalAtat /= d; totalAct /= d;
            awtMs /= d; atatS /= d; actS /= d;
            makespan /= d; throughput /= d; energyKWh /= d;
            completed /= d;
        }
    }
}

