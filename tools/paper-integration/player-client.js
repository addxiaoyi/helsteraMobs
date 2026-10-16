const mineflayer = require('mineflayer');

const [host = '127.0.0.1', rawPort = '25731'] = process.argv.slice(2);
const port = Number(rawPort);
const reportPath = process.argv[4];
let shieldTimer = null;
let usePackets = 0;
const report = line => {
  process.stdout.write(`${line}\n`);
  if (reportPath) require('fs').appendFileSync(reportPath, `${line}\n`);
};
const clientPacket = bot => {
  bot._client.on('packet', (data, meta) => {
    if (!meta || meta.state !== 'play') return;
    if (meta.name !== 'block_place' && meta.name !== 'use_item') return;
    usePackets += 1;
    if (usePackets <= 5) report(`PAPER_USE_PACKET name=${meta.name}`);
  });
};
if (!Number.isInteger(port) || port < 1 || port > 65535) {
  throw new Error(`Invalid Paper port: ${rawPort}`);
}

const bot = mineflayer.createBot({ host, port, username: 'HelsteraTester', version: '1.21.1' });
clientPacket(bot);
const rawWrite = bot._client.write.bind(bot._client);
bot._client.write = (name, data) => {
  if (name === 'use_item' || name === 'block_place' || name === 'block_dig') {
    usePackets += 1;
    if (usePackets <= 8) report(`PAPER_USE_OUT name=${name}`);
  }
  return rawWrite(name, data);
};
bot.once('spawn', () => report('PAPER_PLAYER_READY'));
bot.once('spawn', () => {
  setTimeout(async () => {
    try {
      const shield = bot.inventory.items().find(stack => stack.name === 'shield');
      if (shield) {
        await bot.equip(shield, 'hand');
        bot.activateItem();
        shieldTimer = setInterval(() => {
          if (!bot.ended) bot.activateItem();
        }, 250);
        report('PAPER_SHIELD_BLOCKING_ACTIVE');
      }
    } catch (error) {
      report(`PAPER_SHIELD_ERROR ${error.message}`);
    }
  }, 750);
});
bot.once('spawn', () => {
  setTimeout(async () => {
    try {
      const potion = bot.inventory.items().find(stack => stack.name === 'potion');
      if (!potion) {
        const inventory = bot.inventory.items().map(stack =>
          `${stack.name}:${stack.count}:${stack.slot}`).join(',');
        report(`PAPER_POTION_DRINK missing-potion inventory=${inventory}`);
        return;
      }
      await bot.equip(potion, 'hand');
      bot.activateItem();
      report('PAPER_POTION_DRINK attempted=true');
    } catch (error) {
      report(`PAPER_POTION_DRINK error=${error.message}`);
    }
  }, 5000);
});
bot.once('spawn', () => {
  const runBlockWorkflow = async () => {
    try {
      await new Promise(resolve => setTimeout(resolve, 5000));
      const origin = bot.entity.position.floored();
      report(`PAPER_BLOCK_START pos=${origin.x},${origin.y},${origin.z} inventory=${bot.inventory.items().length}`);
      const target = bot.blockAt(origin.offset(2, 1, 0));
      if (!target || target.boundingBox === 'empty') {
        report(`PAPER_BLOCK_TARGET missing pos=${origin.x},${origin.y},${origin.z}`);
        return;
      }
      await bot.dig(target, true);
      report(`PAPER_BLOCK_DIG type=${target.name}`);
      const placeAt = bot.blockAt(origin.offset(1, 1, 0));
      if (placeAt && bot.inventory.items().length > 0) {
        const item = bot.inventory.items().find(stack => stack.name === 'stone');
        if (item) {
          await bot.equip(item, 'hand');
          await bot.placeBlock(placeAt, { x: 1, y: 0, z: 0 });
          report(`PAPER_BLOCK_PLACE type=${item.name}`);
        } else {
          report('PAPER_BLOCK_PLACE missing-stone');
        }
      } else {
        report('PAPER_BLOCK_PLACE missing-support');
      }
    } catch (error) {
      report(`PAPER_BLOCK_ERROR ${error.stack || error.message}`);
    }
  };
  void runBlockWorkflow();
});
bot.once('spawn', () => {
  setTimeout(async () => {
    const boat = Object.values(bot.entities).find(entity =>
      typeof entity.name === 'string' && entity.name.toLowerCase().includes('boat'));
    if (!boat) {
      report('PAPER_VEHICLE_DESTROY missing-boat');
      return;
    }
    try {
      for (let attempt = 0; attempt < 20 && boat.isValid !== false; attempt += 1) {
        bot.attack(boat);
        await new Promise(resolve => setTimeout(resolve, 250));
      }
      report('PAPER_VEHICLE_DESTROY attack-window-complete');
    } catch (error) {
      report(`PAPER_VEHICLE_DESTROY error=${error.message}`);
    }
  }, 16000);
});
bot.on('error', error => process.stderr.write(`${error.stack || error.message}\n`));
bot.on('end', () => {
  if (shieldTimer) clearInterval(shieldTimer);
  process.exit(0);
});

process.on('SIGTERM', () => bot.quit('integration complete'));
process.on('SIGINT', () => bot.quit('integration complete'));
