'use strict';

const assert = require('node:assert/strict');
const { test } = require('node:test');
const { ProtoDefCompiler } = require('protodef').Compiler;
const data = require('minecraft-data')('1.21.1');
const patch = require('./paper-1.21.1-client-protocol');

test('pinned trim material consumes the float, registry ID and trailing component', () => {
  const compiler = new ProtoDefCompiler();
  compiler.addTypes(require('minecraft-protocol/src/datatypes/compiler-minecraft'));
  compiler.addTypesToCompile({ ...data.protocol.types, ...patch[data.version.majorVersion].types });
  require('prismarine-nbt').addTypesToCompiler('big', compiler);
  const protocol = compiler.compileProtoDefSync();
  // Independent wire sample: gold, item 42, model 0.5, one override (ID 3),
  // followed by the anonymous NBT string component "Gold".
  const bytes = Buffer.from('04676f6c642a3f0000000103087265736f6c766564080004476f6c64', 'hex');
  const decoded = protocol.parsePacketBuffer('ArmorTrimMaterial', bytes);
  assert.equal(decoded.metadata.size, bytes.length);
  assert.deepEqual(decoded.data, { assetName: 'gold', ingredientId: 42, itemModelIndex: 0.5,
    overrideArmorAssets: [{ key: 3, value: 'resolved' }],
    description: { type: 'string', value: 'Gold' } });
  assert.throws(() => protocol.parsePacketBuffer('ArmorTrimMaterial', bytes.subarray(0, -1)));
});

test('recipe ID 15 decodes the complete smelting payload', () => {
  const parser = require('minecraft-protocol').createDeserializer({
    state: 'play', version: '1.21.1', customPackets: patch });
  // One recipe: test, serializer 15, empty group, category 0, one ingredient
  // with an empty slot, empty output, experience 0.5 and 200 ticks.
  const bytes = Buffer.from('0104746573740f00000100003f000000c801', 'hex');
  const decoded = parser.proto.parsePacketBuffer('packet_declare_recipes', bytes);
  assert.equal(decoded.metadata.size, bytes.length);
  assert.equal(decoded.data.recipes[0].type, 'minecraft:smelting');
  assert.equal(decoded.data.recipes[0].data.experience, 0.5);
  assert.equal(decoded.data.recipes[0].data.cookTime, 200);
});
