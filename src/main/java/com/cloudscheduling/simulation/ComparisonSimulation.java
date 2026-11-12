package com.cloudscheduling.simulation;

import java.util.Locale;

/**
 * ComparisonSimulation – runs MAADRR vs SSP-ARR on identical infrastructure,
 * workloads, VM assignments and random seeds, then prints a side-by-side
 * comparative table with percentage improvements.
 *
 * Improvement formula (lower-is-better metrics: AWT, ATAT, Makespan):
 *   Improvement(%) = (MAADRR - SSP-ARR) / MAADRR * 100
 *
 * Improvement formula (higher-is-better: Throughput):
 *   Improvement(%) = (SSP-ARR - MAADRR) / MAADRR * 100
 */
public class ComparisonSimulation {

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
        System.out.println(" MAADRR vs SSP-ARR – Head-to-Head Comparison");
        System.out.println(" Same Infra | Same Workload | Same VM Assignment");
        System.out.println(" Only local scheduler differs.");
        System.out.println("============================================================");
        System.out.println();

        // Accumulators for global averages
        double sumAwtImp = 0, sumAtatImp = 0, sumMsImp = 0, sumTpImp = 0, sumJfiImp = 0, sumLifImp = 0, sumPreemptImp = 0;
        int count = 0;

        // Column header
        System.out.printf("%-5s | %-16s | %-16s | %-17s | %-14s | %-13s | %-13s | %-13s%n",
            "Tasks",
            "AWT(ms)[MAA/SSP]",
            "ATAT(s)[MAA/SSP]",
            "Makespan[MAA/SSP]",
            "TP [MAA/SSP]",
            "JFI [MAA/SSP]",
            "LIF [MAA/SSP]",
            "Preempt[MAA/SSP]");
        System.out.println("-".repeat(122));

        for (int size : PAPER_SIZES) {
            MAADRRSimulation.RunResult maadrr = new MAADRRSimulation.RunResult();
            SSPARRSimulation.RunResult ssparr = new SSPARRSimulation.RunResult();

            for (int run = 0; run < RUNS; run++) {
                maadrr.add(MAADRRSimulation.runExperiment(size));
                ssparr.add(SSPARRSimulation.runExperiment(size));
            }
            maadrr.divide(RUNS);
            ssparr.divide(RUNS);

            // Percentage improvements (positive = SSP-ARR wins)
            double awtImp     = pctImprove(maadrr.averageWaitingTimeMs,  ssparr.averageWaitingTimeMs,  false);
            double atatImp    = pctImprove(maadrr.averageTurnaroundTime, ssparr.averageTurnaroundTime, false);
            double msImp      = pctImprove(maadrr.makespan,              ssparr.makespan,              false);
            double tpImp      = pctImprove(maadrr.throughput,            ssparr.throughput,            true);
            double jfiImp     = pctImprove(maadrr.jainsFairnessIndex,    ssparr.jainsFairnessIndex,    true);
            double lifImp     = pctImprove(maadrr.loadImbalanceFactor,   ssparr.loadImbalanceFactor,   false);
            double preemptImp = pctImprove(maadrr.totalPreemptions,      ssparr.totalPreemptions,      false);

            System.out.printf(
                "%-5d | %7.1f / %7.1f | %7.2f / %7.2f | %7.2f / %7.2f | %5.3f / %5.3f | %5.3f/%5.3f | %5.3f/%5.3f | %5d / %5d%n",
                size,
                maadrr.averageWaitingTimeMs,  ssparr.averageWaitingTimeMs,
                maadrr.averageTurnaroundTime, ssparr.averageTurnaroundTime,
                maadrr.makespan,              ssparr.makespan,
                maadrr.throughput,            ssparr.throughput,
                maadrr.jainsFairnessIndex,    ssparr.jainsFairnessIndex,
                maadrr.loadImbalanceFactor,   ssparr.loadImbalanceFactor,
                maadrr.totalPreemptions,      ssparr.totalPreemptions
            );
            System.out.printf(
                "      | AWT: %+5.1f%%        | ATAT: %+5.1f%%       | MS: %+5.1f%%         | TP: %+5.1f%%    | JFI: %+5.1f%% | LIF: %+5.1f%% | Preempt: %+5.1f%%%n",
                awtImp, atatImp, msImp, tpImp, jfiImp, lifImp, preemptImp
            );
            System.out.println("-".repeat(122));

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
        System.out.println("=====================================================================================================");
        System.out.println(" OVERALL AVERAGE IMPROVEMENT of SSP-ARR over MAADRR");
        System.out.println("=====================================================================================================");
        System.out.printf("  AWT      improvement: %+6.2f%%%n", sumAwtImp     / count);
        System.out.printf("  ATAT     improvement: %+6.2f%%%n", sumAtatImp    / count);
        System.out.printf("  Makespan improvement: %+6.2f%%%n", sumMsImp      / count);
        System.out.printf("  Throughput improvement: %+6.2f%%%n", sumTpImp      / count);
        System.out.printf("  Jain's Fairness Index improvement: %+6.2f%%%n", sumJfiImp     / count);
        System.out.printf("  Load Imbalance Factor improvement: %+6.2f%%%n", sumLifImp     / count);
        System.out.printf("  Preemption Reduction : %+6.2f%%%n", sumPreemptImp / count);
        System.out.println("=====================================================================================================");
        System.out.println(" COMPARISON COMPLETE");
        System.out.println("============================================================");
    }

    /**
     * Calculates percentage improvement of SSP-ARR relative to MAADRR.
     * @param baseline  MAADRR value
     * @param proposed  SSP-ARR value
     * @param higherBetter  true for throughput, false for AWT/ATAT/Makespan
     */
    private static double pctImprove(double baseline, double proposed, boolean higherBetter) {
        if (baseline == 0) return 0;
        if (higherBetter) return (proposed - baseline) / baseline * 100.0;
        return (baseline - proposed) / baseline * 100.0;
    }
}
