# Minecraft 1.21.1 生物渲染图

从 `mcmods_exporter` 0.9.4 开始，1.21.1 生物渲染图不再调用带主窗口 Scissor 裁剪的 `InventoryScreen.renderEntityInInventoryFollowsMouse`。

Minecraft 1.21.1 的该方法会按照游戏主窗口的尺寸换算裁剪区域，而导出器使用独立的 32、128 或 256 像素离屏帧缓冲。两者坐标空间不同会导致实体被完整裁掉，最终 PNG 文件存在但全部像素透明。

导出器现在直接调用无 Scissor 的底层实体渲染入口，并自行设置固定的斜向身体、头部和相机角度。输出仍位于：

```text
entities/renders/32/<namespace>/<path>.png
entities/renders/128/<namespace>/<path>.png
entities/renders/256/<namespace>/<path>.png
```

每张实体图写盘前都会检查 Alpha 通道。全透明图会抛出导出错误，不会再计入 `manifest.json` 中 `entity_renders.images` 的成功数量。

网站端应保留 PNG Alpha 通道，不要增加黑色底色。展示时可使用 CSS 棋盘格或网站主题背景，以便区分透明区域。
