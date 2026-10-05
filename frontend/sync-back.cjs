/**
 * 把 frontend/index.html 同步回插件的正式源文件。
 *
 *   node frontend/sync-back.cjs          直接覆盖
 *   node frontend/sync-back.cjs --check  只比对，不写入（有差异则退出码 1）
 *
 * frontend/index.html 是你的编辑副本；helstera-web/src/main/resources/web/index.html
 * 才是插件打包时真正读进 jar 的文件。两者必须保持一致，否则改了却没生效。
 */
const fs = require('fs');
const path = require('path');

const EDIT = path.join(__dirname, 'index.html');
const REAL = path.join(__dirname, '..', 'helstera-web', 'src', 'main', 'resources', 'web', 'index.html');
const checkOnly = process.argv.includes('--check');

const a = fs.readFileSync(EDIT, 'utf8');
let b;
try { b = fs.readFileSync(REAL, 'utf8'); }
catch (e) { console.log('读不到正式源文件: ' + REAL); process.exit(2); }

if (a === b) {
  console.log('一致，无需同步。');
  process.exit(0);
}
const lines = (x, y) => x.split('\n').length;
console.log('存在差异：编辑副本 ' + lines(a) + ' 行 / ' + a.length + ' 字节，'
  + '正式源 ' + lines(b) + ' 行 / ' + b.length + ' 字节');

if (checkOnly) {
  console.log('(--check 模式，未写入)');
  process.exit(1);
}
fs.writeFileSync(REAL, a);
console.log('已写回: ' + REAL);
console.log('提示：需要 mvn install 并重启服务端才会生效。');