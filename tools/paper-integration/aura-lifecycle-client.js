'use strict';
const mineflayer = require('mineflayer');
const bot = mineflayer.createBot({host:'127.0.0.1', port:25731, username:'AuraPlayer', version:'1.21.1', auth:'offline'});
let quitting = false, completed = false, joined = false;
const bars = new Map();
const timer = setTimeout(() => fail(new Error('Aura lifecycle probe timeout')), 180000);
function emit(value) { console.log(`AURALIFE_CLIENT ${JSON.stringify(value)}`); }
function fail(error) {
  if (completed) return;
  completed=true; clearTimeout(timer); emit({event:'summary', passed:false, error:String(error.message || error)});
  bot.quit(); setTimeout(() => process.exit(1), 300);
}
function plain(value) {
  if (typeof value === 'string') { try { return plain(JSON.parse(value)); } catch { return value; } }
  if (!value || typeof value !== 'object') return '';
  // Protocol 1.21 uses anonymous NBT components; Mineflayer's chat parser accepts both forms.
  try { const Chat = require('prismarine-chat')('1.21.1'); return Chat.fromNotch(value).toString(); } catch {}
  return String(value.text || '');
}
bot.on('spawn', () => {
  bot.physicsEnabled=false;
  if (!joined) { joined=true; emit({event:'join', name:'AuraPlayer', uuid:bot.player.uuid}); }
});
bot._client.on('boss_bar', packet => {
  const id=packet.entityUUID;
  if (packet.action === 0) {
    const title=plain(packet.title);
    if (!title.startsWith('AuraAudit_')) return;
    bars.set(id,title);
    emit({event:'bar_add', id, title, health:packet.health, color:packet.color, dividers:packet.dividers});
  } else if (bars.has(id) && packet.action === 1) {
    emit({event:'bar_remove', id, title:bars.get(id)}); bars.delete(id);
  } else if (bars.has(id) && packet.action === 2) {
    emit({event:'bar_progress', id, health:packet.health});
  }
});
bot.on('messagestr', message => {
  if (message !== 'AURA_LIFECYCLE_QUIT') return;
  if (bars.size) { fail(new Error('BossBars leaked before quit')); return; }
  quitting=true; emit({event:'quit_requested', activeBars:bars.size}); bot.quit();
});
bot.on('error', fail);
bot.on('kicked', reason => { if (!quitting) fail(new Error('Unexpected kick: '+String(reason))); });
bot.on('end', () => {
  if (!quitting) { fail(new Error('Unexpected disconnect')); return; }
  if (completed) return;
  completed=true; clearTimeout(timer); emit({event:'summary', passed:true}); setTimeout(() => process.exit(0),300);
});
