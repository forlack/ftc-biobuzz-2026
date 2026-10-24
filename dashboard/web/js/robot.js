// Robot tab: files on the robot (view, edit, download) and the hardware map editor.
import { api, escapeHtml, getFile, putFile, toast } from './api.js';
import { HardwareEditor } from './hardware.js';
import { HistoryPanel } from './history.js';

export class RobotTab {
  constructor() {
    this.hardware = new HardwareEditor(document.getElementById('hardware'));
    this.history = new HistoryPanel(document.getElementById('history'), (path) => {
      this.refresh();
      if (path.endsWith('.xml')) this.hardware.load(path);
    });
    this.open = null;   // { path, hash }
    document.getElementById('snapshot-save').addEventListener('click', () => this.saveSnapshot());
    document.getElementById('files-refresh').addEventListener('click', () => this.refresh());
    document.getElementById('editor-cancel').addEventListener('click', () => this.close());
    document.getElementById('editor-save').addEventListener('click', () => this.save());
  }

  async refresh(hardwareMap) {
    if (hardwareMap !== undefined) this.hardwareMap = hardwareMap;
    try {
      const files = await api('/api/files');
      document.getElementById('files').innerHTML = files.map((f) => `<tr>
          <td>${escapeHtml(f.path)}</td>
          <td class="r">${size(f.size)}</td>
          <td class="r">${new Date(f.modified).toLocaleString()}</td>
          <td class="r"><button class="btn small" data-edit="${escapeHtml(f.path)}">Open</button>
            <button class="btn small" data-history="${escapeHtml(f.path)}">History</button>
            <a class="btn small" href="/api/file?path=${encodeURIComponent(f.path)}" download="${escapeHtml(f.path.split('/').pop())}">Download</a></td>
        </tr>`).join('') || '<tr><td class="muted">No files yet.</td></tr>';
      document.querySelectorAll('[data-edit]').forEach((button) =>
        button.addEventListener('click', () => this.edit(button.dataset.edit)));
      document.querySelectorAll('[data-history]').forEach((button) =>
        button.addEventListener('click', () => this.history.open(button.dataset.history)));
      this.refreshSnapshots();
      const map = this.hardwareMap || (files.find((f) => f.path.endsWith('.xml')) || {}).path;
      if (map !== this.hardware.path) this.hardware.load(map);
    } catch (error) {
      toast(`Could not list files: ${error.message}`, true);
    }
  }

  // ---- Known good snapshots ------------------------------------------------------

  async refreshSnapshots() {
    try {
      const list = await api('/api/snapshots');
      const holder = document.getElementById('snapshots');
      holder.innerHTML = list.length ? list.map((s) => `<tr>
          <td><b>${escapeHtml(s.name)}</b><div class="muted">${new Date(s.time).toLocaleString()} · ${s.files.length} files</div></td>
          <td class="r"><button class="btn small primary" data-snapshot="${escapeHtml(s.id)}">Restore</button></td>
        </tr>`).join('') : '<tr><td class="muted">No snapshots yet. Save one when the robot works well.</td></tr>';
      holder.querySelectorAll('[data-snapshot]').forEach((button) => button.addEventListener('click', () => {
        const snapshot = list.find((s) => s.id === button.dataset.snapshot);
        this.restoreSnapshot(snapshot);
      }));
    } catch (error) {
      toast(`Could not list snapshots: ${error.message}`, true);
    }
  }

  async saveSnapshot() {
    const input = document.getElementById('snapshot-name');
    const name = input.value.trim();
    if (!name) { toast('Give the snapshot a name, like "Saturday – worked"', true); input.focus(); return; }
    try {
      const snapshot = await api('/api/snapshots', { method: 'POST', body: { name } });
      input.value = '';
      toast(`Saved snapshot "${snapshot.name}" (${snapshot.files.length} files)`);
      this.refreshSnapshots();
    } catch (error) {
      toast(error.message, true);
    }
  }

  async restoreSnapshot(snapshot) {
    const when = new Date(snapshot.time).toLocaleString();
    if (!confirm(`Put these files back the way they were in "${snapshot.name}" (${when})?\n\n${snapshot.files.join('\n')}\n\n`
        + 'Every replaced file stays in its History, so this can be undone. Autons made after the snapshot are kept.')) return;
    try {
      const result = await api('/api/snapshots/restore', { method: 'POST', body: { id: snapshot.id } });
      const map = result.restored.some((p) => p.endsWith('.xml'));
      toast(`Restored "${result.name}": ${result.restored.length} files${map ? '. Restart the robot app for the hardware map.' : ''}`);
      this.refresh();
      if (this.hardware.path) this.hardware.load(this.hardware.path);
    } catch (error) {
      toast(error.message, true);
    }
  }

  async edit(path) {
    try {
      const file = await getFile(path);
      this.open = { path, hash: file.hash };
      document.getElementById('editor').hidden = false;
      document.getElementById('editor-name').textContent = path;
      document.getElementById('editor-text').value = file.text;
      document.getElementById('editor-status').textContent = '';
    } catch (error) {
      toast(error.message, true);
    }
  }

  close() {
    this.open = null;
    document.getElementById('editor').hidden = true;
  }

  async save() {
    if (!this.open) return;
    const status = document.getElementById('editor-status');
    try {
      const result = await putFile(this.open.path, document.getElementById('editor-text').value, this.open.hash);
      this.open.hash = result.hash;
      status.textContent = `Saved ${this.open.path}`;
      status.className = 'status ok';
      this.refresh();
    } catch (error) {
      status.textContent = error.message;
      status.className = 'status bad';
    }
  }
}

function size(bytes) {
  return bytes < 1024 ? `${bytes} B` : `${(bytes / 1024).toFixed(1)} KB`;
}
