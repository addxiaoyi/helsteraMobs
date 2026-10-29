'use strict';

/**
 * 独立菜单探针：只做「开一个菜单、把窗口内容打出来」，不跑完整工作流。
 *
 * <p>存在的理由：完整菜单工作流失败时只能看到一句
 * "Permission-gated button was not rendered"，无法区分是窗口没开、
 * 窗口开了但格子空、还是格子索引对不上。这个脚本把窗口本身摊开打印，
 * 用于定位这一类问题，不参与通过/失败判定。</p>
 *
 * <p>用法：node menu-probe.js [host] [port] [menuId]</p>
 */

const mineflayer = require('mineflayer');

const [host = '127.0.0.1', rawPort = '25731', menuId = 'parity-page-1',
  username = process.env.PAPER_PROBE_USER || 'HelsteraTester'] = process.argv.slice(2);
const port = Number(rawPort);

const bot = mineflayer.createBot({
  host,
  port,
  username,
  version: process.env.PAPER_MINEFLAYER_VERSION || '1.21.1'
});

bot.on('windowOpen', window => {
  console.log(`PROBE windowOpen id=${window.id} type=${window.type}`);
});

bot.on('windowClose', window => {
  console.log(`PROBE windowClose id=${window?.id} title=${JSON.stringify(window?.title ?? null)}`);
});

bot.on('error', error => console.log(`PROBE error ${error.message}`));

bot.once('spawn', async () => {
  console.log('PROBE spawned');
  await new Promise(resolve => setTimeout(resolve, 1500));
  console.log(`PROBE chat /helstera menu open ${menuId}`);
  bot.chat(`/helstera menu open ${menuId}`);
  await new Promise(resolve => setTimeout(resolve, 3000));

  const window = bot.currentWindow;
  if (!window) {
    console.log('PROBE no-window');
  } else {
    console.log(`PROBE window id=${window.id} slots=${window.slots.length}`);
    console.log(`PROBE title=${JSON.stringify(window.title)}`);
    window.slots.forEach((slot, index) => {
      if (slot) console.log(`PROBE slot[${index}] name=${slot.name} count=${slot.count}`);
    });
  }
  bot.quit('probe done');
  process.exit(0);
});

setTimeout(() => {
  console.log('PROBE overall-timeout');
  process.exit(2);
}, 60000);
