'use strict';

const mineflayer = require('mineflayer');
const fs = require('fs');

const [host = '127.0.0.1', rawPort = '25731', reportPath = '', audienceReportPath = ''] = process.argv.slice(2);
const port = Number(rawPort);
const timeoutMs = 30000;
const events = [];
let shieldTimer = null;
let spawningDistanceWalkReady = false;
let spawningDistanceWalkOutside = false;
let spawningDistanceWalkClient = null;
let spawningDistanceWalkTask = null;
let targeterArgumentValidationComplete = false;
let interactableProbeReady = null;
let interactableClientProbeComplete = process.env.PAPER_INTERACTABLE_WORKFLOW !== 'true';

if (!Number.isInteger(port) || port < 1 || port > 65535) {
  throw new Error(`Invalid Paper port: ${rawPort}`);
}

const version = process.env.PAPER_MINEFLAYER_VERSION || '1.21.1';
const bot = mineflayer.createBot({ host, port, username: 'HelsteraTester', version });
const guest = mineflayer.createBot({ host, port, username: 'AudienceGuest', version });
const guestEvents = [];

function waitFor(predicate, label, clients = bot) {
  const sources = Array.isArray(clients) ? clients : [clients];
  return new Promise((resolve, reject) => {
    const cleanup = () => {
      clearTimeout(timer);
      for (const client of sources) {
        client.removeListener('message', check);
        client.removeListener('windowOpen', check);
        client.removeListener('windowClose', check);
        client.removeListener('end', onEnd);
        client.removeListener('error', onError);
      }
    };
    const check = value => {
      if (!predicate(value)) return;
      cleanup();
      resolve(value);
    };
    const onEnd = reason => {
      if (sources.some(client => !client.ended)) return;
      cleanup();
      reject(new Error(`Disconnected while waiting for ${label}: ${reason}`));
    };
    const onError = error => {
      cleanup();
      reject(new Error(`Client error while waiting for ${label}: ${error.message}`));
    };
    const timer = setTimeout(() => {
      cleanup();
      reject(new Error(`Timed out waiting for ${label}`));
    }, timeoutMs);
    for (const client of sources) {
      client.on('message', check);
      client.on('windowOpen', check);
      client.on('windowClose', check);
      client.on('end', onEnd);
      client.on('error', onError);
    }
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

function observeProbeReadiness(client, text) {
  if (text.includes('PAPER_SPAWNING_DISTANCE_WALK_READY')) {
    spawningDistanceWalkReady = true;
    spawningDistanceWalkClient = client;
  }
  if (text.includes('PAPER_SPAWNING_DISTANCE_WALK_OUTSIDE')) {
    spawningDistanceWalkOutside = true;
  }
  if (text.includes('PAPER_TARGETER_ARGUMENT_VALIDATION_COMPLETE')) {
    targeterArgumentValidationComplete = true;
  }
  const interactableReady = text.match(/PAPER_INTERACTABLE_READY (\d+) (\d+)/);
  if (interactableReady) {
    interactableProbeReady = {
      blockedId: Number(interactableReady[1]),
      allowedId: Number(interactableReady[2]),
      client
    };
  }
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
  observeProbeReadiness(bot, text);
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
guest.on('message', message => {
  const text = message.toString();
  guestEvents.push(`chat:${text}`);
  observeProbeReadiness(guest, text);
});
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

async function runInteractableClientProbe() {
  await waitFor(() => interactableProbeReady, 'Interactable client probe readiness', [bot, guest]);
  const { blockedId, allowedId, client } = interactableProbeReady;
  if (blockedId === allowedId) throw new Error('Interactable probe reused an entity id');

  const interactWithOffhand = async (entityId, label) => {
    await waitFor(() => client.entities[entityId], `${label} entity spawn`, client);
    client._client.write('use_entity', {
      target: entityId,
      mouse: 0,
      hand: 1,
      sneaking: false
    });
    const observation = await waitFor(value => {
      const text = value?.toString?.() || '';
      return text.includes(`PAPER_INTERACTABLE_${label}_OBSERVED cancelled=`) ? text : false;
    }, `${label} interaction result`, client);
    const observedText = observation?.toString?.() || String(observation);
    const cancellation = observedText.match(/cancelled=(true|false)$/);
    if (!cancellation) {
      throw new Error(`Unexpected ${label} interaction observation: ${observedText}`);
    }
    return cancellation[1] === 'true';
  };

  const blockedCancelled = await interactWithOffhand(blockedId, 'BLOCKED');
  const allowedCancelled = await interactWithOffhand(allowedId, 'ALLOWED');
  const report = {
    status: blockedCancelled && !allowedCancelled ? 'passed' : 'failed',
    blockedEntityId: blockedId,
    allowedEntityId: allowedId,
    observations: {
      distinctEntities: true,
      blockedInteractionSent: true,
      blockedEventObserved: true,
      blockedCancelled,
      allowedInteractionSent: true,
      allowedEventObserved: true,
      allowedCancelled
    }
  };
  const line = `PAPER_INTERACTABLE_CLIENT_REPORT ${JSON.stringify(report)}\n`;
  if (reportPath) fs.appendFileSync(reportPath, line, 'utf8');
  console.log(line.trim());
  interactableClientProbeComplete = true;
  if (report.status !== 'passed') {
    throw new Error(`Interactable client observations failed: ${JSON.stringify(report)}`);
  }
}

async function runSpawningDistanceWalk(client) {
  try {
    await waitFor(() => client.entity != null, 'spawning-distance client spawn', client);
    const start = client.entity.position.clone();
    client.creative.startFlying();
    await client.look(0, 0, true);
    client.setControlState('forward', true);
    const deadline = Date.now() + 10000;
    while (!client.ended && !spawningDistanceWalkOutside && Date.now() < deadline &&
        client.entity.position.distanceTo(start) <= 20) {
      await new Promise(resolve => setTimeout(resolve, 50));
    }
    client.setControlState('forward', false);
    client.creative.stopFlying();
    const displacement = client.entity.position.distanceTo(start);
    const line = `PAPER_SPAWNING_DISTANCE_WALK displacement=${displacement.toFixed(2)} ` +
      `outside-signal=${spawningDistanceWalkOutside}`;
    if (reportPath) fs.appendFileSync(reportPath, `${line}\n`, 'utf8');
    console.log(line);
    if (displacement <= 8) {
      if (reportPath) fs.appendFileSync(reportPath,
        'PAPER_SPAWNING_DISTANCE_WALK_ERROR client movement stayed inside radius\n', 'utf8');
      process.exitCode = 1;
    }
  } catch (error) {
    client.setControlState('forward', false);
    client.creative.stopFlying();
    if (reportPath) fs.appendFileSync(reportPath,
      `PAPER_SPAWNING_DISTANCE_WALK_ERROR ${error.message}\n`, 'utf8');
    console.error(error.stack || error.message);
    process.exitCode = 1;
  }
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

  if (bot.currentWindow) {
    await bot.closeWindow(bot.currentWindow);
    await waitForWindowClose();
  }
  bot.chat('/helstera menu open parity-page-1');
  await waitForWindowTitle('Parity Page 1');
  await bot.clickWindow(26, 0, 0);
  await waitForWindowTitle('Parity Page 2');
  await bot.clickWindow(13, 0, 0);
  await waitFor(
    value => value?.toString?.().includes('MENU_ALLOWED_ACTION_RAN'),
    'allowed menu action',
    [bot, guest]
  );
  await waitForWindowClose();
  const actionMessageRecipient = events.some(event => event.includes('MENU_ALLOWED_ACTION_RAN'))
    ? bot.username
    : guestEvents.some(event => event.includes('MENU_ALLOWED_ACTION_RAN')) ? guest.username : null;
  const report = {
    status: 'passed',
    player: bot.username,
    actionMessageRecipient,
    observations: {
      pageOne: true,
      deniedClickStayedOpen: true,
      paginationClick: true,
      pageTwo: true,
      allowedActionMessage: true,
      actionClosedInventory: true,
      deniedActionMessageAbsent: !events.concat(guestEvents)
        .some(event => event.includes('MENU_DENIED_ACTION_RAN'))
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
  if (reportPath) fs.appendFileSync(reportPath, line, 'utf8');
  console.log(line.trim());

  // Run the high-volume client probes after the menu protocol has completed.
  // Mineflayer's 1.21.1 component decoder can lose framing while entity and
  // inventory packets arrive together; the menu assertions need a stable
  // window sequence before those probes start sending movement/click traffic.
  if (process.env.PAPER_INTERACTABLE_WORKFLOW === 'true') {
    await runInteractableClientProbe();
  }
  if (process.env.PAPER_SPAWNING_DISTANCE_WALK_WORKFLOW === 'true') {
    await waitFor(() => spawningDistanceWalkReady
      && targeterArgumentValidationComplete
      && interactableClientProbeComplete,
    'spawning-distance walk readiness and self-test completion', [bot, guest]);
    spawningDistanceWalkTask = runSpawningDistanceWalk(spawningDistanceWalkClient || bot);
  }

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
        permissionNegativeTesterOmitted: !hasEvent(events, 'AUDIENCE_PERMISSION_NEGATIVE'),
        permissionNegativeGuestIncluded: hasEvent(guestEvents, 'AUDIENCE_PERMISSION_NEGATIVE'),
        factionIncludedTester: hasEvent(events, 'AUDIENCE_FACTION_INCLUDED'),
        factionIncludedGuest: hasEvent(guestEvents, 'AUDIENCE_FACTION_INCLUDED'),
        factionNegativeTesterIncluded: hasEvent(events, 'AUDIENCE_FACTION_NEGATIVE'),
        factionNegativeGuestExcluded: !hasEvent(guestEvents, 'AUDIENCE_FACTION_NEGATIVE')
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
  if (process.env.PAPER_SPAWNING_DISTANCE_WALK_WORKFLOW === 'true' && (bot.entity || guest.entity)) {
    if (!spawningDistanceWalkTask) {
      await waitFor(() => spawningDistanceWalkReady
        && targeterArgumentValidationComplete
        && interactableClientProbeComplete,
      'spawning-distance walk readiness and self-test completion', [bot, guest]);
      spawningDistanceWalkTask = runSpawningDistanceWalk(spawningDistanceWalkClient || bot);
    }
    await spawningDistanceWalkTask;
  }
  await new Promise(resolve => setTimeout(resolve, 30000));
  if (shieldTimer) clearInterval(shieldTimer);
  bot.quit('integration complete');
  guest.quit('integration complete');
});
