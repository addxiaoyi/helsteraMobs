'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { PotionPacketContract } = require('./potion-packet-contract');

// Independent wire fixture: Haste is registry ID 2 in protocol 1.21.1.
function fixture(name) {
  const add = (duration, amplifier, flags) => ({
    type: 'entity_effect', entityId: 42, effectId: 2, duration, amplifier, flags
  });
  const remove = { type: 'remove_entity_effect', entityId: 42, effectId: 2 };
  if (name === 'packet_cancel') return [];
  if (name === 'packet_force') return [add(80, 2, 14), remove, add(60, 1, 9), remove];
  if (name === 'packet_expire') return [add(8, 0, 13), remove];
  return [add(80, 2, name === 'packet_default' ? 14 : Number(name.slice(-1)) + 8), remove];
}

function replay(mutate = packets => packets) {
  const contract = new PotionPacketContract(2);
  const names = ['packet_default', ...Array.from({ length: 8 }, (_, f) => `packet_flags_${f}`),
    'packet_expire', 'packet_force', 'packet_cancel'];
  for (const backend of ['native', 'imported']) {
    for (const name of names) {
      const phase = `${backend}:${name}`;
      contract.message(`POTION_PHASE ${phase}`);
      for (const packet of mutate(fixture(name), phase)) contract.packet(packet.type, packet, 42);
      contract.message(`POTION_END ${phase}`);
    }
  }
  contract.message('POTION_RESET');
  return contract.message('POTION_DONE passed=true');
}

test('complete native/imported fixture validates flags, cancellation and ordered removal', () => {
  assert.deepEqual(replay(), { comparisons: 12, phases: 24, packets: 48 });
});
for (const [name, mutate] of [
  ['missing add', packets => packets.filter(p => p.type !== 'entity_effect')],
  ['missing remove', packets => packets.filter(p => p.type !== 'remove_entity_effect')],
  ['wrong duration', packets => packets.map(p => p.type === 'entity_effect' ? { ...p, duration: p.duration - 1 } : p)],
  ['wrong amplifier', packets => packets.map(p => p.type === 'entity_effect' ? { ...p, amplifier: 3 } : p)],
  ['wrong particles/icon/ambient', packets => packets.map(p => p.type === 'entity_effect' ? { ...p, flags: p.flags ^ 7 } : p)],
  ['missing blend', packets => packets.map(p => p.type === 'entity_effect' ? { ...p, flags: p.flags & 7 } : p)],
  ['wrong effect ID', packets => packets.map(p => ({ ...p, effectId: 3 }))],
  ['wrong recipient', packets => packets.map(p => ({ ...p, entityId: 43 }))],
  ['reordered packets', packets => packets.toReversed()],
  ['duplicate packet', packets => [...packets, ...packets]]
]) {
  test(`rejects ${name}`, () => assert.throws(() => replay(mutate), /Packet mismatch/));
}
test('cancelled addition must produce no effect packet', () => {
  assert.throws(() => replay((packets, phase) => phase.endsWith(':packet_cancel')
    ? fixture('packet_default') : packets), /Packet mismatch/);
});
test('forced replacement requires removal between the two additions', () => {
  assert.throws(() => replay((packets, phase) => phase.endsWith(':packet_force')
    ? [packets[0], packets[2], packets[3]] : packets), /Packet mismatch/);
});
test('aggregate success cannot hide missing phases or an interrupted phase', () => {
  const contract = new PotionPacketContract(2);
  assert.throws(() => contract.message('POTION_DONE passed=true'), /Missing packet phases/);
  contract.message('POTION_PHASE native:packet_default');
  assert.throws(() => contract.message('POTION_RESET'), /incomplete phase/);
  assert.throws(() => contract.message('POTION_DONE passed=true'), /Phase incomplete/);
});
test('duplicate/reordered phases and server failure are rejected', () => {
  const contract = new PotionPacketContract(2);
  assert.throws(() => contract.message('POTION_PHASE imported:packet_default'), /reordered phase/);
  contract.message('POTION_PHASE native:packet_default');
  assert.throws(() => contract.message('POTION_PHASE native:packet_default'), /not closed/);
  assert.throws(() => contract.message('POTION_DONE passed=false'), /Server assertions failed/);
});
test('self Haste packets outside a phase fail; unrelated entities do not satisfy a phase', () => {
  const contract = new PotionPacketContract(2);
  assert.equal(contract.packet('entity_effect', fixture('packet_default')[0], 43), false);
  assert.throws(() => contract.packet('entity_effect', fixture('packet_default')[0], 42), /outside an active phase/);
});
