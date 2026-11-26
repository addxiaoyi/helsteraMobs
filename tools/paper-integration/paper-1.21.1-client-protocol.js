'use strict';

// Paper 1.21.1 TrimMaterial.DIRECT_STREAM_CODEC contains itemModelIndex and
// registry IDs for overrideArmorMaterials. minecraft-data's 1.21.1 definition
// currently omits the float and reads those IDs as strings. Keep the correction
// local to this pinned client; every packet must still decode completely.
const recipes = structuredClone(require('minecraft-data')('1.21.1').protocol.play.toClient.types.packet_declare_recipes);
// RecipeSerializer's pinned registry has no crafting_special_banneraddpattern.
// Its stale entry shifts every serializer from shield decoration onward.
const recipeFields = recipes[1][0].type[1].type[1];
recipeFields.find(field => field.name === 'type').type = 'Paper1211RecipeType';
const recipeNames = ['crafting_shaped', 'crafting_shapeless', 'crafting_special_armordye',
  'crafting_special_bookcloning', 'crafting_special_mapcloning', 'crafting_special_mapextending',
  'crafting_special_firework_rocket', 'crafting_special_firework_star', 'crafting_special_firework_star_fade',
  'crafting_special_tippedarrow', 'crafting_special_bannerduplicate', 'crafting_special_shielddecoration',
  'crafting_special_shulkerboxcoloring', 'crafting_special_suspiciousstew', 'crafting_special_repairitem',
  'smelting', 'blasting', 'smoking', 'campfire_cooking', 'stonecutting', 'smithing_transform',
  'smithing_trim', 'crafting_decorated_pot'];

module.exports = {
  '1.21': { types: {
    Paper1211RecipeType: ['mapper', { type: 'varint',
      mappings: Object.fromEntries(recipeNames.map((name, index) => [index, 'minecraft:' + name])) }],
    ArmorTrimMaterial: ['container', [
    { name: 'assetName', type: 'string' },
    { name: 'ingredientId', type: 'varint' },
    { name: 'itemModelIndex', type: 'f32' },
    { name: 'overrideArmorAssets', type: ['array', { countType: 'varint',
      type: ['container', [
        { name: 'key', type: 'varint' },
        { name: 'value', type: 'string' }
      ]] }] },
    { name: 'description', type: 'anonymousNbt' }
  ]] }, play: { toClient: { types: { packet_declare_recipes: recipes } } } }
};
