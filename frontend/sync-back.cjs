/**
 * 同步 frontend/index.html 与插件正式源文件。
 *
 *   node frontend/sync-back.cjs          同步（方向由修改时间推断）
 *   node frontend/sync-back.cjs --check  只检查，不写入（有差异则退出码 1）
 *   node frontend/sync-back.cjs --to-real 强制以编辑副本为准
 *   node frontend/sync-back.cjs --to-edit 强制以正式源为准
 *
 * ## 为什么需要方向判断
 *
 * 两个副本里，**helstera-web/src/main/resources/web/index.html 才是插件
 * 打包时真正读进 jar 的文件**；frontend/index.html 只是编辑副本。
 *
 * 旧版本无条件用编辑副本覆盖正式源。那样一旦有人直接改了正式源
 * （这才是正确的改动位置），下次谁跑一次本脚本，改动就被旧副本
 * 静默覆盖——而且没有任何提示。构建照样通过，只是改动没了。
 *
 * 因此改为：按 mtime 推断方向，两边都更新过、或都无法判断时**拒绝执行**
 * 并说明情况，把决定权交回给人。宁可停下，也不能静默丢改动。
 */
const fs = require('fs');
const path = require('path');

const EDIT = path.join(__dirname, 'index.html');
const REAL = path.join(__dirname, '..', 'helstera-web', 'src', 'main', 'resources', 'web', 'index.html');

const argv = process.argv.slice(2);
const checkOnly = argv.includes('--check');
const force = argv.includes('--to-real') || argv.includes('--to-edit');
const toReal = argv.includes('--to-real');

let a, b, sa, sb;
try {
  a = fs.readFileSync(EDIT, 'utf8');
  sa = fs.statSync(EDIT);
} catch (e) {
  console.error('读不到编辑副本: ' + EDIT);
  process.exit(2);
}
try {
  b = fs.readFileSync(REAL, 'utf8');
  sb = fs.statSync(REAL);
} catch (e) {
  console.error('读不到正式源文件: ' + REAL);
  process.exit(2);
}

const lines = (x) => x.split('\n').length;
const size = (x) => x.length;

if (a === b) {
  console.log('一致，无需同步。');
  process.exit(0);
}

const report = () => {
  console.log('存在差异：');
  console.log('  编辑副本   ' + lines(a) + ' 行 / ' + size(a) + ' 字节 / ' + sa.mtime.toISOString());
  console.log('  正式源文件 ' + lines(b) + ' 行 / ' + size(b) + ' 字节 / ' + sb.mtime.toISOString());
  console.log('');
  console.log('  正式源才是插件打包时读进 jar 的那份。');
  console.log('  明确方向：--to-real（编辑副本覆盖正式源）/ --to-edit（正式源覆盖编辑副本）');
};

// 时间阈值：文件系统时间戳精度有限，1 秒内的差异视为「同时修改」。
const SKEW_MS = 1000;

let dir;           // 'to-real' | 'to-edit' | null（无法判断）
if (force) {
  dir = toReal ? 'to-real' : 'to-edit';
  console.log('已指定方向：' + dir + '（忽略时间戳判断）');
} else if (sb.mtimeMs - sa.mtimeMs > SKEW_MS) {
  dir = 'to-edit';
  console.log('判断：正式源更新，将同步到编辑副本。');
} else if (sa.mtimeMs - sb.mtimeMs > SKEW_MS) {
  dir = 'to-real';
  console.log('判断：编辑副本更新，将同步到正式源。');
} else {
  console.error('无法判断同步方向：两份文件的修改时间几乎相同。');
  report();
  console.error('');
  console.error('已中止，避免覆盖掉其中一份的改动。');
  process.exit(3);
}

console.log(report());

if (checkOnly) {
  console.log('(--check 模式，未写入)');
  process.exit(1);
}

if (dir === 'to-real') {
  fs.writeFileSync(REAL, a);
  console.log('已写回: ' + REAL);
} else {
  fs.writeFileSync(EDIT, b);
  console.log('已写回: ' + EDIT);
}
console.log('提示：需要 mvn package 并重启服务端才会生效。');
