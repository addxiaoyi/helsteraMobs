package dev.helstera.web;

/**
 * 内嵌网页开发器页面（vanilla JS，零外部依赖也能工作；
 * 若有网络则加载 three.js 做骨骼预览）。
 */
final class WebPage {

    private WebPage() {
    }

    static final String HTML = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
            <meta charset="utf-8">
            <title>helsteraMobs 网页开发器</title>
            <style>
              :root { --bg:#f5f7fa; --card:#fff; --line:#e2e6ea; --accent:#2b6cb0; --txt:#1a202c; }
              * { box-sizing:border-box; margin:0; padding:0; }
              body { font-family:"Segoe UI","Microsoft YaHei",sans-serif; background:var(--bg); color:var(--txt); }
              header { background:#1a365d; color:#fff; padding:14px 24px; display:flex; gap:16px; align-items:center; }
              header h1 { font-size:18px; font-weight:600; }
              header input { flex:0 0 260px; padding:6px 10px; border-radius:6px; border:none; font-size:13px; }
              main { display:grid; grid-template-columns:280px 1fr 380px; gap:14px; padding:14px; height:calc(100vh - 54px); }
              .card { background:var(--card); border:1px solid var(--line); border-radius:10px; padding:14px; overflow:auto; }
              .card h2 { font-size:14px; color:var(--accent); margin-bottom:10px; }
              ul { list-style:none; }
              li { padding:7px 10px; border-radius:6px; cursor:pointer; font-size:13px; }
              li:hover { background:#ebf4ff; }
              li.sel { background:#bee3f8; font-weight:600; }
              textarea { width:100%; height:calc(100% - 90px); font-family:Consolas,monospace; font-size:12.5px;
                         border:1px solid var(--line); border-radius:8px; padding:10px; resize:none; }
              .row { display:flex; gap:8px; margin-top:10px; flex-wrap:wrap; }
              button { padding:7px 14px; border:none; border-radius:6px; background:var(--accent); color:#fff;
                       cursor:pointer; font-size:13px; }
              button.sec { background:#718096; }
              button:hover { filter:brightness(1.1); }
              #out { white-space:pre-wrap; font-size:12px; background:#2d3748; color:#9ae6b4; border-radius:8px;
                     padding:10px; margin-top:10px; min-height:60px; max-height:200px; overflow:auto; display:none; }
              .meta { font-size:12px; color:#4a5568; line-height:1.7; }
              .badge { display:inline-block; background:#ebf8ff; color:#2b6cb0; border-radius:4px;
                       padding:1px 6px; margin:1px; font-size:11px; }
            </style>
            </head>
            <body>
            <header>
              <h1>helsteraMobs 开发器</h1>
              <input id="token" placeholder="访问令牌（见启动日志 /helstera web status）">
              <span id="status" style="font-size:12px;opacity:.8"></span>
            </header>
            <main>
              <div class="card">
                <h2>模型（/api/models）</h2>
                <ul id="models"></ul>
                <h2 style="margin-top:14px">生物配置（mobs/*.yml）</h2>
                <ul id="mobs"></ul>
                <button class="sec" onclick="loadMobs()">刷新生物列表</button>
              </div>
              <div class="card">
                <h2>YAML 编辑器（保存自动版本备份，可回滚）</h2>
                <input id="mobName" placeholder="文件名，如 golem.yml" style="width:100%;padding:7px;border:1px solid var(--line);border-radius:6px;margin-bottom:8px;">
                <textarea id="editor" spellcheck="false" placeholder="schema-version: 1&#10;id: example/golem&#10;mob:&#10;  entity-type: ARMOR_STAND&#10;  health: 40&#10;  ai-profile: default&#10;  model: example/crystal_golem"></textarea>
                <div class="row">
                  <button onclick="save()">保存</button>
                  <button class="sec" onclick="restore()">回滚到选中版本</button>
                  <select id="versions" style="flex:1;padding:6px;"></select>
                </div>
                <div id="out"></div>
              </div>
              <div class="card">
                <h2>模型详情 / 校验</h2>
                <div id="meta" class="meta">← 点击左侧模型查看骨骼/动画/挂接点</div>
                <div class="row">
                  <input id="valId" placeholder="模型目录名，如 example_crystal_golem" style="flex:1;padding:7px;border:1px solid var(--line);border-radius:6px;">
                  <button class="sec" onclick="validate()">校验</button>
                </div>
                <h2 style="margin-top:14px">资源包</h2>
                <p class="meta"><a href="/pack.zip" target="_blank">下载 /pack.zip</a> 手动安装，或由服务器 /helstera pack apply 自动下发。</p>
              </div>
            </main>
            <script>
            const $ = id => document.getElementById(id);
            const H = () => ({'Authorization':'Bearer '+$('token').value,'Content-Type':'application/json'});
            let selMob = null;
            async function api(path, opt) {
              const r = await fetch(path, Object.assign({headers:H()}, opt||{}));
              const t = await r.text();
              show((r.ok?'':'[HTTP '+r.status+'] ')+t);
              try { return JSON.parse(t); } catch { return t; }
            }
            function show(msg){ const o=$('out'); o.style.display='block'; o.textContent=msg; }
            async function loadModels(){
              const d = await api('/api/models'); const ul=$('models'); ul.innerHTML='';
              (d.models||[]).forEach(m=>{
                const li=document.createElement('li');
                li.innerHTML = m.id+' <span class="badge">'+m.bones+' 骨骼</span>';
                li.onclick=()=>{ document.querySelectorAll('#models li').forEach(x=>x.classList.remove('sel'));
                  li.classList.add('sel'); showMeta(m); };
                ul.appendChild(li);
              });
            }
            function showMeta(m){
              $('meta').innerHTML = '<b>'+m.name+'</b> ('+m.id+')<br>版本 '+m.version+' · 作者 '+m.author+
                '<br>缩放 ×'+m.scale+'<br>动画: '+(m.animations||[]).map(a=>'<span class="badge">'+a+'</span>').join('')+
                '<br>挂接点: '+m['attach-points']+' 处';
            }
            async function loadMobs(){
              const d = await api('/api/mobs'); const ul=$('mobs'); ul.innerHTML='';
              (Array.isArray(d)?d:d.mobs||[]).forEach(n=>{
                const li=document.createElement('li'); li.textContent=n;
                li.onclick=async()=>{ document.querySelectorAll('#mobs li').forEach(x=>x.classList.remove('sel'));
                  li.classList.add('sel'); selMob=n; $('mobName').value=n;
                  const f=await api('/api/mobs/file?name='+n); $('editor').value=f.content||''; loadVersions(); };
                ul.appendChild(li);
              });
            }
            async function save(){
              if(!$('mobName').value) return show('请填写文件名');
              await api('/api/mobs/save',{method:'POST',body:JSON.stringify({name:$('mobName').value,content:$('editor').value})});
              loadMobs();
            }
            async function loadVersions(){
              if(!$('mobName').value) return;
              const d=await api('/api/mobs/versions?name='+$('mobName').value);
              const s=$('versions'); s.innerHTML='';
              (d.versions||[]).forEach(v=>{ const o=document.createElement('option'); o.textContent=v; s.appendChild(o); });
            }
            async function restore(){
              if(!$('mobName').value||!$('versions').value) return show('请选择文件与版本');
              await api('/api/mobs/restore',{method:'POST',body:JSON.stringify({name:$('mobName').value,version:$('versions').value})});
              const f=await api('/api/mobs/file?name='+$('mobName').value); $('editor').value=f.content||'';
            }
            async function validate(){ await api('/api/validate',{method:'POST',body:JSON.stringify({model-id:$('valId').value})}); }
            $('token').addEventListener('change',()=>{ loadModels(); loadMobs(); });
            fetch('/api/status').then(r=>r.json()).then(d=>$('status').textContent='服务端 '+d.plugin+' · 模型 '+d.models+' · 实例 '+d.instances).catch(()=>{});
            </script>
            </body>
            </html>
            """;
}
