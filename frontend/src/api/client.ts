// HTTP API of the service. Errors carry the message of the server.

export class ApiError extends Error {
  status: number;
  constructor(message: string, status: number) {
    super(message);
    this.status = status;
  }
}

async function request<T = any>(path: string, opts: RequestInit = {}): Promise<T> {
  let r: Response;
  try {
    r = await fetch(path, opts);
  } catch (e) {
    throw new ApiError('Нет связи с сервером', 0);
  }
  if (!r.ok) {
    let msg = `${r.status} ${r.statusText}`;
    try {
      const j = await r.json();
      if (j.error) msg = j.error;
    } catch (e) { /* not json */ }
    throw new ApiError(msg, r.status);
  }
  return (r.status === 204 ? null : r.json()) as T;
}

const json = (method: string, body?: unknown): RequestInit => ({
  method, headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body || {}),
});
const enc = encodeURIComponent;

export const api = {
  status: () => request('/api/status'),
  params: () => request('/api/params'),
  datasets: () => request<any[]>('/api/datasets'),
  dataset: (id: string) => request(`/api/datasets/${id}`),
  overview: (id: string) => request(`/api/datasets/${id}/overview`),
  bbox: (id: string) => request<number[]>(`/api/datasets/${id}/bbox`),
  feature: (id: string, fid: string) => request(`/api/datasets/${id}/features/${enc(fid)}`),
  issues: (id: string) => request<any[]>(`/api/datasets/${id}/issues?limit=2000`),
  issueFeatures: (id: string, code: string) => request(`/api/datasets/${id}/issue-features?code=${enc(code)}`),
  deleteDataset: (id: string) => request(`/api/datasets/${id}`, {method: 'DELETE'}),
  cancelImport: (id: string) => request(`/api/datasets/${id}/cancel`, {method: 'POST'}),
  createVersion: (id: string, edits: unknown[], note: string) => request(`/api/datasets/${id}/versions`, json('POST', {edits, note})),
  updateEdits: (id: string, edits: unknown[]) => request(`/api/datasets/${id}/edits`, json('PUT', {edits})),
  runs: (id: string) => request<any[]>(`/api/datasets/${id}/runs`),
  run: (id: string) => request(`/api/runs/${id}`),
  startRun: (ds: string, body: unknown) => request(`/api/datasets/${ds}/runs`, json('POST', body)),
  updateRun: (id: string, patch: unknown) => request(`/api/runs/${id}`, json('PATCH', patch)),
  cancelRun: (id: string) => request(`/api/runs/${id}/cancel`, {method: 'POST'}),
  deleteRun: (id: string) => request(`/api/runs/${id}`, {method: 'DELETE'}),
  variant: (run: string, v: string) => request(`/api/runs/${run}/variants/${enc(v)}.geojson`),
  journal: (run: string, v: string, after = 0) => request(`/api/runs/${run}/journal?variant=${enc(v)}&after=${after}`),
  search: (run: string, oks: string) => request(`/api/runs/${run}/oks/${enc(oks)}/search`),
  compare: (a: string, b: string) => request(`/api/compare?a=${enc(a)}&b=${enc(b)}`),
  compareFeatures: (a: string, b: string) => request(`/api/compare/features?a=${enc(a)}&b=${enc(b)}`),

  /** Upload with progress; returns {promise, abort}. */
  upload(file: File, onProgress: (loaded: number, total: number) => void) {
    const xhr = new XMLHttpRequest();
    const promise = new Promise<any>((resolve, reject) => {
      const fd = new FormData();
      fd.append('file', file);
      xhr.upload.onprogress = (ev) => ev.lengthComputable && onProgress(ev.loaded, ev.total);
      xhr.onload = () => {
        if (xhr.status >= 300) {
          let msg = String(xhr.status);
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
