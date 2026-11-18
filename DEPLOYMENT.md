# SSP-ARR Cloud Scheduling Deployment Guide

This guide details how to deploy both the **Frontend Observability Dashboard** and the **Java CloudSim Plus Simulation Engine** to the cloud.

---

## Architecture Overview

```
                      ┌─────────────────────────────────────────┐
                      │             User's Browser              │
                      └────────────────────┬────────────────────┘
                                           │
                    ┌──────────────────────┴──────────────────────┐
                    │                                             │
                    ▼                                             ▼
       ┌────────────────────────┐                   ┌───────────────────────────┐
       │     Vercel Platform    │                   │   Render / Railway Cloud  │
       │  (Frontend Dashboard)  │                   │  (Java Simulation Server) │
       │                        │                   │                           │
       │ • Static HTML5 / CSS3  │                   │ • Java 21 (Temurin JRE)   │
       │ • Vanilla JS           │                   │ • CloudSim Plus 8.5.7     │
       │ • Chart.js + SVG       │◄─── CORS REST ────┤ • Virtual Threads         │
       │ • 12 GoCJ Trace Sets   │       API         │ • On-Demand /api/simulate │
       │ • Global CDN           │                   │ • Docker Container        │
       └────────────────────────┘                   └───────────────────────────┘
```

---

## 1. Deploy Frontend Dashboard to Vercel (Recommended - 2 Minutes)

Vercel provides free, high-performance static hosting with global CDN.

### Step 1: Push your latest code to GitHub
Make sure your branch (e.g. `main` or `ssp-arr-v2`) is pushed to GitHub:
```bash
git push -u origin ssp-arr-v2
```

### Step 2: Import into Vercel
1. Log in to [Vercel](https://vercel.com).
2. Click **"Add New..."** → **"Project"**.
3. Select your repository `ssp-arr-cloud-task-scheduling` (or `ssp-arr-cloud-task-scheduling1`).
4. Leave all build settings at their defaults:
   - **Framework Preset**: `Other`
   - **Root Directory**: `./` (both root and `/dashboard` are fully pre-configured)
5. Click **"Deploy"**.

Your dashboard will be live at `https://<your-project>.vercel.app` in under 30 seconds!

> [!TIP]
> The Vercel deployment includes all verified CloudSim Plus simulation datasets across all 12 GoCJ workload scales and 5 global policies, the full 10-slide Viva Voce Defense Deck, live visual simulation engine, and complete IEEE paper viewer! It works 100% autonomously without needing any backend server!

---

## 2. Deploy Instantly to GitHub Pages (Automated via GitHub Actions)

The repository includes a ready-to-use GitHub Actions workflow (`.github/workflows/deploy-pages.yml`):

1. Go to your GitHub repository: `https://github.com/Tharun7209/ssp-arr-cloud-task-scheduling1`.
2. Navigate to **Settings** → **Pages**.
3. Under **Build and deployment** → **Source**, select **GitHub Actions**.
4. Push your code to `main`:
   ```bash
   git push target1 ssp-arr-v2:main
   ```
5. GitHub will automatically deploy your presentation site to:
   ```
   https://tharun7209.github.io/ssp-arr-cloud-task-scheduling1/
   ```

---

## 3. External Examiner Presentation Mode (Viva Voce Defense Guide)

When presenting to your external examiner:
1. Open the deployed website (on Vercel, GitHub Pages, or local browser).
2. Click the gold **"🎓 Viva Defense"** button in the top navigation bar or select **"🎓 Viva Defense Deck"** in the sidebar.
3. Press **`F`** on your keyboard to enter distraction-free **Projector Fullscreen Mode**.
4. Use **`←`** and **`→`** arrow keys to step through the 10 chapters:
   - **Chapter 1:** Title, Abstract & Student Identity
   - **Chapter 2:** Motivation & The Cloud Scheduling Bottleneck (&delta;<sub>cs</sub> = 5ms thrashing)
   - **Chapter 3:** Two-Tier System Architecture (Broker + VM-Local Scheduler)
   - **Chapter 4:** Mathematical Formulations (CV Volatility, EWMA, NPE Gate, SPECpower)
   - **Chapter 5:** CloudSim Plus 8.5.7 Infrastructure (30 Hosts, 80 VMs, GoCJ Traces)
   - **Chapter 6:** Empirical Results (-50.3% AWT, -43.6% ATAT, -99.3% Preemptions, 254.7s CPU saved)
   - **Chapter 7:** 5 Global Schedulers Validation (Dy. MM, DRALBA, PSSLB, PSSELB, DeRALBA)
   - **Chapter 8:** Live Visual Demonstration Playground
   - **Chapter 9:** External Examiner Defense Q&A (Top 10 hard questions answered)
   - **Chapter 10:** Interactive Evaluation Rubric Scorecard (100/100 Grade A+ calculation)
5. Switch to **"⚡ Live Sim"** to run the live discrete-event execution race in front of the examiner!

---

## 4. Deploy Live Simulator Backend to Render (Free Cloud Docker)

If you want the dashboard's **"Run Simulation"** button to execute CloudSim Plus in real-time in the cloud, deploy the containerized backend:

### Option A: 1-Click Render Blueprint
1. Log in to [Render](https://render.com).
2. Click **"New +"** → **"Blueprint"**.
3. Select your GitHub repository `ssp-arr-cloud-task-scheduling`.
4. Render will automatically detect [`render.yaml`](render.yaml) and build the multi-stage [`Dockerfile`](Dockerfile).
5. Click **"Apply"**.

### Option B: Manual Web Service on Render
1. Click **"New +"** → **"Web Service"**.
2. Select your repository.
3. Choose **Docker** as the environment.
4. Set **Health Check Path** to `/api/health`.
5. Click **"Create Web Service"**.

Render will output your live URL (e.g., `https://ssp-arr-simulator.onrender.com`).

---

## 3. Deploy Simulator to Railway

1. Log in to [Railway](https://railway.app).
2. Click **"New Project"** → **"Deploy from GitHub repo"**.
3. Select `ssp-arr-cloud-task-scheduling`.
4. Railway will automatically detect the `Dockerfile` and deploy the service.
5. In your service settings, click **"Generate Domain"** to get your public HTTPS URL.

---

## 4. Connect Vercel Frontend to Cloud Simulator Backend

Once your backend is running on Render or Railway:

1. Open your deployed Vercel dashboard (`https://<your-app>.vercel.app`).
2. Navigate to the **"Experiments"** tab in the sidebar.
3. Under **"Cloud Deployment & Simulator API Connection"**, enter your backend URL:
   ```
   https://ssp-arr-simulator.onrender.com
   ```
4. Click **"Connect Backend"**.
5. The status badge in the top navigation will turn green: **"Live Engine Connected"**.

Now, when you click **"Run Simulation"**, the request will trigger live CloudSim Plus execution in the cloud and stream real-time metrics directly to your charts!

---

## 5. Local Development & Testing

You can also run the full full-stack platform locally on your computer with a single command:

```bash
# 1. Compile and launch the built-in HTTP server on port 8080
mvn compile exec:java -Dexec.mainClass="com.cloudscheduling.simulation.SimulationServer"

# 2. Open in your browser:
http://localhost:8080
```

### Available REST Endpoints:
- `GET /api/health` — Verifies engine health and Java version.
- `GET /api/results` — Returns full multi-policy JSON results.
- `GET /api/simulate?policy=DYNAMIC_MAX_MIN&tasks=100` — Runs on-demand simulation.
