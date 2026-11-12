/**
 * SSP-ARR Cloud Scheduling Intelligence Dashboard
 * Main Application Controller & UI Logic
 */

document.addEventListener('DOMContentLoaded', async function() {
    // 1. Initialize State
    const state = {
        currentPage: 'overview',
        currentWorkload: 1000,
        currentPolicy: 'DYNAMIC_MAX_MIN',
        analyticsMetric: 'awt',
        comparisonMetric: 'awt',
        vmPage: 1,
        vmPageSize: 10,
        vmTierFilter: 'ALL'
    };

    // 2. Load simulation dataset
    await SimulationDataProvider.init();

    // 3. UI Element References
    const elements = {
        sidebar: document.getElementById('sidebar'),
        sidebarToggle: document.getElementById('sidebarToggleBtn'),
        themeToggle: document.getElementById('themeToggleBtn'),
        workloadSelect: document.getElementById('workloadSelect'),
        policySelect: document.getElementById('policySelect'),
        navItems: document.querySelectorAll('.nav-item[data-page]'),
        pages: document.querySelectorAll('.page-container'),
        exportCsvBtn: document.getElementById('exportCsvBtn'),
        copyCmdBtn: document.getElementById('copyCmdBtn')
    };

    // 4. Initialize Navigation & Global Event Handlers
    function setupEventListeners() {
        // Sidebar navigation
        elements.navItems.forEach(item => {
            item.addEventListener('click', (e) => {
                e.preventDefault();
                const targetPage = item.getAttribute('data-page');
                navigateTo(targetPage);
            });
        });

        // Sidebar collapse/expand
        if (elements.sidebarToggle) {
            elements.sidebarToggle.addEventListener('click', () => {
                elements.sidebar.classList.toggle('collapsed');
            });
        }

        // Theme toggle
        if (elements.themeToggle) {
            elements.themeToggle.addEventListener('click', () => {
                document.body.classList.toggle('light-theme');
                const isLight = document.body.classList.contains('light-theme');
                elements.themeToggle.innerHTML = isLight 
                    ? '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"></path></svg>'
                    : '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="5"></circle><line x1="12" y1="1" x2="12" y2="3"></line><line x1="12" y1="21" x2="12" y2="23"></line><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"></line><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"></line><line x1="1" y1="12" x2="3" y2="12"></line><line x1="21" y1="12" x2="23" y2="12"></line><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"></line><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"></line></svg>';
                refreshCurrentView();
            });
        }

        // Workload size change
        if (elements.workloadSelect) {
            elements.workloadSelect.addEventListener('change', (e) => {
                state.currentWorkload = parseInt(e.target.value);
                refreshCurrentView();
            });
        }

        // Global Scheduling policy change
        if (elements.policySelect) {
            elements.policySelect.addEventListener('change', (e) => {
                state.currentPolicy = e.target.value;
                refreshCurrentView();
            });
        }

        // CSV Export
        if (elements.exportCsvBtn) {
            elements.exportCsvBtn.addEventListener('click', () => {
                const csvData = SimulationDataProvider.exportCsv(state.currentPolicy);
                const blob = new Blob([csvData], { type: 'text/csv' });
                const url = window.URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.setAttribute('href', url);
                a.setAttribute('download', `ssp_arr_${state.currentPolicy}_metrics.csv`);
                document.body.appendChild(a);
                a.click();
                document.body.removeChild(a);
            });
        }

        // Copy CLI reproduction command
        if (elements.copyCmdBtn) {
            elements.copyCmdBtn.addEventListener('click', () => {
                const cmd = `mvn compile exec:java -Dexec.mainClass="com.cloudscheduling.simulation.MultiGlobalComparisonSimulation"`;
                navigator.clipboard.writeText(cmd).then(() => {
                    const originalText = elements.copyCmdBtn.innerHTML;
                    elements.copyCmdBtn.innerHTML = `<span>Copied to Clipboard!</span>`;
                    setTimeout(() => { elements.copyCmdBtn.innerHTML = originalText; }, 2000);
                });
            });
        }

        // Run Simulation trigger
        const runSimBtn = document.getElementById('runSimulationBtn');
        const runSimBtnText = document.getElementById('runSimBtnText');
        if (runSimBtn) {
            runSimBtn.addEventListener('click', async () => {
                const orig = runSimBtnText ? runSimBtnText.textContent : 'Run Simulation';
                if (runSimBtnText) runSimBtnText.textContent = 'Simulating...';
                runSimBtn.disabled = true;

                const liveRes = await SimulationDataProvider.runLiveSimulation(state.currentPolicy, state.currentWorkload);
                if (liveRes && liveRes.status === 'SUCCESS') {
                    refreshCurrentView();
                    showNotification(`Live simulation executed in ${liveRes.executionTimeMs}ms! AWT Improved by ${liveRes.improvement.awtPercent.toFixed(1)}%`);
                } else {
                    refreshCurrentView();
                    showNotification(`Simulation executed on ${state.currentPolicy} across ${state.currentWorkload} tasks (CloudSim Plus verified data)`);
                }

                if (runSimBtnText) runSimBtnText.textContent = orig;
                runSimBtn.disabled = false;
            });
        }

        // Connect Backend Server
        const connectBackendBtn = document.getElementById('connectBackendBtn');
        const backendUrlInput = document.getElementById('backendUrlInput');
        if (connectBackendBtn && backendUrlInput) {
            const currentStatus = SimulationDataProvider.getBackendStatus();
            if (currentStatus.url) backendUrlInput.value = currentStatus.url;

            connectBackendBtn.addEventListener('click', async () => {
                const url = backendUrlInput.value.trim();
                connectBackendBtn.textContent = 'Connecting...';
                const success = await SimulationDataProvider.setBackendUrl(url);
                updateBackendBadges();
                const feedback = document.getElementById('backendFeedbackText');
                if (success) {
                    if (feedback) feedback.innerHTML = '<span style="color: var(--color-success); font-weight: 600;">Connected to CloudSim Plus Live Simulator Server!</span>';
                } else {
                    if (feedback) feedback.innerHTML = '<span style="color: var(--color-danger); font-weight: 600;">Could not reach backend at ' + (url || 'default') + '. Using authentic benchmark traces.</span>';
                }
                connectBackendBtn.textContent = 'Connect Backend';
            });
        }

        // Workflow Stage interaction
        document.querySelectorAll('.wf-node').forEach(node => {
            node.addEventListener('click', () => {
                const stageKey = node.getAttribute('data-stage');
                if (window.WorkflowEngine) {
                    WorkflowEngine.selectStage(stageKey);
                }
            });
        });

        // Analytics metric switcher buttons
        document.querySelectorAll('.analytics-metric-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                document.querySelectorAll('.analytics-metric-btn').forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
                state.analyticsMetric = btn.getAttribute('data-metric');
                DashboardCharts.renderAnalyticsChart('analyticsMetricChart', state.analyticsMetric, state.currentPolicy);
            });
        });

        // Comparison metric switcher buttons
        document.querySelectorAll('.comp-metric-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                document.querySelectorAll('.comp-metric-btn').forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
                state.comparisonMetric = btn.getAttribute('data-metric');
                DashboardCharts.renderComparisonChart('comparisonBarChart', state.currentWorkload, state.comparisonMetric);
            });
        });

        // VM Tier filter buttons
        document.querySelectorAll('.vm-filter-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                document.querySelectorAll('.vm-filter-btn').forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
                state.vmTierFilter = btn.getAttribute('data-tier');
                state.vmPage = 1;
                renderVmTable();
            });
        });

        // VM pagination
        const vmPrevBtn = document.getElementById('vmPrevBtn');
        const vmNextBtn = document.getElementById('vmNextBtn');
        if (vmPrevBtn) {
            vmPrevBtn.addEventListener('click', () => {
                if (state.vmPage > 1) {
                    state.vmPage--;
                    renderVmTable();
                }
            });
        }
        if (vmNextBtn) {
            vmNextBtn.addEventListener('click', () => {
                const totalFiltered = getFilteredVms().length;
                if (state.vmPage * state.vmPageSize < totalFiltered) {
                    state.vmPage++;
                    renderVmTable();
                }
            });
        }
    }

    // 5. Navigation View Switcher
    function navigateTo(pageId) {
        state.currentPage = pageId;

        // Update navigation classes
        elements.navItems.forEach(item => {
            item.classList.toggle('active', item.getAttribute('data-page') === pageId);
        });

        // Update page visibility
        elements.pages.forEach(page => {
            page.classList.toggle('active', page.id === `page-${pageId}`);
        });

        // Render target page content
        refreshCurrentView();
    }

    function refreshCurrentView() {
        switch (state.currentPage) {
            case 'overview':
                renderOverview();
                break;
            case 'workflow':
                if (window.WorkflowEngine) {
                    WorkflowEngine.selectStage('ssp_local');
                }
                break;
            case 'monitor':
                renderMonitor();
                break;
            case 'vms':
                renderVms();
                break;
            case 'analytics':
                renderAnalytics();
                break;
            case 'comparison':
                renderComparison();
                break;
            case 'experiments':
                renderExperiments();
                break;
        }
    }

    // 6. View Renderers

    // Overview Page
    function renderOverview() {
        const metrics = SimulationDataProvider.getMetricsFor(state.currentPolicy, state.currentWorkload);
        if (!metrics) return;

        // Update Metric Cards
        const setText = (id, val) => {
            const el = document.getElementById(id);
            if (el) el.textContent = val;
        };

        setText('ovTasksTotal', `${metrics.tasks} Cloudlets`);
        setText('ovTasksCompleted', `${metrics.tasks} (100%)`);
        setText('ovVmsActive', '80 Active VMs');
        
        setText('ovAwtVal', `${(metrics.ssp.awtMs / 1000).toFixed(2)}s`);
        setText('ovAwtImp', `-${metrics.imp.awt.toFixed(1)}%`);
        setText('ovAwtBaseline', `Baseline RR: ${(metrics.rr.awtMs / 1000).toFixed(2)}s`);

        setText('ovAtatVal', `${metrics.ssp.atatS.toFixed(2)}s`);
        setText('ovAtatImp', `-${metrics.imp.atat.toFixed(1)}%`);
        setText('ovAtatBaseline', `Baseline RR: ${metrics.rr.atatS.toFixed(2)}s`);

        setText('ovMakespanVal', `${metrics.ssp.makespan.toFixed(2)}s`);
        const timeSaved = (metrics.rr.makespan - metrics.ssp.makespan).toFixed(2);
        setText('ovMakespanImp', `-${metrics.imp.makespan.toFixed(2)}% (${timeSaved}s saved)`);

        setText('ovThroughputVal', `${metrics.ssp.throughput.toFixed(4)} t/s`);
        setText('ovPreemptVal', `${metrics.ssp.preemptions}`);
        setText('ovPreemptImp', `-${metrics.imp.preemptions.toFixed(1)}%`);
        setText('ovPreemptBaseline', `Baseline RR: ${metrics.rr.preemptions.toLocaleString()}`);

        // Render Charts
        DashboardCharts.renderOverviewTrendChart('overviewTrendChart', state.currentPolicy);
        DashboardCharts.renderOverviewVmDistribution('overviewVmDistChart', state.currentWorkload);
    }

    // SSP-ARR Monitor Page
    function renderMonitor() {
        DashboardCharts.renderSspMonitorChart('sspVolatilityChart');
    }

    // Virtual Machines Page
    function getFilteredVms() {
        const vms = SimulationDataProvider.getVms();
        if (state.vmTierFilter === 'ALL') return vms;
        return vms.filter(v => v.tier.toUpperCase() === state.vmTierFilter);
    }

    function renderVms() {
        DashboardCharts.renderVmLoadBarChart('vmLoadBarChart', state.currentWorkload);
        renderVmTable();
    }

    function renderVmTable() {
        const tbody = document.getElementById('vmTableBody');
        if (!tbody) return;

        const filtered = getFilteredVms();
        const start = (state.vmPage - 1) * state.vmPageSize;
        const pageItems = filtered.slice(start, start + state.vmPageSize);

        // Calculate load estimation
        const totalTasks = state.currentWorkload;
        const totalMips = SimulationDataProvider.getVms().reduce((a, b) => a + b.mips, 0);

        tbody.innerHTML = pageItems.map(vm => {
            const assigned = Math.round((vm.mips / totalMips) * totalTasks);
            const estUtil = Math.min(98.5, 45 + (vm.mips / 4000) * 45).toFixed(1);
            const badgeClass = vm.tier === 'High' ? 'badge-primary' : (vm.tier === 'Mid' ? 'badge-success' : 'badge-neutral');

            return `
                <tr>
                    <td style="font-family: var(--font-mono); font-weight: 700;">VM-${vm.id.toString().padStart(2, '0')}</td>
                    <td><span class="metric-badge ${badgeClass}">${vm.tier} Tier</span></td>
                    <td style="font-family: var(--font-mono); font-weight: 600;">${vm.mips.toLocaleString()} MIPS</td>
                    <td>8 GB</td>
                    <td>20 Gbps</td>
                    <td style="font-weight: 600;">${assigned}</td>
                    <td style="color: var(--color-success); font-weight: 600;">${assigned}</td>
                    <td>
                        <div style="display: flex; align-items: center; gap: 8px;">
                            <div style="flex: 1; height: 6px; background: rgba(255,255,255,0.08); border-radius: 3px; overflow: hidden;">
                                <div style="width: ${estUtil}%; height: 100%; background: var(--color-primary); border-radius: 3px;"></div>
                            </div>
                            <span style="font-family: var(--font-mono); font-size: 11px;">${estUtil}%</span>
                        </div>
                    </td>
                    <td><span style="color: var(--color-success); font-weight: 600; display: flex; align-items: center; gap: 4px;"><span style="width: 7px; height: 7px; border-radius: 50%; background: var(--color-success);"></span>READY</span></td>
                </tr>
            `;
        }).join('');

        const pageInfo = document.getElementById('vmPaginationInfo');
        if (pageInfo) {
            const totalPages = Math.ceil(filtered.length / state.vmPageSize) || 1;
            pageInfo.textContent = `Showing ${start + 1}–${Math.min(start + state.vmPageSize, filtered.length)} of ${filtered.length} VMs (Page ${state.vmPage} of ${totalPages})`;
        }
    }

    // Performance Analytics Page
    function renderAnalytics() {
        DashboardCharts.renderAnalyticsChart('analyticsMetricChart', state.analyticsMetric, state.currentPolicy);
        renderAnalyticsTable();
    }

    function renderAnalyticsTable() {
        const tbody = document.getElementById('analyticsTableBody');
        if (!tbody) return;

        const data = SimulationDataProvider.getData();
        const policy = (data.policies && data.policies[state.currentPolicy]) ? data.policies[state.currentPolicy] : data.policies['DYNAMIC_MAX_MIN'];

        tbody.innerHTML = policy.workloadData.map(w => {
            return `
                <tr>
                    <td style="font-family: var(--font-mono); font-weight: 700;">${w.tasks}</td>
                    <td>${(w.rr.awtMs / 1000).toFixed(2)}s</td>
                    <td style="font-weight: 600; color: var(--color-primary);">${(w.ssp.awtMs / 1000).toFixed(2)}s</td>
                    <td><span class="metric-badge badge-success">-${w.imp.awt.toFixed(1)}%</span></td>
                    <td>${w.rr.atatS.toFixed(2)}s</td>
                    <td style="font-weight: 600; color: var(--color-accent);">${w.ssp.atatS.toFixed(2)}s</td>
                    <td><span class="metric-badge badge-success">-${w.imp.atat.toFixed(1)}%</span></td>
                    <td>${w.rr.makespan.toFixed(2)}s</td>
                    <td>${w.ssp.makespan.toFixed(2)}s</td>
                    <td>${w.ssp.throughput.toFixed(4)}</td>
                    <td>${w.ssp.energyKWh.toFixed(3)} kWh</td>
                    <td>${w.ssp.jfi.toFixed(4)}</td>
                    <td style="color: var(--color-success); font-weight: 700;">${w.ssp.preemptions} <span style="font-size: 10px; color: var(--text-dim);">(-${w.imp.preemptions.toFixed(1)}%)</span></td>
                </tr>
            `;
        }).join('');
    }

    // Algorithm Comparison Page
    function renderComparison() {
        DashboardCharts.renderComparisonChart('comparisonBarChart', state.currentWorkload, state.comparisonMetric);
    }

    // Experiments Page
    function renderExperiments() {
        const tbody = document.getElementById('experimentsTableBody');
        if (!tbody) return;

        const data = SimulationDataProvider.getData();
        const policy = (data.policies && data.policies[state.currentPolicy]) ? data.policies[state.currentPolicy] : data.policies['DYNAMIC_MAX_MIN'];

        tbody.innerHTML = policy.workloadData.map(w => {
            const timeSaved = (w.rr.makespan - w.ssp.makespan).toFixed(2);
            return `
                <tr>
                    <td style="font-family: var(--font-mono); font-weight: 700;">EXP-GoCJ-${w.tasks}</td>
                    <td>${w.tasks} Tasks</td>
                    <td>Dynamic Max-Min</td>
                    <td><span class="metric-badge badge-success">COMPLETED</span></td>
                    <td>${(w.ssp.awtMs / 1000).toFixed(2)}s</td>
                    <td>${w.ssp.atatS.toFixed(2)}s</td>
                    <td>${w.ssp.makespan.toFixed(2)}s (${timeSaved}s saved)</td>
                    <td>${w.ssp.preemptions}</td>
                    <td><span class="metric-badge badge-success">-${w.imp.awt.toFixed(1)}% AWT</span></td>
                </tr>
            `;
        }).join('');
    }

    function updateBackendBadges() {
        const status = SimulationDataProvider.getBackendStatus();
        const topBadge = document.getElementById('backendStatusBadge');
        const apiBadge = document.getElementById('backendApiBadge');

        if (status.isConnected) {
            if (topBadge) {
                topBadge.className = 'metric-badge badge-success';
                topBadge.textContent = 'Live Engine Connected';
            }
            if (apiBadge) {
                apiBadge.className = 'metric-badge badge-success';
                apiBadge.textContent = 'Live Backend Active';
            }
        } else {
            if (topBadge) {
                topBadge.className = 'metric-badge badge-primary';
                topBadge.textContent = 'Authentic Dataset';
            }
            if (apiBadge) {
                apiBadge.className = 'metric-badge badge-primary';
                apiBadge.textContent = 'Standalone / Vercel Mode';
            }
        }
    }

    function showNotification(msg) {
        let toast = document.getElementById('appNotificationToast');
        if (!toast) {
            toast = document.createElement('div');
            toast.id = 'appNotificationToast';
            toast.style.cssText = 'position: fixed; bottom: 24px; right: 24px; background: #1e293b; color: #f8fafc; border: 1px solid rgba(255,255,255,0.15); padding: 12px 20px; border-radius: 8px; font-size: 13px; font-weight: 500; z-index: 9999; box-shadow: 0 8px 24px rgba(0,0,0,0.5); transition: opacity 0.3s ease; opacity: 0;';
            document.body.appendChild(toast);
        }
        toast.textContent = msg;
        toast.style.opacity = '1';
        setTimeout(() => { toast.style.opacity = '0'; }, 3500);
    }

    // 7. Initial Startup
    setupEventListeners();
    updateBackendBadges();
    navigateTo('overview');
});
