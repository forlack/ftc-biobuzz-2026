// Read-only log viewer. Poll only while the Robot tab is visible; no telemetry stream changes.
export function diagnosticLine(line) {
  try {
    const s = JSON.parse(line), l = s.load || {}, c = s.camera || {}, m = s.memory || {};
    if (s.kind === 'event') return `${new Date(s.timeMs).toLocaleTimeString()} [${s.level}] ${s.source}: ${s.message}`;
    const mb = n => Number.isFinite(n) ? (n / 1048576).toFixed(1) : '?';
    return `${new Date(s.timeMs).toLocaleTimeString()} [${s.session}] ${s.opModeState} ${s.opMode || ''}`
      + ` | loop ${l.loopMs ?? '?'} ms | CPU ${l.appCpu ?? '?'}% (one core)`
      + ` | Java ${mb(s.javaUsedBytes)}/${mb(s.javaHeapLimitBytes)} MB | native ${mb(m.nativeAllocatedBytes)} MB`
      + ` | system free ${mb(m.systemAvailableBytes)} MB${m.systemLowMemory ? ' LOW MEMORY' : ''}`
      + ` | camera ${c.state || '?'} ${Number(c.fps || 0).toFixed(1)} FPS`
      + ` | preview ${c.previewEnabled ? 'on' : 'off'} age ${c.previewAgeMs ?? '?'} ms`
      + ` | camera report age ${c.reportAgeMs ?? '?'} ms${c.problem ? ' | ' + c.problem : ''}`;
  } catch { return line; }
}

// Combine a bounded snapshot; do not infer a crash from a missing error line.
export function problemRows(snapshot) {
  const groups = new Map();
  const add = (level, source, message, time, stamp = snapshot.collectedAt || Date.now()) => {
    const key = source + ':' + message.replace(/frame=\d+/g, 'frame=*').replace(/0x[0-9a-f]+/gi, '0x*');
    const found = groups.get(key);
    if (found) {
      found.count++;
      if (stamp < found.firstStamp) { found.firstStamp = stamp; found.first = time; }
      if (stamp >= found.lastStamp) { found.lastStamp = stamp; found.last = time; found.message = message; }
    } else groups.set(key, { level, source, message, first: time, last: time, firstStamp: stamp, lastStamp: stamp, count: 1 });
  };
  for (const line of (snapshot.events || '').split('\n')) {
    try {
      const event = JSON.parse(line);
      if (event.level === 'WARN' || event.level === 'ERROR') {
        add(event.level, event.source, event.message, new Date(event.timeMs).toLocaleTimeString(), event.timeMs);
      }
    } catch { /* Empty/missing archive, not a logged event. */ }
  }
  for (const line of (snapshot.android || '').split('\n')) {
    const match = line.match(/^(\d\d-\d\d\s+[\d:.]+)\s+\d+\s+\d+\s+([VDIWEF])\s+([^:]+):\s?(.*)$/);
    if (!match) continue;
    const [, time, priority, tag, message] = match;
    // These fixed-webcam capability probes do not establish a stream failure.
    if (/Failed to get (?:min |max )?(?:zoom|pan\/tilt).*error -9/i.test(message)) continue;
    if (!/[WEF]/.test(priority) && !/failed to send EOF/i.test(message)) continue;
    const year = new Date(snapshot.collectedAt || Date.now()).getFullYear();
    let stamp = new Date(`${year}-${time.replace(/\s+/, 'T')}`).getTime();
    // Logcat omits the year. Pick the nearest year at the December/January boundary.
    const base = snapshot.collectedAt || Date.now();
    if (stamp > base + 183 * 86400000) stamp = new Date(`${year - 1}-${time.replace(/\s+/, 'T')}`).getTime();
    if (stamp < base - 183 * 86400000) stamp = new Date(`${year + 1}-${time.replace(/\s+/, 'T')}`).getTime();
    add(priority === 'W' || priority === 'D' ? 'WARN' : 'ERROR', tag.trim(), message, time, Number.isFinite(stamp) ? stamp : base);
  }
  const s = snapshot.context || {}, c = s.camera || {}, m = s.memory || {};
  const fresh = Number.isFinite(s.timeMs) && Math.abs((snapshot.collectedAt || Date.now()) - s.timeMs) < 5000;
  const active = s.opModeState === 'init' || s.opModeState === 'running';
  if (s.logWriteError) add('ERROR', 'Log storage', s.logWriteError, 'current');
  if (fresh && m.systemLowMemory) add('WARN', 'Memory', 'Android reports low system memory', 'current');
  if (fresh && active && c.reportAgeMs != null && c.reportAgeMs < 5000) {
    const disabled = /^off\b/i.test(c.problem || '');
    if (!disabled && c.problem) add('WARN', 'Camera status', c.problem, 'current');
    if (!disabled && /ERROR|unavailable/i.test(c.state || '')) add('ERROR', 'Camera status', c.state, 'current');
    if (c.state === 'STREAMING' && c.previewEnabled && c.previewWanted && c.previewAgeMs > 3000)
      add('WARN', 'Preview', 'No new preview frame for ' + Math.round(c.previewAgeMs / 1000) + ' seconds', 'current');
  }
  return [...groups.values()].sort((a, b) => b.lastStamp - a.lastStamp);
}

export function formatProblems(snapshot) {
  const rows = problemRows(snapshot);
  const notice = snapshot.notice ? snapshot.notice + '\n\n' : '';
  return notice + (rows.length ? rows.map(r =>
    `[${r.level}] ${r.source}: ${r.message}\n  ${r.count} occurrence(s) · first ${r.first} · last ${r.last}`
  ).join('\n\n') : 'No problems found.');
}

export class RobotLogView {
  constructor() {
    this.text = '';
    this.busy = false;
    this.source = document.getElementById('logs-source');
    document.getElementById('logs-refresh').addEventListener('click', () => this.refresh());
    const updateAuto = () => {
      document.getElementById('logs-auto').disabled = ['problems', 'system'].includes(this.source.value);
    };
    updateAuto();
    this.source.addEventListener('change', () => { updateAuto(); this.text = ''; this.render(); this.refresh(); });
    document.getElementById('logs-filter').addEventListener('input', () => this.render());
    setInterval(() => {
      if (!document.hidden && document.getElementById('tab-robot').classList.contains('active')
          && document.getElementById('logs-auto').checked && this.source.value !== 'system' && this.source.value !== 'problems') this.refresh();
    }, 3000);
  }

  async refresh() {
    if (this.busy) return;
    this.busy = true;
    const source = this.source.value;
    const url = '/api/logs?source=' + encodeURIComponent(source);
    const download = document.getElementById('logs-download');
    download.href = url;
    download.download = 'robot-' + source + (source === 'problems' ? '.json' : '.txt');
    try {
      const response = await fetch(url, { cache: 'no-store' });
      if (!response.ok) throw new Error(`Log request failed (${response.status})`);
      const text = await response.text();
      if (this.source.value !== source) return;
      this.text = text;
      this.render();
      const status = source === 'system' ? null : await fetch('/api/diagnostics', { cache: 'no-store' })
        .then(r => r.ok ? r.json() : null).catch(() => null);
      if (this.source.value !== source) return;
      document.getElementById('logs-status').textContent = '';
      if (status?.logWriteError) document.getElementById('logs-status').textContent = 'Log write failed: ' + status.logWriteError;
    } catch (error) { document.getElementById('logs-status').textContent = error.message; }
    finally {
      this.busy = false;
      if (this.source.value !== source) this.refresh();
    }
  }

  render() {
    const filter = document.getElementById('logs-filter').value.toLowerCase();
    if (this.source.value === 'problems') {
      let text = 'Click Refresh to collect recent problems.';
      try { text = formatProblems(JSON.parse(this.text)); } catch { }
      document.getElementById('robot-log').textContent = text.split('\n\n')
        .filter(block => !filter || block.toLowerCase().includes(filter)).join('\n\n');
      return;
    }
    const lines = this.text.trimEnd().split('\n').reverse();
    const shown = this.source.value === 'system' ? lines : lines.map(diagnosticLine);
    document.getElementById('robot-log').textContent = shown.filter(line => !filter || line.toLowerCase().includes(filter)).join('\n');
  }
}
