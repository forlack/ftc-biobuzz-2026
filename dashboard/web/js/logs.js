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

export class RobotLogView {
  constructor() {
    this.text = '';
    this.busy = false;
    this.source = document.getElementById('logs-source');
    document.getElementById('logs-refresh').addEventListener('click', () => this.refresh());
    this.source.addEventListener('change', () => { this.text = ''; this.render(); this.refresh(); });
    document.getElementById('logs-filter').addEventListener('input', () => this.render());
    setInterval(() => {
      if (!document.hidden && document.getElementById('tab-robot').classList.contains('active')
          && document.getElementById('logs-auto').checked && this.source.value !== 'system') this.refresh();
    }, 3000);
  }

  async refresh() {
    if (this.busy) return;
    this.busy = true;
    const source = this.source.value;
    const url = '/api/logs?source=' + encodeURIComponent(source);
    const download = document.getElementById('logs-download');
    download.href = url;
    download.download = 'robot-' + source + '.txt';
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
      document.getElementById('logs-status').textContent = source === 'system'
        ? 'Accessible Android logcat (manual Refresh) — native crashes and USB errors may appear here; use ADB for a full capture.'
        : 'Persistent samples at 1 Hz. New session ID means the dashboard process restarted; camera report age grows when vision is not updating.';
      if (source.startsWith('events')) document.getElementById('logs-status').textContent = 'Saved robot messages and camera state changes. Native camera/USB errors: select Android logcat, then Refresh.';
      if (status?.logWriteError) document.getElementById('logs-status').textContent = 'Log write failed: ' + status.logWriteError;
    } catch (error) { document.getElementById('logs-status').textContent = error.message; }
    finally {
      this.busy = false;
      if (this.source.value !== source) this.refresh();
    }
  }

  render() {
    const filter = document.getElementById('logs-filter').value.toLowerCase();
    const lines = this.text.trimEnd().split('\n');
    const shown = this.source.value === 'system' ? lines : lines.map(diagnosticLine);
    document.getElementById('robot-log').textContent = shown.filter(line => !filter || line.toLowerCase().includes(filter)).join('\n');
  }
}
