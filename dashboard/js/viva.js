/**
 * SSP-ARR Cloud Scheduling Intelligence Dashboard
 * Viva Voce Presentation Deck & External Defense Controller
 * 
 * Provides interactive slide-based presentation, projector mode,
 * examiner defense Q&A, and interactive evaluation scorecard.
 */

const VivaDeckController = (function() {
    let currentSlide = 1;
    const totalSlides = 10;

    const slidesMeta = [
        { id: 1, title: 'Title & Project Abstract', tag: 'Overview' },
        { id: 2, title: 'The Cloud Scheduling Bottleneck', tag: 'Motivation' },
        { id: 3, title: 'Two-Tier System Architecture', tag: 'Architecture' },
        { id: 4, title: 'Mathematical Formulations & Dual-Mode Mechanics', tag: 'Core Theory' },
        { id: 5, title: 'Experimental Setup & GoCJ Workload Traces', tag: 'CloudSim Plus' },
        { id: 6, title: 'Benchmark Results & Empirical Breakthroughs', tag: 'Key Findings' },
        { id: 7, title: 'Multi-Global Schedulers Validation', tag: '5 Dispatchers' },
        { id: 8, title: 'Interactive Live Visual Demonstration', tag: 'Live Simulation' },
        { id: 9, title: 'External Examiner Defense Q&A', tag: 'Viva Defense' },
        { id: 10, title: 'Examiner Evaluation Rubric & Scorecard', tag: 'Grading Rubric' }
    ];

    function init() {
        bindEvents();
        updateSlideView();
        initScorecard();
        initQaAccordions();
    }

    function bindEvents() {
        // Slide Navigation Buttons
        const prevBtn = document.getElementById('vivaPrevBtn');
        const nextBtn = document.getElementById('vivaNextBtn');
        const fsBtn = document.getElementById('vivaFullscreenBtn');
        const printBtn = document.getElementById('vivaPrintBtn');

        if (prevBtn) prevBtn.addEventListener('click', prevSlide);
        if (nextBtn) nextBtn.addEventListener('click', nextSlide);
        if (fsBtn) fsBtn.addEventListener('click', toggleFullscreen);
        if (printBtn) printBtn.addEventListener('click', printHandout);

        // Slide Selector Pills
        const pillsContainer = document.getElementById('vivaSlidePills');
        if (pillsContainer) {
            pillsContainer.innerHTML = slidesMeta.map(s => `
                <button class="viva-slide-pill ${s.id === 1 ? 'active' : ''}" data-slide="${s.id}">
                    <span class="pill-num">${s.id}</span>
                    <span class="pill-text">${s.title}</span>
                </button>
            `).join('');

            pillsContainer.querySelectorAll('.viva-slide-pill').forEach(btn => {
                btn.addEventListener('click', () => {
                    const slideNum = parseInt(btn.getAttribute('data-slide'));
                    goToSlide(slideNum);
                });
            });
        }

        // Keyboard Shortcuts (Arrow Keys & Fullscreen)
        document.addEventListener('keydown', (e) => {
            // Only handle if defense page is active
            const defensePage = document.getElementById('page-defense');
            if (!defensePage || !defensePage.classList.contains('active')) return;

            if (e.key === 'ArrowRight' || e.key === 'PageDown' || e.key === ' ') {
                e.preventDefault();
                nextSlide();
            } else if (e.key === 'ArrowLeft' || e.key === 'PageUp') {
                e.preventDefault();
                prevSlide();
            } else if (e.key === 'Home') {
                e.preventDefault();
                goToSlide(1);
            } else if (e.key === 'End') {
                e.preventDefault();
                goToSlide(totalSlides);
            } else if (e.key.toLowerCase() === 'f' && !e.ctrlKey && !e.metaKey) {
                const tag = e.target.tagName.toLowerCase();
                if (tag !== 'input' && tag !== 'textarea') {
                    e.preventDefault();
                    toggleFullscreen();
                }
            }
        });
    }

    function nextSlide() {
        if (currentSlide < totalSlides) {
            currentSlide++;
            updateSlideView();
        }
    }

    function prevSlide() {
        if (currentSlide > 1) {
            currentSlide--;
            updateSlideView();
        }
    }

    function goToSlide(num) {
        if (num >= 1 && num <= totalSlides) {
            currentSlide = num;
            updateSlideView();
        }
    }

    function updateSlideView() {
        // Toggle slide containers
        document.querySelectorAll('.viva-slide').forEach(slide => {
            slide.classList.remove('active');
            if (parseInt(slide.getAttribute('data-slide-index')) === currentSlide) {
                slide.classList.add('active');
            }
        });

        // Update Counter & Badge
        const counter = document.getElementById('vivaSlideCounter');
        const titleBadge = document.getElementById('vivaSlideTitleBadge');
        if (counter) counter.textContent = `Slide ${currentSlide} of ${totalSlides}`;
        if (titleBadge) {
            const meta = slidesMeta[currentSlide - 1];
            titleBadge.textContent = `${meta.tag}: ${meta.title}`;
        }

        // Update Prev/Next Button States
        const prevBtn = document.getElementById('vivaPrevBtn');
        const nextBtn = document.getElementById('vivaNextBtn');
        if (prevBtn) prevBtn.disabled = currentSlide === 1;
        if (nextBtn) nextBtn.disabled = currentSlide === totalSlides;

        // Update Pill Highlights
        document.querySelectorAll('.viva-slide-pill').forEach(btn => {
            btn.classList.remove('active');
            if (parseInt(btn.getAttribute('data-slide')) === currentSlide) {
                btn.classList.add('active');
                btn.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' });
            }
        });
    }

    function toggleFullscreen() {
        const deckContainer = document.getElementById('vivaDeckContainer');
        if (!deckContainer) return;

        if (!document.fullscreenElement) {
            if (deckContainer.requestFullscreen) {
                deckContainer.requestFullscreen();
            } else if (deckContainer.webkitRequestFullscreen) {
                deckContainer.webkitRequestFullscreen();
            }
            deckContainer.classList.add('fullscreen-mode');
        } else {
            if (document.exitFullscreen) {
                document.exitFullscreen();
            }
            deckContainer.classList.remove('fullscreen-mode');
        }
    }

    function printHandout() {
        window.print();
    }

    // Interactive Examiner Scoring Rubric
    function initScorecard() {
        const criteria = [
            { id: 'crit1', name: 'Problem Formulation & Motivation', max: 20, defaultVal: 20 },
            { id: 'crit2', name: 'Algorithmic Novelty (SSP-ARR Dual Mode)', max: 25, defaultVal: 25 },
            { id: 'crit3', name: 'Simulation Rigor (CloudSim Plus & GoCJ)', max: 20, defaultVal: 20 },
            { id: 'crit4', name: 'Multi-Global Validation & Metrics Depth', max: 20, defaultVal: 20 },
            { id: 'crit5', name: 'Presentation & Interactive Defense Quality', max: 15, defaultVal: 15 }
        ];

        const container = document.getElementById('rubricCriteriaContainer');
        if (!container) return;

        container.innerHTML = criteria.map(c => `
            <div class="rubric-row">
                <div class="rubric-info">
                    <span class="rubric-title">${c.name}</span>
                    <span class="rubric-sub">Max ${c.max} marks</span>
                </div>
                <div class="rubric-input-wrap">
                    <input type="range" class="rubric-slider" id="rubric_slider_${c.id}" min="0" max="${c.max}" value="${c.defaultVal}" data-max="${c.max}">
                    <span class="rubric-score-badge" id="rubric_val_${c.id}">${c.defaultVal} / ${c.max}</span>
                </div>
            </div>
        `).join('');

        function updateTotals() {
            let total = 0;
            criteria.forEach(c => {
                const slider = document.getElementById(`rubric_slider_${c.id}`);
                const valBadge = document.getElementById(`rubric_val_${c.id}`);
                const val = slider ? parseInt(slider.value) : c.defaultVal;
                total += val;
                if (valBadge) valBadge.textContent = `${val} / ${c.max}`;
            });

            const totalEl = document.getElementById('rubricTotalScore');
            const gradeEl = document.getElementById('rubricGradeBadge');
            if (totalEl) totalEl.textContent = `${total} / 100`;
            if (gradeEl) {
                if (total >= 90) {
                    gradeEl.className = 'metric-badge badge-success';
                    gradeEl.textContent = 'Grade: O (Outstanding - A+)';
                } else if (total >= 80) {
                    gradeEl.className = 'metric-badge badge-primary';
                    gradeEl.textContent = 'Grade: A+ (Excellent)';
                } else if (total >= 70) {
                    gradeEl.className = 'metric-badge badge-accent';
                    gradeEl.textContent = 'Grade: A (Very Good)';
                } else {
                    gradeEl.className = 'metric-badge badge-warning';
                    gradeEl.textContent = 'Grade: B (Pass)';
                }
            }
        }

        criteria.forEach(c => {
            const slider = document.getElementById(`rubric_slider_${c.id}`);
            if (slider) slider.addEventListener('input', updateTotals);
        });

        updateTotals();
    }

    // Examiner Defense Q&A Accordion Handlers
    function initQaAccordions() {
        document.querySelectorAll('.viva-qa-card').forEach(card => {
            const header = card.querySelector('.qa-question');
            if (header) {
                header.addEventListener('click', () => {
                    card.classList.toggle('open');
                });
            }
        });
    }

    return {
        init,
        nextSlide,
        prevSlide,
        goToSlide,
        toggleFullscreen,
        printHandout
    };
})();
