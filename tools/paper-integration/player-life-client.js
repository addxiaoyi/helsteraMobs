'use strict';
const mineflayer = require('mineflayer');
const names = ['VariableOwner', 'VariablePeer'];
const active = new Map(), done = new Set();
let finished = false;
const started = performance.now();
const timer = setTimeout(() => finish(new Error('Player lifecycle timeout')), 120000);
function event(data) { console.log(`PLAYER_LIFE_CLIENT ${JSON.stringify({...data, elapsedMillis:performance.now()-started})}`); }
function finish(error) {
  if (finished) return;
  finished = true; clearTimeout(timer);
  event({event:'summary', passed:!error, error:error?.message});
  for (const bot of active.values()) bot.quit();
  setTimeout(() => process.exit(error ? 1 : 0), 300);
}
function connect(name, attempt) {
  event({event:'connect', name, attempt});
  const bot = mineflayer.createBot({host:'127.0.0.1', port:25731, username:name, version:'1.21.1', auth:'offline', respawn:false});
  active.set(name, bot);
  let requested, pendingControl, spawned = false;
  function consumeControl() {
    // Chat can arrive before Mineflayer has emitted its initial spawn callback.
    if (!spawned || !pendingControl || requested || finished) return;
    requested = pendingControl.action;
    event({event:'control', name, attempt, ...pendingControl}); bot.quit();
  }
  bot.on('spawn', () => {
    event({event:spawned ? 'respawned' : 'join', name, attempt, uuid:bot.player.uuid}); spawned = true;
    consumeControl();
  });
  bot.on('death', () => {
    event({event:'death', name, attempt});
    setTimeout(() => { if (!finished) { event({event:'respawn-request', name, attempt}); bot.respawn(); } }, 4000);
  });
  bot.on('messagestr', message => {
    const match = /^PLAYER_LIFE_CONTROL (reconnect|done) (\w+)$/.exec(message);
    if (!match || pendingControl || requested) return;
    pendingControl = {action:match[1], phase:match[2]}; consumeControl();
  });
  bot.on('end', () => {
    event({event:'end', name, attempt});
    if (finished) return;
    if (requested === 'reconnect') connect(name, attempt + 1); // No timer or disk-write wait.
    else if (requested === 'done') { done.add(name); if (done.size===2) finish(); }
    else finish(new Error('Unexpected disconnect'));
  });
  bot.on('error', finish);
  bot.on('kicked', reason => { if (!requested && !finished) finish(new Error('Unexpected kick: '+String(reason))); });
}
names.forEach(name => connect(name, 1));
