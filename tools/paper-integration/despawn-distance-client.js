'use strict';
const mineflayer = require('mineflayer');
let joined = false;
const queued = [];
const bot = mineflayer.createBot({host: '127.0.0.1', port: 25731, username: 'DespawnWalker', version: '1.21.1', auth: 'offline'});
bot.physicsEnabled = false;
// ProtoDef logs and drops PartialReadError without emitting a client error. Record
// packet identity so the evidence checker can distinguish unrelated recipe data
// from a broken movement/acknowledgement stream.
bot._client.on('state', state => {
  const parser = bot._client.deserializer;
  const parse = parser.parsePacketBuffer.bind(parser);
  parser.parsePacketBuffer = buffer => {
    try { return parse(buffer); } catch (error) {
      const id = require('protodef').types.varint[0](buffer, 0).value;
      const mappings = require('minecraft-data')('1.21.1').protocol[state].toClient.types.packet[1][0].type[1].mappings;
      event({event: 'packet-error', state, packet: mappings['0x' + id.toString(16).padStart(2, '0')], partial: !!error.partialReadError, bytes: buffer.length});
      throw error;
    }
  };
});
const timer = setTimeout(() => process.exit(1), 180000);
const event = data => console.log(`DDIST_CLIENT ${JSON.stringify(data)}`);
function ack(phase) {
  event({event: 'ack', phase, position: bot.entity.position});
  bot.chat(`/distanceprobe ack ${phase}`);
}
bot.once('spawn', () => {joined = true; event({event: 'join', uuid: bot.player.uuid}); queued.splice(0).forEach(ack);});
bot.on('messagestr', message => {
  const match = /^DDIST_CONTROL (near|middle|far|spectator)$/.exec(message);
  if (match) { if (joined) ack(match[1]); else queued.push(match[1]); }
});
bot.on('error', error => {event({event: 'error', message: error.message}); process.exitCode = 1;});
bot.on('end', reason => {clearTimeout(timer); event({event: 'end', reason});});
