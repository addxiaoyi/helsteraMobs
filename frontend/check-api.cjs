/**
 * 前端 API 对账：前端请求的端点 vs 服务端实际提供的路由。
 *
 * 两个方向都要查，缺一不可：
 *   A. 前端调用了服务端没有的端点 -> 页面必然 404
 *   B. 服务端提供了前端从未调用的端点 -> 多半是改名后遗留的死路由
 *
 * 用法: node frontend/check-api.cjs      打印差异，退出码 1 表示有缺失
 */
const fs = require('fs');
const path = require('path');

const WEB = path.join(__dirname, '..', 'helstera-web', 'src', 'main', 'java', 'dev', 'helstera', 'web');
const HTML = path.join(__dirname, '..', 'helstera-web', 'src', 'main', 'resources', 'web', 'index.html');

// ---- 前端端点：fetch/api 调用 + WS ----
const html = fs.readFileSync(HTML, 'utf8');
const front = new Set();

const reFetch = /(?:fetch|api)\s*\(\s*[`'"](\/[^`'"]*)[`'"]/g;
let m;
while ((m = reFetch.exec(html))) front.add(m[1]);

// 模板串拼接的路径，例如 `${BASE}/api/models/...`
const reTpl = /`\$\{[^}]+\}(\/[^`]*)`/g;
while ((m = reTpl.exec(html))) front.add(m[1]);

const reWs = /new\s+WebSocket\s*\(\s*[`'"](\/[^`'"]*)[`'"]/g;
while ((m = reWs.exec(html))) front.add(m[1]);

// ---- 服务端路由：WebServerService 里注册的路径 ----
const server = new Set();
const files = fs.readdirSync(WEB).filter(f => f.endsWith('.java'));
for (const f of files) {
  const src = fs.readFileSync(path.join(WEB, f), 'utf8');
  // route("path"...) / path("/api/x") / startsWith("/api/x")
  const patterns = [
    /route\s*\(\s*[`'"](\/[^`'"]*)[`'"]/g,
    /path\s*\(\s*[`'"](\/[^`'"]*)[`'"]/g,
    /equals\s*\(\s*[`'"](\/[^`'"]*)[`'"]/g,
    /startsWith\s*\(\s*[`'"](\/[^`'"]*)[`'"]/g,
  ];
  for (const p of patterns) {
    while ((m = p.exec(src))) server.add(m[1]);
  }
}

console.log('前端端点: ' + front.size + ' / 服务端路由: ' + server.size);
console.log('\n=== 前端调用了、服务端未提供 ===');
const missing = [...front].filter(e => {
  // 去掉模板占位与路径参数后比对
  const norm = e.replace(/\$\{[^}]+\}/g, '').split('?')[0];
  if (server.has(norm)) return false;
  // 服务端可能按前缀匹配（如 startsWith("/api/models")）
  return ![...server].some(s => norm.startsWith(s) || s.startsWith(norm));
});
missing.forEach(e => console.log('  ' + e));

console.log('\n=== 服务端提供、前端未调用（可能是死路由）===');
const unused = [...server].filter(s => {
  if (!s.startsWith('/api')) return false;
  const norm = s.replace(/\$\{[^}]+\}/g, '');
  return ![...front].some(e => e.startsWith(norm) || norm.startsWith(e.split('?')[0]));
});
unused.forEach(e => console.log('  ' + e));

console.log('\n结论: ' + (missing.length ? missing.length + ' 个端点缺失' : '无缺失'));
process.exit(missing.length ? 1 : 0);
