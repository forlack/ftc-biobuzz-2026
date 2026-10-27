// History of one robot file: every saved version, what changed, and Restore.
import { api, escapeHtml, formatTime, toast } from './api.js';

export class HistoryPanel {
  constructor(holder, onRestored) {
    this.holder = holder;
    this.onRestored = onRestored;
    this.path = null;
  }

  async open(path) {
    this.path = path;
    this.holder.hidden = false;
    this.holder.innerHTML = `<div class="card-head"><h3>History: ${escapeHtml(path)}</h3>
      <button class="btn small" data-close>Close</button></div><div class="muted">Loading…</div>`;
    this.holder.querySelector('[data-close]').addEventListener('click', () => this.close());
    try {
      const [history, current] = await Promise.all([
        api(`/api/history?path=${encodeURIComponent(path)}`),
        fetch(`/api/file?path=${encodeURIComponent(path)}`).then((r) => (r.ok ? r.text() : '')),
      ]);
      this.render(history, current);
    } catch (error) {
      this.holder.querySelector('.muted').textContent = error.message;
    }
    this.holder.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }

  close() {
    this.holder.hidden = true;
    this.path = null;
  }

  render(history, current) {
    const versions = history.versions;
    const rows = versions.map((v, index) => {
      const isCurrent = v.hash === history.currentHash;
      return `<tr data-id="${escapeHtml(v.id)}">
        <td>${formatTime(v.time)}</td>
        <td>${isCurrent ? '<span class="pill running">current</span>' : ''}${v.original ? '<span class="pill">before the dashboard</span>' : ''}</td>
        <td class="r"><button class="btn small" data-changes>${index === versions.length - 1 ? 'Show' : 'Changes'}</button>
          <button class="btn small primary" data-restore ${isCurrent ? 'disabled' : ''}>Restore</button></td>
      </tr><tr class="changes" hidden><td colspan="3"></td></tr>`;
    }).join('');
    this.holder.innerHTML = `<div class="card-head"><h3>History: ${escapeHtml(history.path)}</h3>
        <button class="btn small" data-close>Close</button></div>
      <div class="muted">Every save is kept here (the newest 30). Restoring saves that version as a new one, so it can be undone too.</div>
      ${versions.length ? `<table class="files history">${rows}</table>` : '<div class="muted">No saved versions yet.</div>'}`;
    this.holder.querySelector('[data-close]').addEventListener('click', () => this.close());
    this.holder.querySelectorAll('tr[data-id]').forEach((row, index) => {
      const id = row.dataset.id;
      row.querySelector('[data-changes]').addEventListener('click', async () => {
        const box = row.nextElementSibling;
        if (!box.hidden) { box.hidden = true; return; }
        const text = await fetch(`/api/history/file?path=${encodeURIComponent(history.path)}&id=${encodeURIComponent(id)}`).then((r) => r.text());
        // Compare with the version saved just before this one, so you see what this save changed.
        const before = versions[index + 1]
          ? await fetch(`/api/history/file?path=${encodeURIComponent(history.path)}&id=${encodeURIComponent(versions[index + 1].id)}`).then((r) => r.text())
          : null;
        box.firstElementChild.innerHTML = describeChanges(before, text);
        box.hidden = false;
      });
      row.querySelector('[data-restore]').addEventListener('click', async () => {
        const when = formatTime(versions[index].time);
        if (!confirm(`Put ${history.path} back to the version from ${when}?`)) return;
        try {
          await api('/api/history/restore', { method: 'POST', body: { path: history.path, id } });
          toast(`Restored ${history.path} from ${when}`);
          if (this.onRestored) this.onRestored(history.path);
          this.open(history.path);
        } catch (error) {
          toast(error.message, true);
        }
      });
    });
  }
}

/** What changed between two versions: settings by name for JSON, otherwise changed lines. */
export function describeChanges(before, after) {
  if (before === null) return `<pre class="diff">${escapeHtml(after.slice(0, 4000))}</pre>`;
  try {
    const a = flatten(JSON.parse(before));
    const b = flatten(JSON.parse(after));
    const keys = [...new Set([...Object.keys(a), ...Object.keys(b)])].filter((k) => a[k] !== b[k]);
    if (!keys.length) return '<span class="muted">No changes.</span>';
    return `<ul class="changes-list">${keys.slice(0, 60).map((k) =>
      `<li><code>${escapeHtml(k)}</code> ${a[k] === undefined ? '<i>new</i>' : escapeHtml(a[k])} → ${b[k] === undefined ? '<i>removed</i>' : escapeHtml(b[k])}</li>`).join('')}</ul>`;
  } catch {
    const a = before.split('\n');
    const b = after.split('\n');
    const removed = a.filter((line) => !b.includes(line));
    const added = b.filter((line) => !a.includes(line));
    if (!removed.length && !added.length) return '<span class="muted">No changes.</span>';
    return `<pre class="diff">${removed.slice(0, 40).map((l) => `<del>- ${escapeHtml(l)}</del>`).join('\n')}
${added.slice(0, 40).map((l) => `<ins>+ ${escapeHtml(l)}</ins>`).join('\n')}</pre>`;
  }
}

function flatten(value, prefix = '', out = {}) {
  if (value && typeof value === 'object') {
    for (const [key, child] of Object.entries(value)) flatten(child, prefix ? `${prefix}.${key}` : key, out);
  } else {
    out[prefix] = JSON.stringify(value);
  }
  return out;
}
