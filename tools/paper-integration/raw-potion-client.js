const minecraft = require('minecraft-protocol');
const fs = require('fs');

const [host = '127.0.0.1', rawPort = '25731'] = process.argv.slice(2);
const port = Number(rawPort);
const reportPath = process.argv[4];
const version = process.env.PAPER_MINEFLAYER_VERSION || '1.21.11';
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
  username: 'HelsteraTester',
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
  if (meta && meta.name === 'set_slot' && slotReports < 8) {
    slotReports += 1;
    const item = data && data.item ? data.item : {};
    if (data.slot === 37 && (item.itemId ?? item.type) !== 0) potionSlotObserved = true;
    report(`PAPER_RAW_SLOT slot=${data.slot} item-id=${item.itemId ?? item.type ?? 'unknown'}`);
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
  // The runner gives the potion immediately after the join log appears.
  // The integration self-test switches the player from the server's default
  // creative mode to survival before exercising player state.  Wait until that
  // setup has completed so this packet represents a normal drink action.
  const waitForPotion = () => {
    if (potionSlotObserved || client.ended) return sendPotionUse();
    setTimeout(waitForPotion, 250);
  };
  setTimeout(waitForPotion, 11000);
});
client.on('error', error => process.stderr.write(`${error.stack || error.message}\n`));
client.on('end', () => process.exit(0));
