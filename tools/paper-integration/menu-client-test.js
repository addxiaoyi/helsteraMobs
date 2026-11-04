'use strict';

const mineflayer = require('mineflayer');
const fs = require('fs');

const [host = '127.0.0.1', rawPort = '25731', reportPath = '', audienceReportPath = ''] = process.argv.slice(2);
const port = Number(rawPort);
const timeoutMs = 30000;
const events = [];
let shieldTimer = null;
let spawningDistanceWalkReady = false;

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

function waitForWindowTitle(fragment, budgetMs = timeoutMs) {
  return new Promise((resolve, reject) => {
    const deadline = Date.now() + budgetMs;
    const tick = () => {
      const current = bot.currentWindow;
      if (current && titleText(current).includes(fragment)) return resolve(current);
      if (Date.now() >= deadline) return reject(new Error(`Timed out waiting for ${fragment}`));
      setTimeout(tick, 50);
    };
    tick();
  });
}

function waitForWindowClose() {
  return waitFor(() => bot.currentWindow == null, 'inventory close');
}

function packetText(value) {
  if (typeof value === 'string') return value;
  try { return JSON.stringify(value); } catch { return String(value); }
}

bot.on('message', message => {
  const text = message.toString();
  events.push(`chat:${text}`);
  if (text === 'PAPER_IMPORT_MESSAGE, radius=10; complete') {
    console.log('PAPER_IMPORTED_MESSAGE_RECEIVED exact=true');
  }
  if (text === 'PAPER_ESCAPE "quoted; radius=10" C:\\home } done') {
    console.log('PAPER_IMPORTED_ESCAPED_MESSAGE_RECEIVED exact=true');
  }
  if (text.includes('PAPER_SPAWNING_DISTANCE_WALK_READY')) {
    spawningDistanceWalkReady = true;
  }
});
bot.on('title', (message, type) => events.push(`title:${type}:${packetText(message)}`));
bot.on('actionBar', message => events.push(`actionbar:${packetText(message)}`));
bot.on('particle', particle => events.push(`particle:${particle.name || particle.id}`));
bot._client.on('systemChat', packet => events.push(`system:${packet.isActionBar ? 'actionbar' : 'chat'}:${packetText(packet.formattedMessage)}`));
bot._client.on('system_chat', packet => events.push(`raw-system:${packet.isActionBar ? 'actionbar' : 'chat'}:${packetText(packet.content)}`));
bot._client.on('window_items', packet => events.push(
  `window-items id=${packet.windowId} count=${packet.items?.length || 0} slot10=${packetText(packet.items?.[10])}`));
bot._client.on('set_slot', packet => events.push(
  `set-slot id=${packet.windowId} slot=${packet.slot} item=${packetText(packet.item)}`));
bot.on('windowOpen', window => events.push(`open:${titleText(window)}`));
bot.on('windowClose', window => events.push(`close:${titleText(window)}`));
guest.on('message', message => guestEvents.push(`chat:${message.toString()}`));
guest.on('error', error => guestEvents.push(`error:${error.message}`));

// Keep the real player in a client-driven Shield use window while the delayed
// server self-test evaluates Player#isBlocking.
bot.once('spawn', () => {
  setTimeout(async () => {
    try {
      const shield = bot.inventory.items().find(stack => stack.name === 'shield');
      if (!shield) return;
      await bot.equip(shield, 'hand');
      bot.activateItem();
      shieldTimer = setInterval(() => {
        if (!bot.ended) bot.activateItem();
      }, 250);
      process.stdout.write('PAPER_SHIELD_BLOCKING_ACTIVE\n');
    } catch (error) {
      process.stderr.write(`Shield workflow failed: ${error.stack || error.message}\n`);
    }
  }, 750);
});

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
  bot.chat('MENU_PAGE_ONE_READY');
  await waitFor(value => value?.toString?.().includes('MENU_PERMISSION_REVOKED'),
    'menu permission revocation');
  await bot.clickWindow(10, 0, 0);
  await new Promise(resolve => setTimeout(resolve, 500));
  if (!bot.currentWindow || !titleText(bot.currentWindow).includes('Parity Page 1')) {
    throw new Error('Denied menu click unexpectedly closed or changed the inventory');
  }
  bot.chat('MENU_PERMISSION_CHECK_COMPLETE');
  await waitFor(value => value?.toString?.().includes('MENU_PERMISSION_RESTORED'),
    'menu permission restoration');

  await bot.clickWindow(26, 0, 0);
  await waitForWindowTitle('Parity Page 2');
  await bot.clickWindow(13, 0, 0);
  await waitFor(value => value?.toString?.().includes('MENU_ALLOWED_ACTION_RAN'), 'allowed menu action');
  await waitForWindowClose();
  const report = {
    status: 'passed',
    player: bot.username,
    observations: {
      pageOne: true,
      deniedClickStayedOpen: true,
      paginationClick: true,
      pageTwo: true,
      allowedActionMessage: true,
      actionClosedInventory: true,
      deniedActionMessageAbsent: !events.some(event => event.includes('MENU_DENIED_ACTION_RAN'))
    },
    events
  };
  for (const field of ['pageOne', 'deniedClickStayedOpen',
    'paginationClick', 'pageTwo', 'allowedActionMessage',
    'actionClosedInventory', 'deniedActionMessageAbsent']) {
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
  if (process.env.PAPER_SPAWNING_DISTANCE_WALK_WORKFLOW === 'true' && bot.entity) {
    try {
      await waitFor(() => spawningDistanceWalkReady,
        'server spawning-distance probe readiness');
      const start = bot.entity.position.clone();
      bot.creative.startFlying();
      await bot.look(0, 0, true);
      bot.setControlState('forward', true);
      const deadline = Date.now() + 15000;
      while (!bot.ended && Date.now() < deadline &&
          bot.entity.position.distanceTo(start) <= 14) {
        await new Promise(resolve => setTimeout(resolve, 100));
      }
      bot.setControlState('forward', false);
      bot.creative.stopFlying();
      const displacement = bot.entity.position.distanceTo(start);
      const line = `PAPER_SPAWNING_DISTANCE_WALK displacement=${displacement.toFixed(2)}`;
      if (reportPath) fs.appendFileSync(reportPath, `${line}\n`, 'utf8');
      console.log(line);
      if (displacement <= 8) {
        if (reportPath) fs.appendFileSync(reportPath,
          'PAPER_SPAWNING_DISTANCE_WALK_ERROR client movement stayed inside radius\n', 'utf8');
        process.exitCode = 1;
      }
    } catch (error) {
      bot.setControlState('forward', false);
      bot.creative.stopFlying();
      if (reportPath) fs.appendFileSync(reportPath,
        `PAPER_SPAWNING_DISTANCE_WALK_ERROR ${error.message}\n`, 'utf8');
      console.error(error.stack || error.message);
      process.exitCode = 1;
    }
  }
  await new Promise(resolve => setTimeout(resolve, 30000));
  if (shieldTimer) clearInterval(shieldTimer);
  bot.quit('integration complete');
  guest.quit('integration complete');
});
