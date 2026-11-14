"""Deterministic, task-local HTTP fixtures for Android download and browser acceptance."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import time
PAYLOAD=b'car-browser audit\n'+b'x'*(12288-18)
ARTICLE='''<!doctype html><meta charset="utf-8"><title>车览体验测试</title><style>body{font:32px/1.8 sans-serif;margin:60px auto;max-width:1600px}h1{font-size:60px}a{padding:20px;display:inline-block}p{margin:25px 0}</style><article><h1>车览体验测试</h1><a href="/page2">前往第二页</a><a href="/note.txt">下载文本</a>'''+('<p>这是浏览器阅读、查找与返回测试。touch sample 可以在本页找到；页面内容固定，便于逐项验证。</p>'*18)+'</article>'
class Handler(BaseHTTPRequestHandler):
 def do_GET(self):
  route=self.path.split('?')[0]
  if route in ('/article','/page2'):
   data=(ARTICLE if route=='/article' else '<meta charset="utf-8"><title>第二页</title><h1>第二页</h1><a href="/article">返回文章</a>').encode();self.send_response(200);self.send_header('Content-Type','text/html;charset=utf-8');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data);return
  if route=='/cut.bin':
   self.send_response(200);self.send_header('Content-Length','32768');self.send_header('Content-Type','application/octet-stream');self.end_headers();self.wfile.write(b'x'*1024);self.wfile.flush();self.close_connection=True;return
  if route=='/missing.zip':self.send_error(404);return
  if route not in ('/small.bin','/percent','/note.txt','/slow.bin'):self.send_error(404);return
  data=PAYLOAD if route=='/small.bin' else (b'x'*1048576 if route=='/slow.bin' else b'car-browser audit\n')
  self.send_response(200);self.send_header('Content-Length',str(len(data)));self.send_header('Content-Type','text/plain' if route in ('/note.txt','/percent') else 'application/octet-stream')
  if route=='/note.txt':self.send_header('Content-Disposition','attachment; filename="audit615-note.txt"')
  if route=='/percent':self.send_header('Content-Disposition','attachment; filename="audit%20note.txt"')
  self.end_headers()
  try:
   for i in range(0,len(data),16384):
    self.wfile.write(data[i:i+16384]);self.wfile.flush()
    if route=='/slow.bin':time.sleep(.06)
  except (BrokenPipeError,ConnectionResetError):pass
 def log_message(self,fmt,*args):print(fmt%args,flush=True)
if __name__=='__main__':ThreadingHTTPServer(('0.0.0.0',8099),Handler).serve_forever()
