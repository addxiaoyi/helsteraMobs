# 前端独立工作区

这里可以脱离 Minecraft 服务端单独改前端。

## 目录

| 文件 | 作用 |
|---|---|
| `index.html` | **你的编辑副本**。改这个 |
| `serve.cjs` | 本地预览服务器，替换占位符 + 转发接口到真实服务端 |
| `sync-back.cjs` | 把改动写回插件正式源文件 |

## 用法

预览服务已在跑：**http://127.0.0.1:5173/**

改 `index.html` 后直接刷新浏览器即可（已关缓存），**不用** `mvn install`、不用重启 Paper。改完满意后同步回去：

```
node frontend/sync-back.cjs            写回正式源
node frontend/sync-back.cjs --check    只比对不写入
```

写回后需要 `mvn install` + 重启服务端才会真正生效。

预览服务没起时：

```
node frontend/serve.cjs        # 默认 5173
node frontend/serve.cjs 8080   # 换端口
```

## 两个文件的关系

- `frontend/index.html` — 你改的
- `helstera-web/src/main/resources/web/index.html` — 插件打包时真正读进 jar 的

**必须保持一致**，否则改了没生效。`sync-back.cjs` 就是干这个的。

## 注意事项

- `__TOKEN__` 和 `__VERSION__` 是占位符，正式源里保留原样，预览服务会在吐出 HTML 时替换成真实值。**别把真实令牌写死进文件。**
- 服务端没启动时接口会返回 502 并提示启动命令，页面能打开但拿不到数据。
- 令牌从 `srv-1.21.1/plugins/helsteraMobs/config.yml` 读取，不在代码里。
- `dist/` 目录留给编译产物，一般用不到。