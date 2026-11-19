'use strict';
const mineflayer = require('mineflayer');
const [stage, rawPort = '25731'] = process.argv.slice(2);
if (!['write', 'verify', 'final'].includes(stage)) throw new Error('Invalid stage');
const names = ['VariableOwner', 'VariablePeer'];
const connections = new Map();
const completed = new Set();
let finished = false;
let holding = false;
const timer = setTimeout(() => finish(new Error('Player variable client timeout')), 160000);
function event(data) { console.log(`PLAYER_VAR_CLIENT ${JSON.stringify({stage, ...data})}`); }
function finish(error) {
  if (finished) return;
  finished = true; clearTimeout(timer);
  event({event: 'summary', passed: !error, error: error?.message});
  for (const bot of connections.values()) bot.quit();
  setTimeout(() => process.exit(error ? 1 : 0), 300);
}
function connect(name, attempt) {
  const bot = mineflayer.createBot({host: '127.0.0.1', port: Number(rawPort), username: name, version: '1.21.1', auth: 'offline'});
  connections.set(name, bot);
  let requested = false;
  bot.once('spawn', () => event({event: 'join', name, attempt, uuid: bot.player.uuid}));
  bot.on('messagestr', message => {
    const match = /^PLAYER_VAR_CONTROL (reconnect|done|hold) (\w+)$/.exec(message);
    if (!match || requested) return;
    requested = true;
    event({event: 'control', name, attempt, action: match[1], phase: match[2]});
    if (match[1] === 'reconnect') {
      bot.quit();
      setTimeout(() => { if (!finished) connect(name, attempt + 1); }, stage === 'write' ? 25000 : 3000);
    } else {
      completed.add(name);
      if (match[1] === 'hold') holding = true;
      else bot.quit();
      if (completed.size === 2) {
        if (holding) event({event: 'ready-online', name: 'VariablePeer'});
        else finish();
      }
    }
  });
  bot.on('end', () => { event({event: 'quit', name, attempt}); if (holding && name === 'VariablePeer') finish(); else if (!requested && !finished) finish(new Error('Unexpected disconnect')); });
  bot.on('error', error => finish(error));
  bot.on('kicked', reason => { if (!finished && !holding) finish(new Error(`Unexpected kick: ${String(reason)}`)); });
}
names.forEach(name => connect(name, 1));
