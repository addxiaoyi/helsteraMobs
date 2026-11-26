'use strict';

const mineflayer = require('mineflayer');
const mcData = require('minecraft-data')('1.21.1');
const customPackets = require('./paper-1.21.1-client-protocol');
const itemType = mcData.entitiesByName.item.id;
const itemMetadataKey = mcData.entitiesByName.item.metadataKeys.indexOf('item');
const clients = new Map();
const done = new Set();
let finished = false;
const timeout = setTimeout(() => finish(new Error('Merchant-trade fixture timeout')), 300000);

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
  console.log('MERCHANT_TRADE_CLIENT ' + JSON.stringify(serializable));
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
  // inventory.items() omits equipment, including the off-hand slot 45.
  return bot.inventory.slots.filter(Boolean).map(item => ({ name: item.name, type: item.type,
    count: item.count, slot: item.slot, metadata: item.metadata,
    nbt: item.nbt ?? null, components: item.components ?? null,
    removedComponents: item.removedComponents ?? null, componentMap: item.componentMap ?? null }))
    .sort((a, b) => a.slot - b.slot);
}


function message(state, text) {
  if (finished || !text.startsWith('MERCHANT_TRADE_')) return;
  let match;
  if ((match = /^MERCHANT_TRADE_PHASE (\d+) (\S+) (\S+)$/.exec(text))) {
    if (state.phase && !state.ended) throw new Error('New phase before END');
    const index = Number(match[1]);
    if (index !== state.lastIndex + 1) throw new Error('Missing phase');
    state.phase = { index, path: match[2], case: match[3] };
    state.lastIndex = index; state.tick = -1; state.ended = false;
    event({event: 'phase', ...phaseFields(state)});
  } else if ((match = /^MERCHANT_TRADE_TICK (\d+) (\d+)$/.exec(text))) {
    const index=Number(match[1]), tick=Number(match[2]);
    if (!state.phase || state.ended || index !== state.phase.index || tick !== state.tick+1 || tick>15)
      throw new Error('Out of order tick');
    state.tick=tick; event({event:'tick',...phaseFields(state)});
  } else if ((match = /^MERCHANT_TRADE_CLOSE (\d+)$/.exec(text))) {
    if (!state.phase || state.ended || Number(match[1]) !== state.phase.index || state.tick !== 5)
      throw new Error('Invalid close marker');
    event({event:'opened',...phaseFields(state), window:state.window});
    if (state.bot.currentWindow) {
      const id=state.bot.currentWindow.id;
      state.bot.closeWindow(state.bot.currentWindow);
      event({event:'sent-close',...phaseFields(state), windowId:id});
      state.window=null;
    }
  } else if ((match = /^MERCHANT_TRADE_END (\d+)$/.exec(text))) {
    if (!state.phase || state.ended || Number(match[1]) !== state.phase.index || state.tick !==15)
      throw new Error('Invalid end');
    state.ended=true;
    event({event:'end',...phaseFields(state),window:state.window,inventory:inventorySnapshot(state.bot)});
  } else if (text === 'MERCHANT_TRADE_DONE') {
    if (!state.phase || !state.ended || done.has(state.role)) throw new Error('Invalid done');
    done.add(state.role); event({event:'done',...phaseFields(state)});
    if (done.size===2) finish();
  } else throw new Error('Malformed marker '+text);
}
function connect(role, username) {
  const bot=mineflayer.createBot({host:'127.0.0.1',port:25731,username,version:'1.21.1',auth:'offline',customPackets});
  bot.physicsEnabled=false;
  const state={role,bot,phase:null,tick:-1,lastIndex:-1,ended:true,window:null};
  clients.set(role,state); installStrictParser(state);
  bot._client.on('state',()=>installStrictParser(state));
  for (const type of ['open_window','trade_list','close_window','window_items','set_slot']) {
    bot._client.on(type,data=>{
      if(finished) return;
      if(type==='open_window') state.window={id:data.windowId,type:data.inventoryType,title:data.windowTitle,trades:null};
      if(type==='trade_list' && state.window && state.window.id===data.windowId) state.window.trades=data.trades;
      if(type==='close_window' && state.window && state.window.id===data.windowId) state.window=null;
      event({event:'packet',...phaseFields(state),packetType:type,data});
    });
  }
  bot.once('spawn',()=>{bot.physicsEnabled=false;event({event:'join',role,name:username,uuid:bot.player.uuid,entityId:bot.entity.id});});
  bot.on('messagestr',text=>{try{message(state,text);}catch(error){finish(error,role);}});
  bot._client.on('error',error=>finish(error,role)); bot.on('error',error=>finish(error,role));
  bot.on('kicked',reason=>finish(new Error('Unexpected kick: '+String(reason)),role));
  bot.on('end',reason=>{if(!finished)finish(new Error('Unexpected disconnect: '+String(reason)),role);});
}
try { connect('receiver','TradeReceiver'); connect('observer','TradeObserver'); } catch(error) { finish(error); }
