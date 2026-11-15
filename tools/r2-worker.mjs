/**
 * 车览 / 车机项目的发布通道：Cloudflare Worker + R2 binding。
 *
 * 为什么用 binding 而不是 S3 签名：本机到 R2 的 S3 端点 TLS 被中间层打断，
 * Worker 里走 S3 出网又只拿到 503（没有 R2 的 request-id），说明请求没到 R2。
 * R2 binding 由 Cloudflare 内部直连，不经过公网，也不需要 AK/SK。
 *
 * 路由：
 *   GET  /health                                 健康检查（含 binding 是否可用）
 *   GET  /<key...>                               公开读取
 *   PUT  /<key...>   (X-Upload-Token)            写入
 *   DELETE /<key...> (X-Upload-Token)            删除
 *   GET  /_ls?prefix= (X-Upload-Token)           列举
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

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    let key = '';
    try {
      key = decodeURIComponent(url.pathname.replace(/^\/+/, ''));
    } catch {
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

    if (request.method === 'GET' || request.method === 'HEAD') {
      if (!key) return json(404, { ok: false, error: 'no key' });
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
