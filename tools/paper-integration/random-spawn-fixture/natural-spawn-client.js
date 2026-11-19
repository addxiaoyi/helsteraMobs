'use strict';

const mineflayer = require('mineflayer');

const bot = mineflayer.createBot({
  host: process.argv[2] || '127.0.0.1',
  port: Number(process.argv[3] || '25731'),
  username: 'RandomSpawnProbe',
  version: '1.21.1',
  auth: 'offline'
});

const timer = setTimeout(() => {
  console.error('RANDOM_SPAWN_CLIENT|passed=false|reason=timeout');
  bot.quit();
  process.exitCode = 1;
}, 150000);

bot.once('spawn', () => {
  console.log('RANDOM_SPAWN_CLIENT|passed=true|player=RandomSpawnProbe|mode=survival');
});

bot.on('error', error => {
  console.error(`RANDOM_SPAWN_CLIENT|passed=false|reason=${error.name}`);
});

bot.on('end', () => {
  clearTimeout(timer);
  process.exit();
});
