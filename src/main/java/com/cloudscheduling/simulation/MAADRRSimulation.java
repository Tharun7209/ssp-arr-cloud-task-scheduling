package com.cloudscheduling.simulation;

import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.Datacenter;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerTimeShared;
import org.cloudsimplus.schedulers.vm.VmSchedulerTimeShared;
import org.cloudsimplus.utilizationmodels.UtilizationModelDynamic;
import org.cloudsimplus.utilizationmodels.UtilizationModelFull;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * MAADRR – Multi-Agent Adaptive Dynamic Round Robin (standalone runner).
 *
 * Local scheduler:
 *   DTQ_j = clip(median{RB(x) : x in Q_j} + G_j, q_min, q_max)
 *   Rank(x) = A*RB(x) + B*Priority(x)   [A=1, B=0 for GoCJ – no priority field]
 *   G_j = 0  (single-datacenter, no inter-agent communication)
 *
 * Global scheduler: Dynamic Max-Min
 *
 * Infrastructure (matching MAADRR paper, Table 3):
 *   - 30 Hosts, 4 PEs @ 4000 MIPS each
 *   - 80 heterogeneous VMs (MIPS range 1000-4000)
 *   - 5 runs per workload size, mean reported
 *   - 12 workload sizes: 100..2000
 */
public class MAADRRSimulation {

    // ============================================================
    // INFRASTRUCTURE (mirrors MAADRR paper Table 3)
    // ============================================================
    private static final int    HOST_COUNT    = 30;
    private static final int    PES_PER_HOST  = 4;
    private static final double HOST_PE_MIPS  = 4000.0;
    private static final int    VM_COUNT      = 80;

    private static final double[] VM_MIPS_PATTERN = {
        1000, 1200, 1400, 1600, 1800,
        2000, 2200, 2400, 2600, 2800,
        3000, 3200, 3400, 3600, 3800, 4000
    };

    private static final int  VM_RAM     = 8192;
    private static final long VM_BW      = 20000;
    private static final long VM_STORAGE = 10000;
    private static final int  CLOUDLET_PES = 1;

    private static final double CLOUDLET_RAM_UTILIZATION = 0.01;
    private static final double CLOUDLET_BW_UTILIZATION  = 0.01;

    // ============================================================
    // MAADRR PARAMETERS
    // ============================================================
    public static final double CS_OVERHEAD = 0.005; // 5ms context-switch overhead per preemption
    private static final double Q_MIN = 0.001;   // seconds
    private static final double Q_MAX = 60.0;    // seconds
    // Ranking weights (GoCJ has no priority field - B = 0)
    private static final double RANK_A = 1.0;
    private static final double RANK_B = 0.0;
    // Global feedback signal = 0 in single-datacenter mode
    private static final double G_FEEDBACK = 0.0;

    // ============================================================
    // EXPERIMENT PARAMETERS
    // ============================================================
    private static final int RUNS = 5;
    private static final int[] PAPER_SIZES = {
        100, 200, 300, 400, 500, 600,
        700, 800, 900, 1000, 1500, 2000
    };

    // ============================================================
    // MAIN
    // ============================================================
    public static void main(String[] args) {
        Locale.setDefault(Locale.US);
        WorkspaceInfo.print();
        System.out.println();
        System.out.println("============================================================");
        System.out.println(" MAADRR - Multi-Agent Adaptive Dynamic Round Robin");
        System.out.println("============================================================");
        System.out.printf("Hosts: %d | VMs: %d | Runs/size: %d%n", HOST_COUNT, VM_COUNT, RUNS);
        System.out.printf("DTQ = clip(median(RB) + G_j, %.3f, %.1f)%n", Q_MIN, Q_MAX);
        System.out.printf("Rank(x) = %.0f*RB + %.0f*Priority  [GoCJ: B=0]%n", RANK_A, RANK_B);
        System.out.println("============================================================");

        for (int size : PAPER_SIZES) {
            RunResult total = new RunResult();
            for (int run = 0; run < RUNS; run++) {
                total.add(runExperiment(size));
            }
            total.divide(RUNS);

            System.out.printf(
                "Tasks %4d: AWT=%9.3f ms | ATAT=%9.3f s | Makespan=%9.3f s | Throughput=%.4f tasks/s%n",
                size,
                total.averageWaitingTimeMs,
                total.averageTurnaroundTime,
                total.makespan,
                total.throughput
            );
        }

        System.out.println("============================================================");
        System.out.println(" MAADRR EXPERIMENT COMPLETED");
        System.out.println("============================================================");
    }

    // ============================================================
    // EXPERIMENT
    // ============================================================
    static RunResult runExperiment(int workloadSize) {
        CloudSimPlus simulation = new CloudSimPlus();
        createDatacenter(simulation);
        DatacenterBrokerSimple broker = new DatacenterBrokerSimple(simulation);
        List<Vm> vmList = createVms();
        broker.submitVmList(vmList);

        List<Cloudlet> cloudlets = loadGoCJCloudlets(workloadSize);
        int[] assignment = dynamicMaxMinAssignment(cloudlets, vmList);

        List<VMQueue> vmQueues = new ArrayList<>();
        for (Vm vm : vmList) {
            vmQueues.add(new VMQueue(vm));
        }
        for (int i = 0; i < cloudlets.size(); i++) {
            Cloudlet cl = cloudlets.get(i);
            int vmIdx = assignment[i];
            VMQueue q = vmQueues.get(vmIdx);
            double burst = cl.getLength() / vmList.get(vmIdx).getMips();
            q.tasks.add(new TaskState(cl.getId(), burst));
        }

        // Sort each VM queue by ascending rank (Rank = A*RB; ascending burst)
        for (VMQueue vq : vmQueues) {
            List<TaskState> sorted = new ArrayList<>(vq.tasks);
            sorted.sort(Comparator
                .comparingDouble((TaskState t) -> RANK_A * t.remainingBurst + RANK_B * t.priority)
                .thenComparingLong(t -> t.taskId));
            vq.tasks.clear();
            vq.tasks.addAll(sorted);
        }

        return simulateLocalScheduling(vmQueues);
    }

    // ============================================================
    // LOCAL MAADRR SIMULATION
    // ============================================================
    private static RunResult simulateLocalScheduling(List<VMQueue> vmQueues) {
        long startNano = System.nanoTime();
        RunResult result = new RunResult();
        double globalMakespan = 0.0;
        double sumEfficiency = 0.0;
        double sumEfficiencySq = 0.0;
        double[] vmFinishTimes = new double[vmQueues.size()];

        for (int vIdx = 0; vIdx < vmQueues.size(); vIdx++) {
            VMQueue vmQueue = vmQueues.get(vIdx);
            double currentTime = 0.0;

            while (!vmQueue.tasks.isEmpty()) {
                // DTQ_j = clip(median{RB(x) : x in Q_j} + G_j, q_min, q_max)
                double dtq = computeDTQ(vmQueue);

                // Get head task (lowest rank)
                TaskState task = vmQueue.tasks.pollFirst();

                // Execute one slice: min(dtq, remaining_burst)
                double slice = Math.min(dtq, task.remainingBurst);
                task.remainingBurst -= slice;
                currentTime += slice;

                if (task.remainingBurst <= 1e-9) {
                    task.remainingBurst = 0.0;
                    task.finishTime = currentTime;

                    double waitingTime = Math.max(0.0, task.finishTime - task.originalBurst);
                    double turnaround  = task.finishTime;

                    result.totalWaitingTime     += waitingTime;
                    result.totalTurnaroundTime  += turnaround;
                    result.completedTasks++;
                    globalMakespan = Math.max(globalMakespan, task.finishTime);

                    double efficiency = task.originalBurst / Math.max(task.finishTime, 1e-9);
                    sumEfficiency += efficiency;
                    sumEfficiencySq += efficiency * efficiency;
                } else {
                    currentTime += CS_OVERHEAD;
                    result.totalPreemptions++;
                    insertSorted(vmQueue.tasks, task);
                }
            }
            vmFinishTimes[vIdx] = currentTime;
        }

        long endNano = System.nanoTime();
        result.schedulingOverheadMs = (endNano - startNano) / 1_000_000.0;

        if (result.completedTasks > 0) {
            result.averageWaitingTimeMs  = (result.totalWaitingTime  / result.completedTasks) * 1000.0;
            result.averageTurnaroundTime = result.totalTurnaroundTime / result.completedTasks;
            result.jainsFairnessIndex = (sumEfficiencySq > 0)
                ? (sumEfficiency * sumEfficiency) / (result.completedTasks * sumEfficiencySq)
                : 1.0;
        }

        // Load Imbalance Factor (LIF) across all VMs
        double maxFT = 0.0;
        double minFT = Double.MAX_VALUE;
        double sumFT = 0.0;
        for (double ft : vmFinishTimes) {
            if (ft > maxFT) maxFT = ft;
            if (ft < minFT) minFT = ft;
            sumFT += ft;
        }
        double avgFT = vmQueues.size() > 0 ? (sumFT / vmQueues.size()) : 0.0;
        result.loadImbalanceFactor = (avgFT > 0) ? (maxFT - minFT) / avgFT : 0.0;

        result.makespan   = globalMakespan;
        result.throughput = globalMakespan > 0 ? (double) result.completedTasks / globalMakespan : 0.0;
        return result;
    }

    /**
     * DTQ_j = clip(median{RB(x) : x in Q_j} + G_j, q_min, q_max)
     */
    private static double computeDTQ(VMQueue vq) {
        if (vq.tasks.isEmpty()) return Q_MIN;
        List<Double> bursts = new ArrayList<>();
        for (TaskState t : vq.tasks) {
            if (t.remainingBurst > 0) bursts.add(t.remainingBurst);
        }
        if (bursts.isEmpty()) return Q_MIN;
        bursts.sort(Double::compareTo);
        int sz = bursts.size();
        double median = (sz % 2 == 0)
            ? (bursts.get(sz / 2 - 1) + bursts.get(sz / 2)) / 2.0
            : bursts.get(sz / 2);
        return clamp(median + G_FEEDBACK, Q_MIN, Q_MAX);
    }

    /** Insert task back into deque maintaining ascending-rank order. */
    private static void insertSorted(Deque<TaskState> deque, TaskState task) {
        List<TaskState> list = new ArrayList<>(deque);
        deque.clear();
        boolean inserted = false;
        double taskRank = RANK_A * task.remainingBurst + RANK_B * task.priority;
        for (TaskState t : list) {
            if (!inserted && (RANK_A * t.remainingBurst + RANK_B * t.priority) > taskRank) {
                deque.addLast(task);
                inserted = true;
            }
            deque.addLast(t);
        }
        if (!inserted) deque.addLast(task);
    }

    // ============================================================
    // DYNAMIC MAX-MIN GLOBAL SCHEDULER
    // ============================================================
    static int[] dynamicMaxMinAssignment(List<Cloudlet> cloudlets, List<Vm> vmList) {
        int n = cloudlets.size();
        int[] assignment = new int[n];
        double[] vmLoad = new double[vmList.size()];
        List<Integer> remaining = new ArrayList<>();
        for (int i = 0; i < n; i++) remaining.add(i);

        while (!remaining.isEmpty()) {
            int selectedCloudlet = -1, selectedVm = -1;
            double maxMinCompletion = Double.NEGATIVE_INFINITY;

            for (int ci : remaining) {
                double bestCompletion = Double.POSITIVE_INFINITY;
                int bestVm = -1;
                for (int vi = 0; vi < vmList.size(); vi++) {
                    double exec = cloudlets.get(ci).getLength() / vmList.get(vi).getMips();
                    double completion = vmLoad[vi] + exec;
                    if (completion < bestCompletion) { bestCompletion = completion; bestVm = vi; }
                }
                if (bestCompletion > maxMinCompletion) {
                    maxMinCompletion = bestCompletion;
                    selectedCloudlet = ci;
                    selectedVm = bestVm;
                }
            }

            assignment[selectedCloudlet] = selectedVm;
            vmLoad[selectedVm] += cloudlets.get(selectedCloudlet).getLength() / vmList.get(selectedVm).getMips();
            remaining.remove(Integer.valueOf(selectedCloudlet));
        }
        return assignment;
    }

    // ============================================================
    // INFRASTRUCTURE
    // ============================================================
    private static Datacenter createDatacenter(CloudSimPlus simulation) {
        List<Host> hostList = new ArrayList<>();
        for (int h = 0; h < HOST_COUNT; h++) {
            List<Pe> peList = new ArrayList<>();
            for (int p = 0; p < PES_PER_HOST; p++) peList.add(new PeSimple(HOST_PE_MIPS));
            Host host = new HostSimple(32768, 100000, 1_000_000, peList);
            host.setVmScheduler(new VmSchedulerTimeShared());
            hostList.add(host);
        }
        return new DatacenterSimple(simulation, hostList);
    }

    static List<Vm> createVms() {
        List<Vm> vmList = new ArrayList<>();
        for (int i = 0; i < VM_COUNT; i++) {
            double mips = VM_MIPS_PATTERN[i % VM_MIPS_PATTERN.length];
            Vm vm = new VmSimple(mips, CLOUDLET_PES);
            vm.setRam(VM_RAM).setBw(VM_BW).setSize(VM_STORAGE);
            vm.setCloudletScheduler(new CloudletSchedulerTimeShared());
            vmList.add(vm);
        }
        return vmList;
    }

    // ============================================================
    // GoCJ DATASET LOADER
    // ============================================================
    static List<Cloudlet> loadGoCJCloudlets(int workloadSize) {
        String file = "data/GoCJ_Dataset_" + workloadSize + ".txt";
        List<Cloudlet> cloudlets = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            long id = 0;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                for (String token : line.split("[,\\s]+")) {
                    if (token.isBlank()) continue;
                    double value;
                    try { value = Double.parseDouble(token); } catch (NumberFormatException e) { continue; }
                    long length = Math.max(1L, Math.round(value));
                    Cloudlet cl = new CloudletSimple(id++, length, CLOUDLET_PES);
                    cl.setUtilizationModelCpu(new UtilizationModelFull());
                    cl.setUtilizationModelRam(new UtilizationModelDynamic(CLOUDLET_RAM_UTILIZATION));
                    cl.setUtilizationModelBw(new UtilizationModelDynamic(CLOUDLET_BW_UTILIZATION));
                    cloudlets.add(cl);
                    if (cloudlets.size() >= workloadSize) return cloudlets;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Cannot read: " + file, e);
        }
        if (cloudlets.size() < workloadSize)
            throw new IllegalStateException("Dataset has only " + cloudlets.size() + " tasks, expected " + workloadSize);
        return cloudlets;
    }

    // ============================================================
    // UTILITY
    // ============================================================
    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    // ============================================================
    // DATA CLASSES
    // ============================================================
    private static class VMQueue {
        @SuppressWarnings("unused")
        final Vm vm;
        final Deque<TaskState> tasks = new ArrayDeque<>();
        VMQueue(Vm vm) { this.vm = vm; }
    }

    private static class TaskState {
        final long taskId;
        final double originalBurst;
        double remainingBurst;
        double finishTime;
        final double priority = 1.0; // GoCJ has no priority field

        TaskState(long taskId, double burst) {
            this.taskId         = taskId;
            this.originalBurst  = burst;
            this.remainingBurst = burst;
        }
    }

    static class RunResult {
        double totalWaitingTime;
        double totalTurnaroundTime;
        double averageWaitingTimeMs;
        double averageTurnaroundTime;
        double makespan;
        double throughput;
        long   completedTasks;
        double jainsFairnessIndex;
        double loadImbalanceFactor;
        long   totalPreemptions;
        double schedulingOverheadMs;

        void add(RunResult o) {
            totalWaitingTime      += o.totalWaitingTime;
            totalTurnaroundTime   += o.totalTurnaroundTime;
            averageWaitingTimeMs  += o.averageWaitingTimeMs;
            averageTurnaroundTime += o.averageTurnaroundTime;
            makespan              += o.makespan;
            throughput            += o.throughput;
            completedTasks        += o.completedTasks;
            jainsFairnessIndex    += o.jainsFairnessIndex;
            loadImbalanceFactor   += o.loadImbalanceFactor;
            totalPreemptions      += o.totalPreemptions;
            schedulingOverheadMs  += o.schedulingOverheadMs;
        }

        void divide(int d) {
            if (d <= 0) return;
            totalWaitingTime      /= d;
            totalTurnaroundTime   /= d;
            averageWaitingTimeMs  /= d;
            averageTurnaroundTime /= d;
            makespan              /= d;
            throughput            /= d;
            completedTasks        /= d;
            jainsFairnessIndex    /= d;
            loadImbalanceFactor   /= d;
            totalPreemptions      /= d;
            schedulingOverheadMs  /= d;
        }
    }
}
