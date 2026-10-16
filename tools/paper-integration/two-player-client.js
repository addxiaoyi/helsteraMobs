const mineflayer = require('mineflayer');

const [host = '127.0.0.1', rawPort = '25731'] = process.argv.slice(2);
const port = Number(rawPort);
if (!Number.isInteger(port) || port < 1 || port > 65535) {
  throw new Error(`Invalid Paper port: ${rawPort}`);
}

const bots = ['HelsteraTester', 'AudienceGuest'].map(username =>
  mineflayer.createBot({ host, port, username, version: '1.21.1' }));
let ready = 0;
for (const bot of bots) {
  bot.once('spawn', () => {
    ready++;
    if (ready === bots.length) process.stdout.write('PAPER_TWO_PLAYERS_READY\n');
  });
  bot.on('error', error => process.stderr.write(`${error.stack || error.message}\n`));
  bot.on('end', () => {
    if (bots.every(candidate => candidate.ended)) process.exit(0);
  });
}

function stop() {
  for (const bot of bots) bot.quit('integration complete');
}
process.on('SIGTERM', stop);
process.on('SIGINT', stop);
