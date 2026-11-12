package com.cloudscheduling.simulation;

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
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Standard Classical Round Robin (RR) Simulation.
 *
 * Local VM-level scheduler:
 *   - Fixed CPU Time Quantum (q = 1.0s or configurable)
 *   - Standard FIFO circular queue per VM (classical RR, unranked arrival order)
 *
 * Global infrastructure and Dynamic Max-Min task allocation are identical
 * to SSP-ARR and MAADRR setups for fair, rigorous baseline comparison.
 */
public class RRSimulation {

    // ============================================================
    // INFRASTRUCTURE (Identical to SSP-ARR & MAADRR)
    // ============================================================
    private static final int    HOST_COUNT   = 30;
    private static final int    PES_PER_HOST = 4;
    private static final double HOST_PE_MIPS = 4000.0;
    private static final int    VM_COUNT     = 80;

    // Standard fixed quantum for classical Round Robin
    public static final double DEFAULT_FIXED_QUANTUM = 1.0; // 1 second
    public static final double CS_OVERHEAD           = 0.005; // 5ms context-switch overhead per preemption

    private static final int RUNS = 5;
    private static final int[] PAPER_SIZES = {
        100, 200, 300, 400, 500, 600,
        700, 800, 900, 1000, 1500, 2000
    };

    public static void main(String[] args) {
        Locale.setDefault(Locale.US);
        WorkspaceInfo.print();
        System.out.println();
        System.out.println("============================================================");
        System.out.println(" Classical Round Robin (RR) - Fixed Time Quantum Baseline");
        System.out.println("============================================================");
        System.out.printf("Hosts: %d | VMs: %d | Runs/size: %d | Quantum: %.2f s%n",
                HOST_COUNT, VM_COUNT, RUNS, DEFAULT_FIXED_QUANTUM);
        System.out.println("============================================================");

        for (int size : PAPER_SIZES) {
            RunResult total = new RunResult();
            for (int run = 0; run < RUNS; run++) {
                total.add(runExperiment(size, DEFAULT_FIXED_QUANTUM));
            }
            total.divide(RUNS);

            System.out.printf(
                "Tasks %4d: AWT=%11.3f ms | ATAT=%9.3f s | MS=%8.2f s | TP=%.4f | JFI=%.4f | LIF=%.3f | Preempt=%d%n",
                size,
                total.averageWaitingTimeMs,
                total.averageTurnaroundTime,
                total.makespan,
                total.throughput,
                total.jainsFairnessIndex,
                total.loadImbalanceFactor,
                total.totalPreemptions
            );
        }

        System.out.println("============================================================");
        System.out.println(" RR BASELINE EXPERIMENT COMPLETED");
        System.out.println("============================================================");
    }

    public static RunResult runExperiment(int workloadSize, double fixedQuantum) {
        CloudSimPlus simulation = new CloudSimPlus();

        List<Host> hostList = new ArrayList<>();
        for (int h = 0; h < HOST_COUNT; h++) {
            List<Pe> peList = new ArrayList<>();
            for (int p = 0; p < PES_PER_HOST; p++) {
                peList.add(new PeSimple(HOST_PE_MIPS));
            }
            Host host = new HostSimple(32768, 100000, 1_000_000, peList);
            host.setVmScheduler(new VmSchedulerTimeShared());
            hostList.add(host);
        }
        new DatacenterSimple(simulation, hostList);

        DatacenterBrokerSimple broker = new DatacenterBrokerSimple(simulation);
        List<Vm> vmList = MAADRRSimulation.createVms();
        broker.submitVmList(vmList);

        List<Cloudlet> cloudlets = MAADRRSimulation.loadGoCJCloudlets(workloadSize);
        int[] assignment = MAADRRSimulation.dynamicMaxMinAssignment(cloudlets, vmList);

        List<VMQueue> vmQueues = new ArrayList<>();
        for (Vm vm : vmList) {
            vmQueues.add(new VMQueue(vm));
        }

        // Classical RR: tasks enter FIFO queue in arrival order (no priority/burst ranking)
        for (int i = 0; i < cloudlets.size(); i++) {
            Cloudlet cl = cloudlets.get(i);
            int vmIdx = assignment[i];
            double burst = cl.getLength() / vmList.get(vmIdx).getMips();
            vmQueues.get(vmIdx).tasks.add(new TaskState(cl.getId(), cl, burst));
        }

        return simulateLocalRR(vmQueues, fixedQuantum);
    }

    private static RunResult simulateLocalRR(List<VMQueue> vmQueues, double fixedQuantum) {
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
                TaskState task = vmQueue.tasks.pollFirst();

                double slice = Math.min(fixedQuantum, task.remainingBurst);
                task.remainingBurst -= slice;
                currentTime += slice;

                if (task.remainingBurst <= 1e-9) {
                    task.remainingBurst = 0.0;
                    task.finishTime = currentTime;

                    double waitingTime = Math.max(0.0, task.finishTime - task.originalBurst);
                    result.totalWaitingTime    += waitingTime;
                    result.totalTurnaroundTime += task.finishTime;
                    result.completedTasks++;
                    globalMakespan = Math.max(globalMakespan, task.finishTime);

                    double efficiency = task.originalBurst / Math.max(task.finishTime, 1e-9);
                    sumEfficiency += efficiency;
                    sumEfficiencySq += efficiency * efficiency;
                } else {
                    // Standard RR: preempted task incurs context-switch overhead and goes to tail of circular FIFO queue
                    currentTime += CS_OVERHEAD;
                    result.totalPreemptions++;
                    vmQueue.tasks.addLast(task);
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

    private static class VMQueue {
        @SuppressWarnings("unused")
        final Vm vm;
        final Deque<TaskState> tasks = new ArrayDeque<>();
        VMQueue(Vm vm) { this.vm = vm; }
    }

    private static class TaskState {
        @SuppressWarnings("unused")
        final long      cloudletId;
        @SuppressWarnings("unused")
        final Cloudlet  cloudlet;
        final double    originalBurst;
        double          remainingBurst;
        double          finishTime;

        TaskState(long id, Cloudlet cl, double burst) {
            this.cloudletId         = id;
            this.cloudlet           = cl;
            this.originalBurst      = burst;
            this.remainingBurst     = burst;
        }
    }

    public static class RunResult {
        public double totalWaitingTime;
        public double totalTurnaroundTime;
        public double averageWaitingTimeMs;
        public double averageTurnaroundTime;
        public double makespan;
        public double throughput;
        public long   completedTasks;
        public double jainsFairnessIndex;
        public double loadImbalanceFactor;
        public long   totalPreemptions;
        public double schedulingOverheadMs;

        public void add(RunResult o) {
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

        public void divide(int d) {
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
