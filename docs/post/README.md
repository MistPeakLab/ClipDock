# LINUX DO 帖子用的图

[LINUX DO 上的介绍帖](https://linux.do/) 里的图片直接引用这个仓库（经 jsDelivr），仓库里的图一更新，帖子就跟着变。

- `post.md`：帖子全文，粘进论坛编辑器即可
- `src.html`：文字图的源文件。社区规定 AI 生成 / 润色的介绍内容必须以截图发出，所以这些段落做成图片
- `build.py`：把 `src.html` 每一段渲染成 PNG（`update` / `open` / `multi` / `pick` / `transform` / `notes`）

**帖子引用了这些文件，不要改名或删除**：本目录的 6 张 PNG，`docs/social-preview.jpg`，以及 `docs/screenshots/` 里的
`shortcut-open.jpg`、`shortcut-multi.jpg`、`panel-pick.png`、`panel-transform.png`、`home.jpg`、`home-dark.jpg`、`dock.jpg`、`wheel.jpg`、`modes.jpg`、`settings.jpg`、`phone-home.jpg`、`phone-wheel.jpg`、`phone-panel.jpg`、`phone-detail-dark.jpg`。

## 发新版时

1. 改 `src.html` 里 `#update` 那一段（以及需要改的其他段），运行 `python docs/post/build.py`
2. 提交并推送
3. jsDelivr 会缓存 `@main` 约 12 小时，想立刻生效就刷新缓存：

```powershell
"docs/post/update.png","docs/post/open.png" | % { Invoke-RestMethod "https://purge.jsdelivr.net/gh/MistPeakLab/ClipDock@main/$_" }
```
