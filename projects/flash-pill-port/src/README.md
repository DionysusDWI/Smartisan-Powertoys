# FlashPill（M3/M5/M6 · 功能补全版）

闪念胶囊复刻 —— 当前阶段：功能补全（搜索 / 筛选 / 完成、分享 / 日历 / 提醒、小米保活引导）。

## 已实现（全量）

| 模块 | 内容 |
|---|---|
| 侧边栏把手 | 按住录音、短按打开列表、拖动换位；录音时变红 |
| 录音气泡 | 实时波形 + 计时 |
| 数据层 | Room（`Capsule`，含 `done` / `pinned`） |
| 捕获服务 | 前台服务：录音 → 落库 → 异步转写 |
| 列表 | 搜索（文字模糊）+ 色标筛选 + 完成勾选（删除线）+ 置顶排序 |
| 详情 | 编辑文字 / 5 色标 / 置顶 / 完成 / 播放原声 / 分享 / 复制 / 转日历 / 提醒 / 删除 |
| 转写 | WavRecorder + SiliconFlowTranscriber（SenseVoiceSmall） |
| 提醒 | AlarmManager + 通知（15 分钟 / 1 小时 / 明天 9:00） |
| 小米适配 | 悬浮窗 / 录音 / 通知权限引导 + 自启动设置入口 |

## 交互总览

| 操作 | 结果 |
|---|---|
| 按住把手 ≥250ms | 录音，松手保存并转写 |
| 短按把手 | 打开列表 |
| 按住拖动 | 移动把手（取消录音） |
| 点击列表项 | 详情编辑 |
| 勾选复选框 | 标记完成（文字加删除线） |
| 搜索框 / 色标圆点 | 筛选 |
| 详情页按钮 | 播放 / 分享 / 复制 / 转日历 / 提醒 / 删除 |

## 构建

```bash
cd src
cp local.properties.example local.properties   # 填 sdk.dir + SILICONFLOW_API_KEY
gradle wrapper --gradle-version 8.7            # 首次
./gradlew :app:assembleDebug
```

## 已知限制

- **M4（DashScope 实时流式）未实现**：需要 DashScope API Key，且流式协议复杂，待 key 到位后再做
- 转写仍为「录音后上传」（SiliconFlow HTTP）
- Room 版本 1 → 2：升级安装会清空旧数据（`fallbackToDestructiveMigration`）

## 下一步

- M4：DashScope 实时流式（需 key）
- M7：ContentProvider / Intent API（对接自动化脚本）