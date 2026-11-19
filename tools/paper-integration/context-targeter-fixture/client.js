'use strict';

const mineflayer = require('mineflayer');
const bot = mineflayer.createBot({
  host: '127.0.0.1', port: Number(process.argv[2]), version: '1.21.1', username: 'ContextTester'
});
const deadline = setTimeout(() => {
  console.error('Context targeter client timed out');
  bot.quit();
  process.exitCode = 1;
}, 90_000);
bot.once('spawn', () => console.log('CONTEXT_TARGETER_PLAYER_READY'));
bot.on('error', error => {
  console.error(error.message);
  process.exitCode = 1;
  bot.quit();
});
bot.once('end', () => clearTimeout(deadline));
process.on('SIGTERM', () => bot.quit());
