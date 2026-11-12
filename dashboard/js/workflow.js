/**
 * SSP-ARR Cloud Scheduling Intelligence Dashboard
 * Interactive Scheduling Workflow Engine
 */

const WorkflowEngine = (function() {
    const stages = {
        'input': {
            title: '1. Workload Ingestion (GoCJ Real Traces)',
            badge: 'Input Phase',
            desc: 'Parses synthetic and real Google Cloud Jobs (GoCJ) benchmark traces comprising 100 to 2000 cloudlets with execution bursts between 15,000 and 900,000 Million Instructions (MI).',
            formula: 'L_i \\in [1.5 \\times 10^4, 9.0 \\times 10^5] \\text{ MI}',
            inputs: 'Raw trace logs data/GoCJ_Dataset_<size>.txt, task arrival timestamp (t=0 batch).',
            outputs: 'List<Cloudlet> with CPU utilization model and instruction length vectors.',
            parameters: 'Workload Size: 100–2000 Tasks | Task PEs: 1 | RAM: Dynamic | BW: Dynamic',
            status: 'Active (Real Benchmark Loaded)'
        },
        'global': {
            title: '2. Tier-1 Global Task Dispatcher',
            badge: 'Broker Layer',
            desc: 'The Datacenter Broker runs one of 5 evaluated global scheduling policies to compute optimal task-to-VM mapping, preventing initial load skew.',
            formula: '\\text{Assignment}[] = \\text{GlobalSchedulers.assign}(P, C, V)',
            inputs: 'Cloudlet list C (|C| = n), Heterogeneous VM pool V (|V| = m).',
            outputs: 'Assignment array of size n, mapping each task index to VM index [0..79].',
            parameters: 'Available Dispatchers: Dy. MM, DRALBA, PSSLB, PSSELB, DeRALBA.',
            status: 'Evaluated across 5 Policies'
        },
        'vm_queues': {
            title: '3. Heterogeneous VM Queue Allocation',
            badge: 'Hypervisor Queue',
            desc: 'Dispatched tasks enter the local ready queue Q_j of their assigned VM. Initial sorting is performed in ascending order of burst length (SRPT priority).',
            formula: 'B_{i,j} = \\frac{L_i}{\\text{MIPS}_j}, \\quad Q_j = \\text{sort}_{\\text{asc}}(B_{i,j})',
            inputs: 'Assigned cloudlets for VM_j, VM processing speed MIPS_j \\in [1000, 4000].',
            outputs: 'Internal ready queue Q_j with TaskState objects and initial burst tracking.',
            parameters: 'VM Count: 80 | Capacity: 1000–4000 MIPS (steps of 200 MIPS) | RAM: 8 GB',
            status: '80 Queues Active'
        },
        'ssp_local': {
            title: '4. Tier-2 Local SSP-ARR Scheduler',
            badge: 'Proposed Algorithm',
            desc: 'Each VM independently governs its own local queue. Instead of static round robin slicing, SSP-ARR dynamically decides whether to execute reactively or predictively.',
            formula: '\\text{Mode} = (CV_j \\le 0.50) \\;?\\; \\text{Reactive} \\;:\\; \\text{Predictive}',
            inputs: 'Head task t from Q_j, sliding burst history H_j of last W=5 completed cloudlets.',
            outputs: 'Optimal time slice quantum Q_j, updated task remaining burst.',
            parameters: 'History Window W = 5 | Bounds: Q_min = 0.001s, Q_max = 60.0s',
            status: 'Autonomous Per-VM Execution'
        },
        'volatility': {
            title: '5. Volatility Tracking (Coefficient of Variation)',
            badge: 'Mathematical Sensor',
            desc: 'Calculates the real-time sample mean and standard deviation of recent execution bursts to measure workload dispersion and phase changes.',
            formula: 'CV_j = \\frac{\\sigma_j}{\\mu_j} = \\frac{\\sqrt{\\frac{1}{W}\\sum (B_k - \\mu_j)^2}}{\\frac{1}{W}\\sum B_k}',
            inputs: 'ArrayDeque<Double> completedBurstHistory (sliding window size 5).',
            outputs: 'Floating point volatility index CV_j. Stable if CV <= 0.50, Volatile if CV > 0.50.',
            parameters: 'Stability Threshold: \\theta_{CV} = 0.50 | Window: W = 5 bursts',
            status: 'Continuous Rolling Evaluation'
        },
        'reactive_mode': {
            title: '6A. Safe Reactive Mode (Low Volatility)',
            badge: 'Safe Branch',
            desc: 'When burst variability is low (CV <= 0.50), the quantum is safely set to the median of all remaining bursts in the queue, bounded by the candidate task burst.',
            formula: 'Q_{\\text{reactive}} = \\text{median}(\\{RB(x) : x \\in Q_j\\} \\cup \\{RB(t)\\})',
            inputs: 'Remaining bursts RB of all currently queued tasks on VM_j.',
            outputs: 'Median-sized quantum slice ensuring equitable processor sharing without drift.',
            parameters: 'Q = min(median, RB(t)) clamped to [0.001s, 60.0s]',
            status: 'Stable Regime Active'
        },
        'predictive_mode': {
            title: '6B. Adaptive Predictive Mode (High Volatility)',
            badge: 'Proactive Branch',
            desc: 'When workload bursts exhibit high dispersion (CV > 0.50), an Exponentially Weighted Moving Average engine forecasts optimal quantum sizing with error-modulated learning rate alpha.',
            formula: '\\hat{B}_{j,t+1} = \\alpha_t B_{j,t} + (1 - \\alpha_t) \\hat{B}_{j,t}',
            inputs: 'Most recent burst B_{j,t}, previous prediction, Normalized Prediction Error NPE_t.',
            outputs: 'Proactively forecasted burst duration hat{B}_{j,t+1}.',
            parameters: 'Adaptive \\alpha: <10% err -> 0.20 | 10-20% -> 0.40 | 20-30% -> 0.60 | >30% -> 0.80',
            status: 'Activated during Phase Shifts'
        },
        'confidence_gate': {
            title: '7. Confidence Gate & Safety Fallback',
            badge: 'Fault-Tolerant Gate',
            desc: 'Prevents prediction drift. If rolling Normalized Prediction Error exceeds 20%, the system automatically aborts prediction and falls back to Safe Reactive median slicing.',
            formula: 'NPE_t = \\frac{|B_{j,t} - \\hat{B}_{j,t}|}{B_{j,t}} \\le \\tau_{\\text{gate}} (0.20)',
            inputs: 'History of last 5 normalized prediction errors.',
            outputs: 'Boolean gate decision: Verified Reliable (Use EWMA) or Fallback (Use Median).',
            parameters: 'Max Normalized Error Gate: \\tau_{gate} = 0.20 (20%) | Window = 5',
            status: '100% Safety Enforcement'
        },
        'execution': {
            title: '8. SRPT Execution & Starvation Aging',
            badge: 'Execution & Aging',
            desc: 'Executes slice. Preempted tasks incur a 5ms context switch penalty and are re-inserted into the ready queue using an aging-boosted priority to prevent starvation.',
            formula: '\\text{EffectiveBurst} = \\max(0.001, RB - 0.01 \\times \\text{waitingPasses})',
            inputs: 'Allocated quantum Q_j, task remaining burst, accumulated queue passes.',
            outputs: 'CPU time advancement, completion timestamp or preemption re-insertion.',
            parameters: 'Context-Switch Penalty: \\delta_{cs} = 0.005s (5ms) | Aging Factor = 0.01',
            status: '99.3% Preemption Reduction'
        },
        'metrics': {
            title: '9. Multi-Dimensional Metrics Collection',
            badge: 'Analytics Layer',
            desc: 'Computes performance, resource, fairness, and energy metrics across the entire cloud cluster, logging comprehensive results for evaluation.',
            formula: 'AWT, ATAT, ACT, TCT, \\text{Makespan}, \\text{TP}, \\text{Energy (SPECpower)}, JFI, LIF',
            inputs: 'Individual task finish times, CPU busy times, host idle/max power profiles.',
            outputs: 'Standardized empirical evaluation metrics across 12 workload scales.',
            parameters: 'Host P_idle = 150W, P_max = 250W (Linear SPECpower benchmark model)',
            status: 'All 10 Metrics Tracked'
        }
    };

    function selectStage(stageKey) {
        const stage = stages[stageKey];
        if (!stage) return;

        // Highlight active node
        document.querySelectorAll('.wf-node').forEach(node => {
            node.classList.remove('active');
            if (node.getAttribute('data-stage') === stageKey) {
                node.classList.add('active');
            }
        });

        // Update inspector drawer
        const panel = document.getElementById('stageInspector');
        if (panel) {
            panel.classList.add('visible');
            document.getElementById('inspTitle').textContent = stage.title;
            document.getElementById('inspBadge').textContent = stage.badge;
            document.getElementById('inspDesc').textContent = stage.desc;
            document.getElementById('inspFormula').textContent = stage.formula;
            document.getElementById('inspInputs').textContent = stage.inputs;
            document.getElementById('inspOutputs').textContent = stage.outputs;
            document.getElementById('inspParams').textContent = stage.parameters;
            document.getElementById('inspStatus').textContent = stage.status;
            panel.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        }
    }

    return {
        selectStage
    };
})();
