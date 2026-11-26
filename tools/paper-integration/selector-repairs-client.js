'use strict';
const mineflayer = require('mineflayer');
let finished = false;
const bot = mineflayer.createBot({host:'127.0.0.1', port:25731, username:'SelectorPlayer', version:'1.21.1', auth:'offline'});
const timer = setTimeout(() => finish(new Error('Selector repair probe timeout')), 180000);
function event(data) { console.log(`SELECTOR_CLIENT ${JSON.stringify(data)}`); }
function finish(error) {
  if (finished) return;
  finished = true; clearTimeout(timer);
  event({event:'summary', passed:!error, error:error?.message}); bot.quit();
  setTimeout(() => process.exit(error ? 1 : 0), 300);
}
bot.on('spawn', () => { bot.physicsEnabled = false; event({event:'join', name:'SelectorPlayer', uuid:bot.player.uuid}); });
bot.on('messagestr', message => { if (message === 'SELECTOR_REPAIRS_DONE') { event({event:'done'}); finish(); } });
bot.on('error', finish);
bot.on('kicked', reason => { if (!finished) finish(new Error('Unexpected kick: '+String(reason))); });
bot.on('end', () => { if (!finished) finish(new Error('Unexpected disconnect')); });
