'use strict';
const mineflayer = require('mineflayer');
const bot = mineflayer.createBot({host: '127.0.0.1', port: 25731, username: 'CommandTester', version: '1.21.1', auth: 'offline'});
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
const timeoutMillis = Number(process.argv[2] || 300000);
if (!Number.isSafeInteger(timeoutMillis) || timeoutMillis <= 0) throw new Error('Invalid client timeout');
const timer = setTimeout(() => process.exit(1), timeoutMillis);
const event = data => console.log(`DCMD_CLIENT ${JSON.stringify(data)}`);
bot.once('spawn', () => {event({event: 'join', uuid: bot.player.uuid});});
bot.on('error', error => {event({event: 'error', message: error.message}); process.exitCode = 1;});
bot.on('end', reason => {clearTimeout(timer); event({event: 'end', reason});});
