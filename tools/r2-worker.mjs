/**
 * 车览 / 车机项目的发布通道：Cloudflare Worker + R2 binding。
 *
 * 为什么用 binding 而不是 S3 签名：本机到 R2 的 S3 端点 TLS 被中间层打断，
 * Worker 里走 S3 出网又只拿到 503（没有 R2 的 request-id）。R2 binding 由
 * Cloudflare 内部直连，不经过公网，也不需要 AK/SK。
 *
 * 路由：
 *   GET  /                               手动下载页（扫描 <project>/latest.json 渲染）
 *   GET  /health                         健康检查
 *   GET  /<key...>                       公开读取对象
 *   PUT  /<key...>   (X-Upload-Token)    写入
 *   DELETE /<key...> (X-Upload-Token)    删除
 *   GET  /_ls?prefix= (X-Upload-Token)   列举
 *
 * 环境变量：
 *   BUCKET         R2 binding（部署时声明）
 *   UPLOAD_TOKEN   上传口令（secret_text）
 */

function json(status, obj) {
  return new Response(JSON.stringify(obj, null, 2), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' },
  });
}

function authorized(request, env) {
  return !!env.UPLOAD_TOKEN && request.headers.get('X-Upload-Token') === env.UPLOAD_TOKEN;
}

function cacheFor(key) {
  return key.endsWith('.json')
    ? 'public, max-age=60, must-revalidate'
    : 'public, max-age=31536000, immutable';
}

function esc(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function humanSize(n) {
  const v = Number(n) || 0;
  if (v >= 1048576) return (v / 1048576).toFixed(1) + ' MB';
  if (v >= 1024) return Math.round(v / 1024) + ' KB';
  return v + ' B';
}

function humanTime(s) {
  if (!s) return '';
  const d = new Date(s);
  if (isNaN(d.getTime())) return esc(s);
  const p = (x) => String(x).padStart(2, '0');
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) +
    ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
}

/** 扫描 R2 顶层 <project>/latest.json，渲染手动下载页。 */
async function renderIndex(env) {
  const list = await env.BUCKET.list({ limit: 1000 });
  const manifests = new Map();
  for (const o of list.objects) {
    const m = /^([^/]+)\/latest\.json$/.exec(o.key);
    if (m) manifests.set(m[1], o.key);
  }
  const items = [];
  for (const entry of manifests) {
    try {
      const obj = await env.BUCKET.get(entry[1]);
      if (!obj) continue;
      const text = (await obj.text()).replace(/^\uFEFF/, '');
      items.push({ name: entry[0], manifest: JSON.parse(text) });
    } catch (e) {
      /* 单个清单坏了不影响整页 */
    }
  }
  items.sort((a, b) => String(a.name).localeCompare(String(b.name)));

  const cards = items.map((item) => {
    const m = item.manifest || {};
    const apk = String(m.apk || '');
    const href = apk ? '/' + apk.split('/').map(encodeURIComponent).join('/') : '#';
    const sha = String(m.sha256 || '');
    const parts = [];
    parts.push('<article class="card">');
    parts.push('<header><h2>' + esc(item.name) + '</h2><span class="ver">v' + esc(m.version || '?') + '</span></header>');
    parts.push('<p class="meta">' + humanSize(m.size) + ' · ' + humanTime(m.publishedAt) + '</p>');
    if (m.notes) parts.push('<p class="notes">' + esc(m.notes) + '</p>');
    if (sha) parts.push('<p class="sha">SHA-256 <code>' + esc(sha.slice(0, 16)) + '…</code></p>');
    parts.push('<a class="btn" href="' + esc(href) + '" download>下载 APK</a>');
    parts.push('</article>');
    return parts.join('');
  }).join('');

  const body = cards || '<p class="empty">还没有发布任何项目。</p>';

  return [
    '<!doctype html>',
    '<html lang="zh-CN"><head>',
    '<meta charset="utf-8">',
    '<meta name="viewport" content="width=device-width, initial-scale=1">',
    '<title>车机应用下载</title>',
    '<style>',
    ':root{color-scheme:dark}',
    '*{box-sizing:border-box}',
    'body{margin:0;padding:4vw 4vw 8vw;background:#0b0e12;color:#e8edf2;',
    'font:16px/1.6 system-ui,-apple-system,"Segoe UI","Noto Sans SC",sans-serif}',
    'h1{font-size:clamp(22px,3.4vw,40px);margin:0 0 .4em}',
    '.sub{color:#8b95a5;margin:0 0 5vw;font-size:clamp(13px,1.3vw,18px)}',
    '.grid{display:grid;gap:2.4vw;grid-template-columns:repeat(auto-fill,minmax(300px,1fr))}',
    '.card{background:#151b23;border:1px solid #232b36;border-radius:14px;padding:3vw;',
    'display:flex;flex-direction:column;gap:.6em}',
    '.card header{display:flex;align-items:baseline;justify-content:space-between;gap:1em}',
    'h2{font-size:clamp(17px,1.5vw,22px);margin:0;word-break:break-all}',
    '.ver{color:#22c55e;font-weight:600;white-space:nowrap}',
    '.meta,.sha{color:#8b95a5;font-size:clamp(12px,1.05vw,15px);margin:0}',
    '.notes{margin:0;color:#c7d0da;font-size:clamp(13px,1.15vw,16px);white-space:pre-wrap}',
    'code{font-size:.95em}',
    '.btn{margin-top:auto;display:inline-block;text-align:center;padding:.7em 1.2em;border-radius:999px;',
    'background:#22c55e;color:#071009;font-weight:700;text-decoration:none}',
    '.btn:active{transform:scale(.98)}',
    '.empty{color:#8b95a5}',
    'footer{margin-top:6vw;color:#5f6b7c;font-size:clamp(11px,1vw,14px)}',
    '</style></head><body>',
    '<h1>车机应用下载</h1>',
    '<p class="sub">手动下载最新安装包。车机上点「下载 APK」即可；装完系统会让你确认安装。</p>',
    '<div class="grid">' + body + '</div>',
    '<footer>由 Cloudflare R2 + Worker 提供 · 每个包都可对照清单里的 SHA-256 校验</footer>',
    '</body></html>',
  ].join('');
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    let key = '';
    try {
      key = decodeURIComponent(url.pathname.replace(/^\/+/, ''));
    } catch (e) {
      key = url.pathname.replace(/^\/+/, '');
    }

    if (!env.BUCKET) return json(500, { ok: false, error: 'R2 binding BUCKET missing' });

    if (key === 'health') {
      let usable = false;
      let detail = '';
      try {
        await env.BUCKET.list({ limit: 1 });
        usable = true;
      } catch (e) {
        detail = String(e && e.message ? e.message : e);
      }
      return json(200, { ok: true, bucket: env.BUCKET.name || 'bound', usable, detail });
    }

    if (key === '_ls') {
      if (!authorized(request, env)) return json(401, { ok: false, error: 'unauthorized' });
      const prefix = url.searchParams.get('prefix') || '';
      try {
        const list = await env.BUCKET.list({ prefix, limit: 1000 });
        return json(200, {
          ok: true,
          count: list.objects.length,
          truncated: !!list.truncated,
          objects: list.objects.map((o) => ({ key: o.key, size: o.size, uploaded: o.uploaded })),
        });
      } catch (e) {
        return json(500, { ok: false, error: String(e && e.message ? e.message : e) });
      }
    }
    if (key === '_diag') {
      const l = await env.BUCKET.list({ limit: 1000 });
      const ms = l.objects.filter((o) => /^([^/]+)\/latest\.json$/.test(o.key)).map((o) => o.key);
      return json(200, {
        ok: true,
        total: l.objects.length,
        truncated: !!l.truncated,
        sample: l.objects.slice(0, 5).map((o) => o.key),
        manifests: ms,
      });
    }

    if (request.method === 'GET' || request.method === 'HEAD') {
      if (!key) {
        const html = await renderIndex(env);
        return new Response(request.method === 'HEAD' ? null : html, {
          status: 200,
          headers: {
            'content-type': 'text/html; charset=utf-8',
            'cache-control': 'public, max-age=300',
            'access-control-allow-origin': '*',
          },
        });
      }
      const obj = request.method === 'HEAD' ? await env.BUCKET.head(key) : await env.BUCKET.get(key);
      if (!obj) return new Response('not found', { status: 404 });
      const headers = new Headers();
      const meta = obj.httpMetadata || {};
      headers.set('content-type', meta.contentType || 'application/octet-stream');
      headers.set('cache-control', cacheFor(key));
      headers.set('access-control-allow-origin', '*');
      headers.set('etag', obj.httpEtag || '');
      if (obj.size != null) headers.set('content-length', String(obj.size));
      return new Response(request.method === 'HEAD' ? null : obj.body, { status: 200, headers });
    }

    if (request.method === 'PUT') {
      if (!authorized(request, env)) return json(401, { ok: false, error: 'unauthorized' });
      if (!key) return json(400, { ok: false, error: 'no key' });
      const type = request.headers.get('Content-Type') || 'application/octet-stream';
      try {
        const body = await request.arrayBuffer();
        const obj = await env.BUCKET.put(key, body, { httpMetadata: { contentType: type } });
        return json(200, { ok: true, key, size: body.byteLength, etag: obj.httpEtag });
      } catch (e) {
        return json(500, { ok: false, error: String(e && e.message ? e.message : e) });
      }
    }

    if (request.method === 'DELETE') {
      if (!authorized(request, env)) return json(401, { ok: false, error: 'unauthorized' });
      await env.BUCKET.delete(key);
      return json(200, { ok: true, deleted: key });
    }

    return json(405, { ok: false, error: 'method not allowed' });
  },
};
