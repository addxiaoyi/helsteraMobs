const http = require('http');
const get = (p, headers) => new Promise((resolve) => {
  http.get({ host: '127.0.0.1', port: 5173, path: p, headers: headers || {} }, r => {
    let b = '';
    r.on('data', d => b += d);
    r.on('end', () => resolve({ status: r.statusCode, body: b }));
  }).on('error', e => resolve({ status: 0, body: String(e) }));
});
(async () => {
  const root = await get('/');
  console.log('GET /          ', root.status, root.body.length + 'B');
  const tok = /const TOKEN = "([^"]+)"/.exec(root.body);
  const checks = [
    ['TOKEN 已注入', root.body.indexOf('__TOKEN__') < 0 && !!tok],
    ['VERSION 已注入', root.body.indexOf('__VERSION__') < 0],
    ['body 横向布局', /flex-direction:row/.test(root.body.split('</style>')[0])],
    ['rail 在 body 内', /<body>[\s\S]{0,120}<div class="rail"/.test(root.body)],
    ['8 个 tab', (root.body.match(/data-tab="/g) || []).length === 8],
    ['无损坏字符', root.body.indexOf('\uFFFD') < 0],
  ];
  let pass = 0;
  for (const [n, ok] of checks) { if (ok) pass++; console.log((ok ? 'PASS ' : 'FAIL ') + n); }
  const api = await get('/api/status', { Authorization: 'Bearer ' + (tok ? tok[1] : '') });
  console.log('GET /api/status', api.status, api.body.slice(0, 70));
  console.log('--- ' + pass + '/' + checks.length + ' 通过');
})();