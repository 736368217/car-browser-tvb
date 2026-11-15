"""部署 / 更新 Cloudflare Worker 发布通道（R2 binding 版本）。

凭证不写进仓库：从 %USERPROFILE%\\Desktop\\认证\\cf-API.txt 读取
（行序：0 标题 / 1 「帐户 ID」/ 2 值 / 3 「API 令牌」/ 4 值 /
 5 「访问密钥 ID」/ 6 值 / 7 「秘密访问密钥」/ 8 值 / 9 「S3 API 端点」/ 10 值）。

用法：
    python tools\\deploy_worker.py                                  # 部署（绑定 R2 bucket）
    python tools\\deploy_worker.py --bucket releases --worker cf-releases
    python tools\\deploy_worker.py --attach dl.736.de5.net           # 绑定自定义域名
    python tools\\deploy_worker.py --route dl.736.de5.net/*          # 绑定 zone 路由
    python tools\\deploy_worker.py --status                          # 域名 / 路由 / 脚本
"""
import argparse
import json
import os
import pathlib
import secrets
import urllib.error
import urllib.request

API = 'https://api.cloudflare.com/client/v4'
CRED = pathlib.Path(os.path.expanduser('~')) / 'Desktop' / '认证' / 'cf-API.txt'
TOKEN_FILE = pathlib.Path(os.path.expanduser('~')) / 'Desktop' / '认证' / 'r2-upload-token.txt'
ZONE_NAME = '736.de5.net'


def read_creds():
    lines = [l.strip() for l in CRED.read_text(encoding='utf-8').splitlines() if l.strip()]
    return {
        'account': lines[2],
        'token': lines[4],
        'ak': lines[6],
        'sk': lines[8],
        'endpoint': lines[10],
    }


def call(method, path, token, body=None, raw=None, ctype=None):
    url = API + path
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header('Authorization', 'Bearer ' + token)
    if data is not None:
        req.add_header('Content-Type', ctype or 'application/json')
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            return json.loads(r.read().decode('utf-8', 'replace') or '{}')
    except urllib.error.HTTPError as e:
        text = e.read().decode('utf-8', 'replace')
        raise SystemExit(f'{method} {path} -> HTTP {e.code}\n{text[:900]}')


def multipart(fields):
    boundary = '----pi' + secrets.token_hex(12)
    out = bytearray()
    for name, value, filename, ctype in fields:
        out += f'--{boundary}\r\n'.encode()
        if filename:
            out += (
                f'Content-Disposition: form-data; name="{name}"; filename="{filename}"\r\n'
                f'Content-Type: {ctype}\r\n\r\n'
            ).encode()
            out += value if isinstance(value, bytes) else value.encode()
        else:
            out += f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode()
            out += value if isinstance(value, bytes) else value.encode()
        out += b'\r\n'
    out += f'--{boundary}--\r\n'.encode()
    return bytes(out), f'multipart/form-data; boundary={boundary}'


def zone_id(token):
    data = call('GET', f'/zones?name={ZONE_NAME}', token)
    for z in data.get('result', []):
        if z.get('name') == ZONE_NAME:
            return z['id']
    raise SystemExit(f'zone {ZONE_NAME} not found')


def upload_token():
    if TOKEN_FILE.exists():
        t = TOKEN_FILE.read_text(encoding='utf-8').strip()
        if t:
            return t
    t = secrets.token_urlsafe(32)
    TOKEN_FILE.parent.mkdir(parents=True, exist_ok=True)
    TOKEN_FILE.write_text(t, encoding='utf-8')
    return t


def deploy(args, cred):
    script = pathlib.Path(args.script)
    if not script.exists():
        raise SystemExit(f'script not found: {script}')
    source = script.read_text(encoding='utf-8').lstrip('\ufeff').encode('utf-8')
    metadata = {
        'main_module': script.name,
        'compatibility_date': '2026-01-01',
        'bindings': [
            {'type': 'r2_bucket', 'name': 'BUCKET', 'bucket_name': args.bucket},
            {'type': 'secret_text', 'name': 'UPLOAD_TOKEN', 'text': upload_token()},
        ],
    }
    body, ctype = multipart([
        ('metadata', json.dumps(metadata), None, None),
        (script.name, source, script.name, 'application/javascript+module'),
    ])
    res = call('PUT', f"/accounts/{cred['account']}/workers/scripts/{args.worker}", cred['token'],
               raw=body, ctype=ctype)
    print(json.dumps({'deploy': res.get('success'), 'worker': args.worker, 'bucket': args.bucket}, ensure_ascii=False))


def attach(args, cred):
    zid = zone_id(cred['token'])
    body = {
        'environment': 'production',
        'hostname': args.attach,
        'service': args.worker,
        'zone_id': zid,
    }
    res = call('PUT', f"/accounts/{cred['account']}/workers/domains", cred['token'], body=body)
    print(json.dumps({'attached': res.get('success'), 'hostname': args.attach}, ensure_ascii=False))


def add_route(args, cred):
    zid = zone_id(cred['token'])
    body = {'pattern': args.route, 'script': args.worker}
    res = call('POST', f'/zones/{zid}/workers/routes', cred['token'], body=body)
    print(json.dumps({'route': res.get('success'), 'pattern': args.route, 'script': args.worker}, ensure_ascii=False))


def status(args, cred):
    zid = zone_id(cred['token'])
    dom = call('GET', f"/accounts/{cred['account']}/workers/domains?zone_id={zid}", cred['token'])
    routes = call('GET', f'/zones/{zid}/workers/routes', cred['token'])
    scripts = call('GET', f"/accounts/{cred['account']}/workers/scripts", cred['token'])
    print(json.dumps({
        'domains': [{'hostname': d.get('hostname'), 'service': d.get('service')} for d in dom.get('result', [])],
        'routes': [{'pattern': r.get('pattern'), 'script': r.get('script')} for r in routes.get('result', [])],
        'scripts': [s.get('id') for s in scripts.get('result', [])],
    }, ensure_ascii=False, indent=2))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--worker', default='cf-releases')
    ap.add_argument('--script', default='tools/r2-worker.mjs')
    ap.add_argument('--bucket', default='releases')
    ap.add_argument('--attach', help='hostname to bind, e.g. dl.736.de5.net')
    ap.add_argument('--route', help='worker route pattern, e.g. dl.736.de5.net/*')
    ap.add_argument('--status', action='store_true')
    args = ap.parse_args()

    cred = read_creds()
    if args.status:
        status(args, cred)
    elif args.attach:
        attach(args, cred)
    elif args.route:
        add_route(args, cred)
    else:
        deploy(args, cred)


if __name__ == '__main__':
    main()
