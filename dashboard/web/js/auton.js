// Auton tab: our own auton editor. It edits autos/*.json (version 3, lib/AutoRoute.java)
// directly: the start pose, then the steps in order, drives and functions alike. A drive starts
// where the previous one ended, so there is nothing to line up.
//
// Field: Pedro coordinates, 144 x 144 in, x right, y up, headings in degrees counter-clockwise.
import { api, escapeHtml, getFile, putFile, toast } from './api.js';

const FIELD = 144;
// The time estimate, fitted to the simulator (which follows paths with our Foresight tuning):
// straight drives of 6 to 100 in, at 100% and 50%, and a drive that turns half way round.
const TOP_SPEED = 68.5;            // in/s, Foresight maxAchievableForwardVelocity
const CRUISE = 1.065;              // the simulator cruises a little above it (~73 in/s)
const ACCEL = 230, DECEL = 230;    // in/s², as the simulator speeds up and brakes
const END_SETTLE = 0.1;            // s, creeping onto the end point
const TURN_WHILE_DRIVING = 235;    // degrees per second of turning add a second to a drive
// A drive that starts facing another way than the robot: Pedro turns it in place first, then
// drives (seen on the robot and in the simulator: about 280°/s). Smaller differences it turns
// while driving.
const TURN_FIRST = 10;             // degrees
const TURN_RATE = 300;             // degrees per second
const TURN_SETTLE = 0.1;           // seconds
const COLORS = ['#f5b301', '#58a6ff', '#3fb950', '#bc8cff', '#ff7b72', '#39c5cf', '#ffa657', '#d2a8ff'];
const HEADINGS = [
  ['travel', 'Face where it is going'],
  ['backwards', 'Drive backwards'],
  ['keep', 'Keep facing'],
  ['turn', 'Turn to…'],
  ['face', 'Face a point'],
  ['away', 'Face away from a point'],
];

// ---- Geometry ------------------------------------------------------------------------------

const lerp = (a, b, t) => [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t];

/** A point on the Bézier curve through control points pts (any degree). */
function bezier(pts, t) {
  let p = pts;
  while (p.length > 1) p = p.slice(1).map((q, i) => lerp(p[i], q, t));
  return p[0];
}

/** Splits a Bézier curve at t into two curves with the same shape. */
function splitBezier(pts, t) {
  const left = [], right = [];
  let p = pts;
  while (p.length) {
    left.push(p[0]);
    right.unshift(p[p.length - 1]);
    p = p.slice(1).map((q, i) => lerp(p[i], q, t));
  }
  return [left, right];
}

const degrees = (radians) => (radians * 180) / Math.PI;
const wrap = (deg) => ((deg % 360) + 360) % 360;
/** The shorter way from a to b, in [-180, 180): exactly half a turn goes clockwise (as the robot). */
const shortest = (a, b) => { const c = b - a; return c - 360 * Math.floor((c + 180) / 360); };
const round = (v) => Math.round(v * 100) / 100;

/**
 * Where everything is: for each step, a drive's start pose, segments (absolute control points),
 * samples along it, its length and end pose. Mirrors lib/AutoRoute.java's rules.
 */
function layout(route) {
  let pose = { x: route.start.x, y: route.start.y, h: route.start.heading };
  return route.steps.map((step) => {
    if (!step.drive) return { start: pose, end: pose };
    const drive = step.drive;
    const start = pose;
    let from = [start.x, start.y];
    const segments = drive.segments.map((segment) => {
      const pts = [from, ...(segment.via || []), segment.to];
      const samples = [];
      for (let i = 0; i <= 40; i++) samples.push(bezier(pts, i / 40));
      let length = 0;
      for (let i = 1; i < samples.length; i++) length += Math.hypot(samples[i][0] - samples[i - 1][0], samples[i][1] - samples[i - 1][1]);
      from = segment.to;
      return { pts, samples, length };
    });
    const length = segments.reduce((sum, s) => sum + s.length, 0);
    const last = segments[segments.length - 1].pts;
    const end = last[last.length - 1];
    const heading = drive.heading || { type: 'travel' };
    let h;
    if (heading.type === 'keep') h = start.h;
    else if (heading.type === 'turn') h = start.h + shortest(start.h, heading.degrees);
    else if (heading.type === 'face') h = degrees(Math.atan2(heading.y - end[1], heading.x - end[0])) + (heading.away ? 180 : 0);
    else {
      const before = last[last.length - 2];
      h = degrees(Math.atan2(end[1] - before[1], end[0] - before[0])) + (heading.reverse ? 180 : 0);
    }
    pose = { x: end[0], y: end[1], h };
    return { start, end: pose, segments, length };
  });
}

/** The point at fraction f (0 to 1) of a laid-out drive's whole length. */
function pointAlong(info, f) {
  let left = f * info.length;
  for (const segment of info.segments) {
    for (let i = 1; i < segment.samples.length; i++) {
      const a = segment.samples[i - 1], b = segment.samples[i];
      const d = Math.hypot(b[0] - a[0], b[1] - a[1]);
      if (left <= d || (segment === info.segments.at(-1) && i === segment.samples.length - 1)) return lerp(a, b, d ? Math.min(1, left / d) : 0);
      left -= d;
    }
  }
  return info.segments.at(-1).samples.at(-1);
}

/**
 * A rough speed-up / cruise / slow-down profile for a drive of this length: how long it takes,
 * how far along it is at a time, and when it reaches a distance.
 */
function profile(length, percent, turning = 0) {
  const top = (TOP_SPEED * CRUISE * (percent || 100)) / 100;
  const v = length > 0 ? Math.min(top, Math.sqrt((2 * length * ACCEL * DECEL) / (ACCEL + DECEL))) : 0;
  const ta = v / ACCEL, td = v / DECEL;
  const da = (v * ta) / 2, dd = (v * td) / 2;
  const tc = v > 0 ? Math.max(0, (length - da - dd) / v) : 0;
  const moving = ta + tc + td;
  // Turning while driving slows the whole drive: stretch it evenly.
  const stretch = moving > 0 ? (moving + turning / TURN_WHILE_DRIVING) / moving : 1;
  const total = moving * stretch + (length > 0 ? END_SETTLE : 0);
  const base = (t) => {
    if (t <= 0) return 0;
    if (t < ta) return (ACCEL * t * t) / 2;
    if (t < ta + tc) return da + v * (t - ta);
    if (t < moving) { const r = moving - t; return length - (DECEL * r * r) / 2; }
    return length;
  };
  const baseTime = (d) => {
    if (d <= 0) return 0;
    if (d < da) return Math.sqrt((2 * d) / ACCEL);
    if (d < da + v * tc) return ta + (d - da) / v;
    if (d < length) return moving - Math.sqrt((2 * (length - d)) / DECEL);
    return moving;
  };
  const distance = (t) => base(t / stretch);
  const time = (d) => (d >= length ? total : baseTime(d) * stretch);
  return { total, distance, time };
}

/** A drive's profile: its length, speed % and how much it turns on the way. */
function driveProfile(drive, info) {
  let turning = 0, before = headingAlong(drive, info, 0);
  for (let i = 1; i <= 20; i++) {
    const h = headingAlong(drive, info, i / 20);
    turning += Math.abs(shortest(before, h));
    before = h;
  }
  return profile(info.length, drive.speedPercent, turning);
}

/** The robot's heading (degrees) at fraction f of a laid-out drive. */
function headingAlong(drive, info, f) {
  const heading = drive.heading || { type: 'travel' };
  const start = info.start.h;
  if (heading.type === 'keep') return start;
  if (heading.type === 'turn') return start + shortest(start, heading.degrees) * Math.min(1, f / (heading.by || 1));
  const p = pointAlong(info, f);
  if (heading.type === 'face') return degrees(Math.atan2(heading.y - p[1], heading.x - p[0])) + (heading.away ? 180 : 0);
  const a = pointAlong(info, Math.max(0, f - 0.01)), b = pointAlong(info, Math.min(1, f + 0.01));
  return degrees(Math.atan2(b[1] - a[1], b[0] - a[0])) + (heading.reverse ? 180 : 0);
}

/** Seconds a function step takes (the robot's own steps; instant functions take none). */
function functionSeconds(step) {
  const p = step.params || {};
  switch (step.action) {
    case 'wait': return p.seconds ?? 0.5;
    case 'shoot': return (p.seconds ?? 3.5) + 0.1;   // the flywheels run all match: usually ready
    case 'waitForFlywheels': return Math.min(p.timeout ?? 2, 1);
    case 'turnTo': return 0.8;
    default: return 0;
  }
}

// ---- The tab ---------------------------------------------------------------------------------

/** Locked dashboard (view only): the field and the panel do not edit. */
const locked = () => document.body.classList.contains('locked');

const DRAFT = 'biobuzz.auton.draft:';   // + autos/NAME.json, or "(new)": unsaved changes in this browser
const NOT_ON_PATH_HINT = 'turn to, wait and wait for flywheels are steps of their own';

/** Text placed along a curve (canvas pixels), centred, above it, reading left to right. */
function textAlong(g, points, text, offset) {
  if (points.length < 2) return;
  if (points[points.length - 1][0] < points[0][0]) points = [...points].reverse();
  const lengths = [0];
  for (let i = 1; i < points.length; i++) lengths.push(lengths[i - 1] + Math.hypot(points[i][0] - points[i - 1][0], points[i][1] - points[i - 1][1]));
  const total = lengths.at(-1);
  const width = g.measureText(text).width;
  let d = Math.max(0, (total - width) / 2);
  for (const ch of text) {
    const w = g.measureText(ch).width;
    const mid = Math.min(total, d + w / 2);
    let i = 1;
    while (i < lengths.length - 1 && lengths[i] < mid) i++;
    const a = points[i - 1], b = points[i];
    const seg = lengths[i] - lengths[i - 1] || 1;
    const t = (mid - lengths[i - 1]) / seg;
    const angle = Math.atan2(b[1] - a[1], b[0] - a[0]);
    g.save();
    g.translate(a[0] + (b[0] - a[0]) * t + Math.sin(angle) * offset, a[1] + (b[1] - a[1]) * t - Math.cos(angle) * offset);
    g.rotate(angle);
    g.fillText(ch, -w / 2, 0);
    g.restore();
    d += w;
  }
}

export class AutonTab {
  constructor() {
    this.root = document.getElementById('tab-auton');
    this.canvas = document.getElementById('auton-field');
    this.panel = document.getElementById('auton-panel');
    this.image = new Image();
    this.image.src = 'fields/biobuzz.webp';
    this.image.onload = () => this.draw();
    this.actions = [];
    this.files = [];
    this.route = null;       // the auton being edited (version 3 JSON)
    this.path = null;        // autos/NAME.json it came from, or null for a new one
    this.hash = 'new';       // its hash when opened, so a save refuses someone else's change
    this.saved = '';         // JSON as last opened or saved, to show unsaved changes
    this.restored = false;   // unsaved changes were brought back from this browser
    this.selected = -1;      // step index
    this.mode = null;        // null | 'add-drive' | 'place-face'
    this.drag = null;
    this.hover = null;       // field point under the mouse
    this.undoStack = [];
    this.redoStack = [];
    this.preview = null;     // { t, total, playing, last } while previewing

    this.bindField();
    new ResizeObserver(() => this.resize()).observe(this.canvas.parentElement);
    document.addEventListener('keydown', (event) => this.key(event));
  }

  /** Called when the tab is shown. */
  async refresh() {
    try {
      this.actions = await api('/api/actions');
      this.files = (await api('/api/files')).map((f) => f.path).filter((p) => /^autos\/[^/]+\.json$/.test(p)).sort();
    } catch (error) {
      toast(`Could not reach the robot: ${error.message}`, true);
    }
    if (!this.route) {
      let last = null;
      try { last = localStorage.getItem('biobuzz.auton'); } catch { /* private window */ }
      if (last === '(new)' && this.readDraft('(new)')) this.newAuton();
      else {
        const first = this.files.includes(last) ? last : this.files[0];
        if (first) await this.open(first); else this.newAuton();
      }
    }
    this.render();
  }

  // ---- Files and drafts ------------------------------------------------------------------------

  draftKey() { return DRAFT + (this.path || '(new)'); }

  readDraft(key) {
    try { return JSON.parse(localStorage.getItem(DRAFT + key) || 'null'); } catch { return null; }
  }

  /** Keeps unsaved changes in this browser (per auton), so a reload or a closed tab loses nothing. */
  storeDraft() {
    try {
      if (this.dirty()) localStorage.setItem(this.draftKey(), JSON.stringify({ route: this.route, hash: this.hash, saved: this.saved }));
      else localStorage.removeItem(this.draftKey());
    } catch { /* private window or full */ }
  }

  remember_(path) {
    try { localStorage.setItem('biobuzz.auton', path || '(new)'); } catch { /* private window */ }
  }

  async open(path) {
    try {
      const { text, hash } = await getFile(path);
      const route = JSON.parse(text);
      if (route.version !== 3) throw new Error(`${path} is version ${route.version}: convert it with tools/autos_v2_to_v3.py`);
      this.path = path;
      this.saved = JSON.stringify(route);
      this.hash = hash;
      this.route = route;
      const draft = this.readDraft(path);
      this.restored = !!(draft && JSON.stringify(draft.route) !== this.saved);
      if (this.restored) {
        this.route = draft.route;
        this.hash = draft.hash;     // saving then refuses if the robot's copy changed since
        if (draft.hash !== hash) toast(`${path} changed on the robot since your unsaved changes: Save will ask`, true);
      }
      this.selected = -1;
      this.undoStack = [];
      this.redoStack = [];
      this.stopPreview();
      this.remember_(path);
    } catch (error) {
      toast(error.message, true);
    }
    this.render();
  }

  newAuton() {
    const draft = this.readDraft('(new)');
    const start = this.route ? { ...this.route.start } : { x: 56, y: 9, heading: 90 };
    this.route = draft ? draft.route : { version: 3, name: 'new auton', start, steps: [] };
    this.restored = !!draft;
    this.path = null;
    this.hash = 'new';
    this.saved = '';
    this.selected = -1;
    this.undoStack = [];
    this.redoStack = [];
    this.stopPreview();
    this.remember_(null);
    this.render();
  }

  /** Throws the unsaved changes away: back to the file on the robot. */
  discard() {
    try { localStorage.removeItem(this.draftKey()); } catch { /* private window */ }
    if (this.path) this.open(this.path);
    else { this.route = null; this.newAuton(); }
  }

  dirty() {
    return this.route && JSON.stringify(this.route) !== this.saved;
  }

  async save(asNew = false) {
    let path = this.path;
    if (asNew || !path) {
      const name = window.prompt('Save as (letters, digits, - and _):', asNew ? '' : (this.route.name || '').replace(/[^A-Za-z0-9_-]+/g, '-'));
      if (!name) return;
      if (!/^[A-Za-z0-9_-]+$/.test(name)) { toast('Use only letters, digits, - and _', true); return; }
      path = `autos/${name}.json`;
      if (this.files.includes(path) && !window.confirm(`${path} already exists. Replace it?`)) return;
      if (asNew) this.route.name = name;
    }
    const base = path === this.path ? this.hash : (this.files.includes(path) ? '*' : 'new');
    try {
      const result = await putFile(path, `${JSON.stringify(this.route, null, 2)}\n`, base);
      try { localStorage.removeItem(this.draftKey()); } catch { /* private window */ }
      this.path = path;
      this.hash = result.hash;
      this.saved = JSON.stringify(this.route);
      this.restored = false;
      if (!this.files.includes(path)) this.files = [...this.files, path].sort();
      this.remember_(path);
      toast(`Saved ${path} on the robot`);
    } catch (error) {
      toast(error.message, true);   // the robot checks the whole auton: its message says what is wrong
    }
    this.render();
  }

  // ---- Changes, undo ----------------------------------------------------------------------------

  /** Records the auton before a change, for undo. */
  remember() {
    this.undoStack.push(JSON.stringify(this.route));
    if (this.undoStack.length > 100) this.undoStack.shift();
    this.redoStack = [];
  }

  change(fn) {
    if (locked()) { toast('The dashboard is locked: view only. Click the padlock to unlock it.', true); this.render(); return; }
    this.remember();
    fn(this.route);
    this.render();
  }

  undo() {
    if (!this.undoStack.length) return;
    this.redoStack.push(JSON.stringify(this.route));
    this.route = JSON.parse(this.undoStack.pop());
    if (this.selected >= this.route.steps.length) this.selected = this.route.steps.length - 1;
    this.render();
  }

  redo() {
    if (!this.redoStack.length) return;
    this.undoStack.push(JSON.stringify(this.route));
    this.route = JSON.parse(this.redoStack.pop());
    this.render();
  }

  key(event) {
    if (!this.root.classList.contains('active')) return;
    if (event.key === 'Escape') { this.closeMenu(); if (this.mode) { this.mode = null; this.render(); } return; }
    if (event.target.closest && event.target.closest('input, select, textarea')) return;
    if (locked()) return;
    const mod = event.ctrlKey || event.metaKey;
    if (mod && event.key.toLowerCase() === 'z') { event.preventDefault(); if (event.shiftKey) this.redo(); else this.undo(); }
    else if (mod && event.key.toLowerCase() === 'y') { event.preventDefault(); this.redo(); }
  }

  // ---- Steps ---------------------------------------------------------------------------------------

  insertIndex() {
    return this.selected >= 0 ? this.selected + 1 : this.route.steps.length;
  }

  addDriveTo(point) {
    const used = new Set(this.route.steps.filter((s) => s.drive).map((s) => s.drive.name));
    let n = this.route.steps.filter((s) => s.drive).length + 1;
    while (used.has(`path${n}`)) n++;
    const at = this.insertIndex();
    this.change((route) => route.steps.splice(at, 0, { drive: { name: `path${n}`, segments: [{ to: point }], heading: { type: 'keep' } } }));
    this.selected = at;
    this.mode = null;
    this.render();
  }

  defaults(def) {
    return Object.fromEntries(def.params.map((p) => [p.name, p.default]));
  }

  addFunction(name) {
    const def = this.actions.find((a) => a.name === name);
    if (!def) return;
    const at = this.insertIndex();
    this.change((route) => route.steps.splice(at, 0, { action: name, params: this.defaults(def) }));
    this.selected = at;
    this.render();
  }

  addOnDrive(name) {
    const def = this.actions.find((a) => a.name === name);
    const drive = this.route.steps[this.selected]?.drive;
    if (!def || !drive) return;
    this.change(() => { drive.events = [...(drive.events || []), { at: 0.5, action: name, params: this.defaults(def) }]; });
  }

  move(index, by) {
    const to = index + by;
    if (to < 0 || to >= this.route.steps.length) return;
    this.change((route) => { const [step] = route.steps.splice(index, 1); route.steps.splice(to, 0, step); });
    this.selected = to;
    this.render();
  }

  remove(index) {
    this.change((route) => route.steps.splice(index, 1));
    this.selected = Math.min(index, this.route.steps.length - 1);
    this.render();
  }

  /** Splits a drive at a point on it: a new waypoint, same shape. */
  splitAt(stepIndex, segmentIndex, t) {
    this.change((route) => {
      const info = layout(route)[stepIndex];
      const [left, right] = splitBezier(info.segments[segmentIndex].pts, t);
      const point = left[left.length - 1].map(round);
      const segments = route.steps[stepIndex].drive.segments;
      segments.splice(segmentIndex, 1,
        { ...(left.length > 2 ? { via: left.slice(1, -1).map((p) => p.map(round)) } : {}), to: point },
        { ...(right.length > 2 ? { via: right.slice(1, -1).map((p) => p.map(round)) } : {}), to: segments[segmentIndex].to });
    });
  }

  /** Removes a waypoint: the two segments around it become one, keeping both sides' bends. */
  removeWaypoint(stepIndex, segmentIndex) {
    this.change((route) => {
      const segments = route.steps[stepIndex].drive.segments;
      const a = segments[segmentIndex], b = segments[segmentIndex + 1];
      const via = [...(a.via || []), ...(b.via || [])];
      segments.splice(segmentIndex, 2, { ...(via.length ? { via } : {}), to: b.to });
    });
  }

  /** Adds a bend point to a segment: half way along, pushed sideways so it can be seen. */
  addBend(stepIndex, segmentIndex) {
    this.change((route) => {
      const pts = layout(route)[stepIndex].segments[segmentIndex].pts;
      const a = pts[0], b = pts[pts.length - 1];
      const mid = bezier(pts, 0.5);
      const len = Math.hypot(b[0] - a[0], b[1] - a[1]) || 1;
      const side = [-(b[1] - a[1]) / len, (b[0] - a[0]) / len];
      const push = Math.min(12, len / 4);
      const segment = route.steps[stepIndex].drive.segments[segmentIndex];
      segment.via = [...(segment.via || []), [round(mid[0] + side[0] * push), round(mid[1] + side[1] * push)]];
    });
  }

  /**
   * Makes a waypoint smooth: the bend points on each side line up through it, so the robot
   * does not change direction suddenly there. A straight side gets a bend point first.
   */
  smooth(stepIndex, segmentIndex) {
    this.change((route) => {
      const segments = route.steps[stepIndex].drive.segments;
      const info = layout(route)[stepIndex];
      const a = segments[segmentIndex], b = segments[segmentIndex + 1];
      const p = a.to;
      const before = info.segments[segmentIndex].pts.at(-2);
      const after = info.segments[segmentIndex + 1].pts[1];
      let dx = after[0] - before[0], dy = after[1] - before[1];
      const d = Math.hypot(dx, dy) || 1;
      dx /= d; dy /= d;
      const lenA = Math.max(6, Math.hypot(p[0] - before[0], p[1] - before[1]) * (a.via?.length ? 1 : 0.4));
      const lenB = Math.max(6, Math.hypot(after[0] - p[0], after[1] - p[1]) * (b.via?.length ? 1 : 0.4));
      const inPoint = [round(p[0] - dx * lenA), round(p[1] - dy * lenA)];
      const outPoint = [round(p[0] + dx * lenB), round(p[1] + dy * lenB)];
      a.via = a.via?.length ? [...a.via.slice(0, -1), inPoint] : [inPoint];
      b.via = b.via?.length ? [outPoint, ...b.via.slice(1)] : [outPoint];
    });
  }

  /**
   * Face (or face away from) a point: the point appears just above the robot and follows the
   * mouse until a click places it.
   */
  startPlacingFace(away = false) {
    const drive = this.route.steps[this.selected]?.drive;
    if (!drive) return;
    const end = layout(this.route)[this.selected].end;
    this.change(() => {
      drive.heading = { type: 'face', x: round(Math.min(FIELD, Math.max(0, end.x))), y: round(Math.min(FIELD, Math.max(0, end.y + 16))), ...(away ? { away: true } : {}) };
    });
    this.mode = 'place-face';
    this.render();
  }

  // ---- Functions on drives: where they are -------------------------------------------------------

  def(name) { return this.actions.find((a) => a.name === name); }

  /** Seconds a function takes (0: instant). */
  seconds(event) {
    const def = this.def(event.action);
    return def && def.kind === 'step' ? functionSeconds(event) : 0;
  }

  /** Where a function on a drive starts and ends (fractions); a timed one ends later along it. */
  span(drive, info, event) {
    const duration = this.seconds(event);
    if (!duration) return { from: event.at, to: event.at, timed: false, beyond: false };
    const p = driveProfile(drive, info);
    const t = p.time(event.at * info.length) + duration;
    return { from: event.at, to: Math.min(1, p.distance(t) / info.length), timed: true, beyond: t > p.total };
  }

  // ---- Preview -----------------------------------------------------------------------------------------

  /**
   * Each step's time window, and the whole auton's length. A drive that has to face another way
   * at its start first turns in place (turn: degrees, turnTime: seconds), as the robot does.
   */
  timeline() {
    const info = layout(this.route);
    let t = 0;
    const entries = this.route.steps.map((step, i) => {
      let turn = 0, turnTime = 0, duration;
      if (step.drive) {
        turn = shortest(info[i].start.h, headingAlong(step.drive, info[i], 0));
        if (Math.abs(turn) > TURN_FIRST) turnTime = TURN_SETTLE + Math.abs(turn) / TURN_RATE;
        else turn = 0;
        duration = turnTime + driveProfile(step.drive, info[i]).total;
      } else {
        duration = functionSeconds(step);
      }
      const entry = { i, t0: t, t1: t + duration, info: info[i], step, turn, turnTime };
      t += duration;
      return entry;
    });
    return { entries, total: t };
  }

  /** The preview bar: each step's stretch of time in its colour (drives as on the field). */
  timelineGradient() {
    const { entries, total } = this.timeline();
    if (!total) return '#2b3542';
    let n = 0;
    const stops = [];
    for (const e of entries) {
      const from = (e.t0 / total) * 100, to = (e.t1 / total) * 100;
      if (to <= from) { if (e.step.drive) n++; continue; }
      if (e.step.drive) {
        const color = COLORS[n++ % COLORS.length];
        const turned = ((e.t0 + e.turnTime) / total) * 100;
        if (e.turnTime) stops.push(`${color}77 ${from}%`, `${color}77 ${turned}%`);
        stops.push(`${color} ${turned}%`, `${color} ${to}%`);
      } else {
        stops.push(`#8b98a8 ${from}%`, `#8b98a8 ${to}%`);
      }
    }
    return `linear-gradient(to right, ${stops.join(', ')})`;
  }

  /** The robot's pose at a time in the preview, and the step it is on. */
  poseAt(time) {
    const { entries } = this.timeline();
    let pose = { x: this.route.start.x, y: this.route.start.y, h: this.route.start.heading }, current = -1;
    for (const e of entries) {
      if (time < e.t0) break;
      current = e.i;
      if (e.step.drive) {
        const into = Math.min(time, e.t1) - e.t0;
        if (into < e.turnTime) {
          // Turning in place to face the way the drive starts.
          const k = into / e.turnTime;
          const eased = k * k * (3 - 2 * k);
          pose = { x: e.info.start.x, y: e.info.start.y, h: e.info.start.h + e.turn * eased };
          continue;
        }
        const p = driveProfile(e.step.drive, e.info);
        const f = e.info.length ? p.distance(into - e.turnTime) / e.info.length : 1;
        const at = pointAlong(e.info, f);
        pose = { x: at[0], y: at[1], h: headingAlong(e.step.drive, e.info, f) };
      } else {
        pose = { x: e.info.start.x, y: e.info.start.y, h: e.info.start.h };
      }
    }
    return { pose, current };
  }

  togglePreview() {
    if (this.preview && this.preview.playing) { this.preview.playing = false; this.render(); return; }
    const total = this.timeline().total;
    if (!this.preview || this.preview.t >= total) this.preview = { t: 0, total };
    this.preview.playing = true;
    this.preview.last = performance.now();
    const tick = (now) => {
      const preview = this.preview;
      if (!preview || !preview.playing) return;
      preview.t = Math.min(preview.total, preview.t + (now - preview.last) / 1000);
      preview.last = now;
      if (preview.t >= preview.total) preview.playing = false;
      this.renderPreview();
      if (preview.playing) requestAnimationFrame(tick);
      else this.render();
    };
    this.render();
    requestAnimationFrame(tick);
  }

  stopPreview() {
    this.preview = null;
  }

  /** Cheap per-frame update while previewing: the field, the time and the current step. */
  renderPreview() {
    this.draw();
    const preview = this.preview;
    const slider = document.getElementById('auton-scrub');
    if (!preview || !slider) return;
    slider.value = String(preview.t);
    document.getElementById('auton-preview-time').textContent = `${preview.t.toFixed(1)} s of ${preview.total.toFixed(1)}`;
    const { current } = this.poseAt(preview.t);
    this.panel.querySelectorAll('.auton-step').forEach((row) => row.classList.toggle('playing', Number(row.dataset.step) === current));
  }

  // ---- Field -----------------------------------------------------------------------------------------

  resize() {
    const box = this.canvas.parentElement.getBoundingClientRect();
    const size = Math.max(200, Math.floor(Math.min(box.width, box.height)));
    const ratio = window.devicePixelRatio || 1;
    this.canvas.style.width = `${size}px`;
    this.canvas.style.height = `${size}px`;
    this.canvas.width = size * ratio;
    this.canvas.height = size * ratio;
    this.draw();
  }

  /** Field inches -> canvas pixels. */
  px([x, y]) { const s = this.canvas.width / FIELD; return [x * s, this.canvas.height - y * s]; }

  fieldPoint(event) {
    const rect = this.canvas.getBoundingClientRect();
    const x = ((event.clientX - rect.left) / rect.width) * FIELD;
    const y = FIELD - ((event.clientY - rect.top) / rect.height) * FIELD;
    // Snap to half inches; Shift places freely (tenths).
    const step = event.shiftKey ? 0.1 : 0.5;
    const snap = (v) => Math.round(Math.min(FIELD, Math.max(0, v)) / step) * step;
    return [round(snap(x)), round(snap(y))];
  }

  /** Every draggable thing on the field, topmost first. */
  handles() {
    if (!this.route) return [];
    const list = [];
    const info = layout(this.route);
    const route = this.route;
    const sh = (route.start.heading * Math.PI) / 180;
    list.push({ kind: 'start-heading', at: [route.start.x + Math.cos(sh) * 12, route.start.y + Math.sin(sh) * 12] });
    const step = route.steps[this.selected];
    if (step && step.drive) {
      const drive = step.drive;
      if (drive.heading && drive.heading.type === 'face') list.push({ kind: 'face', at: [drive.heading.x, drive.heading.y] });
      (drive.events || []).forEach((event, e) => list.push({ kind: 'event', step: this.selected, index: e, at: pointAlong(info[this.selected], event.at) }));
      drive.segments.forEach((segment, s) => {
        (segment.via || []).forEach((via, v) => list.push({ kind: 'via', step: this.selected, segment: s, index: v, at: via }));
        list.push({ kind: 'to', step: this.selected, segment: s, at: segment.to });
      });
    }
    route.steps.forEach((s, i) => {
      if (s.drive && i !== this.selected) list.push({ kind: 'to', step: i, segment: s.drive.segments.length - 1, at: s.drive.segments.at(-1).to });
    });
    list.push({ kind: 'start', at: [route.start.x, route.start.y] });
    return list;
  }

  hit(event) {
    const rect = this.canvas.getBoundingClientRect();
    const scale = this.canvas.width / rect.width;
    const mouse = [(event.clientX - rect.left) * scale, (event.clientY - rect.top) * scale];
    const radius = 10 * (window.devicePixelRatio || 1);
    return this.handles().find((h) => { const [x, y] = this.px(h.at); return Math.hypot(x - mouse[0], y - mouse[1]) <= radius; });
  }

  /** The nearest point on a drive: [step, segment, t, distance in inches]. */
  nearestOnDrive(point, onlyStep = null) {
    let best = null;
    layout(this.route).forEach((info, i) => {
      if (!info.segments || (onlyStep !== null && i !== onlyStep)) return;
      info.segments.forEach((segment, s) => {
        for (let k = 0; k <= 200; k++) {
          const t = k / 200;
          const p = bezier(segment.pts, t);
          const d = Math.hypot(p[0] - point[0], p[1] - point[1]);
          if (!best || d < best[3]) best = [i, s, t, d];
        }
      });
    });
    return best;
  }

  bindField() {
    const canvas = this.canvas;
    canvas.addEventListener('contextmenu', (event) => {
      event.preventDefault();
      if (locked()) return;
      const handle = this.hit(event);
      if (!handle || handle.step === undefined) return;
      const drive = this.route.steps[handle.step].drive;
      if (handle.kind === 'via') this.change(() => { drive.segments[handle.segment].via.splice(handle.index, 1); if (!drive.segments[handle.segment].via.length) delete drive.segments[handle.segment].via; });
      else if (handle.kind === 'to' && handle.segment < drive.segments.length - 1) this.removeWaypoint(handle.step, handle.segment);
      else if (handle.kind === 'event') this.change(() => { drive.events.splice(handle.index, 1); if (!drive.events.length) delete drive.events; });
    });
    canvas.addEventListener('dblclick', (event) => {
      if (!this.route || this.mode || locked()) return;
      const point = this.fieldPoint(event);
      const near = this.nearestOnDrive(point, this.route.steps[this.selected]?.drive ? this.selected : null);
      if (near && near[3] < 4 && near[2] > 0.02 && near[2] < 0.98) {
        this.selected = near[0];
        this.splitAt(near[0], near[1], near[2]);
      }
    });
    canvas.addEventListener('pointerdown', (event) => {
      if (!this.route || event.button !== 0 || locked()) return;
      const point = this.fieldPoint(event);
      if (this.mode === 'add-drive') { this.addDriveTo(point); return; }
      if (this.mode === 'place-face') {
        // Placing: it is under the mouse already; held, it keeps following until let go.
        const heading = this.route.steps[this.selected].drive.heading;
        heading.x = point[0]; heading.y = point[1];
        this.mode = null;
        this.drag = { kind: 'face', placed: true };
        canvas.setPointerCapture(event.pointerId);
        this.render();
        return;
      }
      const handle = this.hit(event);
      if (handle) {
        this.remember();
        this.drag = handle;
        canvas.setPointerCapture(event.pointerId);
        if (handle.step !== undefined && handle.kind !== 'event' && this.selected !== handle.step) { this.selected = handle.step; this.render(); }
        return;
      }
      const near = this.nearestOnDrive(point);
      if (near && near[3] < 4) { this.selected = near[0]; this.render(); }
    });
    canvas.addEventListener('pointermove', (event) => {
      if (!this.route) return;
      this.hover = this.fieldPoint(event);
      const point = this.hover;
      if (this.mode === 'place-face' && !this.drag) {
        const heading = this.route.steps[this.selected]?.drive?.heading;
        if (heading && heading.type === 'face') { heading.x = point[0]; heading.y = point[1]; this.render(); }
        return;
      }
      const handle = this.drag;
      if (!handle) {
        canvas.style.cursor = this.mode ? 'crosshair' : this.hit(event) ? 'grab' : 'default';
        if (this.mode) this.draw();
        return;
      }
      const route = this.route;
      if (handle.kind === 'start') { route.start.x = point[0]; route.start.y = point[1]; }
      else if (handle.kind === 'start-heading') {
        const h = degrees(Math.atan2(point[1] - route.start.y, point[0] - route.start.x));
        const step = event.shiftKey ? 1 : 15;
        route.start.heading = (Math.round(wrap(h) / step) * step) % 360;
      } else if (handle.kind === 'face') { const d = route.steps[this.selected].drive; d.heading.x = point[0]; d.heading.y = point[1]; }
      else if (handle.kind === 'via') route.steps[handle.step].drive.segments[handle.segment].via[handle.index] = point;
      else if (handle.kind === 'to') route.steps[handle.step].drive.segments[handle.segment].to = point;
      else if (handle.kind === 'event') {
        const near = this.nearestOnDrive(point, handle.step);
        if (near) {
          const info = layout(route)[handle.step];
          let before = 0;
          for (let s = 0; s < near[1]; s++) before += info.segments[s].length;
          const along = before + info.segments[near[1]].length * near[2];
          route.steps[handle.step].drive.events[handle.index].at = Math.round((along / info.length) * 100) / 100;
        }
      }
      this.render();
    });
    const end = () => {
      if (!this.drag) return;
      // A drag that changed nothing is not an undo step (placing was recorded when it began).
      if (!this.drag.placed && this.undoStack.at(-1) === JSON.stringify(this.route)) this.undoStack.pop();
      this.drag = null;
      this.render();
    };
    canvas.addEventListener('pointerup', end);
    canvas.addEventListener('pointercancel', end);
    canvas.addEventListener('pointerleave', () => { this.hover = null; if (this.mode) this.draw(); });
  }

  draw() {
    const canvas = this.canvas;
    const g = canvas.getContext('2d');
    const size = canvas.width;
    const s = size / FIELD;
    const k = window.devicePixelRatio || 1;
    g.clearRect(0, 0, size, size);
    if (this.image.complete && this.image.naturalWidth) {
      g.globalAlpha = 0.8;
      g.drawImage(this.image, 0, 0, size, size);
      g.globalAlpha = 1;
    }
    g.strokeStyle = '#ffffff14';
    g.lineWidth = 1;
    for (let i = 1; i < 6; i++) {
      g.beginPath(); g.moveTo(i * 24 * s, 0); g.lineTo(i * 24 * s, size); g.stroke();
      g.beginPath(); g.moveTo(0, i * 24 * s); g.lineTo(size, i * 24 * s); g.stroke();
    }
    if (!this.route) return;
    const info = layout(this.route);
    const robot = this.route.robot || { width: 18, length: 18 };
    const font = (px, weight = 600) => { g.font = `${weight} ${px * k}px system-ui, sans-serif`; };
    const label = (text, [x, y], color = '#e6edf3') => {
      font(12);
      const w = g.measureText(text).width;
      g.fillStyle = '#0d1117cc';
      g.fillRect(x - 3 * k, y - 11 * k, w + 6 * k, 15 * k);
      g.fillStyle = color;
      g.fillText(text, x, y);
    };
    let colorIndex = 0;
    const colors = [];
    // Drives: others dim, the selected one bright with its control lines.
    this.route.steps.forEach((step, i) => {
      if (!step.drive) return;
      const color = COLORS[colorIndex++ % COLORS.length];
      colors[i] = color;
      const selected = i === this.selected;
      const d = info[i];
      g.globalAlpha = selected ? 1 : 0.6;
      g.strokeStyle = color;
      g.lineWidth = (selected ? 4 : 3) * k;
      g.beginPath();
      d.segments.forEach((segment, si) => segment.samples.forEach((p, j) => { const [x, y] = this.px(p); if (j === 0 && si === 0) g.moveTo(x, y); else g.lineTo(x, y); }));
      g.stroke();
      // Which way it goes: a chevron half way.
      const mid = pointAlong(d, 0.5), ahead = pointAlong(d, 0.53);
      const angle = Math.atan2(-(ahead[1] - mid[1]), ahead[0] - mid[0]);
      const [mx, my] = this.px(mid);
      g.fillStyle = color;
      g.beginPath();
      g.moveTo(mx + Math.cos(angle) * 9 * k, my + Math.sin(angle) * 9 * k);
      g.lineTo(mx + Math.cos(angle + 2.5) * 7 * k, my + Math.sin(angle + 2.5) * 7 * k);
      g.lineTo(mx + Math.cos(angle - 2.5) * 7 * k, my + Math.sin(angle - 2.5) * 7 * k);
      g.fill();
      // Functions on the drive: instant ones a ◆ with their name; timed ones a thicker stretch
      // from where they start to where the robot will be when they end, named along it.
      (step.drive.events || []).forEach((event) => {
        const span = this.span(step.drive, d, event);
        const name = this.def(event.action)?.label || event.action;
        if (span.timed) {
          const pts = [];
          for (let f = span.from; f <= span.to + 1e-9; f += Math.max(0.005, (span.to - span.from) / 60)) pts.push(this.px(pointAlong(d, f)));
          pts.push(this.px(pointAlong(d, span.to)));
          g.strokeStyle = '#f5b301';
          g.lineWidth = 8 * k;
          g.lineCap = 'round';
          g.beginPath(); pts.forEach(([x, y], j) => (j ? g.lineTo(x, y) : g.moveTo(x, y))); g.stroke();
          g.lineCap = 'butt';
          for (const f of [span.from, span.to]) {
            const [x, y] = this.px(pointAlong(d, f));
            g.fillStyle = '#f5b301'; g.strokeStyle = '#151b23'; g.lineWidth = 2 * k;
            g.fillRect(x - 5 * k, y - 5 * k, 10 * k, 10 * k); g.strokeRect(x - 5 * k, y - 5 * k, 10 * k, 10 * k);
          }
          font(12, 700);
          g.fillStyle = '#ffe08a';
          textAlong(g, pts, `${name}${span.beyond ? ' (keeps going after)' : ''}`, 12 * k);
        } else {
          const [x, y] = this.px(pointAlong(d, event.at));
          label(name, [x + 10 * k, y - 8 * k], '#ffe08a');
        }
      });
      if (selected) {
        g.setLineDash([4 * k, 4 * k]);
        g.lineWidth = 1 * k;
        g.strokeStyle = '#ffffffaa';
        d.segments.forEach((segment) => {
          if (segment.pts.length < 3) return;
          g.beginPath();
          segment.pts.forEach((p, j) => { const [x, y] = this.px(p); if (j) g.lineTo(x, y); else g.moveTo(x, y); });
          g.stroke();
        });
        if (step.drive.heading && step.drive.heading.type === 'face') {
          const [fx, fy] = this.px([step.drive.heading.x, step.drive.heading.y]);
          const [ex, ey] = this.px([d.end.x, d.end.y]);
          g.strokeStyle = '#ff7b72';
          g.beginPath(); g.moveTo(ex, ey); g.lineTo(fx, fy); g.stroke();
          if (step.drive.heading.away) { g.setLineDash([]); label('back to this', [fx + 12 * k, fy - 10 * k], '#ff9b94'); g.setLineDash([4 * k, 4 * k]); }
        }
        g.setLineDash([]);
        this.drawRobot(g, d.end, robot, color);
        // The heading at each waypoint too.
        let before = 0;
        d.segments.slice(0, -1).forEach((segment) => {
          before += segment.length;
          const f = before / d.length;
          const p = pointAlong(d, f);
          this.drawArrow(g, { x: p[0], y: p[1], h: headingAlong(step.drive, d, f) }, '#ffffffcc', 7);
        });
      } else {
        this.drawRobot(g, d.end, robot, `${color}88`, true);
      }
      g.globalAlpha = 1;
    });
    // The start pose.
    this.drawRobot(g, { x: this.route.start.x, y: this.route.start.y, h: this.route.start.heading }, robot, '#3fb950');
    // Function steps of their own: named where they happen (stacked when several are there).
    const stacks = new Map();
    this.route.steps.forEach((step, i) => {
      if (step.drive) return;
      const p = info[i].start;
      const key = `${Math.round(p.x)},${Math.round(p.y)}`;
      const n = stacks.get(key) || 0;
      stacks.set(key, n + 1);
      const def = this.def(step.action);
      const values = (def?.params || []).map((q) => `${step.params?.[q.name] ?? q.default}${q.unit === '°' ? '°' : q.unit}`);
      const [x, y] = this.px([p.x, p.y]);
      label(`${i + 1}. ${def?.label || step.action}${values.length ? ` ${values.join(' ')}` : ''}`, [x + 14 * k, y + (16 + 16 * n) * k], i === this.selected ? '#f5b301' : '#c9d4e0');
    });
    // Handles.
    for (const h of this.handles()) {
      const [x, y] = this.px(h.at);
      g.lineWidth = 2 * k;
      if (h.kind === 'via') { g.fillStyle = '#151b23'; g.strokeStyle = '#ffffff'; g.beginPath(); g.arc(x, y, 5 * k, 0, Math.PI * 2); g.fill(); g.stroke(); }
      else if (h.kind === 'to') {
        const last = this.route.steps[h.step].drive.segments.length - 1 === h.segment;
        g.fillStyle = last ? '#f5b301' : '#ffffff'; g.strokeStyle = '#151b23';
        g.beginPath(); g.arc(x, y, (last ? 7 : 6) * k, 0, Math.PI * 2); g.fill(); g.stroke();
      } else if (h.kind === 'event') {
        const event = this.route.steps[h.step].drive.events[h.index];
        if (this.seconds(event)) continue;   // timed: drawn as squares above
        g.fillStyle = '#f5b301'; g.strokeStyle = '#151b23';
        g.beginPath(); g.moveTo(x, y - 7 * k); g.lineTo(x + 7 * k, y); g.lineTo(x, y + 7 * k); g.lineTo(x - 7 * k, y); g.closePath(); g.fill(); g.stroke();
      } else if (h.kind === 'face') {
        g.strokeStyle = '#ff7b72';
        g.beginPath(); g.arc(x, y, 7 * k, 0, Math.PI * 2); g.moveTo(x - 11 * k, y); g.lineTo(x + 11 * k, y); g.moveTo(x, y - 11 * k); g.lineTo(x, y + 11 * k); g.stroke();
      } else if (h.kind === 'start-heading') {
        g.fillStyle = '#3fb950'; g.beginPath(); g.arc(x, y, 5 * k, 0, Math.PI * 2); g.fill();
      }
    }
    // Adding a drive: where it would go.
    if (this.mode === 'add-drive' && this.hover) {
      const from = this.selected >= 0 ? info[this.selected].end : info.length ? info.at(-1).end : { x: this.route.start.x, y: this.route.start.y };
      const [ax, ay] = this.px([from.x, from.y]);
      const [bx, by] = this.px(this.hover);
      g.setLineDash([6 * k, 5 * k]); g.strokeStyle = '#f5b301'; g.lineWidth = 2 * k;
      g.beginPath(); g.moveTo(ax, ay); g.lineTo(bx, by); g.stroke(); g.setLineDash([]);
    }
    // Preview: the robot where it would be now.
    if (this.preview) {
      const { pose } = this.poseAt(this.preview.t);
      this.drawRobot(g, pose, robot, '#f5b301', false, true);
    }
    if (this.hover) {
      font(12, 500);
      g.fillStyle = '#e6edf3';
      g.fillText(`${this.hover[0]}, ${this.hover[1]}`, 8 * k, size - 8 * k);
    }
  }

  /** A heading arrow from a point: a shaft and a head. */
  drawArrow(g, pose, color, inches = 10) {
    const s = this.canvas.width / FIELD;
    const k = window.devicePixelRatio || 1;
    const [x, y] = this.px([pose.x, pose.y]);
    const a = (-pose.h * Math.PI) / 180;
    const len = inches * s;
    const tx = x + Math.cos(a) * len, ty = y + Math.sin(a) * len;
    g.strokeStyle = color; g.fillStyle = color; g.lineWidth = 2.5 * k;
    g.beginPath(); g.moveTo(x, y); g.lineTo(tx - Math.cos(a) * 5 * k, ty - Math.sin(a) * 5 * k); g.stroke();
    g.beginPath();
    g.moveTo(tx, ty);
    g.lineTo(tx + Math.cos(a + 2.6) * 9 * k, ty + Math.sin(a + 2.6) * 9 * k);
    g.lineTo(tx + Math.cos(a - 2.6) * 9 * k, ty + Math.sin(a - 2.6) * 9 * k);
    g.closePath(); g.fill();
  }

  drawRobot(g, pose, robot, color, faint = false, filled = false) {
    const s = this.canvas.width / FIELD;
    const k = window.devicePixelRatio || 1;
    const [x, y] = this.px([pose.x, pose.y]);
    g.save();
    g.translate(x, y);
    g.rotate((-pose.h * Math.PI) / 180);
    const w = (robot.length || 18) * s, h = (robot.width || 18) * s;
    if (filled) { g.fillStyle = `${color}33`; g.fillRect(-w / 2, -h / 2, w, h); }
    g.strokeStyle = color;
    g.lineWidth = (faint ? 1 : 2) * k;
    g.strokeRect(-w / 2, -h / 2, w, h);
    g.restore();
    this.drawArrow(g, pose, color, faint ? 7 : 11);
  }

  // ---- Panel -----------------------------------------------------------------------------------------

  label(name) {
    return this.def(name)?.label || name;
  }

  summary(step, info) {
    if (step.drive) {
      const d = step.drive;
      const heading = d.heading || { type: 'travel' };
      const how = heading.type === 'turn' ? `turn to ${heading.degrees}°` : heading.type === 'face' ? (heading.away ? 'face away from a point' : 'face a point')
        : heading.type === 'keep' ? 'keep facing' : heading.reverse ? 'backwards' : 'face travel';
      const parts = [`to ${round(info.end.x)}, ${round(info.end.y)}`, how];
      if (d.segments.length > 1) parts.push(`${d.segments.length - 1} waypoint${d.segments.length > 2 ? 's' : ''}`);
      if (d.speedPercent) parts.push(`${d.speedPercent}%`);
      const events = (d.events || []).map((e) => this.label(e.action));
      return `<b>Drive ${escapeHtml(d.name)}</b> <span class="muted">${escapeHtml(parts.join(' · '))}</span>`
        + (events.length ? `<div class="on-drive">◆ ${escapeHtml(events.join(', '))}</div>` : '');
    }
    const def = this.def(step.action);
    const values = (def?.params || []).map((p) => `${step.params?.[p.name] ?? p.default}${p.unit === '°' ? '°' : ` ${p.unit}`}`);
    return `<b>${escapeHtml(this.label(step.action))}</b> <span class="muted">${escapeHtml(values.join(', '))}</span>`;
  }

  render() {
    this.draw();
    if (!this.route) { this.panel.innerHTML = '<div class="muted">Loading…</div>'; return; }
    this.storeDraft();
    const route = this.route;
    const info = layout(route);
    const { total, entries } = this.timeline();
    if (this.preview) this.preview.total = total;
    const fileOptions = this.files.map((f) => `<option value="${escapeHtml(f)}" ${f === this.path ? 'selected' : ''}>${escapeHtml(f.replace(/^autos\/|\.json$/g, ''))}</option>`).join('');
    const dirty = this.dirty();
    const playing = this.preview && this.preview.playing;
    const step = route.steps[this.selected];
    this.panel.innerHTML = `
      <button class="auton-preview ${playing ? 'playing' : ''}" id="auton-preview" data-testid="auton-preview">${playing ? '■ Stop preview' : '▶ Preview'}</button>
      ${this.preview ? `<div class="auton-scrub"><input type="range" id="auton-scrub" min="0" max="${total}" step="0.05" value="${this.preview.t}"
          style="background: ${this.timelineGradient()}" title="Each stretch is a step: drives in their colour on the field (paler while turning in place first), functions in grey">
        <span id="auton-preview-time" class="muted">${this.preview.t.toFixed(1)} s of ${total.toFixed(1)}</span>
        <button class="icon" id="auton-preview-close" title="Close the preview">✕</button></div>` : ''}

      <section class="auton-card">
        <div class="auton-card-head">Auton file
          <span class="state">${dirty ? '<b class="warn-text">● not saved</b>' : this.path ? '<span class="ok-text">✓ saved</span>' : ''}</span></div>
        <div class="auton-files">
          <select id="auton-file" aria-label="Auton file">${this.path ? '' : '<option selected>(new, not saved)</option>'}${fileOptions}</select>
          <button class="btn small" id="auton-new">New</button>
          <button class="btn small" id="auton-history" ${this.path ? '' : 'disabled'}>History</button>
        </div>
        <div class="auton-files">
          <label class="grow-label">Name <input id="auton-name" value="${escapeHtml(route.name || '')}"></label>
          <button class="btn primary ${dirty ? 'pulse' : ''}" id="auton-save" data-testid="auton-save">Save</button>
          <button class="btn" id="auton-save-as">Save as…</button>
        </div>
        <div class="auton-files robot-size">
          <span class="muted">Robot</span>
          <label>front to back <input type="number" step="0.5" min="6" max="30" id="auton-robot-length" value="${route.robot?.length ?? 18}"> in</label>
          <label>side to side <input type="number" step="0.5" min="6" max="30" id="auton-robot-width" value="${route.robot?.width ?? 18}"> in</label>
        </div>
        ${this.restored && dirty ? `<div class="auton-restored">Your unsaved changes from this browser are back.
          <button class="btn small" id="auton-discard">Discard them</button></div>` : ''}
      </section>

      <section class="auton-card">
        <div class="auton-card-head">Sequence
          <span class="state"><span class="${total > 30 ? 'bad-text' : ''}" id="auton-time">about ${total.toFixed(1)} s of 30${total > 30 ? ': too long!' : ''}</span>
          <button class="icon" id="auton-undo" ${this.undoStack.length ? '' : 'disabled'} title="Undo (Ctrl+Z)">↶</button>
          <button class="icon" id="auton-redo" ${this.redoStack.length ? '' : 'disabled'} title="Redo (Ctrl+Shift+Z)">↷</button></span></div>
        <ol class="auton-steps" id="auton-steps">
          <li class="auton-step start ${this.selected === -1 ? 'selected' : ''}" data-step="-1">
            <span class="num">●</span><span class="what"><b>Start</b>
              <span class="xyz">x <input type="number" step="0.5" data-start="x" value="${route.start.x}">
              y <input type="number" step="0.5" data-start="y" value="${route.start.y}">
              facing <input type="number" step="5" data-start="heading" value="${route.start.heading}">°</span></span>
          </li>
          ${route.steps.map((st, i) => `
          <li class="auton-step ${i === this.selected ? 'selected' : ''} ${st.drive ? 'drive' : 'function'}" data-step="${i}">
            <span class="num">${i + 1}</span><span class="what">${this.summary(st, info[i])}</span>
            <span class="time muted">${entries[i].t1 > entries[i].t0 ? `${(entries[i].t1 - entries[i].t0).toFixed(1)} s` : ''}</span>
            <span class="tools"><button class="icon" data-up="${i}" title="Move up">↑</button><button class="icon" data-down="${i}" title="Move down">↓</button><button class="icon bad" data-remove="${i}" title="Remove">✕</button></span>
          </li>`).join('')}
        </ol>
        <div class="auton-add">
          <button class="btn ${this.mode === 'add-drive' ? 'primary' : ''}" id="auton-add-drive" data-testid="auton-add-drive">+ Drive</button>
          <button class="btn" id="auton-add-function">+ Function</button>
        </div>
        <div class="muted auton-hint" id="auton-hint">${this.mode === 'add-drive' ? 'Click the field where the drive should go (Esc cancels).'
          : this.mode === 'place-face' ? `Click the point to face${step && step.drive && step.drive.heading && step.drive.heading.away ? ' away from' : ''}: it follows the mouse (Esc keeps it where it is).` : 'New steps go after the selected one.'}</div>
      </section>

      <section class="auton-card selected-card">
        <div class="auton-card-head">${step ? (step.drive ? `Selected: drive ${escapeHtml(step.drive.name)}` : `Selected: ${escapeHtml(this.label(step.action))}`) : 'Selected: start'}</div>
        <div class="auton-detail" id="auton-detail">${this.detail(info)}</div>
      </section>
      <div class="muted auton-help">Drag the yellow end points, white waypoints and hollow bend points. Double-click a drive to add a
        waypoint; right-click a waypoint, bend point or ◆ to remove it. Drag the start (green); its dot turns it.
        Snaps to half inches (Shift: free).</div>`;
    this.bindPanel();
  }

  /** The selected step's settings. */
  detail(info) {
    const step = this.route.steps[this.selected];
    if (!step) return '<div class="muted">The start: drag the green robot on the field (its dot turns it), or type x, y and heading in the Start row. Select a step to change it.</div>';
    if (!step.drive) {
      const def = this.def(step.action);
      return `<div class="muted">${escapeHtml(def?.description || '')}</div>
        ${(def?.params || []).map((p) => `<label class="row">${escapeHtml(p.label)} <input type="number" step="0.1" min="${p.min}" max="${p.max}"
          data-param="${escapeHtml(p.name)}" value="${step.params?.[p.name] ?? p.default}"> ${escapeHtml(p.unit)}</label>`).join('')}`;
    }
    const d = step.drive;
    const heading = d.heading || { type: 'travel' };
    const kind = heading.type === 'travel' && heading.reverse ? 'backwards' : heading.type === 'face' && heading.away ? 'away' : heading.type;
    const di = info[this.selected];
    const start = di.start;
    return `
      <label class="row">Name <input id="drive-name" value="${escapeHtml(d.name)}" aria-label="Drive name"></label>
      <div class="muted">Starts at ${round(start.x)}, ${round(start.y)} facing ${Math.round(wrap(start.h))}° (where the step before ends).</div>
      <div class="sub">Heading</div>
      <div class="row"><select id="drive-heading" data-testid="drive-heading">
        ${HEADINGS.map(([value, text]) => `<option value="${value}" ${value === kind ? 'selected' : ''}>${text}</option>`).join('')}</select>
        ${kind === 'turn' ? `<input type="number" step="5" id="drive-turn" value="${heading.degrees}">° by <input type="number" step="10" min="10" max="100" id="drive-by" value="${Math.round((heading.by ?? 1) * 100)}">% of the drive` : ''}
        ${kind === 'face' || kind === 'away' ? `x <input type="number" step="0.5" id="drive-face-x" value="${heading.x}"> y <input type="number" step="0.5" id="drive-face-y" value="${heading.y}">
          <button class="btn small" id="drive-face-pick">Move it</button>` : ''}
      </div>
      <div class="sub">Speed</div>
      <label class="row"><input type="number" step="5" min="1" max="100" id="drive-speed" placeholder="100" value="${d.speedPercent ?? ''}"> % of top speed (empty: full speed)</label>
      <div class="sub">Points</div>
      <table class="kv auton-points">
        ${d.segments.map((segment, s) => `<tr>
          <td>${s === d.segments.length - 1 ? 'End' : `Waypoint ${s + 1}`}</td>
          <td>x <input type="number" step="0.5" data-to="${s}" data-axis="0" value="${segment.to[0]}"> y <input type="number" step="0.5" data-to="${s}" data-axis="1" value="${segment.to[1]}"></td>
          <td><span class="muted">${(segment.via || []).length} bend${(segment.via || []).length === 1 ? '' : 's'}</span>
            <button class="btn small" data-bend="${s}" title="Add a bend point to the part before this point">+ bend</button>
            ${s < d.segments.length - 1 ? `<button class="btn small" data-smooth="${s}" title="Line up the bends on both sides so the robot drives smoothly through">Smooth</button>
            <button class="icon bad" data-unwaypoint="${s}" title="Remove this waypoint">✕</button>` : ''}</td></tr>`).join('')}
      </table>
      <div class="sub">Functions on this drive</div>
      ${(d.events || []).map((event, e) => {
        const def = this.def(event.action);
        const span = this.span(d, di, event);
        return `<div class="row event-row">${span.timed ? '■' : '◆'} <b>${escapeHtml(def?.label || event.action)}</b>
          ${span.timed ? 'starts' : ''} at <input type="number" step="5" min="0" max="100" data-event-at="${e}" value="${Math.round(event.at * 100)}">%
          ${(def?.params || []).map((p) => `${escapeHtml(p.label)} <input type="number" step="0.1" data-event-param="${e}" data-name="${escapeHtml(p.name)}" value="${event.params?.[p.name] ?? p.default}"> ${escapeHtml(p.unit)}`).join('')}
          <button class="icon bad" data-event-remove="${e}" title="Remove">✕</button>
          ${span.timed ? `<div class="muted">The robot keeps driving: done at ${Math.round(span.to * 100)}% of the drive${span.beyond ? ', still going when the drive ends' : ''}.</div>` : ''}</div>`;
      }).join('')}
      <div class="row"><button class="btn small" id="event-add">+ Function on this drive</button>
        <span class="muted">drag it along the drive on the field</span></div>`;
  }

  /** A list of functions to pick from, under a button. */
  menu(anchor, defs, onPick, note) {
    this.closeMenu();
    const box = document.createElement('div');
    box.className = 'auton-menu';
    box.innerHTML = defs.map((d) => `<button data-pick="${escapeHtml(d.name)}"><b>${escapeHtml(d.label)}</b>
      <span>${escapeHtml(d.description || '')}${d.kind === 'step' ? ' <i>(takes time)</i>' : ''}</span></button>`).join('')
      + (note ? `<div class="muted note">${escapeHtml(note)}</div>` : '');
    document.body.appendChild(box);
    const r = anchor.getBoundingClientRect();
    box.style.left = `${Math.min(r.left, window.innerWidth - box.offsetWidth - 8)}px`;
    box.style.top = `${Math.min(r.bottom + 4, window.innerHeight - box.offsetHeight - 8)}px`;
    box.querySelectorAll('[data-pick]').forEach((b) => b.addEventListener('click', () => { this.closeMenu(); onPick(b.dataset.pick); }));
    setTimeout(() => {
      this.outside = (event) => { if (!box.contains(event.target)) this.closeMenu(); };
      document.addEventListener('pointerdown', this.outside);
    });
    this.openMenu = box;
  }

  closeMenu() {
    if (this.openMenu) this.openMenu.remove();
    this.openMenu = null;
    if (this.outside) document.removeEventListener('pointerdown', this.outside);
    this.outside = null;
  }

  bindPanel() {
    const $ = (id) => document.getElementById(id);
    const on = (selector, type, fn) => this.panel.querySelectorAll(selector).forEach((el) => el.addEventListener(type, (event) => fn(el, event)));
    $('auton-preview').addEventListener('click', () => this.togglePreview());
    if ($('auton-scrub')) {
      $('auton-scrub').addEventListener('input', (event) => { this.preview.playing = false; this.preview.t = Number(event.target.value); this.renderPreview(); });
      $('auton-preview-close').addEventListener('click', () => { this.stopPreview(); this.render(); });
      this.renderPreview();
    }
    $('auton-file').addEventListener('change', (event) => this.open(event.target.value));
    $('auton-new').addEventListener('click', () => this.newAuton());
    $('auton-save').addEventListener('click', () => this.save(false));
    $('auton-save-as').addEventListener('click', () => this.save(true));
    if ($('auton-discard')) $('auton-discard').addEventListener('click', () => { if (window.confirm('Throw away your unsaved changes?')) this.discard(); });
    $('auton-history').addEventListener('click', () => window.dispatchEvent(new CustomEvent('biobuzz:history', { detail: this.path })));
    $('auton-undo').addEventListener('click', () => this.undo());
    $('auton-redo').addEventListener('click', () => this.redo());
    $('auton-name').addEventListener('change', (event) => this.change((r) => { r.name = event.target.value; }));
    for (const side of ['length', 'width']) {
      $(`auton-robot-${side}`).addEventListener('change', (event) => {
        const v = Number(event.target.value);
        if (!(v >= 6 && v <= 30)) { toast('Robot size: 6 to 30 inches', true); this.render(); return; }
        this.change((r) => { r.robot = { width: 18, length: 18, ...(r.robot || {}), [side]: v }; });
      });
    }
    $('auton-add-drive').addEventListener('click', () => { this.mode = this.mode === 'add-drive' ? null : 'add-drive'; this.render(); });
    $('auton-add-function').addEventListener('click', (event) => this.menu(event.currentTarget, this.actions, (name) => this.addFunction(name)));
    on('[data-start]', 'change', (el) => { const v = Number(el.value); if (Number.isFinite(v)) this.change((r) => { r.start[el.dataset.start] = v; }); });
    on('.auton-step', 'click', (el, event) => {
      if (event.target.closest('button, input')) return;
      this.selected = Number(el.dataset.step);
      this.render();
    });
    on('[data-up]', 'click', (el) => this.move(Number(el.dataset.up), -1));
    on('[data-down]', 'click', (el) => this.move(Number(el.dataset.down), 1));
    on('[data-remove]', 'click', (el) => this.remove(Number(el.dataset.remove)));
    const step = this.route.steps[this.selected];
    if (!step) return;
    if (!step.drive) {
      on('[data-param]', 'change', (el) => { const v = Number(el.value); if (Number.isFinite(v)) this.change(() => { step.params = { ...(step.params || {}), [el.dataset.param]: v }; }); });
      return;
    }
    const d = step.drive;
    $('drive-name').addEventListener('change', (event) => this.change(() => { d.name = event.target.value.trim() || d.name; }));
    $('drive-heading').addEventListener('change', (event) => {
      const value = event.target.value;
      if (value === 'face' || value === 'away') { this.startPlacingFace(value === 'away'); return; }
      const end = layout(this.route)[this.selected].end;
      this.change(() => {
        if (value === 'travel') d.heading = { type: 'travel' };
        else if (value === 'backwards') d.heading = { type: 'travel', reverse: true };
        else if (value === 'keep') d.heading = { type: 'keep' };
        else d.heading = { type: 'turn', degrees: Math.round(wrap(end.h)) };
      });
    });
    const number = (id, fn) => { const el = $(id); if (el) el.addEventListener('change', () => { const v = Number(el.value); if (el.value.trim() === '' || Number.isFinite(v)) this.change(() => fn(el.value.trim() === '' ? null : v)); }); };
    number('drive-turn', (v) => { if (v !== null) d.heading.degrees = v; });
    number('drive-by', (v) => { if (v === null || v >= 100) delete d.heading.by; else d.heading.by = Math.max(0.1, v / 100); });
    number('drive-face-x', (v) => { if (v !== null) d.heading.x = v; });
    number('drive-face-y', (v) => { if (v !== null) d.heading.y = v; });
    number('drive-speed', (v) => { if (v === null || v >= 100) delete d.speedPercent; else d.speedPercent = Math.max(1, Math.round(v)); });
    if ($('drive-face-pick')) $('drive-face-pick').addEventListener('click', () => { this.remember(); this.mode = 'place-face'; this.render(); });
    on('[data-to]', 'change', (el) => { const v = Number(el.value); if (Number.isFinite(v)) this.change(() => { d.segments[Number(el.dataset.to)].to[Number(el.dataset.axis)] = v; }); });
    on('[data-bend]', 'click', (el) => this.addBend(this.selected, Number(el.dataset.bend)));
    on('[data-smooth]', 'click', (el) => this.smooth(this.selected, Number(el.dataset.smooth)));
    on('[data-unwaypoint]', 'click', (el) => this.removeWaypoint(this.selected, Number(el.dataset.unwaypoint)));
    on('[data-event-at]', 'change', (el) => { const v = Number(el.value); if (Number.isFinite(v)) this.change(() => { d.events[Number(el.dataset.eventAt)].at = Math.min(1, Math.max(0, v / 100)); }); });
    on('[data-event-param]', 'change', (el) => { const v = Number(el.value); if (Number.isFinite(v)) this.change(() => { const e = d.events[Number(el.dataset.eventParam)]; e.params = { ...(e.params || {}), [el.dataset.name]: v }; }); });
    on('[data-event-remove]', 'click', (el) => this.change(() => { d.events.splice(Number(el.dataset.eventRemove), 1); if (!d.events.length) delete d.events; }));
    $('event-add').addEventListener('click', (event) => this.menu(event.currentTarget, this.actions.filter((a) => a.onPath),
      (name) => this.addOnDrive(name), `Not here: ${NOT_ON_PATH_HINT}.`));
  }
}
