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
 *   GET  /_diag                          排查：对象总数 / manifest 命中 / 样例 key
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

function stamp(d) {
  const p = (x) => String(x).padStart(2, '0');
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) +
    ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
}

function relative(ts, now) {
  const s = Math.max(0, Math.round((now - ts) / 1000));
  if (s < 90) return '刚刚';
  const m = Math.round(s / 60);
  if (m < 60) return m + ' 分钟前';
  const h = Math.round(m / 60);
  if (h < 24) return h + ' 小时前';
  const day = Math.round(h / 24);
  if (day < 31) return day + ' 天前';
  const mo = Math.round(day / 30);
  if (mo < 12) return mo + ' 个月前';
  return Math.round(mo / 12) + ' 年前';
}

/** notes 可能有多行：压成一行摘要，完整内容塞进 title。 */
function summarize(notes) {
  return String(notes || '').replace(/\s+/g, ' ').trim();
}

/* 车机屏很宽（2880），纯 px 会让整页缩成一小块：尺寸一律以屏宽比例为主，
   同时用 clamp 保手机端可读。1vw @2880 = 28.8px。 */
const CSS = [
  ':root{color-scheme:dark}',
  '*{box-sizing:border-box}',
  'body{margin:0;padding:clamp(16px,1.8vw,52px);background:#0b0e12;color:#e8edf2;',
  'font-family:system-ui,-apple-system,"Segoe UI","Noto Sans SC",sans-serif;',
  'font-size:clamp(15px,.9vw,26px);line-height:1.5;-webkit-font-smoothing:antialiased}',
  '.wrap{max-width:min(2480px,94vw);margin:0 auto}',
  '.top{display:flex;flex-wrap:wrap;gap:.4em 1.2em;align-items:baseline;justify-content:space-between;',
  'margin:0 0 clamp(14px,1.2vw,34px)}',
  'h1{font-size:clamp(22px,1.8vw,52px);margin:0;letter-spacing:.01em;font-weight:700}',
  '.stat{color:#7f8b9a;font-size:clamp(12px,.62vw,18px);white-space:nowrap}',
  '.bar{display:flex;flex-wrap:wrap;gap:clamp(10px,.8vw,24px);margin:0 0 clamp(16px,1.4vw,38px)}',
  'input#q{flex:1 1 clamp(240px,30vw,760px);min-height:clamp(46px,2.4vw,70px);',
  'padding:0 clamp(12px,.8vw,24px);border-radius:12px;border:1px solid #2a3441;',
  'background:#121822;color:#e8edf2;font-size:clamp(15px,.8vw,23px)}',
  'input#q::placeholder{color:#6b7686}',
  'select#sort{min-height:clamp(46px,2.4vw,70px);padding:0 clamp(10px,.7vw,20px);border-radius:12px;',
  'border:1px solid #2a3441;background:#121822;color:#e8edf2;font-size:clamp(15px,.8vw,23px);',
  'min-width:clamp(150px,10vw,280px)}',
  '.grid{display:grid;gap:clamp(12px,1vw,30px);',
  'grid-template-columns:repeat(auto-fill,minmax(clamp(300px,24vw,700px),1fr));grid-auto-rows:1fr}',
  '.card{display:flex;flex-direction:column;gap:.45em;min-width:0;',
  'padding:clamp(14px,1.05vw,30px);background:#141a22;border:1px solid #232b36;border-radius:clamp(12px,.9vw,26px)}',
  '.card[hidden]{display:none}',
  '.row{display:flex;align-items:center;gap:.6em;min-width:0}',
  '.name{font-size:clamp(16px,1.05vw,30px);font-weight:600;margin:0;overflow:hidden;',
  'text-overflow:ellipsis;white-space:nowrap;flex:1 1 auto;min-width:0}',
  '.badge{flex:0 0 auto;font-size:clamp(12px,.62vw,18px);font-weight:700;color:#22c55e;',
  'background:rgba(34,197,94,.12);border:1px solid rgba(34,197,94,.28);border-radius:999px;',
  'padding:.15em .6em;white-space:nowrap}',
  '.date{margin:0;color:#93a0b0;font-size:clamp(12px,.62vw,18px);white-space:nowrap;',
  'overflow:hidden;text-overflow:ellipsis}',
  '.notes{margin:.1em 0 0;color:#c3ccd8;font-size:clamp(13px,.7vw,20px);',
  'display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;min-height:2.9em}',
  '.meta{margin:0;padding:0;list-style:none;display:flex;flex-wrap:wrap;gap:.3em 1em;',
  'color:#7f8b9a;font-size:clamp(11px,.55vw,16px)}',
  '.meta code{color:#9fb0c3;font-size:inherit}',
  '.btn{margin-top:auto;display:block;text-align:center;',
  'padding:clamp(10px,.75vw,22px) 1em;border-radius:clamp(10px,.7vw,20px);',
  'background:#22c55e;color:#071009;font-weight:700;text-decoration:none;',
  'font-size:clamp(14px,.75vw,22px)}',
  '.btn:active{transform:scale(.99)}',
  '.none{color:#7f8b9a;padding:1.4em .2em}',
  '.none[hidden]{display:none}',
  'footer{margin:clamp(24px,2.2vw,60px) 0 0;color:#5f6b7c;font-size:clamp(11px,.55vw,16px)}',
].join('');

const SCRIPT = [
  '(function(){',
  'var q=document.getElementById("q"),sort=document.getElementById("sort");',
  'var grid=document.getElementById("grid"),none=document.getElementById("none");',
  'var cards=[].slice.call(grid.querySelectorAll(".card"));',
  'function apply(){',
  '  var term=(q.value||"").trim().toLowerCase();',
  '  var shown=0;',
  '  cards.forEach(function(c){',
  '    var hit=!term||(c.getAttribute("data-search")||"").indexOf(term)>=0;',
  '    if(hit)c.removeAttribute("hidden");else c.setAttribute("hidden","");',
  '    if(hit)shown++;',
  '  });',
  '  none.hidden=shown>0;',
  '  var mode=sort.value;',
  '  var sorted=cards.slice().sort(function(a,b){',
  '    if(mode==="name")return a.getAttribute("data-name").localeCompare(b.getAttribute("data-name"));',
  '    if(mode==="size")return Number(b.getAttribute("data-size"))-Number(a.getAttribute("data-size"));',
  '    return Number(b.getAttribute("data-ts"))-Number(a.getAttribute("data-ts"));',
  '  });',
  '  sorted.forEach(function(c){grid.appendChild(c);});',
  '}',
  'q.addEventListener("input",apply);',
  'sort.addEventListener("change",apply);',
  'apply();',
  '})();',
].join('');

/** 扫描 R2 顶层 <project>/latest.json，渲染手动下载页。 */
async function renderIndex(env) {
  const list = await env.BUCKET.list({ limit: 1000 });
  const manifests = new Map();
  for (const o of list.objects) {
    const m = /^([^/]+)\/latest\.json$/.exec(o.key);
    if (m) manifests.set(m[1], o.key);
  }

  const now = Date.now();
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
  items.sort((a, b) => {
    const ta = Date.parse(a.manifest.publishedAt || '') || 0;
    const tb = Date.parse(b.manifest.publishedAt || '') || 0;
    return tb - ta;
  });

  let latest = 0;
  const cards = items.map((item) => {
    const m = item.manifest || {};
    const apk = String(m.apk || '');
    const href = apk ? '/' + apk.split('/').map(encodeURIComponent).join('/') : '#';
    const sha = String(m.sha256 || '').toLowerCase();
    const ts = Date.parse(m.publishedAt || '') || 0;
    if (ts > latest) latest = ts;
    const when = ts ? new Date(ts) : null;
    const notes = summarize(m.notes);
    const search = (item.name + ' ' + notes).toLowerCase();

    const parts = [];
    parts.push('<article class="card" data-name="' + esc(item.name) + '"' +
      ' data-ts="' + ts + '" data-size="' + (Number(m.size) || 0) + '"' +
      ' data-search="' + esc(search) + '">');
    parts.push('<div class="row"><h2 class="name" title="' + esc(item.name) + '">' + esc(item.name) + '</h2>' +
      '<span class="badge">v' + esc(m.version || '?') + '</span></div>');
    parts.push('<p class="date">更新于 ' + (when ? stamp(when) + ' · ' + relative(when.getTime(), now) : '未知时间') + '</p>');
    parts.push('<p class="notes"' + (notes ? ' title="' + esc(notes) + '"' : '') + '>' +
      (notes ? esc(notes) : '暂无更新说明') + '</p>');
    parts.push('<ul class="meta"><li>' + humanSize(m.size) + '</li>' +
      (sha ? '<li>SHA-256 <code>' + esc(sha.slice(0, 12)) + '</code></li>' : '') + '</ul>');
    parts.push('<a class="btn" href="' + esc(href) + '" download>下载 APK</a>');
    parts.push('</article>');
    return parts.join('');
  }).join('');

  const stat = items.length
    ? '共 ' + items.length + ' 个项目 · 最近更新 ' + stamp(new Date(latest))
    : '还没有发布任何项目';

  const body = [
    '<!doctype html>',
    '<html lang="zh-CN"><head>',
    '<meta charset="utf-8">',
    '<meta name="viewport" content="width=device-width, initial-scale=1">',
    '<title>车机应用下载</title>',
    '<style>' + CSS + '</style>',
    '</head><body><div class="wrap">',
    '<div class="top"><h1>车机应用下载</h1><span class="stat">' + esc(stat) + '</span></div>',
    '<div class="bar">',
    '<input id="q" type="search" placeholder="筛选项目（名称或更新说明）" autocomplete="off">',
    '<select id="sort"><option value="date">最近更新</option><option value="name">名称 A–Z</option>' +
      '<option value="size">体积从大到小</option></select>',
    '</div>',
    '<div class="grid" id="grid">' + cards + '</div>',
    '<p class="none" id="none" hidden>没有匹配的项目，换个关键词试试。</p>',
    '<footer>由 Cloudflare R2 + Worker 提供 · 每个包都可对照清单里的 SHA-256 校验</footer>',
    '</div><script>' + SCRIPT + '</script></body></html>',
  ].join('');

  return body;
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

    if (request.method === 'GET' || request.method === 'HEAD') {
      if (!key) {
        const html = await renderIndex(env);
        return new Response(request.method === 'HEAD' ? null : html, {
          status: 200,
          headers: {
            'content-type': 'text/html; charset=utf-8',
            'cache-control': 'public, max-age=30, must-revalidate',
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
