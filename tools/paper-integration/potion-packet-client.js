'use strict';

const mineflayer = require('mineflayer');
const data = require('minecraft-data')('1.21.1');
const { PotionPacketContract } = require('./potion-packet-contract');
const [host = '127.0.0.1', rawPort = '25731'] = process.argv.slice(2);
const port = Number(rawPort);
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid Paper port');
const contract = new PotionPacketContract(data.effectsByName.Haste.id);
const bot = mineflayer.createBot({ host, port, username: 'PotionTester', version: '1.21.1', auth: 'offline' });
let complete = false;
const timer = setTimeout(() => finish(new Error('Timed out waiting for Potion packets')), 90000);

function finish(error, result) {
  if (complete) return;
  complete = true;
  clearTimeout(timer);
  if (error) console.error(`PAPER_POTION_PACKET_CLIENT passed=false error=${error.message}`);
  else console.log(`PAPER_POTION_PACKET_CLIENT passed=true comparisons=${result.comparisons} phases=${result.phases} packets=${result.packets}`);
  bot.quit();
  setTimeout(() => process.exit(error ? 1 : 0), 250);
}

bot.on('message', message => {
  if (complete) return;
  const text = message.toString();
  if (!text.startsWith('POTION_')) return;
  console.log(text);
  try {
    const result = contract.message(text);
    if (result) finish(null, result);
  } catch (error) { finish(error); }
});
for (const type of ['entity_effect', 'remove_entity_effect']) {
  bot._client.on(type, packet => {
    if (complete) return;
    try {
      if (contract.packet(type, packet, bot.entity?.id)) {
        console.log(`POTION_PACKET phase=${contract.active} type=${type} data=${JSON.stringify(packet)}`);
      }
    } catch (error) { finish(error); }
  });
}
bot.on('error', finish);
bot.on('kicked', reason => finish(new Error(`Kicked: ${String(reason)}`)));
bot.on('end', () => { if (!complete) finish(new Error('Disconnected before Potion packet validation')); });
bot.once('spawn', () => console.log(`PAPER_POTION_PACKET_PLAYER_READY effect-id=${data.effectsByName.Haste.id}`));
