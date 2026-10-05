/**
 * 前端独立预览服务器。
 *
 * 用途：让你脱离 Minecraft 服务端单独改 index.html。改完直接刷新浏览器，
 * 不用每次跑 mvn install + 重启 Paper（重启一轮要 3 分钟）。
 *
 *   node frontend/serve.cjs            默认 5173 端口
 *   node frontend/serve.cjs 8080       指定端口
 *
 * 它做两件事：
 *   1. 把 __TOKEN__ / __VERSION__ 替换成真实值再吐给浏览器，
 *      否则前端拿不到令牌，所有 /api/* 都会 401。
 *   2. 把 /api/* 与 /pack.zip 原样转发到真实服务端（默认 127.0.0.1:8765）。
 *      服务端没起时返回 502 并在页面提示，而不是静默失败。
 */
const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = parseInt(process.argv[2] || '5173', 10);
const UPSTREAM = process.env.HELSTERA_UPSTREAM || 'http://127.0.0.1:8765';
const PAGE = path.join(__dirname, 'index.html');

function readToken() {
  // 令牌从服务端 config.yml 读，不在代码里硬编码
  const cfg = path.join(__dirname, '..', 'srv-1.21.1', 'plugins', 'helsteraMobs', 'config.yml');
  try {
    const m = fs.readFileSync(cfg, 'utf8').match(/^\s*token:\s*"?([^"\s]+)"?/m);
    if (m) return m[1];
  } catch (e) { /* 读不到就退回默认值 */ }
  return '3577090e627c074b';
}

const server = http.createServer((req, res) => {
  if (req.url.startsWith('/api/') || req.url.startsWith('/pack.zip')) {
    const proxy = http.request(UPSTREAM + req.url, { method: req.method, headers: req.headers }, up => {
      res.writeHead(up.statusCode, up.headers);
      up.pipe(res);
    });
    proxy.on('error', () => {
      res.writeHead(502, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({
        error: '连不上 Minecraft 服务端 (' + UPSTREAM + ')。请先启动服务端，否则接口拿不到数据。',
        hint: '启动：cd srv-1.21.1 && java -Xms1G -Xmx2G -jar paper.jar --nogui'
      }));
    });
    req.pipe(proxy);
    return;
  }

  let html;
  try {
    html = fs.readFileSync(PAGE, 'utf8');
  } catch (e) {
    res.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' });
    return res.end('读不到 frontend/index.html');
  }
  html = html.split('__TOKEN__').join(readToken())
           .split('__VERSION__').join('preview');
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(html);
});

server.listen(PORT, () => {
  console.log('前端预览: http://127.0.0.1:' + PORT + '/');
  console.log('接口转发到: ' + UPSTREAM);
  console.log('编辑 frontend/index.html 后直接刷新浏览器即可（已禁用缓存）');
});