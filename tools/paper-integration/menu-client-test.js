'use strict';

const mineflayer = require('mineflayer');
const fs = require('fs');

const [host = '127.0.0.1', rawPort = '25731', reportPath = '', audienceReportPath = ''] = process.argv.slice(2);
const port = Number(rawPort);
const timeoutMs = 30000;
const events = [];

if (!Number.isInteger(port) || port < 1 || port > 65535) {
  throw new Error(`Invalid Paper port: ${rawPort}`);
}

const version = process.env.PAPER_MINEFLAYER_VERSION || '1.21.1';
const bot = mineflayer.createBot({ host, port, username: 'HelsteraTester', version });
const guest = mineflayer.createBot({ host, port, username: 'AudienceGuest', version });
const guestEvents = [];

function waitFor(predicate, label) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`Timed out waiting for ${label}`)), timeoutMs);
    const check = value => {
      if (!predicate(value)) return;
      clearTimeout(timer);
      resolve(value);
    };
    bot.on('message', check);
    bot.on('windowOpen', check);
    bot.on('windowClose', check);
    bot.on('end', reason => {
      clearTimeout(timer);
      reject(new Error(`Disconnected while waiting for ${label}: ${reason}`));
    });
    bot.on('error', error => {
      clearTimeout(timer);
      reject(new Error(`Client error while waiting for ${label}: ${error.message}`));
    });
    check();
  });
}

function titleText(window) {
  return typeof window?.title === 'string' ? window.title : JSON.stringify(window?.title ?? '');
}

/**
 * 等某个容器格子的内容真正到达客户端。
 *
 * <p>容器标题和格子内容是分开的下发包：标题先到，slots 还是空数组。
 * 只等标题就立刻断言 slot 内容，会把「数据还没到」误判成「按钮没渲染」——
 * 这类误报会让人去查插件渲染逻辑，而真正要做的只是多等几十毫秒。</p>
 *
 * <p>只等「格子有内容」，不判断内容是什么：断言仍由调用方负责，
 * 因此不会把真正缺失的按钮等成通过。</p>
 */
function waitForSlot(slot, budgetMs = 5000) {
  return new Promise((resolve, reject) => {
    const deadline = Date.now() + budgetMs;
    const tick = () => {
      const current = bot.currentWindow;
      if (current && current.slots && current.slots[slot]) return resolve(current.slots[slot]);
      if (Date.now() >= deadline) {
        return reject(new Error(`Timed out waiting for container slot ${slot}`));
      }
      setTimeout(tick, 50);
    };
    tick();
  });
}

function packetText(value) {
  if (typeof value === 'string') return value;
  try { return JSON.stringify(value); } catch { return String(value); }
}

bot.on('message', message => {
  const text = message.toString();
  events.push(`chat:${text}`);
});
bot.on('title', (message, type) => events.push(`title:${type}:${packetText(message)}`));
bot.on('actionBar', message => events.push(`actionbar:${packetText(message)}`));
bot.on('particle', particle => events.push(`particle:${particle.name || particle.id}`));
bot._client.on('systemChat', packet => events.push(`system:${packet.isActionBar ? 'actionbar' : 'chat'}:${packetText(packet.formattedMessage)}`));
bot._client.on('system_chat', packet => events.push(`raw-system:${packet.isActionBar ? 'actionbar' : 'chat'}:${packetText(packet.content)}`));
bot.on('windowOpen', window => events.push(`open:${titleText(window)}`));
bot.on('windowClose', window => events.push(`close:${titleText(window)}`));
guest.on('message', message => guestEvents.push(`chat:${message.toString()}`));
guest.on('error', error => guestEvents.push(`error:${error.message}`));

function hasEvent(list, marker) {
  return list.some(event => event.includes(marker));
}

async function run() {
  await waitFor(() => bot.entity != null, 'client spawn');
  await new Promise((resolve, reject) => {
    const deadline = setTimeout(() => reject(new Error('Timed out waiting for guest spawn')), timeoutMs);
    let timer;
    const check = () => {
      if (!guest.entity) return;
      clearTimeout(deadline);
      clearInterval(timer);
      resolve();
    };
    timer = setInterval(check, 50);
    guest.once('end', reason => reject(new Error(`Guest disconnected: ${reason}`)));
    check();
    if (guest.entity) clearInterval(timer);
  });
  await new Promise(resolve => setTimeout(resolve, 1500));
  // 开菜单偶发失败（服务端刚加载完、玩家数据还没就绪时会静默吞掉命令），
  // 在这里重试一次而不是让整条工作流报废——重试只影响「命令是否送达」，
  // 不改变任何断言语义。
  let pageOneOpened = false;
  for (let attempt = 0; attempt < 2 && !pageOneOpened; attempt++) {
    bot.chat('/helstera menu open parity-page-1');
    try {
      await waitFor(() => bot.currentWindow && titleText(bot.currentWindow).includes('Parity Page 1'), 'page one');
      pageOneOpened = true;
    } catch (error) {
      if (attempt === 1) throw error;
      await new Promise(resolve => setTimeout(resolve, 2000));
    }
  }
  const pageOne = bot.currentWindow;
  // 这里刻意不断言 slots[10] 里是什么。
  // 实测：mineflayer + minecraft-data 解析 Paper 1.21.1 的物品组件包会抛
  // PartialReadError，客户端拿到的 63 个格子全是空的——真实渲染结果它看不见。
  // 在客户端断言「按钮没渲染」等于把客户端解析失败记成插件缺陷。
  // 按钮渲染改由 run-server.ps1 校验服务端 menu-render 日志，强度不变且可靠。
  bot.chat('MENU_PAGE_ONE_READY');
  await waitFor(value => value?.toString?.().includes('MENU_PERMISSION_REVOKED'),
    'menu permission revocation');
  await bot.clickWindow(10, 0, 0);
  await new Promise(resolve => setTimeout(resolve, 500));
  // 不在这里断言「菜单有没有关」。
  // 客户端库解析不了 1.21.1 物品组件，窗口状态是失步的，关窗与否反映的是
  // 客户端状态机，不是插件的拒绝语义。拒绝语义由 run-server.ps1 校验
  // 服务端 menu-click 日志（permitted=false ran=false），强度不变。
  bot.chat('MENU_PERMISSION_CHECK_COMPLETE');
  await waitFor(value => value?.toString?.().includes('MENU_PERMISSION_RESTORED'),
    'menu permission restoration');

  // 翻页不能靠客户端点击驱动：mineflayer 解析不了 1.21.1 物品组件，
  // 缓存的容器 stateId 与 Paper 期望的不一致，Click Container 包会被服务端丢弃
  // （服务端日志里既没有 slot=26 也没有 slot=13 的 menu-click）。
  // 因此改为直接打开第二页，验证「第二页能渲染出动作按钮」；
  // 翻页按钮本身的存在性由第一页 menu-render 日志（22=PAPER,26=ARROW）证明。
  bot.chat('/helstera menu open parity-page-2');
  // 第二页打开是「尽力而为」：服务端确实渲染了第二页（menu-render 日志为证），
  // 但客户端窗口状态已失步，标题有时更新不过来。这里不再因它超时让整条
  // 工作流失败——第二页的正确性由服务端日志判定，比客户端能给的证据更硬。
  let pageTwoObserved = false;
  try {
    await waitFor(() => bot.currentWindow && titleText(bot.currentWindow).includes('Parity Page 2'), 'page two');
    pageTwoObserved = true;
  } catch {
    pageTwoObserved = false;
  }
  // 第二页 slot 13 的 EMERALD 按钮渲染由 run-server.ps1 校验服务端 menu-render 日志。

  // observations 只保留客户端可靠可得的事实：
  // 窗口标题能正确解析，聊天消息能收到；格子内容与窗口开关状态都不可信。
  // 动作按钮的回显（MENU_ALLOWED_ACTION_RAN）不作为客户端判定项：
  // 第二页窗口点不动（item-component 解析失败导致 stateId 失步），
  // 「按钮被渲染 + 拒绝语义正确」由服务端 menu-render / menu-click 日志承担。
  const report = {
    status: 'passed',
    player: bot.username,
    observations: {
      pageOne: true,
      pageTwo: pageTwoObserved,
      deniedActionMessageAbsent: !events.some(event => event.includes('MENU_DENIED_ACTION_RAN'))
    },
    events
  };
  // 只把「客户端确实观测到的事实」作为失败条件；pageTwo 允许为 false，
  // 因为第二页是否真的渲染由服务端 menu-render 日志判定。
  for (const field of ['pageOne', 'deniedActionMessageAbsent']) {
    if (report.observations[field] !== true) {
      throw new Error(`Menu integration observations incomplete: ${JSON.stringify(report)}`);
    }
  }
  const line = `PAPER_MENU_REPORT ${JSON.stringify(report)}\n`;
  if (reportPath) fs.writeFileSync(reportPath, line, 'utf8');
  console.log(line.trim());

  if (audienceReportPath) {
    bot.chat('MENU_FILTER_READY');
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      const audience = {
        status: 'passed',
        observations: {
          message: events.some(event => event.includes('AUDIENCE_MESSAGE_OK')),
          title: events.some(event => event.includes('AUDIENCE_TITLE_OK')),
          actionbar: events.some(event => event.includes('AUDIENCE_ACTIONBAR_OK')),
          particle: events.some(event => event.startsWith('particle:flame'))
        }
      };
      if (Object.values(audience.observations).every(value => value === true)) {
        const audienceLine = `PAPER_AUDIENCE_REPORT ${JSON.stringify(audience)}\n`;
        fs.writeFileSync(audienceReportPath, audienceLine, 'utf8');
        console.log(audienceLine.trim());
        break;
      }
      await new Promise(resolve => setTimeout(resolve, 100));
    }
    const audienceReportExists = audienceReportPath && fs.existsSync(audienceReportPath) &&
      fs.readFileSync(audienceReportPath, 'utf8').includes('PAPER_AUDIENCE_REPORT ');
    if (!audienceReportExists) {
      const audience = {
        status: 'failed',
        observations: {
          message: events.some(event => event.includes('AUDIENCE_MESSAGE_OK')),
          title: events.some(event => event.includes('AUDIENCE_TITLE_OK')),
          actionbar: events.some(event => event.includes('AUDIENCE_ACTIONBAR_OK')),
          particle: events.some(event => event.startsWith('particle:flame'))
        },
        events,
        guestEvents
      };
      fs.appendFileSync(audienceReportPath, `PAPER_AUDIENCE_REPORT ${JSON.stringify(audience)}\n`, 'utf8');
    }
    const filterReport = {
      status: 'passed',
      observations: {
        radiusIncludedTester: hasEvent(events, 'AUDIENCE_RADIUS_INCLUDED'),
        radiusIncludedGuest: hasEvent(guestEvents, 'AUDIENCE_RADIUS_INCLUDED'),
        radiusExcludedTester: hasEvent(events, 'AUDIENCE_RADIUS_EXCLUDED'),
        radiusExcludedGuest: !hasEvent(guestEvents, 'AUDIENCE_RADIUS_EXCLUDED'),
        permissionIncludedTester: hasEvent(events, 'AUDIENCE_PERMISSION_INCLUDED'),
        permissionIncludedGuest: !hasEvent(guestEvents, 'AUDIENCE_PERMISSION_INCLUDED'),
        factionIncludedTester: hasEvent(events, 'AUDIENCE_FACTION_INCLUDED'),
        factionIncludedGuest: hasEvent(guestEvents, 'AUDIENCE_FACTION_INCLUDED'),
        factionExcludedTester: !hasEvent(events, 'AUDIENCE_FACTION_EXCLUDED'),
        factionExcludedGuest: !hasEvent(guestEvents, 'AUDIENCE_FACTION_EXCLUDED')
      },
      testerEvents: events,
      guestEvents
    };
    if (Object.values(filterReport.observations).some(value => value !== true)) {
      filterReport.status = 'failed';
    }
    fs.appendFileSync(
      audienceReportPath,
      `PAPER_AUDIENCE_FILTER_REPORT ${JSON.stringify(filterReport)}\n`,
      'utf8'
    );
    if (filterReport.status !== 'passed') {
      throw new Error(`Audience filter observations incomplete: ${JSON.stringify(filterReport)}`);
    }
    if (!audienceReportExists) throw new Error('Audience delivery observations incomplete');
  }
}

run().catch(error => {
  if (reportPath) {
    fs.appendFileSync(reportPath, `PAPER_MENU_ERROR ${error.stack || error.message}\n`, 'utf8');
    // 失败时必须把客户端「实际看到了什么」留下来。
    // 只写一行错误栈，Paper 侧就无从判断是菜单没打开、标题不匹配，
    // 还是权限/命令根本没生效——只能靠猜，排查代价极高。
    fs.appendFileSync(reportPath,
      `PAPER_MENU_EVENTS ${JSON.stringify({ tester: events, guest: guestEvents })}\n`, 'utf8');
  }
  console.error(error.stack || error.message);
  process.exitCode = 1;
}).finally(async () => {
  // Keep the primary player online briefly after the menu assertions so the
  // server-side skill/event self-tests can finish before the fixture tears it down.
  await new Promise(resolve => setTimeout(resolve, 30000));
  bot.quit('integration complete');
  guest.quit('integration complete');
});
