#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DashScope 文生图（万相）客户端 —— 给 Smartisan Powertoys 生成图标资源。

## API（实测自阿里云百炼文档）

```
① POST https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis
   Headers: Authorization: Bearer sk-xxx
            Content-Type: application/json
            X-DashScope-Async: enable        ← ★ 必选；缺了报 "does not support synchronous calls"
   Body: { "model": ..., "input": {"prompt":..., "negative_prompt":...},
           "parameters": {"style":..., "size":"1024*1024", "n":1} }
   → output.task_id

② GET https://dashscope.aliyuncs.com/api/v1/tasks/{task_id}    轮询
   → output.task_status: PENDING/RUNNING/SUCCEEDED/FAILED
   → output.results[].url  ★ 有效期 24 小时 ⇒ 必须立刻下载
```

## ⚠️ 限流

QPS 2 ／ **同时处理中任务数 = 1** ⇒ ★ **必须串行**（本脚本就是串行的）。

## 用法

    python scripts/gen_icons.py --list                 # 看要生成哪些
    python scripts/gen_icons.py --only app_icon        # 只生成一张（验证通路）
    python scripts/gen_icons.py                        # 全部生成（跳过已存在的）
    python scripts/gen_icons.py --force                # 全部重生成

★ Key 从**用户环境变量** `DASHSCOPE_API_KEY` 读（Windows 下新加的要读注册表，见 --key-from-registry）。
"""
import argparse
import json
import os
import subprocess
import sys
import time
import urllib.request
import urllib.error

BASE = "https://dashscope.aliyuncs.com"
CREATE = BASE + "/api/v1/services/aigc/text2image/image-synthesis"
TASK = BASE + "/api/v1/tasks/"

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUTDIR = os.path.join(ROOT, ".ref", "ui_assets")

# ★★★★ 两套风格预设（任务 AP）—— 由 --set 选择
#
# 参照物：锤子 Smartisan OS 的原生图标（实拍见 .ref/ui_assets/_smartisan_launcher.png）
#   设置=金属齿轮、录音机=镀铬麦克风、时钟=玻璃反光表盘、便签=层叠纸张+回形针
#   ★ 特征：像「微缩实物」，有材质质感与柔和立体光影，**没有粗描边**
STYLES = {
    "flat": (
        "扁平矢量图标（flat vector icon），只有一个主体符号，极简几何造型，"
        "描边很粗（相当于画布宽度的百分之四），造型饱满占据画面中央约百分之七十，"
        "背景是纯色米白 #F2EDE4；主体用深墨蓝 #16202B 勾轮廓并填充，"
        "内部用青绿 #2E7D6F 做主色块，并且必须有一处明显的琥珀黄 #E8A33D 作为点缀，"
        "纯色平涂，绝对没有渐变、没有阴影、没有立体感、没有纹理、没有文字，"
        "正方形构图，四周留白均匀；这个图标缩小到 48 像素时仍要一眼认出"
    ),
    "skeuo": (
        "★ 精细的拟物化图标（skeuomorphic icon），画面里是【一件微缩的真实物件】，"
        "真实还原材质：磨砂金属、抛光镀铬、清玻璃、牛皮纸、软橡胶、拉丝铝，"
        "柔和的定向光从左上方照来，物件上有自然的高光反射与柔和接地投影，"
        "有真实的厚度与轻微立体感；★ 绝对没有粗描边、没有卡通感、没有扁平色块，"
        "形体完全靠明暗与材质塑造；"
        "背景是干净的浅米色到浅灰的柔和渐变，像摄影棚里的一块衬布；"
        "居中构图，画面里只有这一件物件，四周留白均匀，物件占据中央约百分之七十；"
        "整体色调克制，只用一点点琥珀金或青绿作为点缀色；"
        "没有文字、没有商标；缩小到 48 像素时仍能一眼认出它是什么东西"
    ),
}

# ★ 兼容旧名字：默认用扁平那套
STYLE = (
    "扁平矢量图标（flat vector icon），只有一个主体符号，极简几何造型，"
    "描边很粗（相当于画布宽度的百分之四），造型饱满占据画面中央约百分之七十，"
    "背景是纯色米白 #F2EDE4；主体用深墨蓝 #16202B 勾轮廓并填充，"
    "内部用青绿 #2E7D6F 做主色块，并且必须有一处明显的琥珀黄 #E8A33D 作为点缀，"
    "纯色平涂，绝对没有渐变、没有阴影、没有立体感、没有纹理、没有文字，"
    "正方形构图，四周留白均匀；这个图标缩小到 48 像素时仍要一眼认出"
)
NEG = "文字, 字母, 汉字, 数字, 水印, 签名, 照片写实, 3D 渲染, 立体, 阴影, 渐变, 复杂背景, 杂乱, 多个主体, 细线条, 低分辨率, 模糊"

# ★ 拟物化的负向词必须**换一套** —— 扁平那套把「照片写实/3D渲染/立体/阴影/渐变」
#   全否掉了，照搬会把拟物化直接压死（这是本脚本第一版的真实错误，保留记录）。
NEG_SKEUO = ("文字, 字母, 汉字, 数字, 水印, 签名, 商标, "
             "扁平色块, 纯色平涂, 粗描边, 卡通, 简笔画, 矢量插画, 图标化, "
             # ⚠️ 这几条是 feat_caption 画成「一杯威士忌」之后补的
             "杯子, 玻璃杯, 酒杯, 液体, 饮料, 酒, 手表, 腕表, "
             "复杂背景, 杂乱, 多个主体, 低分辨率, 模糊, 畸变")
NEGS = {"flat": NEG, "skeuo": NEG_SKEUO}


# 任务名 → (中文主题, 补充描述)
JOBS = {
    # ---- 应用图标 ----
    "app_icon": ("应用图标", "一个工具箱的抽象符号：一个圆角正方形盒子，盒子正面有一个闪电形状的凹槽，盒子右上角露出一把螺丝刀的顶端"),
    # ---- 功能图标（6）----
    "feat_brightness": ("亮度", "一颗太阳，中心是实心圆，周围八条等长的直线光芒"),
    "feat_battery":  ("电量", "一节竖直的电池，内部有三格电量条，顶部有一个小凸起"),
    "feat_caption":  ("实时字幕", "一个圆角对话气泡，气泡内部有三条长度递减的横线"),
    "feat_perfmode": ("性能模式", "一枚向右上方倾斜的闪电，闪电底部有一条水平的速度线"),
    "feat_perfmon":  ("性能监控", "一个半圆形仪表盘，指针指向右侧偏上，表盘下方有四个刻度点"),
    "feat_hello":    ("示例组件", "三个大小不同的圆角方块堆叠，最大的在底部"),
    # ---- 分类图标（6）----
    "cat_display":   ("分类·显示", "一台显示器，屏幕里有一轮升起的太阳和一条地平线"),
    "cat_input":     ("分类·输入", "一个键盘的俯视简图，上面有四排小方块按键"),
    "cat_performance": ("分类·性能", "一枚竖直的火箭，尾部有三片三角形尾翼"),
    "cat_media":     ("分类·媒体", "一个圆形播放按钮，中间是实心三角形"),
    "cat_system":    ("分类·系统", "一个六齿齿轮，中心是空心圆"),
    "cat_other":     ("分类·其它", "四个小圆点排成一个菱形"),
    # ---- 界面装饰 ----
    "header":        ("顶部装饰", "一条横向的抽象科技装饰带：左侧是电路板的折线，中间是一条流动的波浪，右侧是三个同心圆弧"),
}


# ★★ 拟物化专用的「物件」描述 —— 扁平那套写的是抽象符号（如「四个小圆点排成菱形」），
#    拟物化要求画面里是【一件微缩的真实物件】，因此必须逐个重写。
#    参照锤子 Smartisan OS 原生图标（.ref/ui_assets/_smartisan_launcher.png）。
SKEUO_DESC = {
    "app_icon": ("一个微缩的深灰色金属工具箱，箱体是磨砂金属并有圆润的倒角与铆钉，"
                 "正面居中一枚镀铬的闪电形状铭牌，右上角露出螺丝刀的镀铬手柄顶端"),
    "feat_brightness": ("一个真实的灯泡，玻璃罩是清玻璃并透出温暖的琥珀色灯丝光，"
                        "灯头是带螺纹的金属底座"),
    "feat_battery": ("一节真实的圆柱形干电池，外皮是哑光深灰配一圈琥珀金色环带，"
                     "正极是光亮的镀铬凸起，表面有细腻的磨砂颗粒"),
    # ⚠️ 第一版写的是「清玻璃对话气泡」⇒ 模型画成了一【杯威士忌】（玻璃＋琥珀色液体），
    #    完全读不出「字幕」。改成"横向的电子字幕屏"，并显式否掉杯具与液体。
    "feat_caption": ("一个横向的长方形深色玻璃显示条摆件，像一块小小的电子字幕屏，"
                     "外框是磨砂铝金属边框，玻璃面板内部横向排列着三条暖白色发光短线，"
                     "三条线的长度依次递减"),
    "feat_perfmode": ("一枚真实的闪电造型金属徽章，厚实的抛光镀铬立体闪电，"
                      "放置在一块拉丝铝底座上，闪电的棱边有锐利的高光"),
    # ⚠️ 第一版写「怀表式仪表盘」⇒ 模型抓住了"表"字，画出一块【手表】。
    #    去掉"怀表"，改用「半圆形、底部是一条直线」把形状说死。
    "feat_perfmon": ("一块微缩的半圆形仪表盘摆件，它的底部是一条平整的水平直线，"
                     "上方是半圆弧形，盘面是深色哑光，边缘是抛光镀铬的半圆形金属边框，"
                     "盘面中央有一根细长的琥珀金色指针指向右上方，盘面上有一圈压印的刻度线"),
    "feat_hello": ("三个大小不同的软橡胶圆角方块真实地堆叠在一起，底层最大是深灰色，"
                   "中层是青绿色，顶层最小是琥珀金色，橡胶表面有细微的哑光颗粒"),
    "cat_display": ("一台微缩的桌面显示器，屏幕是深色清玻璃并映出一轮升起的琥珀金色太阳，"
                    "机身与支架是拉丝铝，底座是磨砂金属圆盘"),
    "cat_input": ("一个微缩的机械键盘俯视图，键帽是深灰色磨砂塑料方块并整齐排成四排，"
                  "其中一枚键帽是琥珀金色，外壳是拉丝铝"),
    "cat_performance": ("一个微缩的金属火箭模型，箭体是抛光镀铬，尾翼是三片拉丝铝三角翼，"
                        "尖端泛着一点琥珀金色的光"),
    "cat_media": ("一枚真实的圆形播放按钮实体按键，按键是抛光镀铬的圆盘，"
                  "中央是一个凸起的青绿色三角形，外围是一圈磨砂金属环"),
    # ⚠️ 第一版只写「六个齿」⇒ 出图是一圈又密又尖的齿，像【锯片】。
    #    补上"齿形厚实方正、不要尖刺、齿与齿之间间隔很大"。
    "cat_system": ("一枚真实的微缩金属齿轮，整个齿轮只有六个宽大厚实的齿，"
                   "齿形是方正圆钝的方块而不是尖刺，齿与齿之间的间隔很大，"
                   "轮体表面是拉丝铝并有精细的机加工纹路，"
                   "中心是一个透空的圆孔，轮缘有倒角高光"),
    "cat_other": ("四颗微缩的抛光金属小球排成一个菱形，小球是镜面镀铬质感，"
                  "底下垫着柔软的浅灰色衬布"),
    "header": ("一条横向的微缩金属装饰带摆件，材质是拉丝铝配深色凹槽，"
               "左段是电路板折线凹槽并嵌有青绿色微光，中段是一条流动的抛光镀铬波浪，"
               "右段是三个同心圆弧凹槽"),
}


def get_key(use_registry: bool) -> str:
    k = os.environ.get("DASHSCOPE_API_KEY", "").strip()
    if k:
        return k
    if use_registry:
        # ★ Windows：新加到「用户变量」的环境变量，当前进程继承不到 ⇒ 从注册表读
        try:
            out = subprocess.check_output(
                ["powershell", "-NoProfile", "-Command",
                 "[Environment]::GetEnvironmentVariable('DASHSCOPE_API_KEY','User')"],
                text=True, timeout=20).strip()
            if out:
                return out
        except Exception as e:
            print(f"  ⚠ 读注册表失败: {e}", file=sys.stderr)
    return ""


def post_json(url: str, body: dict, key: str, extra_headers: dict | None = None) -> dict:
    data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="POST")
    req.add_header("Authorization", f"Bearer {key}")
    req.add_header("Content-Type", "application/json")
    for k, v in (extra_headers or {}).items():
        req.add_header(k, v)
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read().decode("utf-8"))


def get_json(url: str, key: str) -> dict:
    req = urllib.request.Request(url, method="GET")
    req.add_header("Authorization", f"Bearer {key}")
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read().decode("utf-8"))


def download(url: str, path: str) -> int:
    req = urllib.request.Request(url)
    with urllib.request.urlopen(req, timeout=180) as r:
        blob = r.read()
    with open(path, "wb") as f:
        f.write(blob)
    return len(blob)


def gen_one(name: str, key: str, model: str, size: str, style: str, force: bool,
            style_text: str = STYLE, prefix: str = "",
            style_set: str = "flat") -> bool:
    os.makedirs(OUTDIR, exist_ok=True)
    dst = os.path.join(OUTDIR, f"{prefix}{name}.jpg")
    if os.path.exists(dst) and not force:
        print(f"  - {name} 已存在，跳过（--force 可重生）")
        return True

    title, desc = JOBS[name]
    if style_set == "skeuo":
        desc = SKEUO_DESC.get(name, desc)
    prompt = f"{style_text}。画面内容：{desc}。用途：软件界面里的{title}图标。"

    try:
        res = post_json(CREATE, {
            "model": model,
            "input": {"prompt": prompt, "negative_prompt": NEGS.get(style_set, NEG)},
            "parameters": {"style": style, "size": size, "n": 1},
        }, key, {"X-DashScope-Async": "enable"})
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "ignore")[:400]
        print(f"  ✗ {name} 创建任务失败 HTTP {e.code}: {body}")
        return False
    except Exception as e:
        print(f"  ✗ {name} 创建任务异常: {e}")
        return False

    task_id = (res.get("output") or {}).get("task_id")
    if not task_id:
        print(f"  ✗ {name} 没拿到 task_id: {json.dumps(res, ensure_ascii=False)[:300]}")
        return False
    print(f"  · {name} task={task_id} 等待中…", end="", flush=True)

    # ★ 串行轮询（限流：同时处理中任务数 = 1）
    for i in range(90):
        time.sleep(4)
        try:
            st = get_json(TASK + task_id, key)
        except Exception as e:
            print(f"\n  ✗ {name} 轮询异常: {e}")
            return False
        status = (st.get("output") or {}).get("task_status", "?")
        if status == "SUCCEEDED":
            results = (st.get("output") or {}).get("results") or []
            if not results:
                print(f"\n  ✗ {name} 成功但没结果")
                return False
            url = results[0].get("url")
            try:
                n = download(url, dst)
                print(f"\r  ✓ {name}  →  {os.path.relpath(dst, ROOT)}  ({n/1024:.0f} KB)")
                return True
            except Exception as e:
                print(f"\n  ✗ {name} 下载失败: {e}\n     url={url}")
                return False
        if status in ("FAILED", "CANCELED", "UNKNOWN"):
            msg = (st.get("output") or {}).get("message") or st.get("message") or ""
            print(f"\n  ✗ {name} 任务 {status}: {msg}")
            return False
        if i % 5 == 4:
            print(".", end="", flush=True)
    print(f"\n  ✗ {name} 超时")
    return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", help="只生成指定的一个（逗号分隔）")
    ap.add_argument("--force", action="store_true", help="已存在也重生")
    ap.add_argument("--model", default="wanx2.1-t2i-turbo")
    ap.add_argument("--size", default="1024*1024")
    ap.add_argument("--style", default=None, help="wanx 风格枚举；不填则按 --set 自动选")
    ap.add_argument("--set", dest="style_set", default="flat",
                    choices=["flat", "skeuo"],
                    help="风格集：flat=扁平矢量；skeuo=拟物化（仿锤子原生图标）")
    ap.add_argument("--list", action="store_true", help="列出要生成的项")
    ap.add_argument("--key-from-registry", action="store_true", default=True,
                    help="环境变量读不到时从 Windows 用户变量读（默认开）")
    args = ap.parse_args()

    if args.list:
        print(f"共 {len(JOBS)} 项，输出到 .ref/ui_assets/：")
        for k, (t, d) in JOBS.items():
            print(f"  {k:18} {t:10} {d[:40]}…")
        return

    key = get_key(args.key_from_registry)
    if not key:
        print("✗ 没拿到 DASHSCOPE_API_KEY", file=sys.stderr)
        sys.exit(1)
    style_text = STYLES[args.style_set]
    prefix = "" if args.style_set == "flat" else (args.style_set + "_")
    wx_style = args.style or ("<flat illustration>" if args.style_set == "flat" else "<photography>")
    print(f"Key: {key[:6]}…{key[-4:]}（长度 {len(key)}）  模型: {args.model}  尺寸: {args.size}")
    print(f"风格集: {args.style_set}  (wanx style={wx_style})  文件名前缀: {prefix!r}")

    names = list(JOBS)
    if args.only:
        names = [n.strip() for n in args.only.split(",") if n.strip() in JOBS]
        if not names:
            print(f"✗ --only 里没有有效的名字；可选: {', '.join(JOBS)}", file=sys.stderr)
            sys.exit(1)

    print(f"要生成 {len(names)} 张（串行，限流 QPS2/并发1）\n")
    ok = 0
    for n in names:
        if gen_one(n, key, args.model, args.size, wx_style, args.force,
                   style_text, prefix, args.style_set):
            ok += 1
        time.sleep(2)      # 限流保护

    print(f"\n完成：{ok}/{len(names)}")
    print(f"输出目录：{os.path.relpath(OUTDIR, ROOT)}")


if __name__ == "__main__":
    main()
