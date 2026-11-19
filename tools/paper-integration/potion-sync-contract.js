'use strict';

const assert = require('node:assert/strict');
const stages = ['live', 'watcher-rejoin', 'owner-rejoin', 'changed', 'hidden', 'cleared', 'clean-rejoin'];
const phases = ['native', 'imported'].flatMap(backend => stages.map(stage => `${backend}:${stage}`));
const hasteId = 2;
const invisibleId = 13;
// MobEffect's pinned particle factory uses floor(38.25) alpha for ambient
// effects, and 255 otherwise. Haste RGB is 0xd9c043.
const particles = ambient => [{ type: 'entity_effect', data: ((ambient ? 38 : 255) << 24) | 0xd9c043 }];

function serverAssertions() {
  const result = ['source-identical', 'source-imported', 'players-clean', 'cleanup'];
  for (const backend of ['native', 'imported']) for (const stage of stages) {
    const prefix = `${backend}-${stage}-`;
    result.push(...['owner-state', 'cow-state', 'isolation', 'ack-owner', 'ack-watcher'].map(k => prefix + k));
    if (stage === 'live') for (const role of ['owner', 'cow']) {
      result.push(...['haste-cast', 'invisible-cast', 'initial-state'].map(k => prefix + role + '-' + k));
    }
    if (['changed', 'hidden'].includes(stage)) for (const role of ['owner', 'cow']) {
      result.push(prefix + role + '-cast', prefix + role + '-initial-state');
    }
    if (stage.endsWith('rejoin')) {
      result.push(prefix + 'quit', prefix + 'rejoin');
      if (stage !== 'watcher-rejoin') result.push(prefix + 'persisted-duration');
    }
  }
  return result;
}

function validateServerLog(text) {
  const observation = text.split(/\r?\n/).find(line => line.includes('[自检] potion-sync ') && line.endsWith(' passed=true'));
  assert.ok(observation, 'Missing server success observation');
  const observed = [...observation.matchAll(/(?:^| )([a-z-]+)=(true|false)(?= |$)/g)]
    .filter(match => match[1] !== 'passed');
  const expected = serverAssertions();
  assert.equal(observed.length, expected.length, 'Incomplete server assertion count');
  assert.equal(new Set(observed.map(match => match[1])).size, expected.length, 'Duplicate server assertion');
  assert.ok(observed.every(match => match[2] === 'true'), 'False server assertion');
  assert.deepEqual(observed.map(match => match[1]).sort(), expected.sort(), 'Incorrect server assertion names');
  assert.ok(observation.endsWith(` assertions=${expected.length} passed=true`), 'Incorrect assertion total');
  for (const backend of ['native', 'imported']) for (const stage of ['owner-rejoin', 'clean-rejoin'])
    for (const effect of ['haste', 'invisibility']) {
    const traces = [...text.matchAll(/potion-sync-duration backend=(native|imported) stage=(owner-rejoin|clean-rejoin) effect=(haste|invisibility) quit=(\d+) join=(\d+)/g)]
      .filter(match => match[1] === backend && match[2] === stage && match[3] === effect);
    assert.equal(traces.length, 1, 'Missing or duplicate persistence duration trace');
    const quit = Number(traces[0][4]), join = Number(traces[0][5]);
    assert.equal(join, Math.max(0, quit - 1), 'Incorrect saved duration after logout tick');
    assert.ok(stage === 'clean-rejoin' ? quit === 0 : quit > 1 && quit < 1200, 'Invalid persistence setup');
  }
  return { count: expected.length, observation: observation.slice(observation.indexOf('potion-sync ')) };
}

function marker(text) {
  assert.ok(text.startsWith('POTION_SYNC '), 'Invalid sync marker');
  const [command, ...fields] = text.slice('POTION_SYNC '.length).split(' ');
  const result = { command };
  for (const field of fields) {
    const [key, value] = field.split('=');
    assert.ok(key && value !== undefined && result[key] === undefined, 'Invalid or duplicate marker field');
    result[key] = value;
  }
  return result;
}

class PotionSyncContract {
  constructor() {
    this.connections = new Map();
    this.records = new Map();
    this.next = { owner: 0, watcher: 0 };
    this.done = new Set();
    this.reconnections = 0;
  }

  connect(role) {
    assert.ok(['owner', 'watcher'].includes(role));
    const previous = this.connections.get(role);
    if (previous) {
      assert.ok(previous.reconnect, 'Unplanned reconnect');
      this.reconnections++;
    }
    this.connections.set(role, { metadata: new Map(), effects: new Map(), packets: [], cursor: 0,
      spawned: new Set(), destroyed: new Set(), previousId: previous?.selfId, selfId: null, phase: null });
  }

  packet(role, type, data) {
    const state = this.connections.get(role);
    assert.ok(state, 'Packet before connection');
    if (type === 'login') state.selfId = data.entityId;
    if (type === 'spawn_entity') state.spawned.add(data.entityId);
    if (type === 'entity_destroy') for (const id of data.entityIds) {
      state.destroyed.add(id); state.metadata.delete(id); state.spawned.delete(id);
    }
    if (type === 'entity_metadata') {
      const values = state.metadata.get(data.entityId) || { flags: 0, particles: [], ambient: false,
        flagsType: 'byte', particlesType: 'particles', ambientType: 'boolean' };
      for (const entry of data.metadata) {
        if (entry.key === 0) { values.flagsType = entry.type; values.flags = entry.value; }
        if (entry.key === 10) { values.particlesType = entry.type; values.particles = entry.value; }
        if (entry.key === 11) { values.ambientType = entry.type; values.ambient = entry.value; }
      }
      state.metadata.set(data.entityId, values);
    }
    if (['entity_effect', 'remove_entity_effect'].includes(type)) {
      state.packets.push({ type, ...data });
      if (data.entityId === state.selfId) {
        if (type === 'entity_effect') state.effects.set(data.effectId, data);
        else state.effects.delete(data.effectId);
      }
    }
  }

  message(role, text) {
    const m = marker(text);
    const state = this.connections.get(role);
    if (m.command === 'DONE') {
      assert.equal(m.passed, 'true', 'Server sync probe failed');
      assert.equal(this.next[role], phases.length, 'Missing sync checkpoints');
      assert.equal(this.done.has(role), false, 'Duplicate completion');
      this.done.add(role);
      return this.done.size === 2 ? this.result() : null;
    }
    const phase = `${m.backend}:${m.stage}`;
    assert.equal(phase, phases[this.next[role]], 'Missing, duplicate, or reordered sync phase');
    if (m.command === 'BEGIN') {
      assert.equal(state.phase, null, 'Unclosed sync phase');
      state.phase = phase;
      if (m.stage === 'live') state.destroyed.clear();
    } else if (m.command === 'RECONNECT') {
      assert.equal(state.phase, phase, 'Reconnect outside phase');
      const expected = m.stage === 'watcher-rejoin' ? 'watcher' : 'owner';
      if (role === expected) state.reconnect = true;
      return { reconnect: expected };
    } else if (m.command === 'CHECK') {
      assert.ok(state.phase === phase || state.phase === null && state.previousId !== undefined,
        'Checkpoint without begin/reconnect');
      this.check(role, m);
      this.next[role]++;
      state.phase = null;
      state.reconnect = false;
      state.cursor = state.packets.length;
      return { acknowledge: phase };
    } else assert.fail(`Unexpected marker ${m.command}`);
    return null;
  }

  check(role, m) {
    const state = this.connections.get(role);
    const owner = Number(m.owner), cow = Number(m.cow);
    assert.ok(Number.isInteger(owner) && Number.isInteger(cow) && owner !== cow);
    const clear = ['cleared', 'clean-rejoin'].includes(m.stage);
    const hidden = ['hidden', 'cleared', 'clean-rejoin'].includes(m.stage);
    if (role === 'watcher' && ['owner-rejoin', 'clean-rejoin'].includes(m.stage)) {
      assert.notEqual(owner, state.ownerId, 'Observed player entity was not recreated');
      assert.ok(state.destroyed.has(state.ownerId), 'Old observed player was not removed');
    }
    state.ownerId = owner;
    for (const [id, entity] of [[owner, 'owner'], [cow, 'cow']]) {
      if (role === 'owner' && entity === 'owner') continue;
      assert.ok(state.spawned.has(id), `Untracked ${entity}`);
      const metadata = state.metadata.get(id) || { flags: 0, particles: [], ambient: false,
        flagsType: 'byte', particlesType: 'particles', ambientType: 'boolean' };
      assert.equal(metadata.flagsType, 'byte');
      assert.equal(metadata.particlesType, 'particles');
      assert.equal(metadata.ambientType, 'boolean');
      assert.equal((metadata.flags & 32) !== 0, !clear, `${entity} invisibility`);
      assert.deepEqual(metadata.particles, hidden ? [] : particles(m.stage !== 'changed'), `${entity} particle metadata`);
      const ambient = m.stage !== 'changed' && !(m.stage === 'clean-rejoin' && entity === 'owner');
      assert.equal(metadata.ambient, ambient, `${entity} ambient metadata`);
    }
    // Ordinary observers get metadata, not effect-duration packets for the watched entities.
    const watchedPackets = state.packets.filter(p => p.entityId === cow || role === 'watcher' && p.entityId === owner);
    assert.deepEqual(watchedPackets, [], 'Effect packets unexpectedly sent to ordinary observer');
    if (role === 'owner') {
      assert.equal(state.selfId, owner, 'Wrong recipient ID');
      const own = state.packets.slice(state.cursor).filter(p => p.entityId === owner);
      const add = (effectId, duration, amplifier, flags) => ({ type: 'entity_effect', entityId: owner,
        effectId, duration, amplifier, flags });
      const remove = effectId => ({ type: 'remove_entity_effect', entityId: owner, effectId });
      if (m.stage === 'live') assert.deepEqual(own, [add(hasteId, 1200, 2, 11), add(invisibleId, 1200, 0, 9)]);
      else if (m.stage === 'changed' || m.stage === 'hidden') assert.deepEqual(own,
        [remove(hasteId), add(hasteId, 1200, 1, m.stage === 'changed' ? 14 : 8)]);
      else if (m.stage === 'cleared') assert.deepEqual(own, [remove(hasteId), remove(invisibleId)]);
      else if (m.stage === 'owner-rejoin') {
        assert.notEqual(state.selfId, state.previousId, 'Player entity was not recreated');
        assert.equal(own.length, 2, 'Missing/duplicate login effects');
        for (const [effectId, field, amplifier, flags] of [[hasteId, 'haste', 2, 3], [invisibleId, 'invisible', 0, 1]]) {
          const matches = own.filter(p => p.effectId === effectId);
          assert.equal(matches.length, 1, 'Incorrect login effect IDs');
          const packet = matches[0];
          const duration = Number(m[field]);
          assert.ok(Number.isInteger(duration) && duration > 0 && duration < 1200);
          // CHECK is sent ten server ticks after join. A loaded effect is not recast.
          assert.ok(packet.duration - duration >= 9 && packet.duration - duration <= 11, 'Login duration not the remaining duration');
          assert.deepEqual(packet, add(effectId, packet.duration, amplifier, flags));
        }
      } else assert.deepEqual(own, [], 'Unexpected effect replay');
      assert.deepEqual([...state.effects.keys()].sort((a, b) => a - b), clear ? [] : [hasteId, invisibleId]);
      if (m.stage === 'clean-rejoin') assert.notEqual(state.selfId, state.previousId, 'Clean rejoin did not recreate player');
    }
    const normalized = { particles: hidden ? [] : particles(m.stage !== 'changed'), invisible: !clear,
      ambient: m.stage !== 'changed', ownerLevel: clear ? null : ['changed', 'hidden'].includes(m.stage) ? 1 : 2 };
    this.records.set(`${role}:${m.backend}:${m.stage}`, normalized);
  }

  result() {
    assert.equal(this.reconnections, 6, 'Missing reconnect sessions');
    for (const role of ['owner', 'watcher']) for (const stage of stages) {
      assert.deepEqual(this.records.get(`${role}:native:${stage}`), this.records.get(`${role}:imported:${stage}`));
    }
    return { comparisons: 14, checkpoints: this.records.size, reconnections: this.reconnections };
  }
}

module.exports = { PotionSyncContract, stages, phases, particles, serverAssertions, validateServerLog };
