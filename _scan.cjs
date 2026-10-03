const fs = require('fs');
const p = 'helstera-web/src/main/resources/web/index.html';
const s = fs.readFileSync(p, 'utf8');
// U+FFFD 替换字符 = 解码失败留下的痕迹
const lines = s.split('\n');
let bad = 0;
lines.forEach((ln, i) => {
  if (ln.indexOf('\uFFFD') >= 0) {
    bad++;
    console.log('L' + (i + 1) + ': ' + ln.trim().slice(0, 120));
  }
});
console.log('replacement-chars-lines', bad);
console.log('total-lines', lines.length);