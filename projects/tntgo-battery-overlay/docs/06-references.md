# 06 · 参考资料与链接汇总

> 整理时间：2026-09-09

## 电量 / 串口

| 资料 | 链接 | 说明 |
|---|---|---|
| TNT-Go-Battery-Monitor | https://github.com/SA-GIMA/TNT-Go-Battery-Monitor | 有线版串口电量监控（Python CLI + GUI），`at+adb` → `+BATCG=` |
| TNTgo-Boom | https://github.com/electrie00/TNTgo-Boom | macOS 状态栏显示 TNTgo 电量与充电状态（串口读取 + VoodooI2C 驱动） |
| CSDN 串口数据与电量解析 | https://blog.csdn.net/weixin_32466193/article/details/162866804 | `+BATCG=` 解析说明 |

## TNT GO 破解 / ADB / 逆向

| 资料 | 链接 | 说明 |
|---|---|---|
| 企鹅大大的博客 · 开启 ADB | https://qiedd.com/875.html | 串口 `AT+ADB`（9600）、adb pull 系统 APK |
| 企鹅大大的博客 · APK 逆向 | https://qiedd.com/889.html | `BostonScreenMirror.apk`、`com.smartisanos.boston.base`、R2 ROM 提取 |
| APK 备份下载 | https://dl.qiedd.com/android/TNT_go/apk/ | 社区整理的 TNT GO 内置 APK |
| B 站开启 ADB 教程 | https://bilibili.com/BV1YR4y177YV | 参考视频 |
| 酷安教程 | https://www.coolapk.com/feed/31049522 | 参考帖 |
| 新浪 / 什么值得买：安装第三方 App | https://k.sina.cn/article_1823348853_6cae187502000ywjs.html | 串口开 ADB + 装 Nova Launcher 流程 |
| 贴吧：Recovery / 刷机 | https://tieba.baidu.com/p/8317551470 | TNT GO 进入 Recovery 与刷机讨论 |

## TNT 系统 / 二次开发

| 资料 | 链接 | 说明 |
|---|---|---|
| TNT-Anywhere | https://github.com/CashewTeam/TNT-Anywhere | TNT 启动逆向、SmartisanOS 私有 API、Pro3（8.0.4 / Android 10）适配分支、Overlay display 调试 |
| Linux 手写笔方案 | https://zhuanlan.zhihu.com/p/539458130 | libevdev 模拟 TNT GO 手写笔输入 |
| Smartisan Launcher（社区维护） | https://github.com/rianlu/smartisan-launcher-maintained | 非官方维护的锤子桌面 |

## 硬件参数 / 评测

| 资料 | 链接 | 说明 |
|---|---|---|
| 天极参数页（无线版） | http://product.yesky.com/product/1105/1105112/param.shtml | 官方参数（2160×1440、10160mAh、接口等） |
| 百度百科「扩展本」 | https://baike.baidu.com/item/TNT%20go/55307030 | 硬件规格、连接方式、尺寸重量 |
| 什么值得买评测（无线版） | https://post.smzdm.com/p/az3knw9o | 无线机制、兼容性、配件细节 |
| 砍柴网 TNT go 体验 | https://www.kanchai.com/...（搜索「TNT go 体验 用 TNT 办公」） | TNT 2.0 状态栏双电量显示佐证 |
| 什么值得买：TNT go 发布 | https://post.smzdm.com/p/az5gxvvn | 发布信息、价格、键盘 / 手写笔参数 |

## 小米 17 Pro Max

| 资料 | 链接 | 说明 |
|---|---|---|
| 小米商城规格页 | https://www.mi.com/xiaomi-17-pro-max/specs | USB 3.2 Gen1；原装线不支持 USB3 传输 |
| 海备思：小米有线投屏方法 | https://www.bilibili.com/video/BV1XESvYHEfH | 小米 13 Ultra / 14 起支持 DP Alt Mode |

## 关键结论备忘

- TNT GO 电量：串口 `at+adb` → `+BATCG=` 第 2 字段
- TNT GO USB：VID:PID `31ce:5101`
- 有线模式显示层归手机；渲染首选 `Presentation`
- 小米 17 Pro Max 有线投屏：先换全功能线