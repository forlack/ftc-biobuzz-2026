// Robot tab: files on the robot (view, edit, download) and the hardware map editor.
import { api, escapeHtml, getFile, putFile, toast } from './api.js';
import { HardwareEditor } from './hardware.js';
import { HistoryPanel } from './history.js';

export class RobotTab {
  constructor() {
    this.hardware = new HardwareEditor(document.getElementById('hardware'), () => this.refresh());
    this.history = new HistoryPanel(document.getElementById('history'), (path) => {
      this.refresh();
      if (path.endsWith('.xml')) this.hardware.load(path);
    });
    this.open = null;   // { path, hash }
    document.getElementById('snapshot-save').addEventListener('click', () => this.saveSnapshot());
    document.getElementById('files-refresh').addEventListener('click', () => this.refresh());
    document.getElementById('editor-cancel').addEventListener('click', () => this.close());
    document.getElementById('editor-save').addEventListener('click', () => this.save());
    document.getElementById('adb-refresh').addEventListener('click', () => this.refreshAdb());
    document.getElementById('adb-restart').addEventListener('click', () => this.restartAdb());
  }

  // ---- adb on the hub ---------------------------------------------------------------

  showAdb(status) {
    let html;
    if (status.listening == null) html = escapeHtml(status.note || '');
    else {
      html = status.listening
        ? '<b class="ok-text">Listening</b> on port 5555. If the laptop still cannot connect, try Restart adb anyway, then the laptop reset.'
        : '<b class="bad-text">Not listening</b> on port 5555: adb on the hub is down. Restart adb.';
      if (status.note) html += `<br>${escapeHtml(status.note)}`;
    }
    document.getElementById('adb-status').innerHTML = html;
    document.getElementById('adb-restart').disabled = !status.canRestart;
  }

  async refreshAdb() {
    try { this.showAdb(await api('/api/adb')); } catch (error) { document.getElementById('adb-status').textContent = error.message; }
  }

  async restartAdb() {
    const button = document.getElementById('adb-restart');
    button.disabled = true;
    button.textContent = 'Restarting…';
    try {
      const status = await api('/api/adb/restart', { method: 'POST', body: {} });
      this.showAdb(status);
      if (status.error) toast(status.error, true); else toast('adb restarted. Reconnect: adb connect 192.168.43.1:5555');
    } catch (error) {
      toast(error.message, true);
      this.refreshAdb();
    } finally {
      button.textContent = 'Restart adb';
    }
  }

  async refresh(hardwareMap) {
    if (hardwareMap !== undefined) this.hardwareMap = hardwareMap;
    this.refreshAdb();
    try {
      const files = await api('/api/files');
      document.getElementById('files').innerHTML = files.map((f) => `<tr>
          <td>${escapeHtml(f.path)}</td>
          <td class="r">${size(f.size)}</td>
          <td class="r">${new Date(f.modified).toLocaleString()}</td>
          <td class="r"><button class="btn small" data-edit="${escapeHtml(f.path)}">Open</button>
            <button class="btn small" data-history="${escapeHtml(f.path)}">History</button>
            <a class="btn small" href="/api/file?path=${encodeURIComponent(f.path)}" download="${escapeHtml(f.path.split('/').pop())}">Download</a>
            ${deletable(f.path, this.hardwareMap) ? `<button class="btn small danger" data-delete="${escapeHtml(f.path)}" title="Delete it from the robot (its history stays)">Delete</button>` : ''}</td>
        </tr>`).join('') || '<tr><td class="muted">No files yet.</td></tr>';
      document.querySelectorAll('[data-edit]').forEach((button) =>
        button.addEventListener('click', () => this.edit(button.dataset.edit)));
      document.querySelectorAll('[data-delete]').forEach((button) =>
        button.addEventListener('click', () => this.remove(button.dataset.delete)));
      document.querySelectorAll('[data-history]').forEach((button) =>
        button.addEventListener('click', () => this.history.open(button.dataset.history)));
      this.refreshSnapshots();
      const maps = files.map((f) => f.path).filter((p) => p.endsWith('.xml')).sort();
      this.hardware.setMaps(maps, this.hardwareMap);
      // Keep the map being looked at; otherwise open the one the robot uses.
      if (!this.hardware.path || !maps.includes(this.hardware.path)) this.hardware.load(this.hardwareMap || maps[0]);
    } catch (error) {
      toast(`Could not list files: ${error.message}`, true);
    }
  }

  /** Deletes an auton or an unused hardware map, after asking. */
  async remove(path) {
    if (!window.confirm(`Delete ${path} from the robot? Its saved versions stay in history (and in Git after robot-sync).`)) return;
    try {
      await api(`/api/file?path=${encodeURIComponent(path)}`, { method: 'DELETE' });
      toast(`Deleted ${path}`);
      if (this.hardware.path === path) this.hardware.path = null;
      this.refresh();
    } catch (error) {
      toast(error.message, true);
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

/** Autons and hardware maps can be deleted; robot.json and the map in use cannot. */
function deletable(path, activeMap) {
  if (path === 'settings/robot.json' || path.startsWith('history/') || path.startsWith('snapshots/')) return false;
  return path !== activeMap;
}
