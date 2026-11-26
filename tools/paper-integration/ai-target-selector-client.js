'use strict';
const bot = require('mineflayer').createBot({host:'127.0.0.1',port:25731,username:'AiObserver',version:'1.21.1',auth:'offline',customPackets:require('./paper-1.21.1-client-protocol')});
bot.physicsEnabled=false;
let finished=false;
const timeout=setTimeout(()=>finish(new Error('AI target client timed out')),600000);
function finish(error) {
  if(finished)return;finished=true;clearTimeout(timeout);
  console.log('AI_TARGET_CLIENT '+JSON.stringify({event:'summary',passed:!error,error:error?.message||null}));
  try{bot.quit();}catch(_){}
  setTimeout(()=>process.exit(error?1:0),300);
}
bot.on('spawn',()=>console.log('AI_TARGET_CLIENT '+JSON.stringify({event:'spawn',username:bot.username})));
bot.on('messagestr',message=>{if(message==='AI_TARGET_DONE')finish();});
bot.on('error',error=>finish(error));bot.on('kicked',reason=>{if(!finished)finish(new Error(String(reason)));});
bot.on('end',()=>{if(!finished)finish(new Error('Unexpected disconnect'));});
