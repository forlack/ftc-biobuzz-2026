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
    title: 'Flywheels', unit: 'RPM',
    series: [
      { label: 'Pollen', key: 'Pollen RPM', color: '#f5b301', width: 2 },
      { label: 'Pollen target', key: 'Pollen target', color: '#ffe8a3', dash: [6, 5], width: 1.5 },
      { label: 'Nectar', key: 'Nectar RPM', color: '#58a6ff', width: 2 },
      { label: 'Nectar target', key: 'Nectar target', color: '#b8d8ff', dash: [6, 5], width: 1.5 },
    ],
  },
  {
    title: 'Flywheel error', unit: '%',
    series: [
      { label: 'Pollen', get: PERCENT_OFF('Pollen RPM', 'Pollen target'), color: '#f5b301', width: 1.5 },
      { label: 'Nectar', get: PERCENT_OFF('Nectar RPM', 'Nectar target'), color: '#58a6ff', width: 1.5 },
    ],
    band: 2,
  },
  {
    title: 'Intake', unit: 'RPM',
    series: [
      { label: 'Intake', key: 'Intake RPM', color: '#3fb950', width: 2 },
      { label: 'Target', key: 'Intake target', color: '#b4f0bf', dash: [6, 5], width: 1.5 },
    ],
  },
  {
    title: 'Battery & loop', unit: '',
    series: [
      { label: 'Battery V', key: 'Battery V', color: '#ff7b72', width: 1.5 },
      { label: 'Loop ms', key: 'Loop ms', color: '#bc8cff', width: 1, scale: 'ms' },
    ],
  },
];

const MAX_POINTS = 60 * 250;   // a minute at 250 loops per second

export class LiveTab {
  constructor() {
    this.windowSeconds = 10;
    this.paused = false;
    this.frames = [];          // every frame received, trimmed to MAX_POINTS
    this.latest = {};          // newest value of every key
    this.latestText = {};
    this.trail = [];
    this.lastPose = null;
    this.rateCount = 0;
    this.rateStart = performance.now();
    this.dirty = false;
    this.charts = CHARTS.map((spec) => this.makeChart(spec));
    this.field = document.getElementById('field');
    this.fieldImage = new Image();
    this.fieldImage.src = 'visualizer/fields/biobuzz.webp';
    this.fieldImage.onload = () => this.drawField();
    this.bindToolbar();
    window.addEventListener('resize', () => this.resize());
    requestAnimationFrame(() => this.tick());
  }

  makeChart(spec) {
    const card = document.createElement('div');
    card.className = 'card chart';
    card.innerHTML = `<div class="card-head"><h3>${escapeHtml(spec.title)}</h3><span class="muted">${escapeHtml(spec.unit)}</span></div>
      <div class="legend"></div><div class="plot"></div>`;
    document.getElementById('charts').appendChild(card);
    const legend = card.querySelector('.legend');
    legend.innerHTML = spec.series.map((s) =>
      `<span><i style="background:${s.color}"></i>${escapeHtml(s.label)} <b>—</b></span>`).join('');
    const holder = card.querySelector('.plot');
    const axis = { stroke: '#8b98a8', grid: { stroke: '#222a35', width: 1 }, ticks: { stroke: '#2a3340' }, font: '11px system-ui' };
    const scales = { x: { time: false } };
    const axes = [{ ...axis, values: (u, ticks) => ticks.map((t) => `${t.toFixed(0)}s`) }, { ...axis, size: 56 }];
    if (spec.series.some((s) => s.scale === 'ms')) {
      scales.ms = { auto: true };
      axes.push({ ...axis, scale: 'ms', side: 1, grid: { show: false }, size: 44 });
    }
    const chart = { spec, legend, holder, plot: null };
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
    document.getElementById('telemetry-filter').addEventListener('input', () => this.renderTable());
  }

  setPaused(paused) {
    this.paused = paused;
    document.getElementById('pause').textContent = paused ? 'Resume' : 'Pause';
    this.dirty = true;
  }

  /** New frames from the websocket. */
  addFrames(frames) {
    for (const frame of frames) {
      if (this.frames.length && frame.t < this.frames[this.frames.length - 1].t - 1) {
        this.frames = [];   // the robot restarted: start over
        this.trail = [];
      }
      this.frames.push(frame);
      Object.assign(this.latest, frame.n);
      for (const key of Object.keys(frame.n)) delete this.latestText[key];
      if (frame.s) {
        Object.assign(this.latestText, frame.s);
        for (const key of Object.keys(frame.s)) delete this.latest[key];
      }
      if (frame.pose) {
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
    this.dirty = true;
  }

  tick() {
    if (this.dirty && document.getElementById('tab-live').classList.contains('active')) {
      this.dirty = false;
      if (!this.paused) this.renderCharts();
      this.renderTable();
      this.drawField();
    }
    const now = performance.now();
    if (now - this.rateStart > 1000) {
      document.getElementById('rate').textContent = this.rateCount ? `${Math.round(this.rateCount * 1000 / (now - this.rateStart))} samples/s` : '';
      this.rateCount = 0;
      this.rateStart = now;
    }
    requestAnimationFrame(() => this.tick());
  }

  renderCharts() {
    if (!this.frames.length) return;
    const end = this.frames[this.frames.length - 1].t;
    const start = end - this.windowSeconds;
    let first = this.frames.length - 1;
    while (first > 0 && this.frames[first - 1].t >= start) first--;
    const shown = this.frames.slice(first);
    const xs = shown.map((f) => f.t - end);   // seconds before now
    for (const chart of this.charts) {
      const data = [xs, ...chart.spec.series.map((s) => shown.map((f) => {
        const value = s.get ? s.get(f) : f.n[s.key];
        return value === undefined ? null : value;
      }))];
      chart.plot.setData(data);
      chart.plot.setScale('x', { min: -this.windowSeconds, max: 0 });
      this.updateLegend(chart, null);
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
  }

  resize() {
    for (const chart of this.charts) chart.plot.setSize({ width: chart.holder.clientWidth, height: 220 });
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
    if (this.trail.length > 1) {
      g.strokeStyle = '#f5b301aa';
      g.lineWidth = 2;
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
    g.lineWidth = 2;
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
