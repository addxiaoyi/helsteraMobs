'use strict';

const mineflayer = require('mineflayer');

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(check, label, timeout = 30000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (check()) return;
    await sleep(100);
  }
  throw new Error(`Timed out waiting for ${label}`);
}

async function main() {
  const [host = '127.0.0.1', port = '25731', username = 'BowTensionProbe'] = process.argv.slice(2);
  const bot = mineflayer.createBot({ host, port: Number(port), username });
  let ended = false;
  let acknowledgements = 0;
  let acknowledge;
  bot.once('end', () => { ended = true; });
  bot.on('messagestr', message => {
    if (!message.startsWith('BOW_TENSION_ACK|index=')) return;
    acknowledgements++;
    if (acknowledge) {
      const resolve = acknowledge;
      acknowledge = null;
      resolve();
    }
  });
  try {
    await new Promise((resolve, reject) => {
      bot.once('spawn', resolve);
      bot.once('error', reject);
      setTimeout(() => reject(new Error('Timed out joining Paper')), 30000).unref();
    });
    await until(() => bot.inventory.items().some(item => item.name === 'bow'), 'fixture bow');
    const bowSlot = bot.inventory.slots.findIndex(item => item?.name === 'bow');
    if (bowSlot < 36 || bowSlot > 44) throw new Error('Fixture bow is not in the hotbar');
    bot.setQuickBarSlot(bowSlot - 36);
    await until(() => bot.heldItem?.name === 'bow', 'equipped bow');

    const chargeTicks = [3, 4, 5, 6, 7, 8];
    for (let shot = 0; shot < chargeTicks.length; shot++) {
      const ticks = chargeTicks[shot];
      if (ended) throw new Error('Disconnected before all shots');
      const before = acknowledgements;
      bot.activateItem();
      await sleep(ticks * 50);
      bot.deactivateItem();
      await new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
          acknowledge = null;
          reject(new Error(`Paper did not acknowledge shot ${shot + 1}`));
        }, 8000);
        acknowledge = () => { clearTimeout(timer); resolve(); };
        if (acknowledgements > before) acknowledge();
      });
    }
    console.log(`BOW_TENSION_CLIENT|name=${username}|shots=${acknowledgements}|charges=${chargeTicks.join(',')}|passed=${acknowledgements === chargeTicks.length}`);
    bot.quit('fixture complete');
  } catch (error) {
    console.error(`BOW_TENSION_CLIENT|passed=false|type=${error.constructor.name}`);
    if (!ended) bot.quit('fixture failed');
    process.exitCode = 1;
  }
}

main();
