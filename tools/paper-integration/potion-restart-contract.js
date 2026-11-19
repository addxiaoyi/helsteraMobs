'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');
const crypto = require('node:crypto');
const nbt = require('prismarine-nbt');
const { particles } = require('./potion-sync-contract');

const sha256 = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const uuid = words => {
  assert.ok(Array.isArray(words) && words.length === 4, 'Missing saved UUID');
  const hex = words.map(word => (word >>> 0).toString(16).padStart(8, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
};
const decode = bytes => nbt.simplify(nbt.parseUncompressed(bytes));

// Read the saved entity region, not a command that might select a replacement Cow.
// Pinned Paper 1.21.1 writes ordinary gzip/zlib/uncompressed Anvil records here.
function readEntityRegion(bytes) {
  assert.ok(bytes.length >= 8192 && bytes.length % 4096 === 0, 'Truncated Anvil region');
  const result = [];
  for (let index = 0; index < 1024; index++) {
    const offset = bytes.readUIntBE(index * 4, 3) * 4096;
    const sectors = bytes[index * 4 + 3];
    if (!offset && !sectors) continue;
    assert.ok(offset >= 8192 && sectors > 0 && offset + sectors * 4096 <= bytes.length, 'Invalid region location');
    const size = bytes.readUInt32BE(offset);
    assert.ok(size > 1 && size + 4 <= sectors * 4096, 'Truncated entity record');
    const compression = bytes[offset + 4];
    assert.ok([1, 2, 3].includes(compression), 'Unsupported entity compression');
    const payload = bytes.subarray(offset + 5, offset + 4 + size);
    const raw = compression === 1 ? zlib.gunzipSync(payload)
      : compression === 2 ? zlib.inflateSync(payload) : payload;
    const data = decode(raw);
    assert.ok(Array.isArray(data.Entities), 'Missing entity chunk list');
    for (const entity of data.Entities) result.push({ entity, raw });
  }
  return result;
}

function snapshot(server, identities) {
  const world = path.join(server, 'integration-world');
  const playerBytes = fs.readFileSync(path.join(world, 'playerdata', identities.owner + '.dat'));
  const owner = decode(zlib.gunzipSync(playerBytes));
  const matches = [];
  for (const file of fs.readdirSync(path.join(world, 'entities')).filter(name => /^r\.-?\d+\.-?\d+\.mca$/.test(name))) {
    for (const entry of readEntityRegion(fs.readFileSync(path.join(world, 'entities', file)))) {
      if (uuid(entry.entity.UUID) === identities.cow) matches.push(entry);
    }
  }
  assert.equal(matches.length, 1, 'Saved recipient Cow missing or duplicated');
  assert.equal(uuid(owner.UUID), identities.owner, 'Saved player UUID changed');
  assert.equal(matches[0].entity.id, 'minecraft:cow', 'Saved recipient type changed');
  return { owner, cow: matches[0].entity, playerBytes, cowChunkBytes: matches[0].raw };
}

function effects(entity, clean = false) {
  if (clean && entity.active_effects === undefined) return [];
  const values = entity.active_effects;
  assert.ok(Array.isArray(values), 'Invalid saved effects');
  if (clean) { assert.deepEqual(values, [], 'Cleared effects resurrected on disk'); return []; }
  assert.equal(values.length, 2, 'Missing or unexpected saved effect');
  const readBoolean = (value, key, defaultValue) => {
    const actual = value[key];
    if (actual === undefined) return defaultValue;
    assert.ok(actual === 0 || actual === 1, `Invalid saved ${key} flag`);
    return Boolean(actual);
  };
  return [['minecraft:haste', 2, true, false], ['minecraft:invisibility', 0, false, false]].map(
    ([id, amplifier, visible, icon]) => {
      const found = values.filter(value => value.id === id);
      assert.equal(found.length, 1, 'Duplicate or missing saved effect type');
      const value = found[0];
      // Legacy-compatible MobEffectInstance NBT omits amplifier 0 and ambient false.
      assert.equal(value.amplifier === undefined ? 0 : value.amplifier, amplifier);
      assert.equal(readBoolean(value, 'ambient', false), true);
      // Minecraft's MobEffectInstance NBT reader defaults these optional tags to true.
      assert.equal(readBoolean(value, 'show_particles', true), visible);
      assert.equal(readBoolean(value, 'show_icon', true), icon);
      assert.ok(Number.isInteger(value.duration) && value.duration > 0 && value.duration < 1200,
        'Effect duration reset or expired');
      assert.equal(value.hidden_effect, undefined, 'Unexpected hidden effect');
      return { id, amplifier, duration: value.duration };
    });
}

function validateSnapshot(saved, identities, clean = false, previous) {
  const result = {};
  for (const role of ['owner', 'cow']) {
    const entity = saved[role];
    assert.equal(uuid(entity.UUID), identities[role], 'Persistent entity UUID changed');
    assert.ok(Array.isArray(entity.Pos) && entity.Pos.length === 3 && entity.Pos.every(Number.isFinite));
    result[role] = effects(entity, clean);
    if (previous) {
      assert.deepEqual(entity.Pos, previous[role].Pos, 'Saved recipient position changed');
      if (!clean) for (const value of result[role]) {
        const before = effects(previous[role]).find(e => e.id === value.id);
        assert.ok(value.duration < before.duration, 'Saved effect did not tick after restart');
      }
    }
  }
  return result;
}

function validatePackets(events, identities, saved, mode) {
  assert.ok(['active', 'cleared', 'clean'].includes(mode));
  const clean = mode !== 'active';
  const observations = [];
  for (const role of ['owner', 'watcher']) {
    const records = events.filter(e => e.role === role && e.type === 'packet');
    const logins = records.filter(e => e.packetType === 'login');
    assert.equal(logins.length, 1, 'Missing or repeated player login');
    const ownerId = role === 'owner' ? logins[0].data.entityId
      : records.find(e => e.packetType === 'spawn_entity' && e.data.objectUUID === identities.owner)?.data.entityId;
    const cowId = records.find(e => e.packetType === 'spawn_entity' && e.data.objectUUID === identities.cow)?.data.entityId;
    assert.ok(Number.isInteger(ownerId) && Number.isInteger(cowId) && ownerId !== cowId, 'Persistent entity not tracked');
    const identity = events.filter(e => e.type === 'identity' && e.role === role);
    assert.equal(identity.length, 1, 'Missing or duplicate client UUID');
    assert.equal(identity[0].uuid, identities[role], 'Client UUID changed across restart');
    for (const id of role === 'owner' ? [cowId] : [ownerId, cowId]) {
      const fields = { 0: { type: 'byte', value: 0 }, 10: { type: 'particles', value: [] }, 11: { type: 'boolean', value: false } };
      for (const record of records.filter(e => e.packetType === 'entity_metadata' && e.data.entityId === id)) {
        for (const field of record.data.metadata) if ([0, 10, 11].includes(field.key)) fields[field.key] = field;
      }
      assert.equal(fields[0].type, 'byte');
      assert.equal(fields[10].type, 'particles');
      assert.equal(fields[11].type, 'boolean');
      assert.equal((fields[0].value & 32) !== 0, !clean, 'Restored invisibility metadata differs');
      assert.deepEqual(fields[10].value, clean ? [] : particles(true), 'Restored particle metadata differs');
      if (!clean) assert.equal(fields[11].value, true, 'Restored ambient metadata differs');
      if (mode === 'clean') assert.equal(fields[11].value, false, 'Clean restart retained stale ambient metadata');
    }
    const packets = records.filter(e => ['entity_effect', 'remove_entity_effect'].includes(e.packetType));
    assert.ok(packets.every(e => role === 'owner' && e.data.entityId === ownerId), 'Unexpected observer effect packet');
    if (role === 'owner') {
      const adds = packets.filter(e => e.packetType === 'entity_effect');
      const removes = packets.filter(e => e.packetType === 'remove_entity_effect');
      assert.equal(adds.length, mode === 'clean' ? 0 : 2, 'Effect reapplied, missing, or resurrected');
      for (const [effectId, amplifier, flags, name] of [[2, 2, 3, 'minecraft:haste'], [13, 0, 1, 'minecraft:invisibility']]) {
        if (mode === 'clean') continue;
        const matches = adds.filter(e => e.data.effectId === effectId);
        assert.equal(matches.length, 1, 'Incorrect login effect IDs');
        if (saved) {
          const before = effects(saved.owner).find(e => e.id === name);
          assert.deepEqual(matches[0].data, { entityId: ownerId, effectId, amplifier, flags, duration: before.duration },
            'Login effect differs from saved player data');
        } else {
          assert.deepEqual(matches[0].data, { entityId: ownerId, effectId, amplifier, flags,
            duration: matches[0].data.duration }, 'Incorrect active effect packet');
          assert.ok(Number.isInteger(matches[0].data.duration) && matches[0].data.duration > 0,
            'Active effect packet has no remaining duration');
        }
      }
      assert.deepEqual(removes.map(e => e.data.effectId).sort((a, b) => a - b), mode === 'cleared' ? [2, 13] : [],
        'Missing or unexpected effect removal');
      if (removes.length) assert.ok(packets.indexOf(removes[0]) > packets.indexOf(adds[1]), 'Removal precedes login effects');
    }
    observations.push({ role, mode, ownerTracked: true, cowTracked: true, metadata: true, loginEffects: true });
  }
  return observations;
}

module.exports = { sha256, uuid, decode, readEntityRegion, snapshot, effects, validateSnapshot, validatePackets };

if (require.main === module) {
  const [command, server, ownerId, cowId, previousPath] = process.argv.slice(2);
  assert.ok(['snapshot', 'active', 'clean'].includes(command), 'Expected snapshot, active, or clean command');
  const identities = { owner: ownerId, cow: cowId };
  const saved = snapshot(server, identities);
  const previous = previousPath ? JSON.parse(fs.readFileSync(previousPath, 'utf8')) : undefined;
  const checked = validateSnapshot(saved, identities, command === 'clean', previous);
  process.stdout.write(JSON.stringify({
    status: 'passed',
    mode: command,
    effects: checked,
    snapshot: { owner: saved.owner, cow: saved.cow }
  }) + '\n');
}
