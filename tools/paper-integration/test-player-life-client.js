'use strict';
const assert = require('node:assert/strict');
const {EventEmitter} = require('node:events');
const {readFileSync} = require('node:fs');
const {join} = require('node:path');
const {test} = require('node:test');
const vm = require('node:vm');

function harness() {
  const bots = [], events = [], timers = [];
  let time = 0;
  vm.runInNewContext(readFileSync(join(__dirname, 'player-life-client.js'), 'utf8'), {
    require(name) {
      assert.equal(name, 'mineflayer');
      return {createBot(options) {
        const bot = new EventEmitter();
        Object.assign(bot, {name:options.username, player:{uuid:options.username}, quits:0, respawns:0,
          quit() { this.quits++; }, respawn() { this.respawns++; }});
        bots.push(bot); return bot;
      }};
    },
    console:{log(line) { events.push(JSON.parse(line.split('PLAYER_LIFE_CLIENT ')[1])); }},
    performance:{now() { return time++; }},
    setTimeout(callback, delay) { timers.push({callback, delay}); return timers.length; },
    clearTimeout() {},
    process:{exit() { throw new Error('Unexpected client exit'); }}
  });
  return {bots, events, timers};
}

test('control before initial spawn waits for join, then reconnects directly from end', () => {
  const {bots, events, timers} = harness(), owner = bots[0];
  owner.emit('messagestr', 'PLAYER_LIFE_CONTROL reconnect respawn');
  owner.emit('messagestr', 'PLAYER_LIFE_CONTROL reconnect respawn');
  assert.equal(owner.quits, 0);
  owner.emit('spawn');
  assert.equal(owner.quits, 1);
  assert.deepEqual(events.filter(e => e.name === owner.name).map(e => e.event), ['connect','join','control']);
  const timerCount = timers.length;
  owner.emit('end');
  assert.equal(bots.length, 3);
  assert.equal(bots[2].name, owner.name);
  assert.equal(timers.length, timerCount, 'reconnect must not introduce a timer');
  assert.deepEqual(events.slice(-2).map(e => e.event), ['end','connect']);
});

test('post-spawn controls quit once and actual death still waits before respawn', () => {
  const {bots, events, timers} = harness(), owner = bots[0];
  owner.emit('spawn'); owner.emit('death');
  assert.equal(owner.respawns, 0);
  const respawnTimer = timers.find(t => t.delay === 4000);
  assert.ok(respawnTimer); respawnTimer.callback();
  assert.equal(owner.respawns, 1);
  owner.emit('spawn');
  owner.emit('messagestr', 'PLAYER_LIFE_CONTROL reconnect respawn');
  owner.emit('messagestr', 'PLAYER_LIFE_CONTROL done final');
  assert.equal(owner.quits, 1);
  assert.deepEqual(events.filter(e => e.name === owner.name).map(e => e.event),
    ['connect','join','death','respawn-request','respawned','control']);
});
