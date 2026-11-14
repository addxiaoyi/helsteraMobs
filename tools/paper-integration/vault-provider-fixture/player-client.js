const mineflayer = require('mineflayer');

const [host = '127.0.0.1', rawPort = '25731'] = process.argv.slice(2);
const port = Number(rawPort);
if (!Number.isInteger(port) || port < 1 || port > 65535) {
  throw new Error(`Invalid Paper port: ${rawPort}`);
}

const bot = mineflayer.createBot({
  host,
  port,
  auth: 'offline',
  username: 'VaultTester',
  version: process.env.PAPER_MINEFLAYER_VERSION || '1.21.1'
});

bot.once('spawn', () => process.stdout.write('VAULT_FIXTURE_PLAYER_READY\n'));
bot.on('error', error => process.stderr.write(`${error.stack || error.message}\n`));
bot.on('end', () => process.exit(0));
process.on('SIGTERM', () => bot.quit('integration complete'));
process.on('SIGINT', () => bot.quit('integration complete'));
