// Live tab: high-rate charts, the telemetry table and the field view.
/* global uPlot */
import { escapeHtml } from './api.js';

const COLORS = ['#f5b301', '#58a6ff', '#3fb950', '#bc8cff', '#ff7b72', '#39c5cf', '#d2a8ff', '#ffa657'];

// Charts every robot program feeds. A series can be a telemetry key, or computed from keys.
const PERCENT_OFF = (rpm, target) => (frame) => {
  const t = frame.n[target];
  const r = frame.n[rpm];
  return t ? ((r - t) / t) * 100 : (r === undefined ? undefined : 0);
};
const CHARTS = [
  {
    id: 'flywheels', title: 'Flywheels', unit: 'RPM',
    series: [
      { label: 'Pollen', key: 'Pollen RPM', color: '#f5b301', width: 2 },
      { label: 'Pollen target', key: 'Pollen target', color: '#ffe8a3', dash: [6, 5], width: 1.5 },
      { label: 'Nectar', key: 'Nectar RPM', color: '#58a6ff', width: 2 },
      { label: 'Nectar target', key: 'Nectar target', color: '#b8d8ff', dash: [6, 5], width: 1.5 },
    ],
  },
  {
    id: 'flywheel-error', title: 'Flywheel error', unit: '%',
    series: [
      { label: 'Pollen', get: PERCENT_OFF('Pollen RPM', 'Pollen target'), color: '#f5b301', width: 1.5 },
      { label: 'Nectar', get: PERCENT_OFF('Nectar RPM', 'Nectar target'), color: '#58a6ff', width: 1.5 },
    ],
    band: 2,
  },
  {
    id: 'intake', title: 'Intake', unit: 'RPM',
    series: [
      { label: 'Intake', key: 'Intake RPM', color: '#3fb950', width: 2 },
      { label: 'Target', key: 'Intake target', color: '#b4f0bf', dash: [6, 5], width: 1.5 },
    ],
  },
  {
    id: 'battery', title: 'Battery & loop', unit: '',
    series: [
      { label: 'Battery V', key: 'Battery V', color: '#ff7b72', width: 1.5 },
      { label: 'Loop ms', key: 'Loop ms', color: '#bc8cff', width: 1, scale: 'ms' },
      { label: 'Dashboard ms', key: 'Dashboard ms', color: '#39c5cf', width: 1, scale: 'ms' },
    ],
  },
];

const EXPAND_ICON = '<svg viewBox="0 0 20 20" aria-hidden="true"><path d="M3 8V3h5M17 8V3h-5M3 12v5h5M17 12v5h-5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>';
const PAUSE_ICON = '<svg viewBox="0 0 20 20" aria-hidden="true"><path d="M7 4v12M13 4v12" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"/></svg>';
const PLAY_ICON = '<svg viewBox="0 0 20 20" aria-hidden="true"><path d="M6 4l10 6-10 6z" fill="currentColor"/></svg>';
const SHRINK_ICON = '<svg viewBox="0 0 20 20" aria-hidden="true"><path d="M8 3v5H3M12 3v5h5M8 17v-5H3M12 17v-5h5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>';

const MAX_POINTS = 60 * 250;   // a minute at 250 loops per second

export class LiveTab {
  constructor() {
    this.windowSeconds = 10;
    this.paused = false;
    this.frames = [];          // every frame received, trimmed to MAX_POINTS
    this.latest = {};          // newest value of every key
    this.latestText = {};
    this.trail = [];
    // The Path checkbox: draw the line the robot has driven (remembered in this browser).
    this.showTrail = true;
    try {
      this.showTrail = localStorage.getItem('biobuzz.showTrail') !== 'no';
    } catch {
      // private window: drawn
    }
    const trailToggle = document.getElementById('trail-toggle');
    trailToggle.checked = this.showTrail;
    trailToggle.addEventListener('change', () => {
      this.showTrail = trailToggle.checked;
      try {
        localStorage.setItem('biobuzz.showTrail', this.showTrail ? 'yes' : 'no');
      } catch {
        // private window
      }
      this.dirty = true;
    });
    this.lastPose = null;
    this.rateCount = 0;
    this.rateStart = performance.now();
    this.dirty = false;
    this.charts = loadLayout().map((spec) => this.makeChart(spec));
    this.bindDragging();
    this.bindChartDialog();
    this.field = document.getElementById('field');
    this.fieldImage = new Image();
    this.fieldImage.src = 'fields/biobuzz.webp';
    this.fieldImage.onload = () => this.drawField();
    this.bindToolbar();
    window.addEventListener('resize', () => this.resize());
    // The Field card's two views (field, camera): each zooms to full size; again, or Esc, goes back.
    document.querySelectorAll('#tab-live .view').forEach((view) => {
      const button = view.querySelector('.zoom');
      button.innerHTML = EXPAND_ICON;
      button.addEventListener('click', () => this.zoomView(view));
    });
    document.addEventListener('keydown', (event) => {
      const zoomed = document.querySelector('#tab-live .view.zoomed');
      if (event.key === 'Escape' && zoomed) this.zoomView(zoomed);
    });
    window.addEventListener('keydown', (event) => {
      if (event.key === 'Escape' && this.expanded) this.toggleExpand(this.expanded);
      // Space pauses and resumes the charts (not while typing in a box).
      if (event.key === ' ' && !event.target.closest('input, textarea, select, button')
          && document.getElementById('tab-live').classList.contains('active')) {
        event.preventDefault();
        this.setPaused(!this.paused);
      }
    });
    requestAnimationFrame(() => this.tick());
  }

  makeChart(spec) {
    const card = document.createElement('div');
    card.className = 'card chart';
    card.innerHTML = `<div class="card-head"><h3><span class="grip" draggable="true" title="Drag to move this chart">⠿</span>${escapeHtml(spec.title)}</h3>
      <span class="muted">${escapeHtml(spec.unit)}
        <button class="pause-chart" title="Pause to hover over points and read exact values (Space)">${this.paused ? PLAY_ICON : PAUSE_ICON}</button>
        <button class="expand" title="Full size (Esc to go back)">${EXPAND_ICON}</button>
        <button class="remove" title="Close this chart (Reset charts brings it back)">✕</button></span></div>
      <div class="legend"></div><div class="plot"></div>`;
    document.getElementById('charts').appendChild(card);
    const legend = card.querySelector('.legend');
    legend.innerHTML = spec.series.map((s) =>
      `<span><i style="background:${s.color}"></i>${escapeHtml(s.label)} <b>—</b></span>`).join('') + '<em class="at"></em>';
    const holder = card.querySelector('.plot');
    const axis = { stroke: '#8b98a8', grid: { stroke: '#222a35', width: 1 }, ticks: { stroke: '#2a3340' }, font: '11px system-ui' };
    const scales = { x: { time: false } };
    const axes = [{ ...axis, values: (u, ticks) => ticks.map((t) => `${t.toFixed(0)}s`) }, { ...axis, size: 56 }];
    if (spec.series.some((s) => s.scale === 'ms')) {
      scales.ms = { auto: true };
      axes.push({ ...axis, scale: 'ms', side: 1, grid: { show: false }, size: 44 });
    }
    const chart = { spec, legend, holder, card, plot: null };
    card.querySelector('.remove').addEventListener('click', () => this.removeChart(chart));
    card.querySelector('.expand').addEventListener('click', () => this.toggleExpand(chart));
    card.querySelector('.pause-chart').addEventListener('click', () => this.setPaused(!this.paused));
    card.querySelector('.grip').addEventListener('dragstart', (event) => {
      this.dragging = chart;
      card.classList.add('dragging');
      event.dataTransfer.effectAllowed = 'move';
      event.dataTransfer.setData('text/plain', spec.id || spec.title);
      event.dataTransfer.setDragImage(card, 20, 20);
    });
    card.querySelector('.grip').addEventListener('dragend', () => this.finishDrag());
    const options = {
      width: holder.clientWidth || 600,
      height: 220,
      scales,
      axes,
      cursor: { drag: { x: true, y: false }, points: { size: 6 } },
      legend: { show: false },
      series: [{}, ...spec.series.map((s) => ({
        label: s.label, stroke: s.color, width: s.width || 1.5, dash: s.dash, scale: s.scale || 'y',
        spanGaps: true, points: { show: false },
      }))],
      hooks: {
        setCursor: [(u) => this.updateLegend(chart, u.cursor.idx)],
        draw: spec.band ? [(u) => drawBand(u, spec.band)] : [],
      },
    };
    chart.plot = new uPlot(options, [[], ...spec.series.map(() => [])], holder);
    // Zooming with the mouse pauses the chart so the view stays put; double click resets.
    holder.addEventListener('mousedown', () => { this.setPaused(true); });
    holder.addEventListener('dblclick', () => { this.setPaused(false); });
    return chart;
  }

  // ---- Custom charts ----------------------------------------------------------

  bindChartDialog() {
    const dialog = document.getElementById('chart-dialog');
    document.getElementById('add-chart').addEventListener('click', () => {
      document.getElementById('chart-title').value = '';
      document.getElementById('chart-filter').value = '';
      this.fillChartKeys();
      dialog.showModal();
    });
    document.getElementById('chart-filter').addEventListener('input', () => this.fillChartKeys());
    dialog.addEventListener('close', () => {
      if (dialog.returnValue !== 'create') return;
      const keys = [...document.querySelectorAll('#chart-keys input:checked')].map((i) => i.value);
      if (!keys.length) return;
      this.addChart(document.getElementById('chart-title').value.trim() || keys.join(', '), keys);
    });
  }

  /** Every number the robot is sending now, with its latest value. */
  fillChartKeys() {
    const filter = document.getElementById('chart-filter').value.toLowerCase();
    const checked = new Set([...document.querySelectorAll('#chart-keys input:checked')].map((i) => i.value));
    const keys = Object.keys(this.latest).sort((a, b) => a.localeCompare(b))
      .filter((k) => !filter || k.toLowerCase().includes(filter) || checked.has(k));
    document.getElementById('chart-keys').innerHTML = keys.length
      ? keys.map((k) => `<label><input type="checkbox" value="${escapeHtml(k)}" ${checked.has(k) ? 'checked' : ''}>
          ${escapeHtml(k)}<span class="value">${escapeHtml(formatNumber(this.latest[k]))}</span></label>`).join('')
      : '<div class="muted" style="padding:6px">Nothing to chart yet: start an OpMode.</div>';
  }

  addChart(title, keys) {
    const spec = {
      id: `c${Date.now().toString(36)}`, custom: true, title, unit: '',
      series: keys.map((key, i) => ({ label: key, key, color: COLORS[i % COLORS.length], width: 1.5 })),
    };
    this.charts.push(this.makeChart(spec));
    saveLayout(this.charts);
    this.dirty = true;
    return spec;
  }

  removeChart(chart) {
    if (this.expanded === chart) this.toggleExpand(chart);
    chart.plot.destroy();
    chart.card.remove();
    this.charts = this.charts.filter((c) => c !== chart);
    saveLayout(this.charts);
  }

  /** Back to the four standard charts, in this browser. */
  resetCharts() {
    if (this.expanded) this.toggleExpand(this.expanded);
    for (const chart of this.charts) { chart.plot.destroy(); chart.card.remove(); }
    try { localStorage.removeItem(LAYOUT_KEY); } catch { /* private window */ }
    this.charts = CHARTS.map((spec) => this.makeChart(spec));
    this.dirty = true;
  }

  // Drag a chart by its ⠿ grip to move it; the order is saved in this browser.
  bindDragging() {
    const holder = document.getElementById('charts');
    holder.addEventListener('dragover', (event) => {
      if (!this.dragging) return;
      event.preventDefault();
      const target = event.target.closest('.chart');
      const moving = this.dragging.card;
      if (!target || target === moving) return;
      const box = target.getBoundingClientRect();
      const after = event.clientX > box.left + box.width / 2 || event.clientY > box.bottom - 20;
      holder.insertBefore(moving, after ? target.nextSibling : target);
    });
    holder.addEventListener('drop', (event) => { event.preventDefault(); this.finishDrag(); });
  }

  finishDrag() {
    if (!this.dragging) return;
    this.dragging.card.classList.remove('dragging');
    this.dragging = null;
    const order = [...document.querySelectorAll('#charts .chart')];
    this.charts.sort((a, b) => order.indexOf(a.card) - order.indexOf(b.card));
    saveLayout(this.charts);
    this.resize();
  }

  bindToolbar() {
    document.querySelectorAll('#window-seg button').forEach((button) => {
      button.addEventListener('click', () => {
        document.querySelectorAll('#window-seg button').forEach((b) => b.classList.toggle('active', b === button));
        this.windowSeconds = Number(button.dataset.window);
        this.dirty = true;
      });
    });
    document.getElementById('pause').addEventListener('click', () => this.setPaused(!this.paused));
    document.getElementById('export-csv').addEventListener('click', () => this.exportCsv());
    document.getElementById('reset-charts').addEventListener('click', () => {
      if (confirm('Go back to the standard charts? Charts you added are removed (in this browser only).')) this.resetCharts();
    });
    document.getElementById('telemetry-filter').addEventListener('input', () => this.renderTable());
  }

  setPaused(paused) {
    this.paused = paused;
    document.getElementById('pause').textContent = paused ? 'Resume' : 'Pause';
    document.getElementById('charts').classList.toggle('paused', paused);
    for (const chart of this.charts) {
      const button = chart.card.querySelector('.pause-chart');
      button.innerHTML = paused ? PLAY_ICON : PAUSE_ICON;
      button.title = paused ? 'Resume live charts (Space)' : 'Pause to hover over points and read exact values (Space)';
    }
    this.dirty = true;
  }

  /**
   * Empties the telemetry table (the OpMode stopped or changed); charts are kept. Frames from
   * before {@code robotTime} (still on their way) go to the charts but not back into the table.
   */
  clearTelemetry(robotTime) {
    this.latest = {};
    this.latestText = {};
    this.tableFrom = robotTime ?? null;
    this.dirty = true;
  }

  /**
   * An OpMode was just INIT: wipe the line the robot drew on the field (the robot itself stays).
   * Frames from before {@code robotTime} (still on their way) do not draw it again.
   */
  clearField(robotTime) {
    this.trail = [];
    this.trailFrom = robotTime ?? null;
    this.dirty = true;
  }

  /** The robot app restarted: start the charts over. */
  reset() {
    this.tableFrom = null;
    this.frames = [];
    this.trail = [];
    this.trailFrom = null;
    this.lastSeq = 0;
    this.shownTime = null;
  }

  addFrames(frames) {
    for (const frame of frames) {
      if (frame.seq <= (this.lastSeq || 0)) continue;   // already have it (catch-up after a tab switch)
      this.lastSeq = frame.seq;
      this.frames.push(frame);
      if (this.tableFrom == null || frame.t >= this.tableFrom) {   // not from before a stop
        Object.assign(this.latest, frame.n);
        for (const key of Object.keys(frame.n)) delete this.latestText[key];
        if (frame.s) {
          Object.assign(this.latestText, frame.s);
          for (const key of Object.keys(frame.s)) delete this.latest[key];
        }
      }
      if (frame.pose && (this.trailFrom == null || frame.t >= this.trailFrom)) {
        this.lastPose = frame.pose;
        const last = this.trail[this.trail.length - 1];
        if (!last || Math.hypot(last[0] - frame.pose[0], last[1] - frame.pose[1]) > 0.5) {
          this.trail.push(frame.pose);
          if (this.trail.length > 2000) this.trail.shift();
        }
      }
    }
    if (this.frames.length > MAX_POINTS) this.frames.splice(0, this.frames.length - MAX_POINTS);
    this.rateCount += frames.length;
    this.lastArrival = performance.now();
    this.dirty = true;
  }

  tick() {
    const now = performance.now();
    const visible = document.getElementById('tab-live').classList.contains('active');
    // While data is coming in, charts are redrawn every screen frame on a smooth clock, so
    // they glide instead of jumping each time a batch arrives (about 20 times a second).
    const streaming = this.lastArrival && now - this.lastArrival < 1000;
    if (visible && !this.paused && (streaming || this.dirty)) this.renderCharts(now);
    this.lastTick = now;
    if (this.dirty && visible) {
      this.dirty = false;
      this.renderTable();
      this.drawField();
    }
    if (now - this.rateStart > 1000) {
      document.getElementById('rate').textContent = this.rateCount ? `${Math.round(this.rateCount * 1000 / (now - this.rateStart))} samples/s` : '';
      this.rateCount = 0;
      this.rateStart = now;
    }
    requestAnimationFrame(() => this.tick());
  }

  /** The robot time shown at the right edge: runs with the wall clock, eased toward the data. */
  displayTime(now) {
    const latest = this.frames[this.frames.length - 1].t;
    // Newest sample, plus the time since it arrived, minus a small delay so the next batch
    // is already there when the edge reaches it.
    const target = latest + Math.min(0.25, (now - (this.lastArrival || now)) / 1000) - 0.06;
    if (this.shownTime == null || Math.abs(target - this.shownTime) > 0.5) {
      this.shownTime = target;
    } else {
      this.shownTime += Math.min(0.1, (now - (this.lastTick || now)) / 1000);
      this.shownTime += (target - this.shownTime) * 0.08;
    }
    return this.shownTime;
  }

  renderCharts(now = performance.now()) {
    if (!this.frames.length) return;
    const end = this.displayTime(now);
    const start = end - this.windowSeconds - 0.5;
    let first = this.frames.length - 1;
    while (first > 0 && this.frames[first - 1].t >= start) first--;
    const shown = this.frames.slice(first);
    const xs = shown.map((f) => f.t - end);   // seconds before now
    // Full size: only that chart is visible, so only it is drawn.
    for (const chart of this.expanded ? [this.expanded] : this.charts) {
      const data = [xs, ...chart.spec.series.map((s) => shown.map((f) => {
        const value = s.get ? s.get(f) : f.n[s.key];
        return value === undefined ? null : value;
      }))];
      chart.plot.setData(data);
      chart.plot.setScale('x', { min: -this.windowSeconds, max: 0 });
      this.updateLegend(chart, chart.plot.cursor.left >= 0 ? chart.plot.cursor.idx : null);
    }
  }

  updateLegend(chart, index) {
    if (!chart.plot) return;
    const data = chart.plot.data;
    const spans = chart.legend.querySelectorAll('b');
    chart.spec.series.forEach((s, i) => {
      const values = data[i + 1] || [];
      let value = index == null ? null : values[index];
      if (index == null) {
        for (let j = values.length - 1; j >= 0; j--) if (values[j] != null) { value = values[j]; break; }
      }
      spans[i].textContent = value == null ? '—' : formatNumber(value);
    });
    // Hovering: which moment the values are from.
    const t = index == null ? null : data[0]?.[index];
    chart.legend.querySelector('.at').textContent = t == null ? '' : `at ${t.toFixed(2)} s`;
  }

  /** One of the Field card's views (field or camera) big in the middle of the screen, or back. */
  zoomView(view) {
    const opening = !view.classList.contains('zoomed');
    document.querySelectorAll('#tab-live .view.zoomed').forEach((other) => {
      other.classList.remove('zoomed');
      other.querySelector('.zoom').innerHTML = EXPAND_ICON;
    });
    if (opening) {
      view.classList.add('zoomed');
      view.querySelector('.zoom').innerHTML = `${SHRINK_ICON}<span>Exit full size</span>`;
    }
    document.body.classList.toggle('view-zoomed', opening);
    requestAnimationFrame(() => { this.resize(); this.dirty = true; });
  }

  resize() {
    // The field drawing: as many pixels as it is shown at, so it stays sharp at any size.
    const field = document.getElementById('field');
    const size = Math.round(field.clientWidth * (window.devicePixelRatio || 1));
    if (size > 0 && field.width !== size) {
      field.width = size;
      field.height = size;
      this.dirty = true;
    }
    for (const chart of this.charts) {
      if (chart === this.expanded) {
        // Fill the Live area below its toolbar; the top bar and toolbar stay visible.
        const toolbar = document.querySelector('#tab-live .toolbar').getBoundingClientRect();
        const top = toolbar.bottom + 12;
        chart.card.style.top = `${top}px`;
        const head = chart.card.querySelector('.card-head').offsetHeight + chart.legend.offsetHeight;
        const height = Math.max(200, window.innerHeight - top - 18 - head - 46);
        chart.plot.setSize({ width: chart.holder.clientWidth, height });
      } else {
        chart.plot.setSize({ width: chart.holder.clientWidth, height: 220 });
      }
    }
  }

  /** One chart full size in the Live area; the button again, or Esc, puts it back. */
  toggleExpand(chart) {
    const opening = this.expanded !== chart;
    if (this.expanded) {
      this.expanded.card.classList.remove('expanded');
      this.expanded.card.style.top = '';
      this.expanded.card.querySelector('.expand').innerHTML = EXPAND_ICON;
      this.expanded = null;
    }
    if (opening) {
      this.expanded = chart;
      chart.card.classList.add('expanded');
      chart.card.querySelector('.expand').innerHTML = `${SHRINK_ICON}<span>Exit full size</span>`;
    }
    document.body.classList.toggle('chart-expanded', !!this.expanded);
    requestAnimationFrame(() => { this.resize(); this.dirty = true; });
  }

  renderTable() {
    const filter = document.getElementById('telemetry-filter').value.toLowerCase();
    const rows = [];
    for (const [key, value] of Object.entries(this.latestText)) rows.push([key, value]);
    for (const [key, value] of Object.entries(this.latest)) rows.push([key, formatNumber(value)]);
    document.getElementById('telemetry').innerHTML = rows
      .filter(([key]) => !filter || key.toLowerCase().includes(filter))
      .map(([key, value]) => `<tr><td>${escapeHtml(key)}</td><td>${escapeHtml(value)}</td></tr>`).join('');
  }

  /** Pedro field: 144 x 144 in, x right, y up, heading radians counter-clockwise. */
  drawField() {
    const canvas = this.field;
    const g = canvas.getContext('2d');
    const size = canvas.width;
    const s = size / 144;
    g.clearRect(0, 0, size, size);
    if (this.fieldImage.complete && this.fieldImage.naturalWidth) {
      g.globalAlpha = 0.85;
      g.drawImage(this.fieldImage, 0, 0, size, size);
      g.globalAlpha = 1;
    } else {
      g.strokeStyle = '#2f3843';
      for (let i = 0; i <= 6; i++) {
        g.beginPath(); g.moveTo(i * 24 * s, 0); g.lineTo(i * 24 * s, size); g.stroke();
        g.beginPath(); g.moveTo(0, i * 24 * s); g.lineTo(size, i * 24 * s); g.stroke();
      }
    }
    canvas.dataset.trail = this.trail.length;   // for the tests
    canvas.dataset.trailShown = this.showTrail ? this.trail.length : 0;
    if (this.trail.length > 1 && this.showTrail) {
      g.strokeStyle = '#f5b301aa';
      g.lineWidth = Math.max(2, size / 240);
      g.beginPath();
      this.trail.forEach(([x, y], i) => (i ? g.lineTo(x * s, size - y * s) : g.moveTo(x * s, size - y * s)));
      g.stroke();
    }
    const pose = this.lastPose;
    if (!pose) return;
    const [x, y, heading] = pose;
    g.save();
    g.translate(x * s, size - y * s);
    g.rotate(-heading);
    const half = 8 * s;
    g.fillStyle = '#f5b30133';
    g.strokeStyle = '#f5b301';
    g.lineWidth = Math.max(2, size / 240);
    g.fillRect(-half, -half, half * 2, half * 2);
    g.strokeRect(-half, -half, half * 2, half * 2);
    g.beginPath(); g.moveTo(0, 0); g.lineTo(half * 1.3, 0); g.stroke();
    g.restore();
    document.getElementById('pose-text').textContent =
      `x ${x.toFixed(1)}  y ${y.toFixed(1)}  ${(heading * 180 / Math.PI).toFixed(0)}°`;
  }

  exportCsv() {
    const keys = [...new Set(this.frames.flatMap((f) => Object.keys(f.n)))];
    const lines = [['t', ...keys].join(',')];
    for (const frame of this.frames) lines.push([frame.t, ...keys.map((k) => frame.n[k] ?? '')].join(','));
    const link = document.createElement('a');
    link.href = URL.createObjectURL(new Blob([lines.join('\n')], { type: 'text/csv' }));
    link.download = `telemetry-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-')}.csv`;
    link.click();
  }
}

// Each browser keeps its own charts: which ones, in what order, and any added ones.
const LAYOUT_KEY = 'biobuzz.charts.v2';

function loadLayout() {
  const standard = new Map(CHARTS.map((spec) => [spec.id, spec]));
  try {
    const saved = JSON.parse(localStorage.getItem(LAYOUT_KEY) || 'null');
    if (Array.isArray(saved)) {
      return saved.map((entry) => (entry.custom ? entry : standard.get(entry.id))).filter(Boolean);
    }
    // Charts added before layouts were saved.
    const added = JSON.parse(localStorage.getItem('biobuzz.charts') || '[]');
    return [...CHARTS, ...added];
  } catch {
    return [...CHARTS];
  }
}

function saveLayout(charts) {
  try {
    localStorage.setItem(LAYOUT_KEY, JSON.stringify(charts.map((c) => (c.spec.custom ? c.spec : { id: c.spec.id }))));
  } catch { /* private window: the layout lasts until the page closes */ }
}

function drawBand(u, percent) {
  const g = u.ctx;
  const top = u.valToPos(percent, 'y', true);
  const bottom = u.valToPos(-percent, 'y', true);
  g.save();
  g.fillStyle = '#3fb95014';
  g.fillRect(u.bbox.left, top, u.bbox.width, bottom - top);
  g.restore();
}

export function formatNumber(value) {
  if (typeof value !== 'number') return String(value);
  const magnitude = Math.abs(value);
  if (magnitude >= 1000) return value.toFixed(0);
  if (magnitude >= 10) return value.toFixed(1);
  return value.toFixed(magnitude >= 1 ? 2 : 3);
}

export { COLORS };
