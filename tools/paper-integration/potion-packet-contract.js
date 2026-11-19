'use strict';

const assert = require('node:assert/strict');
const cases = [
  ['packet_default', 80, 2, 6],
  ...Array.from({ length: 8 }, (_, flags) => [`packet_flags_${flags}`, 80, 2, flags]),
  ['packet_expire', 8, 0, 5], ['packet_force', 60, 1, 1], ['packet_cancel', 80, 2, 6]
];

// Paper 1.21.1 sets bit 8 (blend) on newly added effects. Bits 1/2/4
// encode ambient/particles/icon; the pinned packet constructor and player
// onEffectAdded path establish this independently of the observed packets.
function expectedPackets(name, effectId) {
  const [, duration, amplifier, flags] = cases.find(test => test[0] === name);
  const add = (d, a, f) => ({ type: 'entity_effect', effectId, duration: d, amplifier: a, flags: f | 8 });
  const remove = { type: 'remove_entity_effect', effectId };
  if (name === 'packet_cancel') return [];
  if (name === 'packet_force') return [add(80, 2, 6), remove, add(duration, amplifier, flags), remove];
  return [add(duration, amplifier, flags), remove];
}

class PotionPacketContract {
  constructor(effectId) {
    assert.ok(Number.isInteger(effectId) && effectId >= 0, 'Missing Haste registry ID');
    this.effectId = effectId;
    this.phases = ['native', 'imported'].flatMap(backend => cases.map(([name]) => `${backend}:${name}`));
    this.observed = new Map();
    this.active = null;
    this.next = 0;
    this.done = false;
  }

  message(text) {
    if (text.startsWith('POTION_PHASE ')) {
      assert.equal(this.done, false, 'Phase after completion');
      assert.equal(this.active, null, 'Previous phase not closed');
      const phase = text.slice('POTION_PHASE '.length);
      assert.equal(phase, this.phases[this.next], 'Missing, duplicate, or reordered phase');
      this.active = phase;
      this.observed.set(phase, []);
    } else if (text.startsWith('POTION_END ')) {
      const phase = text.slice('POTION_END '.length);
      assert.equal(phase, this.active, 'Unexpected phase end');
      const name = phase.split(':')[1];
      assert.deepEqual(this.observed.get(phase), expectedPackets(name, this.effectId), `Packet mismatch ${phase}`);
      this.active = null;
      this.next++;
    } else if (text === 'POTION_RESET') {
      assert.equal(this.active, null, 'Reset interrupted an incomplete phase');
    } else if (text.startsWith('POTION_DONE ')) {
      assert.equal(text, 'POTION_DONE passed=true', 'Server assertions failed');
      assert.equal(this.done, false, 'Duplicate completion');
      assert.equal(this.active, null, 'Phase incomplete at completion');
      assert.equal(this.next, this.phases.length, 'Missing packet phases');
      for (const [name] of cases) {
        assert.deepEqual(this.observed.get(`native:${name}`), this.observed.get(`imported:${name}`),
          `Native/imported packet mismatch ${name}`);
      }
      this.done = true;
      return { comparisons: cases.length, phases: this.observed.size,
        packets: [...this.observed.values()].reduce((count, packets) => count + packets.length, 0) };
    }
    return null;
  }

  packet(type, packet, playerEntityId) {
    if (packet.entityId !== playerEntityId) return false;
    // Ignore unrelated effects, but never accept a wrong registry ID in place of Haste.
    if (!this.active) {
      assert.notEqual(packet.effectId, this.effectId, 'Haste packet outside an active phase');
      return false;
    }
    const observed = { type, effectId: packet.effectId };
    if (type === 'entity_effect') Object.assign(observed, {
      duration: packet.duration, amplifier: packet.amplifier, flags: packet.flags
    });
    this.observed.get(this.active).push(observed);
    return true;
  }
}

module.exports = { PotionPacketContract, cases, expectedPackets };
