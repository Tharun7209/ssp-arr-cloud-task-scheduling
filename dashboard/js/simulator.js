/**
 * SSP-ARR Cloud Scheduling Intelligence Dashboard
 * Interactive Visual Simulation Playground & Execution Engine
 * 
 * Provides an authentic in-browser discrete-event execution simulation
 * demonstrating Classical RR vs. Proposed SSP-ARR side-by-side.
 */

const LiveSimulatorEngine = (function() {
    // Simulator Configuration & State
    let isRunning = false;
    let isPaused = false;
    let timerId = null;
    let speedMs = 120; // tick interval
    let tasksTotal = 30;
    let csOverheadS = 0.005; // 5ms hardware preemption overhead
    let cvThreshold = 0.50;
    let ewmaAlpha = 0.40;
    let agingFactor = 0.01;

    // Simulation Cluster (6 representative heterogeneous VMs from 1000 to 4000 MIPS)
    const simVms = [
        { id: 0, mips: 1000, name: 'VM-0 (Standard)', color: '#38bdf8' },
        { id: 1, mips: 1600, name: 'VM-1 (Standard+)', color: '#818cf8' },
        { id: 2, mips: 2200, name: 'VM-2 (Mid-Tier)', color: '#a78bfa' },
        { id: 3, mips: 2800, name: 'VM-3 (Mid-Tier+)', color: '#c084fc' },
        { id: 4, mips: 3400, name: 'VM-4 (High-Perf)', color: '#e879f9' },
        { id: 5, mips: 4000, name: 'VM-5 (Compute-Max)', color: '#f472b6' }
    ];

    // State for both algorithms running in parallel on identical GoCJ cloudlets
    let stateRR = null;
    let stateSSP = null;

    // Authentic GoCJ trace distribution samples (15,000 to 800,000 MI)
    const gocjBurstSamples = [
        18500, 24000, 42000, 65000, 89000, 115000, 142000, 198000, 
        235000, 280000, 310000, 375000, 420000, 490000, 560000, 620000, 
        695000, 750000, 810000, 32000, 45000, 78000, 95000, 160000,
        220000, 340000, 460000, 580000, 720000, 180000
    ];

    function generateCloudlets(count) {
        const list = [];
        for (let i = 0; i < count; i++) {
            const mi = gocjBurstSamples[i % gocjBurstSamples.length];
            const variation = 1.0 + (((i * 17 + 7) % 21) - 10) * 0.02;
            const finalMi = Math.round(mi * variation);
            list.push({
                id: i,
                mi: finalMi,
                assignedVm: i % simVms.length,
                rrRemaining: finalMi,
                sspRemaining: finalMi,
                rrPreemptions: 0,
                sspPreemptions: 0,
                rrWaitTime: 0,
                sspWaitTime: 0,
                rrTurnaround: 0,
                sspTurnaround: 0,
                completedRR: false,
                completedSSP: false
            });
        }
        return list;
    }

    function initSimulationState() {
        if (timerId) {
            clearTimeout(timerId);
            timerId = null;
        }
        isRunning = false;
        isPaused = false;

        const cloudlets = generateCloudlets(tasksTotal);

        function createEngineState(isSSP) {
            return {
                isSSP: isSSP,
                simClock: 0.0,
                cloudlets: JSON.parse(JSON.stringify(cloudlets)),
                completedCount: 0,
                totalPreemptions: 0,
                totalCsTime: 0.0,
                totalWaitTime: 0.0,
                totalTurnaroundTime: 0.0,
                activeTask: null,
                activeVmIndex: 0,
                vmQueues: simVms.map(vm => ({
                    vmId: vm.id,
                    mips: vm.mips,
                    readyQueue: [],
                    historyBursts: [],
                    lastPrediction: 0.0,
                    npeHistory: [],
                    currentMode: 'INIT',
                    currentCv: 0.0,
                    lastQuantum: 1.0,
                    totalPreemptions: 0,
                    busyUntil: 0.0
                }))
            };
        }

        stateRR = createEngineState(false);
        stateSSP = createEngineState(true);

        // Populate initial ready queues
        stateRR.cloudlets.forEach(c => {
            stateRR.vmQueues[c.assignedVm].readyQueue.push(c);
        });

        stateSSP.cloudlets.forEach(c => {
            stateSSP.vmQueues[c.assignedVm].readyQueue.push(c);
        });

        // SSP-ARR initial SRPT ordering per queue
        stateSSP.vmQueues.forEach(q => {
            q.readyQueue.sort((a, b) => (a.mi / q.mips) - (b.mi / q.mips));
        });

        renderUI();

        // Ensure play button reflects ready state
        const btn = document.getElementById('simPlayBtn');
        if (btn) {
            btn.innerHTML = `
                <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"></polygon></svg>
                <span>Start Live Visual Simulation</span>
            `;
            btn.classList.remove('btn-warning');
            btn.classList.add('btn-primary');
        }
    }

    // Step a single slice execution on the next ready VM
    function stepEngine(engine) {
        if (!engine || engine.completedCount >= engine.cloudlets.length) {
            return false; // all done
        }

        // Find a VM that has tasks queued
        let attempts = 0;
        let q = null;
        while (attempts < engine.vmQueues.length) {
            const testQ = engine.vmQueues[engine.activeVmIndex];
            if (testQ.readyQueue.length > 0) {
                q = testQ;
                break;
            }
            engine.activeVmIndex = (engine.activeVmIndex + 1) % engine.vmQueues.length;
            attempts++;
        }

        if (!q || q.readyQueue.length === 0) {
            return false;
        }

        // Pop head task
        const task = q.readyQueue.shift();
        const vm = simVms[q.vmId];
        const remainingMi = engine.isSSP ? task.sspRemaining : task.rrRemaining;
        const remainingBurstS = remainingMi / vm.mips;

        let quantumS = 1.0;
        let mode = 'CLASSICAL_RR';
        let cv = 0.0;
        let npe = 0.0;

        if (engine.isSSP) {
            // SSP-ARR 2.0 Local Scheduling Logic
            // 1. Calculate CV from completed bursts history
            if (q.historyBursts.length >= 2) {
                const n = q.historyBursts.length;
                const mean = q.historyBursts.reduce((a, b) => a + b, 0) / n;
                const variance = q.historyBursts.reduce((sum, b) => sum + Math.pow(b - mean, 2), 0) / n;
                const stdDev = Math.sqrt(variance);
                cv = mean > 0 ? (stdDev / mean) : 0.0;
            } else {
                cv = 0.25; // default initial safe stability
            }
            q.currentCv = cv;

            // 2. Decide Mode based on CV stability threshold
            if (cv <= cvThreshold) {
                // Reactive Mode: Median remaining burst
                mode = 'REACTIVE_MEDIAN';
                const queueBursts = q.readyQueue.map(t => t.sspRemaining / vm.mips);
                queueBursts.push(remainingBurstS);
                queueBursts.sort((a, b) => a - b);
                const mid = Math.floor(queueBursts.length / 2);
                const medianBurst = queueBursts.length % 2 !== 0 
                    ? queueBursts[mid] 
                    : (queueBursts[mid - 1] + queueBursts[mid]) / 2;
                quantumS = Math.min(medianBurst, remainingBurstS);
            } else {
                // Predictive Mode: Adaptive EWMA with error modulation
                mode = 'PREDICTIVE_EWMA';
                let lastBurst = q.historyBursts.length > 0 ? q.historyBursts[q.historyBursts.length - 1] : remainingBurstS;
                let prevForecast = q.lastPrediction > 0 ? q.lastPrediction : lastBurst;
                
                // Normalized Prediction Error
                npe = Math.abs(prevForecast - lastBurst) / (lastBurst || 1.0);
                q.npeHistory.push(npe);
                if (q.npeHistory.length > 5) q.npeHistory.shift();

                // Dynamic Alpha modulation based on error
                let alpha = ewmaAlpha;
                if (npe > 0.30) alpha = 0.80;
                else if (npe > 0.20) alpha = 0.60;
                else if (npe > 0.10) alpha = 0.40;
                else alpha = 0.20;

                const forecast = alpha * lastBurst + (1 - alpha) * prevForecast;
                q.lastPrediction = forecast;

                // Confidence Gating: If NPE exceeds gate (20%), fallback to median!
                if (npe > 0.20) {
                    mode = 'CONFIDENCE_GATE_FALLBACK';
                    const queueBursts = q.readyQueue.map(t => t.sspRemaining / vm.mips);
                    queueBursts.push(remainingBurstS);
                    queueBursts.sort((a, b) => a - b);
                    const mid = Math.floor(queueBursts.length / 2);
                    quantumS = queueBursts[mid] || remainingBurstS;
                } else {
                    quantumS = forecast;
                }
            }

            // Bound quantum
            quantumS = Math.max(0.05, Math.min(60.0, quantumS));
            q.currentMode = mode;
            q.lastQuantum = quantumS;
        } else {
            // Classical Round Robin: Rigid static 1.0s quantum
            quantumS = 1.0;
            mode = 'STATIC_RR';
        }

        // Execute slice
        const execTimeS = Math.min(quantumS, remainingBurstS);
        const executedMi = Math.round(execTimeS * vm.mips);
        const newRemaining = Math.max(0, remainingMi - executedMi);

        engine.simClock += execTimeS;

        if (newRemaining <= 0) {
            // Task Finished!
            if (engine.isSSP) {
                task.sspRemaining = 0;
                task.completedSSP = true;
                task.sspTurnaround = engine.simClock;
                q.historyBursts.push(task.mi / vm.mips);
                if (q.historyBursts.length > 5) q.historyBursts.shift();
            } else {
                task.rrRemaining = 0;
                task.completedRR = true;
                task.rrTurnaround = engine.simClock;
            }
            engine.completedCount++;
        } else {
            // Task Preempted!
            if (engine.isSSP) {
                task.sspRemaining = newRemaining;
                task.sspPreemptions++;
            } else {
                task.rrRemaining = newRemaining;
                task.rrPreemptions++;
            }
            engine.totalPreemptions++;
            q.totalPreemptions++;

            // Incur hardware context switch penalty
            engine.simClock += csOverheadS;
            engine.totalCsTime += csOverheadS;

            // Re-insert into ready queue
            if (engine.isSSP) {
                // With Starvation Aging priority bonus: effective burst reduced by passes
                q.readyQueue.push(task);
                q.readyQueue.sort((a, b) => {
                    const burstA = (a.sspRemaining / vm.mips) - (a.sspPreemptions * agingFactor);
                    const burstB = (b.sspRemaining / vm.mips) - (b.sspPreemptions * agingFactor);
                    return burstA - burstB;
                });
            } else {
                // Standard FIFO tail insertion
                q.readyQueue.push(task);
            }
        }

        engine.activeTask = {
            taskId: task.id,
            vmId: vm.id,
            vmName: vm.name,
            execTimeS: execTimeS.toFixed(3),
            remainingMi: newRemaining,
            isFinished: newRemaining <= 0,
            mode: mode,
            quantumS: quantumS.toFixed(3),
            cv: cv.toFixed(3),
            npe: (npe * 100).toFixed(1)
        };

        engine.activeVmIndex = (engine.activeVmIndex + 1) % engine.vmQueues.length;
        return true;
    }

    // Single step iteration
    function step() {
        const moreSSP = stepEngine(stateSSP);

        // Classical RR pacing:
        // Classical RR uses a rigid 1.0s quantum across heavy GoCJ tasks (up to 800k MI).
        // To make the browser demonstration responsive and comparable in real-time,
        // we execute a batch of RR slices per visual tick.
        // Once SSP is complete, RR accelerates to finish the comparison promptly.
        const sspIsFinished = stateSSP && stateSSP.completedCount >= tasksTotal;
        const rrBatchSize = sspIsFinished ? 18 : 6;
        let moreRR = false;
        for (let i = 0; i < rrBatchSize; i++) {
            if (stepEngine(stateRR)) {
                moreRR = true;
            } else {
                break;
            }
        }

        renderUI();

        if (!moreRR && !moreSSP) {
            pause();
            const btn = document.getElementById('simPlayBtn');
            if (btn) {
                btn.innerHTML = `
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"></polygon></svg>
                    <span>Re-run Visual Simulation</span>
                `;
                btn.classList.remove('btn-warning');
                btn.classList.add('btn-primary');
            }
        }
    }

    function runLoop() {
        if (!isRunning || isPaused) return;
        step();
        timerId = setTimeout(runLoop, speedMs);
    }

    function start() {
        const isDone = (stateRR && stateRR.completedCount >= tasksTotal) &&
                       (stateSSP && stateSSP.completedCount >= tasksTotal);
        if (!stateRR || !stateSSP || isDone) {
            initSimulationState();
        }
        isRunning = true;
        isPaused = false;
        runLoop();

        const btn = document.getElementById('simPlayBtn');
        if (btn) {
            btn.innerHTML = `
                <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="4" width="4" height="16"></rect><rect x="14" y="4" width="4" height="16"></rect></svg>
                <span>Pause Simulation</span>
            `;
            btn.classList.add('btn-warning');
            btn.classList.remove('btn-primary');
        }
    }

    function pause() {
        isPaused = true;
        isRunning = false;
        if (timerId) {
            clearTimeout(timerId);
            timerId = null;
        }

        const btn = document.getElementById('simPlayBtn');
        if (btn) {
            const isDone = (stateRR && stateRR.completedCount >= tasksTotal) &&
                           (stateSSP && stateSSP.completedCount >= tasksTotal);
            if (isDone) {
                btn.innerHTML = `
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"></polygon></svg>
                    <span>Re-run Visual Simulation</span>
                `;
                btn.classList.remove('btn-warning');
                btn.classList.add('btn-primary');
            } else {
                btn.innerHTML = `
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"></polygon></svg>
                    <span>Resume Simulation</span>
                `;
                btn.classList.remove('btn-warning');
                btn.classList.add('btn-primary');
            }
        }
    }

    function togglePlay() {
        const isDone = (stateRR && stateRR.completedCount >= tasksTotal) &&
                       (stateSSP && stateSSP.completedCount >= tasksTotal);
        if (isDone) {
            reset();
            start();
            return;
        }

        if (isRunning && !isPaused) {
            pause();
        } else {
            start();
        }
    }

    function reset() {
        pause();
        initSimulationState();
        const btn = document.getElementById('simPlayBtn');
        if (btn) {
            btn.innerHTML = `
                <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"></polygon></svg>
                <span>Start Live Visual Simulation</span>
            `;
            btn.classList.remove('btn-warning');
            btn.classList.add('btn-primary');
        }
    }

    function setSpeed(speed) {
        if (speed === '0.5x') speedMs = 240;
        else if (speed === '1x') speedMs = 120;
        else if (speed === '2x') speedMs = 50;
        else if (speed === 'instant') speedMs = 5;
    }

    function setWorkload(count) {
        tasksTotal = parseInt(count);
        reset();
    }

    function setCsOverhead(ms) {
        csOverheadS = parseFloat(ms) / 1000.0;
    }

    function setCvThreshold(val) {
        cvThreshold = parseFloat(val);
    }

    function setEwmaAlpha(val) {
        ewmaAlpha = parseFloat(val);
    }

    function setAgingFactor(val) {
        agingFactor = parseFloat(val);
    }

    function hasInitialized() {
        return stateRR !== null && stateSSP !== null;
    }

    // Render live updates to HTML elements
    function renderUI() {
        if (!stateRR || !stateSSP) return;

        // Update Progress Bars
        const pctRR = ((stateRR.completedCount / tasksTotal) * 100).toFixed(1);
        const pctSSP = ((stateSSP.completedCount / tasksTotal) * 100).toFixed(1);

        const elBarRR = document.getElementById('simProgressBarRR');
        const elBarSSP = document.getElementById('simProgressBarSSP');
        const elTextRR = document.getElementById('simProgressTextRR');
        const elTextSSP = document.getElementById('simProgressTextSSP');

        if (elBarRR) elBarRR.style.width = pctRR + '%';
        if (elBarSSP) elBarSSP.style.width = pctSSP + '%';
        if (elTextRR) elTextRR.textContent = `${stateRR.completedCount}/${tasksTotal} (${pctRR}%)`;
        if (elTextSSP) elTextSSP.textContent = `${stateSSP.completedCount}/${tasksTotal} (${pctSSP}%)`;

        // Update Preemptions & Overhead Counters
        const elPreemptRR = document.getElementById('simPreemptRR');
        const elPreemptSSP = document.getElementById('simPreemptSSP');
        const elCsSaved = document.getElementById('simCsSaved');
        const elClockRR = document.getElementById('simClockRR');
        const elClockSSP = document.getElementById('simClockSSP');

        if (elPreemptRR) elPreemptRR.textContent = stateRR.totalPreemptions.toLocaleString();
        if (elPreemptSSP) elPreemptSSP.textContent = stateSSP.totalPreemptions.toLocaleString();
        if (elClockRR) elClockRR.textContent = stateRR.simClock.toFixed(2) + 's';
        if (elClockSSP) elClockSSP.textContent = stateSSP.simClock.toFixed(2) + 's';

        if (elCsSaved) {
            const savedPreempt = Math.max(0, stateRR.totalPreemptions - stateSSP.totalPreemptions);
            const savedSeconds = (savedPreempt * csOverheadS).toFixed(2);
            elCsSaved.textContent = `${savedSeconds}s CPU (${savedPreempt.toLocaleString()} Preemptions Avoided)`;
        }

        // Live Active Task Slicing Indicator
        const sspDone = stateSSP.completedCount >= tasksTotal;
        const rrDone = stateRR.completedCount >= tasksTotal;
        const act = stateSSP.activeTask;

        const elActiveVm = document.getElementById('simActiveVm');
        const elActiveTask = document.getElementById('simActiveTask');
        const elActiveMode = document.getElementById('simActiveMode');
        const elActiveQuantum = document.getElementById('simActiveQuantum');
        const elActiveCv = document.getElementById('simActiveCv');

        if (sspDone && !rrDone) {
            if (elActiveVm) elActiveVm.textContent = 'SSP-ARR Complete';
            if (elActiveTask) elActiveTask.textContent = `All ${tasksTotal} tasks completed in ${stateSSP.simClock.toFixed(2)}s! Classical RR still preempting (${stateRR.totalPreemptions.toLocaleString()} preemptions incurred)...`;
            if (elActiveQuantum) elActiveQuantum.textContent = 'Optimal';
            if (elActiveCv) elActiveCv.textContent = '0.00';
            if (elActiveMode) {
                elActiveMode.className = 'metric-badge badge-success';
                elActiveMode.textContent = 'SSP Finished (Waiting for RR)';
            }
        } else if (sspDone && rrDone) {
            const avoided = Math.max(0, stateRR.totalPreemptions - stateSSP.totalPreemptions);
            const timeSaved = (stateRR.simClock - stateSSP.simClock).toFixed(2);
            if (elActiveVm) elActiveVm.textContent = 'Benchmark Finished';
            if (elActiveTask) elActiveTask.textContent = `Execution Complete: SSP saved ${avoided.toLocaleString()} preemptions and ${timeSaved}s makespan vs. Classical RR!`;
            if (elActiveQuantum) elActiveQuantum.textContent = '--';
            if (elActiveCv) elActiveCv.textContent = '--';
            if (elActiveMode) {
                elActiveMode.className = 'metric-badge badge-success';
                elActiveMode.textContent = 'Simulation Completed';
            }
        } else if (act) {
            if (elActiveVm) elActiveVm.textContent = act.vmName;
            if (elActiveTask) elActiveTask.textContent = `Cloudlet #${act.taskId} (${act.execTimeS}s slice, ${act.remainingMi > 0 ? (act.remainingMi / 1000).toFixed(0) + 'k MI remaining' : 'FINISHED'})`;
            if (elActiveQuantum) elActiveQuantum.textContent = act.quantumS + 's';
            if (elActiveCv) elActiveCv.textContent = act.cv;

            if (elActiveMode) {
                if (act.mode === 'REACTIVE_MEDIAN') {
                    elActiveMode.className = 'metric-badge badge-primary';
                    elActiveMode.textContent = '🟢 Safe Reactive (Median Slice)';
                } else if (act.mode === 'PREDICTIVE_EWMA') {
                    elActiveMode.className = 'metric-badge badge-accent';
                    elActiveMode.textContent = '🟣 Adaptive Predictive (EWMA Forecast)';
                } else if (act.mode === 'CONFIDENCE_GATE_FALLBACK') {
                    elActiveMode.className = 'metric-badge badge-warning';
                    elActiveMode.textContent = '⚠️ NPE > 20% Safety Fallback to Median';
                }
            }
        }

        // Render VM Queues Mini Rack
        renderVmQueuesGrid();
    }

    function renderVmQueuesGrid() {
        const grid = document.getElementById('simVmQueuesGrid');
        if (!grid || !stateSSP) return;

        const isCompleted = stateSSP.completedCount >= tasksTotal;

        grid.innerHTML = stateSSP.vmQueues.map((q, idx) => {
            const vm = simVms[idx];
            const queueCount = q.readyQueue.length;
            const modeBadge = isCompleted
                ? '<span style="color: var(--color-success); font-weight:700;">Completed</span>'
                : q.currentMode === 'REACTIVE_MEDIAN' 
                ? '<span style="color: var(--color-primary); font-weight:700;">Reactive</span>'
                : q.currentMode === 'PREDICTIVE_EWMA'
                ? '<span style="color: var(--color-accent); font-weight:700;">Predictive</span>'
                : '<span style="color: var(--text-dim);">Ready</span>';

            const isActive = !isCompleted && stateSSP.activeTask && stateSSP.activeTask.vmId === q.vmId;
            const maxExpected = Math.max(1, Math.ceil(tasksTotal / 6));
            const barWidth = isCompleted ? 100 : Math.min(100, (queueCount / maxExpected) * 100);
            const barColor = isCompleted ? 'var(--color-success)' : vm.color;

            return `
                <div class="sim-vm-card ${isActive ? 'active-vm' : ''}">
                    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px;">
                        <span style="font-weight: 700; font-family: var(--font-mono); color: ${vm.color}; font-size: 11.5px;">${vm.name}</span>
                        <span style="font-size: 10px; color: var(--text-dim);">${vm.mips} MIPS</span>
                    </div>
                    <div style="display: flex; justify-content: space-between; font-size: 11px; margin-bottom: 4px;">
                        <span style="color: var(--text-muted);">${isCompleted ? 'Finished:' : 'Queue:'} <strong>${isCompleted ? 'All Done' : queueCount}</strong></span>
                        <span style="color: var(--text-muted);">Preempt: <strong style="color: var(--color-warning);">${q.totalPreemptions}</strong></span>
                    </div>
                    <div style="display: flex; justify-content: space-between; font-size: 10px; color: var(--text-dim);">
                        <span>CV: ${q.currentCv.toFixed(2)}</span>
                        <span>${modeBadge}</span>
                    </div>
                    <div style="height: 4px; background: rgba(255,255,255,0.08); border-radius: 2px; margin-top: 6px; overflow: hidden;">
                        <div style="width: ${barWidth}%; height: 100%; background: ${barColor}; border-radius: 2px; transition: width 0.15s ease;"></div>
                    </div>
                </div>
            `;
        }).join('');
    }

    return {
        init: initSimulationState,
        start,
        pause,
        step,
        reset,
        togglePlay,
        setSpeed,
        setWorkload,
        setCsOverhead,
        setCvThreshold,
        setEwmaAlpha,
        setAgingFactor,
        hasInitialized
    };
})();
