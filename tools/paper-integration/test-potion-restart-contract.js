'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const zlib = require('node:zlib');
const nbt = require('prismarine-nbt');
const {
  uuid, decode, readEntityRegion, effects, validateSnapshot, validatePackets
} = require('./potion-restart-contract');

const identities = {
  owner: '42fb5ad4-6719-3599-b43f-da6f0f0f1a32',
  watcher: 'f64a63d6-990c-3880-8979-945855eff7a1',
  cow: '7cb5a8e1-f918-4a03-9bc4-5f41de4e2a11'
};
const words = value => value.replaceAll('-', '').match(/.{8}/g).map(part => Number.parseInt(part, 16) | 0);
const activeEffects = () => nbt.list(nbt.comp([
  { id: nbt.string('minecraft:haste'), amplifier: nbt.byte(2), duration: nbt.int(1000),
    ambient: nbt.byte(1), show_particles: nbt.byte(1), show_icon: nbt.byte(0) },
  { id: nbt.string('minecraft:invisibility'), amplifier: nbt.byte(0), duration: nbt.int(1000),
    ambient: nbt.byte(1), show_particles: nbt.byte(0), show_icon: nbt.byte(0) }
]));
const cowValue = () => ({
  UUID: nbt.intArray(words(identities.cow)),
  id: nbt.string('minecraft:cow'),
  Pos: nbt.list(nbt.double([4, 65, -3])),
  active_effects: activeEffects()
});
function regionRecord(compression) {
  const payload = nbt.writeUncompressed(nbt.comp({ Entities: nbt.list(nbt.comp([cowValue()])) }));
  const compressed = compression === 1 ? zlib.gzipSync(payload)
    : compression === 2 ? zlib.deflateSync(payload) : payload;
  const result = Buffer.alloc(12288);
  result.writeUIntBE(2, 0, 3);
  result[3] = 1;
  result.writeUInt32BE(compressed.length + 1, 8192);
  result[8196] = compression;
  compressed.copy(result, 8197);
  return result;
}
const entity = (id, duration = 1000) => ({
  UUID: words(id), id: id === identities.cow ? 'minecraft:cow' : undefined,
  Pos: [4, 65, -3], active_effects: [
    { id: 'minecraft:haste', amplifier: 2, duration, ambient: 1, show_particles: 1, show_icon: 0 },
    { id: 'minecraft:invisibility', amplifier: 0, duration, ambient: 1, show_particles: 0, show_icon: 0 }
  ]
});
const fields = (invisible, particlesValue, ambient) => [
  { key: 0, type: 'byte', value: invisible ? 32 : 0 },
  { key: 10, type: 'particles', value: particlesValue },
  { key: 11, type: 'boolean', value: ambient }
];

function packetFixture(mode) {
  const events = [];
  for (const role of ['owner', 'watcher']) {
    const selfId = role === 'owner' ? 10 : 20;
    const cowId = 30;
    events.push({ type: 'identity', role, uuid: identities[role] });
    events.push({ type: 'packet', role, packetType: 'login', data: { entityId: selfId } });
    events.push({ type: 'packet', role, packetType: 'spawn_entity', data: { entityId: cowId, objectUUID: identities.cow } });
    if (role === 'watcher') events.push({ type: 'packet', role, packetType: 'spawn_entity', data: { entityId: 10, objectUUID: identities.owner } });
    const tracked = role === 'owner' ? [cowId] : [10, cowId];
    for (const id of tracked) {
      if (mode !== 'clean') events.push({ type: 'packet', role, packetType: 'entity_metadata', data: {
        entityId: id, metadata: fields(true, [{ type: 'entity_effect', data: (38 << 24) | 0xd9c043 }], true)
      } });
      if (mode !== 'active') events.push({ type: 'packet', role, packetType: 'entity_metadata', data: {
        entityId: id, metadata: fields(false, [], false)
      } });
    }
  }
  if (mode !== 'clean') {
    for (const effect of [
      { effectId: 2, amplifier: 2, flags: 3 }, { effectId: 13, amplifier: 0, flags: 1 }
    ]) events.push({ type: 'packet', role: 'owner', packetType: 'entity_effect', data: {
      entityId: 10, ...effect, duration: 1000
    } });
  }
  if (mode === 'cleared') for (const effectId of [2, 13]) events.push({
    type: 'packet', role: 'owner', packetType: 'remove_entity_effect', data: { entityId: 10, effectId }
  });
  return events;
}

test('decodes player-compatible int-array UUIDs and region UUIDs', () => {
  assert.equal(uuid(words(identities.owner)), identities.owner);
  for (const compression of [1, 2, 3]) {
    const found = readEntityRegion(regionRecord(compression));
    assert.equal(found.length, 1);
    assert.equal(uuid(found[0].entity.UUID), identities.cow);
    assert.equal(found[0].entity.active_effects[0].duration, 1000);
  }
});

test('rejects malformed or truncated Anvil records', () => {
  assert.throws(() => readEntityRegion(Buffer.alloc(4096)), /Truncated Anvil region/);
  const broken = regionRecord(2);
  broken[3] = 0;
  assert.throws(() => readEntityRegion(broken), /Invalid region location/);
});

test('validates persisted player and Cow effect values, UUIDs, and ticking duration', () => {
  const owner = entity(identities.owner, 900);
  const cow = entity(identities.cow, 901);
  const snapshot = { owner, cow };
  const checked = validateSnapshot(snapshot, identities);
  assert.equal(checked.owner.length, 2);
  assert.ok(checked.cow[0].duration < 1000);
  assert.deepEqual(validateSnapshot({ owner: { ...owner, active_effects: [] }, cow: { ...cow, active_effects: [] } },
    identities, true).owner, []);
  assert.throws(() => effects({ active_effects: [
    { ...owner.active_effects[0], duration: 1200 }, owner.active_effects[1]
  ] }), /duration reset/);
  assert.throws(() => validateSnapshot({ owner, cow: { ...cow, UUID: words(identities.owner) } }, identities), /Persistent entity UUID changed/);
  assert.throws(() => validateSnapshot({ owner, cow }, identities, false, {
    owner: { ...owner, Pos: [0, 0, 0] }, cow
  }), /position changed/);
});

test('active, cleared, and clean packet traces enforce tracking and observer boundaries', () => {
  const active = packetFixture('active');
  assert.equal(validatePackets(active, identities, null, 'active').length, 2);
  const saved = { owner: entity(identities.owner, 1000), cow: entity(identities.cow, 1000) };
  assert.equal(validatePackets(packetFixture('cleared'), identities, saved, 'cleared').length, 2);
  assert.equal(validatePackets(packetFixture('clean'), identities, null, 'clean').length, 2);
  const leaked = packetFixture('clean');
  leaked.push({ type: 'packet', role: 'watcher', packetType: 'entity_effect', data: {
    entityId: 10, effectId: 2, amplifier: 2, flags: 3, duration: 1000
  } });
  assert.throws(() => validatePackets(leaked, identities, null, 'clean'), /observer effect packet/);
  assert.throws(() => validatePackets(active.filter(event => !(event.role === 'watcher'
    && event.packetType === 'spawn_entity' && event.data.objectUUID === identities.cow)), identities, null, 'active'), /not tracked/);
});

test('NBT simplify returns the saved effect structure expected by the contract', () => {
  const parsed = decode(nbt.writeUncompressed(nbt.comp({ active_effects: activeEffects() })));
  assert.equal(parsed.active_effects[1].id, 'minecraft:invisibility');
  assert.equal(parsed.active_effects[0].duration, 1000);
});

test('normalizes omitted optional NBT flags to Minecraft defaults without accepting malformed values', () => {
  const haste = { id: 'minecraft:haste', amplifier: 2, duration: 1000, ambient: 1, show_icon: 0 };
  const invisibility = { id: 'minecraft:invisibility', amplifier: 0, duration: 1000,
    ambient: 1, show_particles: 0, show_icon: 0 };
  assert.equal(effects({ active_effects: [haste, invisibility] })[0].id, 'minecraft:haste');
  const serializedDefaults = [haste, { id: 'minecraft:invisibility', duration: 1000,
    ambient: 1, show_particles: 0, show_icon: 0 }];
  assert.equal(effects({ active_effects: serializedDefaults })[1].amplifier, 0);
  assert.throws(() => effects({ active_effects: [
    { ...haste, show_particles: 2 }, invisibility
  ] }), /Invalid saved show_particles flag/);
  assert.throws(() => effects({ active_effects: [
    { ...haste, show_icon: 0 }, { ...invisibility, show_icon: undefined }
  ] }), /true !== false/);
  assert.deepEqual(effects({}, true), []);
  assert.throws(() => effects({ active_effects: null }, true), /Invalid saved effects/);
});
