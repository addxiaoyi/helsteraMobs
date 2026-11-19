'use strict';

const fs = require('node:fs');
const assert = require('node:assert/strict');
const { PotionSyncContract, validateServerLog } = require('./potion-sync-contract');

function validateEvidence(serverLog, clientLog) {
  const server = validateServerLog(serverLog);
  const contract = new PotionSyncContract();
  let result;
  for (const line of clientLog.split(/\r?\n/)) {
    if (!line.startsWith('SYNC_EVENT ')) continue;
    const event = JSON.parse(line.slice('SYNC_EVENT '.length));
    if (event.type === 'connect') contract.connect(event.role);
    else if (event.type === 'packet') contract.packet(event.role, event.packetType, event.data);
    else if (event.type === 'message') result = contract.message(event.role, event.text) || result;
    else assert.fail('Unknown synchronization event');
  }
  assert.deepEqual(result, { comparisons: 14, checkpoints: 28, reconnections: 6 }, 'Incomplete client event replay');
  assert.equal(clientLog.split(/\r?\n/).filter(line => line ===
    'PAPER_POTION_SYNC_CLIENT passed=true comparisons=14 checkpoints=28 reconnections=6').length, 1);
  assert.equal(clientLog.includes('PAPER_POTION_SYNC_CLIENT passed=false'), false);
  return { serverAssertions: server.count, serverObservation: server.observation, ...result };
}

if (require.main === module) {
  const [serverPath, clientPath] = process.argv.slice(2);
  console.log(JSON.stringify(validateEvidence(fs.readFileSync(serverPath, 'utf8'), fs.readFileSync(clientPath, 'utf8'))));
}
module.exports = { validateEvidence };
