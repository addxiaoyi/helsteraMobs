'use strict';

const mineflayer = require('mineflayer');
const { PotionSyncContract } = require('./potion-sync-contract');
const [host = '127.0.0.1', rawPort = '25731'] = process.argv.slice(2);
const port = Number(rawPort);
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid Paper port');
const contract = new PotionSyncContract();
const bots = new Map();
let complete = false;
const timer = setTimeout(() => finish(new Error('Timed out waiting for Potion synchronization')), 180000);
const record = event => console.log(`SYNC_EVENT ${JSON.stringify(event)}`);

function finish(error, result) {
  if (complete) return;
  complete = true;
  clearTimeout(timer);
  if (error) console.error(`PAPER_POTION_SYNC_CLIENT passed=false error=${error.stack || error.message}`);
  else console.log(`PAPER_POTION_SYNC_CLIENT passed=true comparisons=${result.comparisons} checkpoints=${result.checkpoints} reconnections=${result.reconnections}`);
  for (const bot of bots.values()) bot.quit();
  setTimeout(() => process.exit(error ? 1 : 0), 300);
}

function connect(role) {
  if (complete) return;
  record({ type: 'connect', role });
  contract.connect(role);
  const bot = mineflayer.createBot({ host, port, username: role === 'owner' ? 'SyncOwner' : 'SyncWatcher',
    version: '1.21.1', auth: 'offline' });
  bot.physicsEnabled = false;
  bots.set(role, bot);
  let planned = false;
  for (const type of ['login', 'spawn_entity', 'entity_metadata', 'entity_effect', 'remove_entity_effect', 'entity_destroy']) {
    bot._client.on(type, original => {
      if (complete) return;
      const data = type === 'entity_metadata' ? { entityId: original.entityId,
        metadata: original.metadata.filter(entry => [0, 10, 11].includes(entry.key)) }
        : type === 'spawn_entity' || type === 'login' ? { entityId: original.entityId } : original;
      if (type === 'entity_metadata' && !data.metadata.length) return;
      record({ type: 'packet', role, packetType: type, data });
      try { contract.packet(role, type, data); } catch (error) { finish(error); }
    });
  }
  bot.on('message', message => {
    if (complete) return;
    const text = message.toString();
    if (!text.startsWith('POTION_SYNC ')) return;
    record({ type: 'message', role, text });
    try {
      const action = contract.message(role, text);
      if (action?.reconnect === role) { planned = true; bot.quit(); }
      else if (action?.acknowledge) bot.chat(`/potionsync ${action.acknowledge}`);
      else if (action?.comparisons) finish(null, action);
    } catch (error) { finish(error); }
  });
  bot.on('error', error => finish(error));
  bot.on('kicked', reason => finish(new Error(`Kicked ${role}: ${String(reason)}`)));
  bot.on('end', () => {
    if (complete) return;
    if (!planned) finish(new Error(`Unexpected disconnect: ${role}`));
    else setTimeout(() => { try { connect(role); } catch (error) { finish(error); } }, 400);
  });
}
try { connect('owner'); connect('watcher'); } catch (error) { finish(error); }
