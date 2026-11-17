'use strict';

const mineflayer = require('mineflayer');
const [host = '127.0.0.1', rawPort = '25731'] = process.argv.slice(2);
const port = Number(rawPort);
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid Paper port');
const expected = new Map([
  ['speed_default', [false, 1]], ['speed_double', [false, 2]],
  ['speed_fly_default', [true, 1]], ['speed_fly_double', [true, 2]],
  ['speed_walking', [false, 1]], ['speed_flying', [true, 1]],
  ['speed_type_fallback', [false, 1]], ['speed_type_contains', [true, 1]],
  ['speed_zero', [false, 0]], ['speed_negative', [false, -1]],
  ...['amount', 'a', 'value', 'v', 'multiplier', 'm'].map(alias => [`speed_${alias}`, [false, 2]])
]);
const bot = mineflayer.createBot({ host, port, username: 'SpeedTester', version: '1.21.1', auth: 'offline' });
const received = new Map();
let phase = null;
let complete = false;
const close = (a, b) => Number.isFinite(a) && Math.abs(a - b) < 1e-7;
const timer = setTimeout(() => finish(new Error('Timed out waiting for setspeed packets')), 90000);

function finish(error) {
  if (complete) return;
  complete = true;
  clearTimeout(timer);
  if (error) console.error(error.message);
  else console.log(`PAPER_SET_SPEED_CLIENT passed=true comparisons=${expected.size} phases=${received.size}`);
  bot.quit();
  setTimeout(() => process.exit(error ? 1 : 0), 250);
}

bot.on('message', message => {
  const text = message.toString();
  if (text.startsWith('SPEED_PHASE ')) phase = text.slice('SPEED_PHASE '.length);
  else if (text === 'SPEED_RESET') phase = null;
  else if (text === 'SPEED_DONE') {
    for (const [name, [flying, multiplier]] of expected) {
      // Bukkit sends walk/fly ability fields at half of its public API values.
      const value = Math.fround(Math.fround(multiplier) * Math.fround(flying ? 0.1 : 0.2)) / 2;
      const native = received.get(`native:${name}`);
      const imported = received.get(`imported:${name}`);
      const walk = flying ? Math.fround(0.16) / 2 : value;
      const fly = flying ? value : Math.fround(0.06) / 2;
      if (!native || !imported || !close(native.walkingSpeed, walk) || !close(imported.walkingSpeed, walk)
          || !close(native.flyingSpeed, fly) || !close(imported.flyingSpeed, fly)) {
        return finish(new Error(`Ability mismatch ${name}: ${JSON.stringify({ native, imported, walk, fly })}`));
      }
    }
    finish();
  }
});
bot._client.on('abilities', packet => {
  if (!phase) return;
  received.set(phase, { walkingSpeed: packet.walkingSpeed, flyingSpeed: packet.flyingSpeed });
  console.log(`SPEED_PACKET phase=${phase} walk=${packet.walkingSpeed} fly=${packet.flyingSpeed}`);
});
bot.on('error', finish);
bot.on('kicked', reason => finish(new Error(`Kicked: ${String(reason)}`)));
bot.on('end', () => { if (!complete) finish(new Error('Disconnected before speed validation')); });
bot.once('spawn', () => console.log('PAPER_SET_SPEED_PLAYER_READY'));
