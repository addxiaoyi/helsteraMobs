'use strict';
const mineflayer = require('mineflayer');
const stage = process.argv[2];
if (!['write', 'recover', 'final'].includes(stage)) throw new Error('Invalid stage');
const names = ['VariableOwner', 'VariablePeer'];
const ended = new Set();
let failed = false;
const timer = setTimeout(() => { failed = true; process.exit(1); }, 180000);
function event(data) { console.log(`PLAYER_AUTO_CLIENT ${JSON.stringify({stage, ...data})}`); }
for (const name of names) {
  const bot = mineflayer.createBot({host: '127.0.0.1', port: 25731, username: name, version: '1.21.1', auth: 'offline'});
  bot.once('spawn', () => event({event: 'join', name, uuid: bot.player.uuid}));
  bot.on('error', error => {
    // The two kill stages must close established sockets abruptly.
    if (!['ECONNRESET', 'EPIPE'].includes(error.code)) failed = true;
    event({event: 'error', name, code: error.code});
  });
  bot.on('end', reason => {
    event({event: 'end', name, reason}); ended.add(name);
    if (ended.size === 2) { clearTimeout(timer); event({event: 'summary', passed: !failed}); setTimeout(() => process.exit(failed ? 1 : 0), 200); }
  });
}
