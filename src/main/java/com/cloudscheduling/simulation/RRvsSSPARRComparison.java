package com.cloudscheduling.simulation;

import java.util.Locale;

/**
 * RRvsSSPARRComparison – Head-to-Head Comparison: Classical Round Robin vs. SSP-ARR.
 *
 * Both algorithms run under completely identical conditions:
 *   - Same Datacenter (30 Hosts, 120 PEs @ 4000 MIPS)
 *   - Same Heterogeneous VMs (80 VMs, 1000–4000 MIPS)
 *   - Same GoCJ Workload Traces (100 to 2000 tasks)
 *   - Same Dynamic Max-Min Global Scheduling Task Assignment
 *
 * Differences (local VM-level tier):
 *   - RR: Classical fixed time quantum (10.0 s) with FIFO queue (baseline)
 *   - SSP-ARR: Stability-switched, confidence-gated predictive quantum with SRPT ranking
 *
 * Improvement formulas:
 *   - AWT, ATAT, Makespan (lower is better): (RR - SSP_ARR) / RR * 100
 *   - Throughput (higher is better): (SSP_ARR - RR) / RR * 100
 */
public class RRvsSSPARRComparison {

    private static final int RUNS = 5;
    // Standard baseline quantum for Classical RR in cloud benchmarking is 1.0s (can be toggled or configured)
    private static final double RR_QUANTUM = 1.0; 
    private static final int[] PAPER_SIZES = {
        100, 200, 300, 400, 500, 600,
        700, 800, 900, 1000, 1500, 2000
    };

    public static void main(String[] args) {
        Locale.setDefault(Locale.US);
        WorkspaceInfo.print();
        System.out.println();
        System.out.println("==========================================================================================================================");
        System.out.println(" EXPERIMENTAL EVALUATION: Classical Round Robin (RR) vs. SSP-ARR");
        System.out.println(" Benchmark: Real-world GoCJ Workload Traces (100 - 2000 cloudlets)");
        System.out.println(" Baseline: Classical RR (Fixed Quantum = " + RR_QUANTUM + "s, FIFO, Context Switch = " + RRSimulation.CS_OVERHEAD * 1000 + "ms)");
        System.out.println(" Proposed: SSP-ARR v3 (Adaptive Predictive Quantum, Stability-Switched, Aging-Protected, CS = " + SSPARRSimulation.CS_OVERHEAD * 1000 + "ms)");
        System.out.println(" Metrics: AWT (ms), ATAT (s), Makespan (s), Throughput (tasks/s), Jain's Fairness (JFI), Load Imbalance (LIF)");
        System.out.println("==========================================================================================================================");
        System.out.println();

        double sumAwtImp = 0, sumAtatImp = 0, sumMsImp = 0, sumTpImp = 0, sumJfiImp = 0, sumLifImp = 0, sumPreemptImp = 0;
        int count = 0;

        System.out.printf("%-5s | %-17s | %-17s | %-17s | %-15s | %-15s | %-13s | %-15s%n",
            "Tasks",
            "AWT(ms) [RR/SSP]",
            "ATAT(s) [RR/SSP]",
            "Makespan(s)[RR/SSP]",
            "TP [RR/SSP]",
            "JFI [RR/SSP]",
            "LIF [RR/SSP]",
            "Preempt [RR/SSP]");
        System.out.println("-".repeat(126));

        for (int size : PAPER_SIZES) {
            RRSimulation.RunResult rrResult = new RRSimulation.RunResult();
            SSPARRSimulation.RunResult sspResult = new SSPARRSimulation.RunResult();

            for (int r = 0; r < RUNS; r++) {
                rrResult.add(RRSimulation.runExperiment(size, RR_QUANTUM));
                sspResult.add(SSPARRSimulation.runExperiment(size));
            }
            rrResult.divide(RUNS);
            sspResult.divide(RUNS);

            double awtImp     = pctImprove(rrResult.averageWaitingTimeMs,  sspResult.averageWaitingTimeMs,  false);
            double atatImp    = pctImprove(rrResult.averageTurnaroundTime, sspResult.averageTurnaroundTime, false);
            double msImp      = pctImprove(rrResult.makespan,              sspResult.makespan,              false);
            double tpImp      = pctImprove(rrResult.throughput,            sspResult.throughput,            true);
            double jfiImp     = pctImprove(rrResult.jainsFairnessIndex,    sspResult.jainsFairnessIndex,    true);
            double lifImp     = pctImprove(rrResult.loadImbalanceFactor,   sspResult.loadImbalanceFactor,   false);
            double preemptImp = pctImprove(rrResult.totalPreemptions,      sspResult.totalPreemptions,      false);

            System.out.printf(
                "%-5d | %7.1f / %7.1f | %7.2f / %7.2f | %7.2f / %7.2f | %6.3f / %6.3f | %6.4f/%6.4f | %5.3f / %5.3f | %6d / %6d%n",
                size,
                rrResult.averageWaitingTimeMs,  sspResult.averageWaitingTimeMs,
                rrResult.averageTurnaroundTime, sspResult.averageTurnaroundTime,
                rrResult.makespan,              sspResult.makespan,
                rrResult.throughput,            sspResult.throughput,
                rrResult.jainsFairnessIndex,    sspResult.jainsFairnessIndex,
                rrResult.loadImbalanceFactor,   sspResult.loadImbalanceFactor,
                rrResult.totalPreemptions,      sspResult.totalPreemptions
            );
            System.out.printf(
                "      | AWT: %+5.1f%%        | ATAT: %+5.1f%%       | MS: %+5.1f%%         | TP: %+5.1f%%     | JFI: %+5.2f%%   | LIF: %+5.1f%%   | Preempt: %+5.1f%%%n",
                awtImp, atatImp, msImp, tpImp, jfiImp, lifImp, preemptImp
            );
            System.out.println("-".repeat(126));

            sumAwtImp     += awtImp;
            sumAtatImp    += atatImp;
            sumMsImp      += msImp;
            sumTpImp      += tpImp;
            sumJfiImp     += jfiImp;
            sumLifImp     += lifImp;
            sumPreemptImp += preemptImp;
            count++;
        }

        System.out.println();
        System.out.println("==========================================================================================================================");
        System.out.println(" SUMMARY: AVERAGE PERCENTAGE IMPROVEMENT OF SSP-ARR OVER CLASSICAL RR");
        System.out.println("==========================================================================================================================");
        System.out.printf("  Average Waiting Time (AWT) Reduction    : %+6.2f%%%n", sumAwtImp     / count);
        System.out.printf("  Average Turnaround Time (ATAT) Reduction : %+6.2f%%%n", sumAtatImp    / count);
        System.out.printf("  Makespan Improvement                    : %+6.2f%%%n", sumMsImp      / count);
        System.out.printf("  Throughput Improvement                  : %+6.2f%%%n", sumTpImp      / count);
        System.out.printf("  Jain's Fairness Index (JFI) Improvement : %+6.2f%%%n", sumJfiImp     / count);
        System.out.printf("  Load Imbalance Factor (LIF) Improvement : %+6.2f%%%n", sumLifImp     / count);
        System.out.printf("  Preemption Overhead Reduction           : %+6.2f%%%n", sumPreemptImp / count);
        System.out.println("==========================================================================================================================");
    }

    private static double pctImprove(double baseline, double proposed, boolean higherBetter) {
        if (baseline == 0) return 0;
        if (higherBetter) {
            return (proposed - baseline) / baseline * 100.0;
        }
        return (baseline - proposed) / baseline * 100.0;
    }
}

