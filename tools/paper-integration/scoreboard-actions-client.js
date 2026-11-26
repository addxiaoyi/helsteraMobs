'use strict';
const mineflayer = require('mineflayer');
let finished = false;
const bot = mineflayer.createBot({host:'127.0.0.1',port:25731,username:'ScoreboardPlayer',version:'1.21.1',auth:'offline'});
const timeout = setTimeout(() => finish(new Error('Scoreboard fixture timeout')), 180000);
function event(value) { console.log('SCOREBOARD_CLIENT ' + JSON.stringify(value)); }
function finish(error) {
  if (finished) return;
  finished = true; clearTimeout(timeout); event({event:'summary',passed:!error,error:error?.message});
  bot.quit(); setTimeout(() => process.exit(error ? 1 : 0), 300);
}
bot.on('spawn', () => { bot.physicsEnabled = false; event({event:'join',name:bot.username,uuid:bot.player.uuid}); });
bot.on('messagestr', value => { if (value === 'SCOREBOARD_DONE') finish(); });
bot.on('error', finish);
bot.on('kicked', reason => finish(new Error(String(reason))));
bot.on('end', () => { if (!finished) finish(new Error('Unexpected disconnect')); });
