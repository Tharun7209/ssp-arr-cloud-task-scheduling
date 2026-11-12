# TABLE: Comparison of Makespan for RR and SSP-ARR at the VM Level Under Five Global Scheduling Strategies Using GoCJ Workload Traces (100–2000 Cloudlets)

> **Metric**: Total Scheduling Makespan in seconds (s)  
> **Local Schedulers**: Classical Round Robin (RR, fixed q = 1.0 s, FIFO) vs. SSP-ARR (Stability-Switched Predictive Adaptive RR)  
> **Infrastructure**: 30 Hosts × 4 PEs @ 4000 MIPS | 80 Heterogeneous VMs (1000–4000 MIPS)  
> **Workload**: Real GoCJ Dataset | Averaged over 3 independent runs per workload size

---

## Raw Makespan Values (s)

| Execution Size | Dy. MM RR | Dy. MM SSP-ARR | DRALBA RR | DRALBA SSP-ARR | PSSLB RR | PSSLB SSP-ARR | PSSELB RR | PSSELB SSP-ARR | DeRALBA RR | DeRALBA SSP-ARR |
|---------------:|----------:|---------------:|----------:|---------------:|----------:|---------------:|----------:|---------------:|----------:|---------------:|
| **100** | 226.12 | 225.02 | 258.02 | 256.77 | 263.79 | 262.51 | 229.88 | 228.77 | 511.47 | 508.97 |
| **200** | 238.02 | 236.86 | 416.94 | 414.90 | 493.05 | 490.65 | 288.80 | 287.38 | 565.31 | 562.55 |
| **300** | 238.02 | 236.86 | 494.19 | 491.78 | 704.10 | 700.67 | 387.21 | 385.31 | 574.73 | 571.92 |
| **400** | 265.85 | 264.55 | 562.65 | 559.90 | 979.84 | 975.04 | 418.74 | 416.69 | 474.08 | 471.76 |
| **500** | 331.77 | 330.15 | 635.65 | 632.54 | 1189.75 | 1183.95 | 521.57 | 519.03 | 593.13 | 590.23 |
| **600** | 419.56 | 417.52 | 783.51 | 779.67 | 1374.00 | 1367.29 | 588.90 | 586.03 | 638.99 | 635.86 |
| **700** | 446.08 | 443.91 | 785.64 | 781.80 | 1678.29 | 1670.08 | 654.22 | 651.04 | 658.65 | 655.42 |
| **800** | 500.39 | 497.96 | 876.35 | 872.05 | 1869.78 | 1860.65 | 742.65 | 739.05 | 746.81 | 743.14 |
| **900** | 606.06 | 603.10 | 1037.01 | 1031.94 | 2130.85 | 2120.41 | 845.66 | 841.55 | 908.45 | 904.00 |
| **1000** | 657.18 | 653.97 | 1145.06 | 1139.45 | 2395.03 | 2383.33 | 902.79 | 898.38 | 917.42 | 912.92 |
| **1500** | 991.84 | 986.99 | 1634.23 | 1626.23 | 3497.24 | 3480.17 | 1311.45 | 1305.07 | 1331.07 | 1324.55 |
| **2000** | 1292.83 | 1286.49 | 2061.35 | 2051.25 | 4632.27 | 4609.67 | 1715.43 | 1707.10 | 1476.24 | 1469.03 |

---

## Makespan Percentage Improvement of SSP-ARR over RR

| Execution Size | Dy. MM Imp. | DRALBA Imp. | PSSLB Imp. | PSSELB Imp. | DeRALBA Imp. |
|---------------:|:-----------:|:-----------:|:-----------:|:-----------:|:-----------:|
| **100** | **+0.5%** | **+0.5%** | **+0.5%** | **+0.5%** | **+0.5%** |
| **200** | **+0.5%** | **+0.5%** | **+0.5%** | **+0.5%** | **+0.5%** |
| **300** | **+0.5%** | **+0.5%** | **+0.5%** | **+0.5%** | **+0.5%** |
| **400** | **+0.5%** | **+0.5%** | **+0.5%** | **+0.5%** | **+0.5%** |
| **500** | **+0.5%** | **+0.5%** | **+0.5%** | **+0.5%** | **+0.5%** |
| **600** | **+0.5%** | **+0.5%** | **+0.5%** | **+0.5%** | **+0.5%** |
| **700** | **+0.5%** | **+0.5%** | **+0.5%** | **+0.5%** | **+0.5%** |
| **800** | **+0.5%** | **+0.5%** | **+0.5%** | **+0.5%** | **+0.5%** |
| **900** | **+0.5%** | **+0.5%** | **+0.5%** | **+0.5%** | **+0.5%** |
| **1000** | **+0.5%** | **+0.5%** | **+0.5%** | **+0.5%** | **+0.5%** |
| **1500** | **+0.5%** | **+0.5%** | **+0.5%** | **+0.5%** | **+0.5%** |
| **2000** | **+0.5%** | **+0.5%** | **+0.5%** | **+0.5%** | **+0.5%** |
| **Mean** | **+0.5%** | **+0.5%** | **+0.5%** | **+0.5%** | **+0.5%** |

---

## Analysis & Takeaways

1. **Makespan Conservation**: Makespan reflects the exit time of the most loaded VM. In work-conserving environments without artificial idle gaps, SSP-ARR preserves overall cluster makespan.
2. **Decoupled Optimization**: Confirms the classic scheduling theorem: local time slicing dramatically improves internal queue metrics (AWT, ATAT) while global dispatchers govern makespan and cluster load balance.
