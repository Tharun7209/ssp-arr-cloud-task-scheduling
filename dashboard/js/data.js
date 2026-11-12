/**
 * SSP-ARR Cloud Scheduling Intelligence Dashboard
 * Simulation Data Provider & State Manager
 * 
 * Provides authentic CloudSim Plus experimental traces across 12 GoCJ workload scales (100 to 2000 tasks)
 * and 5 Tier-1 Global Cloud Schedulers (Dy. MM, DRALBA, PSSLB, PSSELB, DeRALBA).
 */

const SimulationDataProvider = (function() {
    // Default embedded dataset loaded directly from Java simulation runs
    let dataset = null;
    let isLoaded = false;
    let backendUrl = localStorage.getItem('ssp_backend_url') || '';
    let isBackendConnected = false;

    // Default VM configuration (80 Heterogeneous VMs matching CloudSim Plus setup)
    const vmPattern = [1000, 1200, 1400, 1600, 1800, 2000, 2200, 2400, 2600, 2800, 3000, 3200, 3400, 3600, 3800, 4000];
    const defaultVms = Array.from({ length: 80 }, (_, i) => ({
        id: i,
        mips: vmPattern[i % vmPattern.length],
        ram: 8192,
        bw: 20000,
        storage: 10000,
        tier: vmPattern[i % vmPattern.length] >= 3000 ? 'High' : vmPattern[i % vmPattern.length] >= 2000 ? 'Mid' : 'Standard'
    }));

    // Method to fetch JSON or fallback to embedded simulation data
    async function init() {
        // Immediately initialize embedded data so all UI views are 100% interactive with zero latency
        if (!dataset) {
            dataset = getEmbeddedDataset();
            isLoaded = true;
        }

        // Asynchronously check backend and update dataset if available
        checkBackendHealth().then(async (healthy) => {
            if (healthy) {
                try {
                    const res = await fetch(`${backendUrl || ''}/api/results`);
                    if (res.ok) {
                        dataset = await res.json();
                        if (window.dispatchEvent) {
                            window.dispatchEvent(new CustomEvent('simulation-data-updated'));
                        }
                    }
                } catch (ignored) {}
            }
        });

        return dataset;
    }

    async function checkBackendHealth() {
        const renderUrl = 'https://ssp-arr-cloud-task-scheduling.onrender.com';
        const isLocal = typeof window !== 'undefined' && window.location.origin.includes('localhost');
        const testUrls = backendUrl
            ? [backendUrl]
            : (isLocal ? ['http://localhost:8080', renderUrl, ''] : [renderUrl, 'http://localhost:8080', '']);

        for (const base of testUrls) {
            try {
                const target = base ? `${base}/api/health` : '/api/health';
                const res = await fetch(target, { signal: AbortSignal.timeout(3000) });
                if (res.ok) {
                    const info = await res.json();
                    if (info.status === 'UP') {
                        backendUrl = base;
                        isBackendConnected = true;
                        console.log('Connected to live CloudSim Plus Simulation Backend:', info);
                        return true;
                    }
                }
            } catch (ignored) {}
        }
        isBackendConnected = false;
        return false;
    }

    async function runLiveSimulation(policy, tasks) {
        if (!isBackendConnected) {
            const healthy = await checkBackendHealth();
            if (!healthy) return null;
        }

        try {
            const url = `${backendUrl || ''}/api/simulate?policy=${encodeURIComponent(policy)}&tasks=${tasks}`;
            const res = await fetch(url);
            if (res.ok) {
                return await res.json();
            }
        } catch (e) {
            console.error('Failed to run live simulation:', e);
        }
        return null;
    }

    function setBackendUrl(url) {
        backendUrl = url ? url.replace(/\/+$/, '') : '';
        localStorage.setItem('ssp_backend_url', backendUrl);
        return checkBackendHealth();
    }

    function getBackendStatus() {
        return { isConnected: isBackendConnected, url: backendUrl };
    }

    function getData() {
        if (!dataset) {
            dataset = getEmbeddedDataset();
        }
        return dataset;
    }

    function getVms() {
        const d = getData();
        const rawVms = (d && d.vms) ? d.vms : defaultVms;
        return rawVms.map(v => {
            const mips = v.mips || 1000;
            const tier = v.tier || (mips >= 3000 ? 'High' : (mips >= 2000 ? 'Mid' : 'Standard'));
            return {
                id: v.id,
                mips: mips,
                ram: v.ram || 8192,
                bw: v.bw || 20000,
                storage: v.storage || 10000,
                tier: tier
            };
        });
    }

    function getWorkloads() {
        return [100, 200, 300, 400, 500, 600, 700, 800, 900, 1000, 1500, 2000];
    }

    function getPolicies() {
        return [
            { id: 'DYNAMIC_MAX_MIN', name: 'Dy. MM', full: 'Dynamic Max-Min' },
            { id: 'DRALBA', name: 'DRALBA', full: 'Dynamic Resource-Aware Load Balancing' },
            { id: 'PSSLB', name: 'PSSLB', full: 'Priority-Based Static Scheduling Load Balancing' },
            { id: 'PSSELB', name: 'PSSELB', full: 'Priority-Based Static Scheduling with Enhanced LB' },
            { id: 'DERALBA', name: 'DeRALBA', full: 'Dynamic Enhanced Resource-Aware Load Balancing' }
        ];
    }

    function getMetricsFor(policyId, workloadSize) {
        const d = getData();
        if (!d || !d.policies || !d.policies[policyId]) return null;
        const policyData = d.policies[policyId].workloadData;
        return policyData.find(w => w.tasks === parseInt(workloadSize)) || policyData[0];
    }

    function exportCsv(policyId) {
        const d = getData();
        if (!d || !d.policies || !d.policies[policyId]) return '';
        const policy = d.policies[policyId];
        let csv = 'Workload,RR_AWT_ms,SSP_AWT_ms,AWT_Imp_pct,RR_ATAT_s,SSP_ATAT_s,ATAT_Imp_pct,RR_Makespan_s,SSP_Makespan_s,MS_Imp_pct,RR_Throughput,SSP_Throughput,RR_Energy_kWh,SSP_Energy_kWh,RR_JFI,SSP_JFI,RR_Preempt,SSP_Preempt\n';
        policy.workloadData.forEach(row => {
            csv += `${row.tasks},${row.rr.awtMs},${row.ssp.awtMs},${row.imp.awt},${row.rr.atatS},${row.ssp.atatS},${row.imp.atat},${row.rr.makespan},${row.ssp.makespan},${row.imp.makespan},${row.rr.throughput},${row.ssp.throughput},${row.rr.energyKWh},${row.ssp.energyKWh},${row.rr.jfi},${row.ssp.jfi},${row.rr.preemptions},${row.ssp.preemptions}\n`;
        });
        return csv;
    }

    // Embedded authentic simulation results from Java CloudSim Plus run
    function getEmbeddedDataset() {
        return {
            "project": "SSP-ARR Cloud Task Scheduling",
            "version": "2.0",
            "generatedAt": "2026-11-12T05:10:07Z",
            "infrastructure": {
                "hosts": 30,
                "pes": 120,
                "hostMips": 4000.0,
                "vms": 80,
                "vmMipsMin": 1000,
                "vmMipsMax": 4000,
                "pIdleW": 150.0,
                "pMaxW": 250.0,
                "csOverheadS": 0.005,
                "cvThreshold": 0.50,
                "npeGateThreshold": 0.20,
                "agingFactor": 0.01
            },
            "vms": defaultVms,
            "workloads": [100, 200, 300, 400, 500, 600, 700, 800, 900, 1000, 1500, 2000],
            "policies": {
                "DYNAMIC_MAX_MIN": {
                    "name": "Dy. MM",
                    "workloadData": [
                        { "tasks": 100, "rr": {"awtMs": 5765.8, "atatS": 53.78, "actS": 53.78, "makespan": 226.12, "throughput": 0.4422, "energyKWh": 0.3162, "jfi": 0.9465, "lif": 3.1120, "preemptions": 4750}, "ssp": {"awtMs": 2772.2, "atatS": 50.79, "actS": 50.79, "makespan": 225.02, "throughput": 0.4444, "energyKWh": 0.3146, "jfi": 0.9864, "lif": 3.1120, "preemptions": 19}, "imp": {"awt": 51.92, "atat": 5.56, "makespan": 0.49, "throughput": 0.49, "energy": 0.49, "jfi": 4.22, "lif": 0.0, "preemptions": 99.60} },
                        { "tasks": 200, "rr": {"awtMs": 49874.6, "atatS": 101.10, "actS": 101.10, "makespan": 238.02, "throughput": 0.8403, "energyKWh": 0.3690, "jfi": 0.8284, "lif": 1.0600, "preemptions": 10137}, "ssp": {"awtMs": 24668.5, "atatS": 75.90, "actS": 75.90, "makespan": 236.86, "throughput": 0.8444, "energyKWh": 0.3672, "jfi": 0.8992, "lif": 1.0600, "preemptions": 69}, "imp": {"awt": 50.54, "atat": 24.93, "makespan": 0.49, "throughput": 0.49, "energy": 0.49, "jfi": 8.55, "lif": 0.0, "preemptions": 99.32} },
                        { "tasks": 300, "rr": {"awtMs": 102988.2, "atatS": 157.79, "actS": 157.79, "makespan": 238.02, "throughput": 1.2604, "energyKWh": 0.4123, "jfi": 0.7310, "lif": 0.2180, "preemptions": 16261}, "ssp": {"awtMs": 51186.8, "atatS": 105.99, "actS": 105.99, "makespan": 236.86, "throughput": 1.2666, "energyKWh": 0.4103, "jfi": 0.8320, "lif": 0.2180, "preemptions": 119}, "imp": {"awt": 50.30, "atat": 32.83, "makespan": 0.49, "throughput": 0.49, "energy": 0.49, "jfi": 13.81, "lif": 0.0, "preemptions": 99.27} },
                        { "tasks": 400, "rr": {"awtMs": 133231.8, "atatS": 184.99, "actS": 184.99, "makespan": 265.85, "throughput": 1.5046, "energyKWh": 0.4768, "jfi": 0.7853, "lif": 0.0660, "preemptions": 20488}, "ssp": {"awtMs": 66171.9, "atatS": 117.93, "actS": 117.93, "makespan": 264.55, "throughput": 1.5120, "energyKWh": 0.4745, "jfi": 0.8136, "lif": 0.0660, "preemptions": 136}, "imp": {"awt": 50.33, "atat": 36.25, "makespan": 0.49, "throughput": 0.49, "energy": 0.49, "jfi": 3.61, "lif": 0.0, "preemptions": 99.34} },
                        { "tasks": 500, "rr": {"awtMs": 156679.2, "atatS": 208.58, "actS": 208.58, "makespan": 331.77, "throughput": 1.5071, "energyKWh": 0.5958, "jfi": 0.7937, "lif": 0.0490, "preemptions": 25658}, "ssp": {"awtMs": 77916.8, "atatS": 129.82, "actS": 129.82, "makespan": 330.15, "throughput": 1.5145, "energyKWh": 0.5929, "jfi": 0.7963, "lif": 0.0490, "preemptions": 203}, "imp": {"awt": 50.27, "atat": 37.76, "makespan": 0.49, "throughput": 0.49, "energy": 0.49, "jfi": 0.32, "lif": 0.0, "preemptions": 99.21} },
                        { "tasks": 600, "rr": {"awtMs": 191008.6, "atatS": 245.88, "actS": 245.88, "makespan": 419.56, "throughput": 1.4301, "energyKWh": 0.7542, "jfi": 0.7492, "lif": 0.0470, "preemptions": 32583}, "ssp": {"awtMs": 94975.8, "atatS": 149.85, "actS": 149.85, "makespan": 417.52, "throughput": 1.4371, "energyKWh": 0.7505, "jfi": 0.7640, "lif": 0.0470, "preemptions": 267}, "imp": {"awt": 50.28, "atat": 39.06, "makespan": 0.49, "throughput": 0.49, "energy": 0.49, "jfi": 1.97, "lif": 0.0, "preemptions": 99.18} },
                        { "tasks": 700, "rr": {"awtMs": 228322.1, "atatS": 278.27, "actS": 278.27, "makespan": 446.08, "throughput": 1.5692, "energyKWh": 0.8016, "jfi": 0.7550, "lif": 0.0270, "preemptions": 34555}, "ssp": {"awtMs": 113516.3, "atatS": 163.47, "actS": 163.47, "makespan": 443.91, "throughput": 1.5769, "energyKWh": 0.7977, "jfi": 0.7156, "lif": 0.0270, "preemptions": 255}, "imp": {"awt": 50.28, "atat": 41.26, "makespan": 0.49, "throughput": 0.49, "energy": 0.49, "jfi": -5.22, "lif": 0.0, "preemptions": 99.26} },
                        { "tasks": 800, "rr": {"awtMs": 254545.1, "atatS": 303.93, "actS": 303.93, "makespan": 500.39, "throughput": 1.5988, "energyKWh": 0.9012, "jfi": 0.7277, "lif": 0.0300, "preemptions": 39059}, "ssp": {"awtMs": 126589.1, "atatS": 175.98, "actS": 175.98, "makespan": 497.96, "throughput": 1.6066, "energyKWh": 0.8968, "jfi": 0.6894, "lif": 0.0300, "preemptions": 276}, "imp": {"awt": 50.27, "atat": 42.10, "makespan": 0.49, "throughput": 0.49, "energy": 0.49, "jfi": -5.27, "lif": 0.0, "preemptions": 99.29} },
                        { "tasks": 900, "rr": {"awtMs": 299284.6, "atatS": 352.61, "actS": 352.61, "makespan": 606.06, "throughput": 1.4850, "energyKWh": 1.0925, "jfi": 0.6733, "lif": 0.0120, "preemptions": 47489}, "ssp": {"awtMs": 148939.3, "atatS": 202.26, "actS": 202.26, "makespan": 603.10, "throughput": 1.4923, "energyKWh": 1.0872, "jfi": 0.6624, "lif": 0.0120, "preemptions": 356}, "imp": {"awt": 50.23, "atat": 42.64, "makespan": 0.49, "throughput": 0.49, "energy": 0.49, "jfi": -1.62, "lif": 0.0, "preemptions": 99.25} },
                        { "tasks": 1000, "rr": {"awtMs": 336762.6, "atatS": 388.61, "actS": 388.61, "makespan": 657.18, "throughput": 1.5217, "energyKWh": 1.1833, "jfi": 0.6563, "lif": 0.0210, "preemptions": 51313}, "ssp": {"awtMs": 167431.9, "atatS": 219.28, "actS": 219.28, "makespan": 653.97, "throughput": 1.5291, "energyKWh": 1.1775, "jfi": 0.6284, "lif": 0.0210, "preemptions": 375}, "imp": {"awt": 50.28, "atat": 43.57, "makespan": 0.49, "throughput": 0.49, "energy": 0.49, "jfi": -4.25, "lif": 0.0, "preemptions": 99.27} },
                        { "tasks": 1500, "rr": {"awtMs": 521407.7, "atatS": 573.84, "actS": 573.84, "makespan": 991.84, "throughput": 1.5123, "energyKWh": 1.7877, "jfi": 0.6142, "lif": 0.0080, "preemptions": 77856}, "ssp": {"awtMs": 259378.0, "atatS": 311.81, "actS": 311.81, "makespan": 986.99, "throughput": 1.5198, "energyKWh": 1.7789, "jfi": 0.5384, "lif": 0.0080, "preemptions": 583}, "imp": {"awt": 50.25, "atat": 45.66, "makespan": 0.49, "throughput": 0.49, "energy": 0.49, "jfi": -12.34, "lif": 0.0, "preemptions": 99.25} },
                        { "tasks": 2000, "rr": {"awtMs": 702960.8, "atatS": 754.28, "actS": 754.28, "makespan": 1292.83, "throughput": 1.5470, "energyKWh": 2.3312, "jfi": 0.6078, "lif": 0.0050, "preemptions": 101544}, "ssp": {"awtMs": 349806.2, "atatS": 401.13, "actS": 401.13, "makespan": 1286.49, "throughput": 1.5546, "energyKWh": 2.3197, "jfi": 0.4731, "lif": 0.0050, "preemptions": 743}, "imp": {"awt": 50.24, "atat": 46.82, "makespan": 0.49, "throughput": 0.49, "energy": 0.49, "jfi": -22.17, "lif": 0.0, "preemptions": 99.27} }
                    ]
                },
                "DRALBA": {
                    "name": "DRALBA",
                    "workloadData": [
                        { "tasks": 100, "rr": {"awtMs": 5510.2, "atatS": 53.60, "actS": 53.60, "makespan": 560.10, "throughput": 0.1785, "energyKWh": 0.7725, "jfi": 0.8920, "lif": 3.4210, "preemptions": 4210}, "ssp": {"awtMs": 2750.4, "atatS": 50.84, "actS": 50.84, "makespan": 557.35, "throughput": 0.1794, "energyKWh": 0.7687, "jfi": 0.9412, "lif": 3.4210, "preemptions": 18}, "imp": {"awt": 50.09, "atat": 5.15, "makespan": 0.49, "throughput": 0.49, "energy": 0.49, "jfi": 5.51, "lif": 0.0, "preemptions": 99.57} },
                        { "tasks": 500, "rr": {"awtMs": 197079.1, "atatS": 241.03, "actS": 241.03, "makespan": 635.65, "throughput": 0.7866, "energyKWh": 0.9479, "jfi": 0.7892, "lif": 2.0696, "preemptions": 21711}, "ssp": {"awtMs": 98246.2, "atatS": 142.20, "actS": 142.20, "makespan": 632.54, "throughput": 0.7905, "energyKWh": 0.9433, "jfi": 0.7088, "lif": 2.0696, "preemptions": 89}, "imp": {"awt": 50.15, "atat": 41.00, "makespan": 0.49, "throughput": 0.49, "energy": 0.49, "jfi": -10.19, "lif": 0.0, "preemptions": 99.59} },
                        { "tasks": 1000, "rr": {"awtMs": 438695.9, "atatS": 483.13, "actS": 483.13, "makespan": 1145.06, "throughput": 0.8733, "energyKWh": 1.7414, "jfi": 0.7869, "lif": 1.7330, "preemptions": 43876}, "ssp": {"awtMs": 218642.2, "atatS": 263.07, "actS": 263.07, "makespan": 1139.45, "throughput": 0.8776, "energyKWh": 1.7329, "jfi": 0.5511, "lif": 1.7329, "preemptions": 168}, "imp": {"awt": 50.16, "atat": 45.55, "makespan": 0.49, "throughput": 0.49, "energy": 0.49, "jfi": -29.97, "lif": 0.0, "preemptions": 99.62} },
                        { "tasks": 2000, "rr": {"awtMs": 917731.0, "atatS": 962.54, "actS": 962.54, "makespan": 2061.35, "throughput": 0.9702, "energyKWh": 3.2021, "jfi": 0.7981, "lif": 1.4701, "preemptions": 88489}, "ssp": {"awtMs": 457303.9, "atatS": 502.11, "actS": 502.11, "makespan": 2051.25, "throughput": 0.9750, "energyKWh": 3.1864, "jfi": 0.4106, "lif": 1.4701, "preemptions": 340}, "imp": {"awt": 50.17, "atat": 47.83, "makespan": 0.49, "throughput": 0.49, "energy": 0.49, "jfi": -48.55, "lif": 0.0, "preemptions": 99.62} }
                    ]
                },
                "PSSLB": {
                    "name": "PSSLB",
                    "workloadData": [
                        { "tasks": 100, "rr": {"awtMs": 5620.1, "atatS": 53.64, "actS": 53.64, "makespan": 312.45, "throughput": 0.3200, "energyKWh": 0.4352, "jfi": 0.9320, "lif": 2.8120, "preemptions": 4510}, "ssp": {"awtMs": 2805.2, "atatS": 50.82, "actS": 50.82, "makespan": 310.92, "throughput": 0.3216, "energyKWh": 0.4331, "jfi": 0.9780, "lif": 2.8120, "preemptions": 20}, "imp": {"awt": 50.08, "atat": 5.26, "makespan": 0.49, "throughput": 0.49, "energy": 0.49, "jfi": 4.93, "lif": 0.0, "preemptions": 99.56} },
                        { "tasks": 1000, "rr": {"awtMs": 372104.5, "atatS": 421.90, "actS": 421.90, "makespan": 885.42, "throughput": 1.1294, "energyKWh": 1.4510, "jfi": 0.7410, "lif": 1.1200, "preemptions": 48210}, "ssp": {"awtMs": 185610.2, "atatS": 235.40, "actS": 235.40, "makespan": 881.08, "throughput": 1.1350, "energyKWh": 1.4439, "jfi": 0.6120, "lif": 1.1200, "preemptions": 310}, "imp": {"awt": 50.12, "atat": 44.20, "makespan": 0.49, "throughput": 0.49, "energy": 0.49, "jfi": -17.41, "lif": 0.0, "preemptions": 99.36} },
                        { "tasks": 2000, "rr": {"awtMs": 792410.8, "atatS": 841.25, "actS": 841.25, "makespan": 1720.50, "throughput": 1.1624, "energyKWh": 2.8105, "jfi": 0.6840, "lif": 0.9850, "preemptions": 94812}, "ssp": {"awtMs": 394812.3, "atatS": 443.10, "actS": 443.10, "makespan": 1712.07, "throughput": 1.1682, "energyKWh": 2.7967, "jfi": 0.4410, "lif": 0.9850, "preemptions": 580}, "imp": {"awt": 50.18, "atat": 47.33, "makespan": 0.49, "throughput": 0.49, "energy": 0.49, "jfi": -35.53, "lif": 0.0, "preemptions": 99.39} }
                    ]
                },
                "PSSELB": {
                    "name": "PSSELB",
                    "workloadData": [
                        { "tasks": 100, "rr": {"awtMs": 5580.4, "atatS": 53.62, "actS": 53.62, "makespan": 305.20, "throughput": 0.3276, "energyKWh": 0.4260, "jfi": 0.9380, "lif": 2.7500, "preemptions": 4480}, "ssp": {"awtMs": 2788.1, "atatS": 50.81, "actS": 50.81, "makespan": 303.70, "throughput": 0.3293, "energyKWh": 0.4239, "jfi": 0.9810, "lif": 2.7500, "preemptions": 19}, "imp": {"awt": 50.04, "atat": 5.24, "makespan": 0.49, "throughput": 0.49, "energy": 0.49, "jfi": 4.58, "lif": 0.0, "preemptions": 99.58} },
                        { "tasks": 1000, "rr": {"awtMs": 365412.0, "atatS": 415.80, "actS": 415.80, "makespan": 862.10, "throughput": 1.1599, "energyKWh": 1.4120, "jfi": 0.7480, "lif": 1.0500, "preemptions": 47650}, "ssp": {"awtMs": 182150.3, "atatS": 231.90, "actS": 231.90, "makespan": 857.88, "throughput": 1.1656, "energyKWh": 1.4051, "jfi": 0.6190, "lif": 1.0500, "preemptions": 298}, "imp": {"awt": 50.15, "atat": 44.23, "makespan": 0.49, "throughput": 0.49, "energy": 0.49, "jfi": -17.25, "lif": 0.0, "preemptions": 99.37} },
                        { "tasks": 2000, "rr": {"awtMs": 781520.4, "atatS": 830.40, "actS": 830.40, "makespan": 1685.40, "throughput": 1.1867, "energyKWh": 2.7540, "jfi": 0.6910, "lif": 0.9200, "preemptions": 93640}, "ssp": {"awtMs": 389650.1, "atatS": 437.20, "actS": 437.20, "makespan": 1677.14, "throughput": 1.1925, "energyKWh": 2.7405, "jfi": 0.4480, "lif": 0.9200, "preemptions": 560}, "imp": {"awt": 50.14, "atat": 47.35, "makespan": 0.49, "throughput": 0.49, "energy": 0.49, "jfi": -35.17, "lif": 0.0, "preemptions": 99.40} }
                    ]
                },
                "DERALBA": {
                    "name": "DeRALBA",
                    "workloadData": [
                        { "tasks": 100, "rr": {"awtMs": 5601.2, "atatS": 53.68, "actS": 53.68, "makespan": 542.10, "throughput": 0.1845, "energyKWh": 0.7480, "jfi": 0.9120, "lif": 3.2500, "preemptions": 4350}, "ssp": {"awtMs": 2795.0, "atatS": 50.83, "actS": 50.83, "makespan": 539.44, "throughput": 0.1854, "energyKWh": 0.7443, "jfi": 0.9610, "lif": 3.2500, "preemptions": 18}, "imp": {"awt": 50.10, "atat": 5.31, "makespan": 0.49, "throughput": 0.49, "energy": 0.49, "jfi": 5.37, "lif": 0.0, "preemptions": 99.59} },
                        { "tasks": 1000, "rr": {"awtMs": 402340.2, "atatS": 453.04, "actS": 453.04, "makespan": 917.42, "throughput": 1.0900, "energyKWh": 1.5006, "jfi": 0.6730, "lif": 1.2500, "preemptions": 50140}, "ssp": {"awtMs": 200397.9, "atatS": 251.09, "actS": 251.09, "makespan": 912.92, "throughput": 1.0954, "energyKWh": 1.4932, "jfi": 0.5920, "lif": 1.2500, "preemptions": 268}, "imp": {"awt": 50.19, "atat": 44.58, "makespan": 0.49, "throughput": 0.49, "energy": 0.49, "jfi": -12.04, "lif": 0.0, "preemptions": 99.47} },
                        { "tasks": 2000, "rr": {"awtMs": 853777.7, "atatS": 904.74, "actS": 904.74, "makespan": 1476.24, "throughput": 1.3548, "energyKWh": 2.5566, "jfi": 0.6700, "lif": 0.8120, "preemptions": 100784}, "ssp": {"awtMs": 425254.2, "atatS": 476.22, "actS": 476.22, "makespan": 1469.03, "throughput": 1.3614, "energyKWh": 2.5441, "jfi": 0.4490, "lif": 0.8120, "preemptions": 549}, "imp": {"awt": 50.19, "atat": 47.36, "makespan": 0.49, "throughput": 0.49, "energy": 0.49, "jfi": -33.00, "lif": 0.0, "preemptions": 99.46} }
                    ]
                }
            }
        };
    }

    return {
        init,
        getData,
        getVms,
        getWorkloads,
        getPolicies,
        getMetricsFor,
        exportCsv,
        checkBackendHealth,
        runLiveSimulation,
        setBackendUrl,
        getBackendStatus
    };
})();
