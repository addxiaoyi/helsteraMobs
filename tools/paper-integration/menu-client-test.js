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

const bot = mineflayer.createBot({ host, port, username: 'HelsteraTester', version: '1.21.1' });
const guest = mineflayer.createBot({ host, port, username: 'AudienceGuest', version: '1.21.1' });
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
  bot.chat('/helstera menu open parity-page-1');
  await waitFor(() => bot.currentWindow && titleText(bot.currentWindow).includes('Parity Page 1'), 'page one');
  const pageOne = bot.currentWindow;
  if (pageOne.slots[10]?.name !== 'redstone') throw new Error('Permission-gated button was not rendered');
  bot.chat('MENU_PAGE_ONE_READY');
  await waitFor(value => value?.toString?.().includes('MENU_PERMISSION_REVOKED'),
    'menu permission revocation');
  await bot.clickWindow(10, 0, 0);
  await new Promise(resolve => setTimeout(resolve, 500));
  const deniedStayedOpen = bot.currentWindow === pageOne;
  if (!deniedStayedOpen) throw new Error('Denied button unexpectedly closed the menu');
  bot.chat('MENU_PERMISSION_CHECK_COMPLETE');
  await waitFor(value => value?.toString?.().includes('MENU_PERMISSION_RESTORED'),
    'menu permission restoration');

  await bot.clickWindow(26, 0, 0);
  await waitFor(() => bot.currentWindow && titleText(bot.currentWindow).includes('Parity Page 2'), 'page two');
  const pageTwo = bot.currentWindow;
  if (pageTwo.slots[13]?.name !== 'emerald') throw new Error('Page two action was not rendered');
  await bot.clickWindow(13, 0, 0);
  await waitFor(() => bot.currentWindow == null, 'close after action');

  await new Promise(resolve => setTimeout(resolve, 500));
  const report = {
    status: 'passed',
    player: bot.username,
    observations: {
      permissionDeniedClickStayedOpen: deniedStayedOpen,
      pageOne: events.some(event => event.startsWith('open:') && event.includes('Parity Page 1')),
      pageTwo: events.some(event => event.startsWith('open:') && event.includes('Parity Page 2')),
      actionClosedInventory: events.some(event => event.startsWith('close:')),
      allowedActionMessage: events.some(event => event.includes('MENU_ALLOWED_ACTION_RAN')),
      deniedActionMessageAbsent: !events.some(event => event.includes('MENU_DENIED_ACTION_RAN'))
    },
    events
  };
  if (Object.values(report.observations).some(value => value !== true)) {
    throw new Error(`Menu integration observations incomplete: ${JSON.stringify(report)}`);
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
  if (reportPath) fs.appendFileSync(reportPath, `PAPER_MENU_ERROR ${error.stack || error.message}\n`, 'utf8');
  console.error(error.stack || error.message);
  process.exitCode = 1;
}).finally(() => {
  bot.quit('integration complete');
  guest.quit('integration complete');
});
