// Robot tab: files on the robot (view, edit, download) and the hardware map editor.
import { api, escapeHtml, getFile, putFile, toast } from './api.js';
import { HardwareEditor } from './hardware.js';

export class RobotTab {
  constructor() {
    this.hardware = new HardwareEditor(document.getElementById('hardware'));
    this.open = null;   // { path, hash }
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
            <a class="btn small" href="/api/file?path=${encodeURIComponent(f.path)}" download="${escapeHtml(f.path.split('/').pop())}">Download</a></td>
        </tr>`).join('') || '<tr><td class="muted">No files yet.</td></tr>';
      document.querySelectorAll('[data-edit]').forEach((button) =>
        button.addEventListener('click', () => this.edit(button.dataset.edit)));
      const map = this.hardwareMap || (files.find((f) => f.path.endsWith('.xml')) || {}).path;
      if (map !== this.hardware.path) this.hardware.load(map);
    } catch (error) {
      toast(`Could not list files: ${error.message}`, true);
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
