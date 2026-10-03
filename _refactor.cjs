const fs = require('fs');
const p = 'helstera-web/src/main/resources/web/index.html';
let s = fs.readFileSync(p, 'utf8');
const before = (s.match(/class="card/g) || []).length;
s = s.split('class="card"').join('class="sec"');
fs.writeFileSync(p, s);
console.log('renamed', before, 'card->sec');