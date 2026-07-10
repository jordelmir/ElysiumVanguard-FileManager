package com.elysium.vanguard.core.server

/**
 * PHASE 2.3 — Single-page web UI for the local file server.
 *
 * Designed to render well on both phone and laptop browsers. Uses vanilla JS (no
 * framework) so the server emits zero dependencies. The UI does:
 *   - List the contents of the current directory
 *   - Navigate into folders
 *   - Download files
 *   - Upload files via drag & drop or file picker
 *   - Display the auth token reminder
 *
 * All endpoints go through the bearer token; the JS reads it from a meta tag injected
 * by the server.
 */
object WebUi {

    fun landingPageHtml(token: String): String = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>Elysium Vanguard · Local Transfer</title>
<meta name="auth-token" content="$token">
<style>
  :root {
    color-scheme: dark;
    --bg: #050810;
    --panel: #0c111c;
    --border: #1a2030;
    --accent: #00ff9d;
    --accent-dim: #00cc7d;
    --danger: #ff3b6b;
    --muted: #8892a6;
    --text: #e6ecf3;
  }
  * { box-sizing: border-box; }
  html, body { margin: 0; padding: 0; background: var(--bg); color: var(--text);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", system-ui, sans-serif;
    min-height: 100vh; }
  header { padding: 24px; border-bottom: 1px solid var(--border); display: flex;
    align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
  header h1 { margin: 0; font-size: 18px; letter-spacing: 0.04em; text-transform: uppercase; }
  header .badge { font-size: 12px; padding: 4px 8px; border-radius: 6px;
    background: var(--panel); border: 1px solid var(--border); color: var(--muted); }
  main { padding: 16px 24px; max-width: 980px; margin: 0 auto; }
  .toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 16px; flex-wrap: wrap; }
  .toolbar .crumb { font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    color: var(--muted); font-size: 13px; word-break: break-all; }
  button { font: inherit; background: var(--accent); color: #00150c; border: 0;
    padding: 8px 14px; border-radius: 6px; cursor: pointer; font-weight: 600; }
  button.ghost { background: transparent; color: var(--text); border: 1px solid var(--border); }
  button:disabled { opacity: 0.5; cursor: not-allowed; }
  ul.list { list-style: none; margin: 0; padding: 0; border: 1px solid var(--border);
    border-radius: 10px; overflow: hidden; background: var(--panel); }
  ul.list li { display: grid; grid-template-columns: 24px 1fr auto auto;
    gap: 12px; padding: 10px 14px; align-items: center; border-bottom: 1px solid var(--border); }
  ul.list li:last-child { border-bottom: 0; }
  ul.list li .name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  ul.list li .meta { color: var(--muted); font-size: 12px; }
  ul.list li.folder { cursor: pointer; }
  ul.list li.folder:hover { background: rgba(0, 255, 157, 0.05); }
  ul.list li .icon { display: inline-flex; width: 24px; justify-content: center;
    color: var(--accent); font-family: ui-monospace, monospace; }
  ul.list li.file .icon { color: var(--muted); }
  .upload-zone { border: 2px dashed var(--border); border-radius: 10px;
    padding: 36px 16px; text-align: center; margin-top: 24px; color: var(--muted);
    transition: all 0.2s ease; }
  .upload-zone.drag { border-color: var(--accent); color: var(--accent); background: rgba(0, 255, 157, 0.05); }
  .upload-zone p { margin: 0 0 8px 0; }
  .toast { position: fixed; bottom: 24px; left: 50%; transform: translateX(-50%);
    background: var(--panel); border: 1px solid var(--border); color: var(--text);
    padding: 10px 16px; border-radius: 8px; opacity: 0; transition: opacity 0.2s;
    pointer-events: none; }
  .toast.show { opacity: 1; }
  .toast.error { border-color: var(--danger); color: var(--danger); }
  .progress { height: 4px; background: var(--border); border-radius: 4px;
    overflow: hidden; margin-top: 4px; }
  .progress > div { height: 100%; background: var(--accent); width: 0%; transition: width 0.1s; }
  .empty { color: var(--muted); padding: 36px 16px; text-align: center; }
</style>
</head>
<body>
<header>
  <h1>⚡ Elysium Vanguard</h1>
  <span class="badge">Local Transfer · Bearer ${token.take(6)}…</span>
</header>
<main>
  <div class="toolbar">
    <button class="ghost" id="up">↑ Up</button>
    <span class="crumb" id="crumb">/</span>
  </div>
  <ul class="list" id="list"><li class="empty">Loading…</li></ul>
  <div class="upload-zone" id="zone">
    <p>Drop files here or click to pick</p>
    <input type="file" id="picker" multiple style="display:none">
    <button class="ghost" id="pick">Choose Files</button>
  </div>
  <div id="uploads"></div>
</main>
<div class="toast" id="toast"></div>
<script>
(function () {
  const token = document.querySelector('meta[name="auth-token"]').content;
  let cwd = "/";
  const listEl = document.getElementById('list');
  const crumbEl = document.getElementById('crumb');
  const upBtn = document.getElementById('up');
  const zone = document.getElementById('zone');
  const picker = document.getElementById('picker');
  const pickBtn = document.getElementById('pick');
  const uploads = document.getElementById('uploads');
  const toast = document.getElementById('toast');

  function showToast(msg, isError) {
    toast.textContent = msg;
    toast.className = 'toast show' + (isError ? ' error' : '');
    setTimeout(() => toast.className = 'toast', 2400);
  }

  function bytesFmt(n) {
    if (!Number.isFinite(n)) return '';
    if (n < 1024) return n + ' B';
    const u = ['KB','MB','GB','TB'];
    let i = -1, v = n;
    do { v /= 1024; i++; } while (v >= 1024 && i < u.length - 1);
    return v.toFixed(v < 10 ? 2 : 1) + ' ' + u[i];
  }

  async function api(path) {
    const r = await fetch(path, { headers: { 'Authorization': 'Bearer ' + token } });
    if (!r.ok) {
      if (r.status === 401) throw new Error('Bad token');
      throw new Error('HTTP ' + r.status);
    }
    return r.json();
  }

  function render(entries) {
    listEl.innerHTML = '';
    if (!entries || entries.length === 0) {
      const li = document.createElement('li');
      li.className = 'empty';
      li.textContent = 'This folder is empty.';
      listEl.appendChild(li);
      return;
    }
    for (const e of entries) {
      const li = document.createElement('li');
      li.className = e.isDirectory ? 'folder' : 'file';
      li.innerHTML =
        '<span class="icon">' + (e.isDirectory ? '▸' : '·') + '</span>' +
        '<span class="name"></span>' +
        '<span class="meta"></span>' +
        '<span class="action"></span>';
      li.querySelector('.name').textContent = e.name;
      li.querySelector('.meta').textContent = e.isDirectory ? '' : bytesFmt(e.size);
      if (e.isDirectory) {
        li.addEventListener('click', () => navigate(e.path));
      } else {
        const a = document.createElement('a');
        a.textContent = 'Download';
        a.href = '/api/file?path=' + encodeURIComponent(e.path);
        a.setAttribute('download', e.name);
        a.style.color = 'var(--accent)';
        a.style.textDecoration = 'none';
        li.querySelector('.action').appendChild(a);
      }
      listEl.appendChild(li);
    }
  }

  async function load(dir) {
    try {
      const url = '/api/list' + (dir && dir !== '/' ? '?path=' + encodeURIComponent(dir) : '');
      const data = await api(url);
      cwd = dir || '/';
      crumbEl.textContent = cwd;
      render(data.entries || []);
    } catch (e) {
      showToast('Failed to load: ' + e.message, true);
    }
  }

  function navigate(p) { load(p); }

  upBtn.addEventListener('click', () => {
    if (!cwd || cwd === '/' || cwd === '.') return;
    const parts = cwd.split('/').filter(Boolean);
    parts.pop();
    load('/' + parts.join('/'));
  });

  async function uploadFile(file, parentPath) {
    const row = document.createElement('div');
    row.style.cssText = 'margin-top:8px;padding:8px 12px;background:var(--panel);border:1px solid var(--border);border-radius:8px;';
    row.innerHTML = '<div style="display:flex;justify-content:space-between;font-size:13px"></div>' +
      '<div class="progress"><div></div></div>';
    row.querySelector('div > div:first-child').innerHTML =
      '<span></span><span></span>';
    uploads.appendChild(row);

    return new Promise((resolve) => {
      const xhr = new XMLHttpRequest();
      const fd = new FormData();
      fd.append('path', parentPath);
      fd.append('name', file.name);
      fd.append('file', file);
      xhr.open('POST', '/api/upload');
      xhr.setRequestHeader('Authorization', 'Bearer ' + token);
      xhr.upload.onprogress = (ev) => {
        if (!ev.lengthComputable) return;
        const pct = Math.round((ev.loaded / ev.total) * 100);
        row.querySelector('.progress > div').style.width = pct + '%';
        row.querySelector('div > div:first-child > span:last-child').textContent = pct + '%';
      };
      xhr.onload = () => {
        const ok = xhr.status >= 200 && xhr.status < 300;
        if (!ok) {
          row.style.borderColor = 'var(--danger)';
          row.querySelector('div > div:first-child > span:last-child').textContent = 'error';
          showToast('Upload failed: ' + xhr.status, true);
        } else {
          row.querySelector('div > div:first-child > span:last-child').textContent = 'done';
        }
        setTimeout(() => row.remove(), 4000);
        resolve();
      };
      xhr.onerror = () => {
        showToast('Upload error', true);
        resolve();
      };
      row.querySelector('div > div:first-child > span:first-child').textContent = file.name;
      xhr.send(fd);
    });
  }

  pickBtn.addEventListener('click', () => picker.click());
  picker.addEventListener('change', async () => {
    for (const f of picker.files) await uploadFile(f, cwd);
    picker.value = '';
    load(cwd);
  });
  zone.addEventListener('dragover', (e) => { e.preventDefault(); zone.classList.add('drag'); });
  zone.addEventListener('dragleave', () => zone.classList.remove('drag'));
  zone.addEventListener('drop', async (e) => {
    e.preventDefault();
    zone.classList.remove('drag');
    for (const f of e.dataTransfer.files) await uploadFile(f, cwd);
    load(cwd);
  });

  load('/');
})();
</script>
</body>
</html>
"""
}