// Minimal headless-Chromium driver over the DevTools protocol. No npm packages: Node's own
// WebSocket and child_process. Used by the dashboard end-to-end test.
import { spawn } from 'node:child_process';
import { existsSync, mkdtempSync, readdirSync, writeFileSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { join } from 'node:path';

function findChrome() {
  if (process.env.CHROME && existsSync(process.env.CHROME)) return process.env.CHROME;
  const cache = join(homedir(), '.cache/ms-playwright');
  if (existsSync(cache)) {
    for (const dir of readdirSync(cache).filter((d) => d.startsWith('chromium-')).sort().reverse()) {
      const candidate = join(cache, dir, 'chrome-linux64/chrome');
      if (existsSync(candidate)) return candidate;
    }
  }
  for (const name of ['/usr/bin/chromium', '/usr/bin/google-chrome', '/usr/bin/google-chrome-stable']) {
    if (existsSync(name)) return name;
  }
  throw new Error('No Chrome/Chromium found. Set CHROME=/path/to/chrome.');
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

export class Browser {
  static async launch({ width = 1500, height = 950 } = {}) {
    const port = 9300 + Math.floor(Math.random() * 500);
    const profile = mkdtempSync(join(tmpdir(), 'biobuzz-chrome-'));
    const chrome = spawn(findChrome(), [
      '--headless=new', `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`,
      `--window-size=${width},${height}`, '--no-first-run', '--no-default-browser-check',
      '--disable-gpu', '--hide-scrollbars', 'about:blank',
    ], { stdio: 'ignore' });
    let targets;
    for (let i = 0; i < 100; i++) {
      try {
        targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
        if (targets.some((t) => t.type === 'page')) break;
      } catch { /* not up yet */ }
      await sleep(100);
    }
    const page = targets.find((t) => t.type === 'page');
    const browser = new Browser(chrome, page.webSocketDebuggerUrl);
    await browser.ready;
    await browser.send('Page.enable');
    await browser.send('Runtime.enable');
    await browser.send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false });
    return browser;
  }

  constructor(process, url) {
    this.process = process;
    this.id = 0;
    this.pending = new Map();
    this.errors = [];
    this.socket = new WebSocket(url);
    this.ready = new Promise((resolve) => this.socket.addEventListener('open', resolve));
    this.socket.addEventListener('message', (event) => {
      const message = JSON.parse(event.data);
      if (message.id && this.pending.has(message.id)) {
        const { resolve, reject } = this.pending.get(message.id);
        this.pending.delete(message.id);
        if (message.error) reject(new Error(message.error.message));
        else resolve(message.result);
      } else if (message.method === 'Runtime.exceptionThrown') {
        this.errors.push(message.params.exceptionDetails.exception?.description || message.params.exceptionDetails.text);
      } else if (message.method === 'Runtime.consoleAPICalled' && message.params.type === 'error') {
        this.errors.push(message.params.args.map((a) => a.value ?? a.description).join(' '));
      }
    });
  }

  send(method, params = {}) {
    const id = ++this.id;
    this.socket.send(JSON.stringify({ id, method, params }));
    return new Promise((resolve, reject) => this.pending.set(id, { resolve, reject }));
  }

  async goto(url) {
    await this.send('Page.navigate', { url });
    await this.waitFor('document.readyState === "complete"');
  }

  /** Runs an expression (or async function body) in the page and returns its value. */
  async eval(expression) {
    const result = await this.send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
    if (result.exceptionDetails) {
      throw new Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text);
    }
    return result.result.value;
  }

  async waitFor(expression, timeout = 10000) {
    const end = Date.now() + timeout;
    let last;
    while (Date.now() < end) {
      try {
        last = await this.eval(`!!(${expression})`);
        if (last) return true;
      } catch (error) { last = error.message; }
      await sleep(100);
    }
    throw new Error(`Timed out waiting for: ${expression} (last: ${last})`);
  }

  async screenshot(path) {
    const { data } = await this.send('Page.captureScreenshot', { format: 'png' });
    writeFileSync(path, Buffer.from(data, 'base64'));
  }

  async close() {
    try { this.socket.close(); } catch { /* already closed */ }
    this.process.kill('SIGKILL');
  }
}

export { sleep };
