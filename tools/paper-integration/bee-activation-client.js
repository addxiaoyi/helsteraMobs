'use strict';

const mineflayer = require('mineflayer');

const [host = '127.0.0.1', rawPort = '25731', reportPath = ''] = process.argv.slice(2);
const port = Number(rawPort);
if (!Number.isInteger(port) || port < 1 || port > 65535) {
  throw new Error(`Invalid Paper port: ${rawPort}`);
}

const report = line => {
  process.stdout.write(`${line}\n`);
  if (reportPath) require('fs').appendFileSync(reportPath, `${line}\n`);
};

const bot = mineflayer.createBot({
  host,
  port,
  username: 'HelsteraTester',
  version: process.env.PAPER_MINEFLAYER_VERSION || '1.21.1'
});

bot.once('spawn', () => report('PAPER_BEE_ACTIVATION_READY'));
bot.on('error', error => process.stderr.write(`${error.stack || error.message}\n`));
bot.on('end', () => process.exit(0));

process.on('SIGTERM', () => bot.quit('integration complete'));
process.on('SIGINT', () => bot.quit('integration complete'));
