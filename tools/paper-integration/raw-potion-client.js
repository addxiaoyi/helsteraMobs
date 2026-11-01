const minecraft = require('minecraft-protocol');
const fs = require('fs');

const [host = '127.0.0.1', rawPort = '25731'] = process.argv.slice(2);
const port = Number(rawPort);
const reportPath = process.argv[4];
const username = process.argv[5] || 'HelsteraTester';
const version = process.env.PAPER_MINEFLAYER_VERSION || '1.21.1';
const report = line => {
  process.stdout.write(`${line}\n`);
  if (reportPath) fs.appendFileSync(reportPath, `${line}\n`);
};

if (!Number.isInteger(port) || port < 1 || port > 65535) {
  throw new Error(`Invalid Paper port: ${rawPort}`);
}

const client = minecraft.createClient({
  host,
  port,
  username,
  version,
  auth: 'offline'
});

let slotReports = 0;
let lastPosition = { x: 0, y: 0, z: 0 };
let potionSlotObserved = false;
client.on('packet', (data, meta) => {
  if (meta && meta.name === 'position' && data
      && Number.isFinite(data.x) && Number.isFinite(data.y) && Number.isFinite(data.z)) {
    lastPosition = { x: data.x, y: data.y, z: data.z };
  }
  if (meta && meta.name === 'set_slot') {
    const item = data && data.item ? data.item : {};
    if (data.slot === 37 && (item.itemId ?? item.type) !== 0) potionSlotObserved = true;
    if (slotReports < 8) {
      slotReports += 1;
      report(`PAPER_RAW_SLOT slot=${data.slot} item-id=${item.itemId ?? item.type ?? 'unknown'}`);
    }
  }
});

let sequence = 1;
let used = false;
const sendPotionUse = () => {
  if (used || client.ended) return;
  used = true;
  // The runner's integration setup places the potion in the second hotbar
  // slot (protocol slot 37); slot 0 is reserved for the shield probe.
  client.write('held_item_slot', { slotId: 1 });
  // Allow Paper to apply the carried-item change before the use packet. A
  // same-tick held-slot/use pair can be consumed against the old main hand.
  setTimeout(() => {
    if (client.ended) return;
    client.write('use_item', {
      hand: 0,
      sequence: sequence++,
      rotation: { x: 0, y: 0 }
    });
    report('PAPER_RAW_POTION_USE started=true');
    setTimeout(() => {
      if (client.ended) return;
      client.write('block_dig', {
        status: 5,
        location: {
          x: Math.floor(lastPosition.x),
          y: Math.floor(lastPosition.y),
          z: Math.floor(lastPosition.z)
        },
        face: 0,
        sequence: sequence++
      });
      report('PAPER_RAW_POTION_USE released=true');
    }, 1700);
  }, 250);
};

client.once('login', () => {
  report('PAPER_RAW_POTION_READY');
  // The runner equips the potion after its self-test inventory setup. Keep
  // slot observation diagnostic, but do not let a missing packet hide the
  // use attempt from the server-side strict evidence probe.
  setTimeout(() => {
    if (!potionSlotObserved) report('PAPER_RAW_POTION_SLOT observed=false');
    sendPotionUse();
  }, 11000);
});
client.on('error', error => process.stderr.write(`${error.stack || error.message}\n`));
client.on('end', () => process.exit(0));
