'use strict';
const mineflayer = require('mineflayer');
const names = ['TargeterTester'];
const bots = [], done = new Set();
let finished = false;
const timer = setTimeout(() => finish(new Error('Targeter context timeout')), 180000);
function event(data) { console.log(`TARGETERS_CLIENT ${JSON.stringify(data)}`); }
function finish(error) {
  if (finished) return;
  finished = true;
  clearTimeout(timer);
  event({event:'summary', passed:!error, error:error?.message});
  for (const bot of bots) bot.quit();
  setTimeout(() => process.exit(error ? 1 : 0), 300);
}
for (const name of names) {
  const bot = mineflayer.createBot({host:'127.0.0.1', port:25731, username:name, version:'1.21.1', auth:'offline'});
  bots.push(bot);
  bot.on('spawn', () => {
    bot.physicsEnabled = false;
    event({event:'join', name, uuid:bot.player.uuid});
  });
  bot.on('messagestr', message => {
    if (message !== 'TARGETERS_DONE') return;
    done.add(name); event({event:'done', name});
    if (done.size === names.length) finish();
  });
  bot.on('error', finish);
  bot.on('kicked', reason => { if (!finished) finish(new Error('Unexpected kick: '+String(reason))); });
  bot.on('end', () => { if (!finished) finish(new Error('Unexpected disconnect: '+name)); });
}
