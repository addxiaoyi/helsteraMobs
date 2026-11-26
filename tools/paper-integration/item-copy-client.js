'use strict';

const mineflayer = require('mineflayer');
const mcData = require('minecraft-data')('1.21.1');
const customPackets = require('./paper-1.21.1-client-protocol');
const itemType = mcData.entitiesByName.item.id;
const itemMetadataKey = mcData.entitiesByName.item.metadataKeys.indexOf('item');
const clients = new Map();
const done = new Set();
let finished = false;
const timeout = setTimeout(() => finish(new Error('Item-copy fixture timeout')), 300000);

function jsonValue(value, ancestors = new Set(), status = { cycles: 0 }) {
  if (typeof value === 'bigint') return { type: 'bigint', value: value.toString() };
  if (value === null || typeof value !== 'object') return value;
  if (ancestors.has(value)) {
    status.cycles++;
    return { type: 'circular-reference' };
  }
  ancestors.add(value);
  const nested = item => jsonValue(item, ancestors, status);
  let result;
  if (Buffer.isBuffer(value)) result = { type: 'Buffer', data: Array.from(value) };
  else if (value instanceof Map) result = { type: 'map', entries: [...value.entries()].map(nested) };
  else if (value instanceof Set) result = { type: 'set', values: [...value.values()].map(nested) };
  else if (ArrayBuffer.isView(value)) result = { type: value.constructor.name, values: Array.from(value).map(nested) };
  else if (Array.isArray(value)) result = value.map(nested);
  else result = Object.fromEntries(Object.entries(value).map(([key, item]) => [key, nested(item)]));
  ancestors.delete(value);
  return result;
}

function event(value) {
  const status = { cycles: 0 };
  const serializable = jsonValue(value, new Set(), status);
  console.log('ITEM_COPY_CLIENT ' + JSON.stringify(serializable));
  if (status.cycles && !finished)
    finish(new Error('Circular client evidence cannot be serialized completely'), value.role);
}

function finish(error, role) {
  if (finished) return;
  finished = true;
  clearTimeout(timeout);
  event({ event: 'summary', passed: !error, role: role || null,
    doneRoles: [...done].sort(), error: error?.message || null });
  for (const { bot } of clients.values()) {
    try { bot.quit(); } catch (_) { /* The failed connection may already be closed. */ }
  }
  setTimeout(() => process.exit(error ? 1 : 0), 300);
}

function phaseFields(state) {
  return { role: state.role, index: state.phase?.index ?? null,
    path: state.phase?.path ?? null, case: state.phase?.case ?? null, tick: state.tick };
}

function packetIdentity(state, buffer) {
  try {
    const id = require('protodef').types.varint[0](buffer, 0).value;
    const mappings = mcData.protocol[state]?.toClient.types.packet[1][0].type[1].mappings;
    return mappings?.['0x' + id.toString(16).padStart(2, '0')] || String(id);
  } catch (_) { return null; }
}

function installStrictParser(state) {
  const parser = state.bot._client.deserializer;
  if (!parser || parser.itemCopyStrict) return;
  parser.itemCopyStrict = true;
  const parse = parser.parsePacketBuffer.bind(parser);
  parser.parsePacketBuffer = buffer => {
    try {
      const parsed = parse(buffer);
      if (parsed.metadata.size !== buffer.length) {
        const error = new Error(`Partial packet decode: ${parsed.metadata.size}/${buffer.length} bytes`);
        error.partialReadError = true;
        throw error;
      }
      return parsed;
    } catch (error) {
      event({ event: 'packet-error', ...phaseFields(state), state: state.bot._client.state,
        packetType: packetIdentity(state.bot._client.state, buffer),
        partial: Boolean(error.partialReadError), bytes: buffer.length, message: error.message,
        rawBase64: buffer.toString('base64') });
      finish(error, state.role);
      // ProtoDef drops PartialReadError without emitting an error; finish above is deliberate.
      throw error;
    }
  };
}

function inventorySnapshot(bot) {
  return bot.inventory.items().map(item => ({ name: item.name, type: item.type,
    count: item.count, slot: item.slot, metadata: item.metadata,
    nbt: item.nbt ?? null, components: item.components ?? null,
    removedComponents: item.removedComponents ?? null, componentMap: item.componentMap ?? null }))
    .sort((a, b) => a.slot - b.slot);
}

function itemStates(state) {
  return [...state.items.values()].filter(item => item.present)
    .map(item => ({ entityId: item.entityId, spawnIndex: item.spawnIndex,
      count: item.count, collected: item.collected }))
    .sort((a, b) => a.entityId - b.entityId);
}

function recordPacket(state, type, data) {
  if (finished) return;
  if (type === 'spawn_entity') {
    if (data.type !== itemType) return;
    state.items.set(data.entityId, { entityId: data.entityId,
      spawnIndex: state.phase?.index ?? null, count: null, collected: 0, present: true });
  } else if (type === 'entity_metadata') {
    const item = state.items.get(data.entityId);
    if (!item) return;
    for (const entry of data.metadata) {
      if (entry.key !== itemMetadataKey) continue;
      if (!Number.isInteger(entry.value?.itemCount))
        throw new Error('Item entity metadata lacks a decoded itemCount');
      item.count = entry.value.itemCount;
      // A late metadata packet cannot recreate an entity cleared by a world change/destroy.
      item.present = item.present && item.count > 0;
    }
  } else if (type === 'collect') {
    const item = state.items.get(data.collectedEntityId);
    if (!Number.isInteger(data.pickupItemCount) || data.pickupItemCount < 0)
      throw new Error('Invalid collect pickupItemCount');
    if (item) {
      item.collected += data.pickupItemCount;
      if (item.count !== null) {
        item.count = Math.max(0, item.count - data.pickupItemCount);
        if (item.count === 0) item.present = false;
      }
    }
  } else if (type === 'entity_destroy') {
    const relevant = data.entityIds.filter(id => state.items.has(id));
    if (!relevant.length) return;
    for (const id of relevant) state.items.get(id).present = false;
    // Keep packet data complete, with a separate list identifying the tracked intersection.
    event({ event: 'packet', ...phaseFields(state), packetType: type, data,
      trackedEntityIds: relevant });
    return;
  }
  event({ event: 'packet', ...phaseFields(state), packetType: type, data });
}

function message(state, text) {
  if (finished || !text.startsWith('ITEM_COPY_')) return;
  let match;
  if ((match = /^ITEM_COPY_PHASE (\d+) (\S+) (\S+)$/.exec(text))) {
    if (state.phase && !state.ended) throw new Error('New phase before previous END');
    const index = Number(match[1]);
    if (!Number.isSafeInteger(index) || index <= state.lastIndex)
      throw new Error('Phase index must increase');
    state.phase = { index, path: match[2], case: match[3] };
    state.lastIndex = index;
    state.tick = -1;
    state.ended = false;
    event({ event: 'phase', ...phaseFields(state) });
  } else if ((match = /^ITEM_COPY_TICK (\d+) (\d+)$/.exec(text))) {
    const index = Number(match[1]), tick = Number(match[2]);
    if (!state.phase || state.ended || index !== state.phase.index || tick !== state.tick + 1 || tick > 10)
      throw new Error('Missing, duplicate, or out-of-order phase tick');
    state.tick = tick;
    event({ event: 'tick', ...phaseFields(state) });
  } else if ((match = /^ITEM_COPY_END (\d+)$/.exec(text))) {
    if (!state.phase || state.ended || Number(match[1]) !== state.phase.index || state.tick !== 10)
      throw new Error('END requires the matching phase and ticks 0 through 10');
    state.ended = true;
    const items = itemStates(state);
    event({ event: 'end', ...phaseFields(state), trackedItemIds: items.map(item => item.entityId),
      phaseTrackedItemIds: items.filter(item => item.spawnIndex === state.phase.index).map(item => item.entityId),
      itemStates: items,
      inventory: state.role === 'receiver' ? inventorySnapshot(state.bot) : undefined });
  } else if (text === 'ITEM_COPY_DONE') {
    if (!state.phase || !state.ended || done.has(state.role)) throw new Error('Unexpected DONE marker');
    done.add(state.role);
    event({ event: 'done', ...phaseFields(state) });
    if (done.size === 2) finish();
  } else {
    throw new Error('Malformed item-copy marker: ' + text);
  }
}

function connect(role, username) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25731,
    username, version: '1.21.1', auth: 'offline', customPackets });
  bot.physicsEnabled = false;
  const state = { role, bot, phase: null, tick: -1, lastIndex: -1, ended: true, items: new Map() };
  clients.set(role, state);
  installStrictParser(state);
  bot._client.on('state', () => installStrictParser(state));
  for (const type of ['spawn_entity', 'entity_metadata', 'collect', 'entity_destroy']) {
    bot._client.on(type, data => {
      try { recordPacket(state, type, data); } catch (error) { finish(error, role); }
    });
  }
  bot._client.on('respawn', data => {
    if (finished) return;
    const previousItemIds = itemStates(state).map(item => item.entityId);
    for (const item of state.items.values()) item.present = false;
    event({ event: 'world-reset', ...phaseFields(state), previousItemIds, data });
  });
  bot.once('spawn', () => {
    bot.physicsEnabled = false;
    event({ event: 'join', role, name: username, uuid: bot.player.uuid, entityId: bot.entity.id });
  });
  bot.on('messagestr', text => {
    try { message(state, text); } catch (error) { finish(error, role); }
  });
  bot._client.on('error', error => finish(error, role));
  bot.on('error', error => finish(error, role));
  bot.on('kicked', reason => finish(new Error('Unexpected kick: ' + String(reason)), role));
  bot.on('end', reason => {
    if (!finished) finish(new Error('Unexpected disconnect: ' + String(reason)), role);
  });
}

try {
  connect('receiver', 'CopyReceiver');
  connect('observer', 'CopyObserver');
} catch (error) { finish(error); }
