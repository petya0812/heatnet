// HTTP API of the service. Errors carry the message of the server.

async function request(path, opts = {}) {
  const r = await fetch(path, opts);
  if (!r.ok) {
    let msg = r.status + ' ' + r.statusText;
    try {
      const j = await r.json();
      if (j.error) msg = j.error;
    } catch (e) { /* not json */ }
    const err = new Error(msg);
    err.status = r.status;
    throw err;
  }
  return r.status === 204 ? null : r.json();
}

const json = (method, body) => ({method, headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body || {})});

export const api = {
  get: (path) => request(path),
  status: () => request('/api/status'),
  params: () => request('/api/params'),
  defaults: () => request('/api/run-params'),
  datasets: () => request('/api/datasets'),
  dataset: (id) => request(`/api/datasets/${id}`),
  overview: (id) => request(`/api/datasets/${id}/overview`),
  bbox: (id) => request(`/api/datasets/${id}/bbox`),
  feature: (id, fid) => request(`/api/datasets/${id}/features/${encodeURIComponent(fid)}`),
  issues: (id, severity) => request(`/api/datasets/${id}/issues?limit=2000${severity ? '&severity=' + severity : ''}`),
  issueFeatures: (id, code) => request(`/api/datasets/${id}/issue-features?code=${encodeURIComponent(code)}`),
  deleteDataset: (id) => request(`/api/datasets/${id}`, {method: 'DELETE'}),
  cancelImport: (id) => request(`/api/datasets/${id}/cancel`, {method: 'POST'}),
  createVersion: (id, edits, note) => request(`/api/datasets/${id}/versions`, json('POST', {edits, note})),
  updateEdits: (id, edits) => request(`/api/datasets/${id}/edits`, json('PUT', {edits})),
  runs: (id) => request(`/api/datasets/${id}/runs`),
  run: (id) => request(`/api/runs/${id}`),
  startRun: (ds, body) => request(`/api/datasets/${ds}/runs`, json('POST', body)),
  updateRun: (id, patch) => request(`/api/runs/${id}`, json('PATCH', patch)),
  cancelRun: (id) => request(`/api/runs/${id}/cancel`, {method: 'POST'}),
  deleteRun: (id) => request(`/api/runs/${id}`, {method: 'DELETE'}),
  variant: (run, v) => request(`/api/runs/${run}/variants/${encodeURIComponent(v)}.geojson`),
  journal: (run, v, after = 0) => request(`/api/runs/${run}/journal?variant=${encodeURIComponent(v)}&after=${after}`),
  search: (run, oks) => request(`/api/runs/${run}/oks/${encodeURIComponent(oks)}/search`),
  compare: (a, b) => request(`/api/compare?a=${encodeURIComponent(a)}&b=${encodeURIComponent(b)}`),
  compareFeatures: (a, b) => request(`/api/compare/features?a=${encodeURIComponent(a)}&b=${encodeURIComponent(b)}`),

  /** Upload with progress; returns {promise, abort}. */
  upload(file, onProgress) {
    const xhr = new XMLHttpRequest();
    const promise = new Promise((resolve, reject) => {
      const fd = new FormData();
      fd.append('file', file);
      xhr.upload.onprogress = (ev) => ev.lengthComputable && onProgress(ev.loaded, ev.total);
      xhr.onload = () => {
        if (xhr.status >= 300) {
          let msg = xhr.status + '';
          try { msg = JSON.parse(xhr.responseText).error || msg; } catch (e) { /* text */ }
          reject(new Error(msg));
        } else resolve(JSON.parse(xhr.responseText));
      };
      xhr.onerror = () => reject(new Error('Нет связи с сервером'));
      xhr.onabort = () => reject(Object.assign(new Error('Загрузка отменена'), {aborted: true}));
      xhr.open('POST', '/api/datasets');
      xhr.send(fd);
    });
    return {promise, abort: () => xhr.abort()};
  },
};
