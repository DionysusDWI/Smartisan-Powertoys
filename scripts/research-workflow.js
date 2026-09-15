export const meta = {
  name: 'smartisan-tnt-workspace-research',
  description: 'Research the Smartisan TNT secondary-development stack and produce a verified dependency/setup plan for this workspace',
  phases: [
    { title: 'Research', detail: '9 parallel domain researchers over web + workspace archives' },
    { title: 'Verify', detail: 'adversarial verification of load-bearing claims against primary sources' },
    { title: 'Synthesize', detail: 'merge into one setup plan with pinned versions' },
  ],
}

const BRIEF = [
'You are researching how to set up an Android secondary-development workspace on Windows 11 for Smartisan (锤子/坚果) TNT system customization.',
'',
'WORKSPACE: the project root — currently EMPTY except these archives (do NOT extract them; other agents are doing that; read-only inspection via tar -xzOf / unzip -p is fine):',
'- Smartisan_TNT.tar.gz (131MB) -> Smartisan_TNT_pkg/: framework jars (smartisan-services-tnt.jar, smartisanos.jar), framework-smartisanos-res.apk, boot/oat artifacts, libsmartisan-tnt.so (arm64+arm), apps (Desktop.apk, SmartisanDesktopSystemUI.apk, DesktopRecentsPsp.apk), host-framework (services.jar, framework.jar, framework-res.apk), config (tnt_compatibility_apps.json, smartisan.x509.pem), and src/ = baksmali smali (~19000 files) + SOURCE_STRUCTURE_tnt.md',
'- TNT_Emulator_Port_Kit.tar.gz (139MB) -> same content PLUS TNT_AOSP_Emulator_Port_Guide.md',
'- FlashPill_package.tar.gz (14.7MB) -> FlashPill_dev/: 闪念胶囊 APK apktool output + 接口分析报告.md + 可移植性分析.md + MANIFEST.md',
'- Xiaomi_FlashPill_dev.tar.gz (14.7MB) -> same smali source',
'- 1788970184456_tntgo-battery-overlay-mvp-2026-09-09.zip -> tntgo-battery-overlay/: Kotlin app (AGP 8.5.2, Kotlin 2.0.20, compileSdk 35, minSdk 29, JDK 17, usb-serial-for-android 3.8.0 via JitPack) showing TNT GO battery on the secondary display',
'- 1788972003258_flash-pill-port-2026-09-10.zip -> flash-pill-port/: Kotlin app (AGP 8.5.2, Kotlin 2.0.20, KSP 2.0.20-1.0.25, Room 2.6.1, OkHttp 4.12.0, compileSdk 35) = 闪念胶囊 clone',
'',
'TARGET DEVICES: Smartisan Nut Pro 3 (DT1901A, Smartisan OS 8.0.4 / Android 10 / SDK 29, arm64-v8a, TNT internal codename "revone"); test device Xiaomi 17 Pro Max (HyperOS 3 / Android 16 / no root); TNT GO portable display.',
'',
'CURRENT MACHINE STATE (already verified, do not re-check):',
'- Windows 11, Git Bash (POSIX sh). Python 3.13.5 + miniconda3 (conda 25.11.0). Node v24.15.0. git 2.54.0. Docker 29.6.2.',
'- Java: ONLY 1.8.0_491 — too old for AGP 8.5.2 (needs JDK 17). No Gradle. No Android SDK. No ~/.gradle or ~/.m2 caches.',
'- adb present in the workspace toolchain (toolchain/android-sdk/platform-tools) (adb 1.0.41 / 37.0.1), plus fastboot and sqlite3.',
'- Disk: P: 1003G free, C: 282G free.',
'- HARD CONSTRAINT: avoid system-level installs / admin. Prefer portable, extract-to-folder, user-scoped tooling (ANDROID_HOME / GRADLE_USER_HOME / JAVA_HOME scoped to the workspace or user dir, venv, no MSI installers, no PATH edits to system).',
'- Network reachable: api.github.com, dl.google.com, repo1.maven.org, jitpack.io, services.gradle.org, pypi.org, mirrors.tuna.tsinghua.edu.cn, cdn.jsdelivr.net, ghproxy.net, localhost:8080 (SearxNG).',
'- Network BLOCKED: raw.githubusercontent.com (connection reset), developer.android.com (timeout), and the WebFetch tool is unusable (its domain safety-check service is unreachable) — DO NOT use WebFetch.',
'',
'SEARCH CHANNELS (use these):',
'1. WebSearch tool — works.',
'2. SearxNG JSON API via Bash+curl (preferred for technical + Chinese queries):',
'   curl -sS -m 30 --get "http://localhost:8080/search" --data-urlencode "q=QUERY" --data-urlencode "format=json" --data-urlencode "engines=ENGINE"',
'   Working engines: bing, quark, 360search, github, stackoverflow, "google scholar".',
'   Currently CAPTCHA/rate-limited (avoid): google, duckduckgo, brave, startpage, baidu, mojeek.',
'   Parse: curl ... | python -c "import sys,json;d=json.load(sys.stdin);[print(r.get(\'title\'),\'|\',r.get(\'url\'),\'|\',(r.get(\'content\') or \'\')[:200]) for r in d.get(\'results\',[])[:10]]"',
'3. Direct curl for docs/APIs. For GitHub raw files use https://cdn.jsdelivr.net/gh/OWNER/REPO@BRANCH/PATH or https://ghproxy.net/https://raw.githubusercontent.com/OWNER/REPO/BRANCH/PATH',
'4. GitHub API: curl -sS https://api.github.com/repos/OWNER/REPO/releases/latest ; tags: .../tags',
'5. PyPI: curl -sS https://pypi.org/pypi/PACKAGE/json',
'',
'RULES:',
'- Every factual claim must carry a source URL. Mark inferred/unsourced claims explicitly as such.',
'- Prefer PRIMARY sources: official docs, release notes, source code, Maven POMs, PyPI JSON, SDK repository XML.',
'- Verify version numbers against real artifact metadata, not memory.',
'- Read-only with respect to the workspace: do NOT extract archives or install anything.',
'- Your final text IS the return value (structured data), not a human-facing message.',
].join('\n')

const DOMAINS = [
  {
    key: 'tnt-arch',
    title: 'Smartisan TNT architecture & secondary-development entry points',
    prompt: [
      'Research: (a) what TNT (Touch & Talk) / internal codename "revone" is, TNT 1.0 vs 2.0, which devices shipped it; (b) the framework-injection mechanism: com.android.server.pc.TntFeatureFactory (interface, in services.jar) vs com.android.server.TntFeatureFactoryImpl (impl, in smartisan-services-tnt.jar), how OEM FeatureFactory is loaded in system_server on Android 10, boot-classpath addition; (c) key constants/flags: ACTION_DESKTOP_READY, com.smartisanos.pcmode.ENTER_PCMODE, revone.action.OPERATING_MODE_CHANGED, DESKTOP_PSP_WINDOW, isSmartisanLargeScreenDevice, smartisanos.util.config.Features / tntType / getDefaultNotificationWidgetsForTnt; (d) how THIRD-PARTY apps interact with the TNT desktop: the tnt_compatibility_apps.json whitelist, multi-window/desktop window manager behaviour, what an app must do to look right on the TNT screen; (e) existing community work on Smartisan OS customization / TNT mods — search Chinese sources via searxng engines quark and 360search with queries like "坚果Pro3 TNT 二次开发", "SmartisanOS 移植", "TNT 桌面 修改", "锤子 系统 框架 注入".',
      'ALSO: read the workspace kit read-only and report what it ALREADY establishes so we do not re-research it:',
      '  tar -xzOf "./TNT_Emulator_Port_Kit.tar.gz" Smartisan_TNT_pkg/TNT_AOSP_Emulator_Port_Guide.md',
      '  tar -xzOf "./TNT_Emulator_Port_Kit.tar.gz" Smartisan_TNT_pkg/README.md',
      '  tar -xzOf "./Smartisan_TNT.tar.gz" Smartisan_TNT_pkg/src/SOURCE_STRUCTURE_tnt.md',
      'In setup_implications, list the concrete analysis tooling the user needs to explore this smali/jar corpus.',
    ].join('\n'),
  },
  {
    key: 'tnt-emulator',
    title: 'Porting the Smartisan TNT framework onto AOSP Android 10 / emulator',
    prompt: [
      'Research the feasibility and mechanics of running the packaged TNT framework on a non-Smartisan Android 10 (SDK 29) target, especially the Android emulator:',
      '(a) how OEM/FeatureFactory selection works in AOSP Android 10 — where FeatureFactoryImpl is chosen, whether a system property or classpath entry can redirect it, and what it takes for services.jar to instantiate com.android.server.TntFeatureFactoryImpl;',
      '(b) boot classpath extension on Android 10 (BOOTCLASSPATH, dexpreopt, /system/framework placement, oat/vdex handling) and its risks;',
      '(c) priv-app requirements + platform signing: Android platform keys, the included smartisan.x509.pem, re-signing workflow, signature-level permission implications;',
      '(d) framework-res.apk overlay mechanism (RRO/SRO, config_* resources, /system/framework/framework-smartisanos-res/);',
      '(e) running an Android 10 emulator with a WRITABLE system partition (-writable-system, adb remount, AVB/verity), AVD image options;',
      '(f) the ABI problem: TNT artifacts are arm64 (libsmartisan-tnt.so, odex/vdex) while default emulators are x86_64 — what are the real options (arm64 AVD on x86 host is extremely slow; does an x86_64 emulator have any path?) — be explicit and honest about feasibility;',
      '(g) whether prebuilt .odex/.vdex should be dropped and dex2oat re-run;',
      '(h) any documented prior attempts to port SmartisanOS or similar Chinese OEM desktop-mode frameworks to AOSP.',
      'ALSO read: tar -xzOf "./TNT_Emulator_Port_Kit.tar.gz" Smartisan_TNT_pkg/TNT_AOSP_Emulator_Port_Guide.md and report what it already claims/solves.',
    ].join('\n'),
  },
  {
    key: 'build-toolchain',
    title: 'Android build toolchain version matrix + portable no-admin Windows install',
    prompt: [
      'This is the highest-value domain — be exact and verify against primary artifacts.',
      'Research: (a) AGP 8.5.2: required minimum Gradle version, required JDK (minimum AND recommended), maximum supported compileSdk; verify via the AGP release notes and the Gradle/AGP compatibility table (fetch the Gradle compatibility page via curl or jsdelivr mirror);',
      '(b) Kotlin 2.0.20 + KSP 2.0.20-1.0.25 compatibility — confirm the KSP version really exists on Maven Central (curl the POM: https://repo1.maven.org/maven2/com/google/devtools/ksp/symbol-processing-api/2.0.20-1.0.25/...);',
      '(c) Room 2.6.1 with KSP 2.0.20 — any known breakage; what Room version is recommended for Kotlin 2.0.20;',
      '(d) compileSdk 35: exact sdkmanager package identifiers (platforms;android-35, build-tools;35.0.0, platform-tools, cmdline-tools;latest) and their approximate download sizes; confirm they exist by fetching https://dl.google.com/android/repository/repository2-3.xml and grepping;',
      '(e) the current commandlinetools-win zip URL + size + the cmdline-tools/latest/ directory-layout gotcha (sdkmanager fails if the folder is not named "latest");',
      '(f) portable JDK 17 for Windows x64: exact Adoptium Temurin 17 zip download URL + size via https://api.adoptium.net/v3/assets/latest/17/hotspot?os=windows&architecture=x64&image_type=jdk; also state whether AGP 8.5.2 is fine on JDK 21 (and if a newer JDK is preferable);',
      '(g) bootstrapping Gradle WITHOUT a system Gradle: how to obtain gradle-wrapper.jar, and whether it is simpler to download the Gradle distribution zip directly from https://services.gradle.org/distributions/ (give the exact recommended 8.x version for AGP 8.5.2 and its size);',
      '(h) how to scope JAVA_HOME / ANDROID_HOME / ANDROID_SDK_ROOT / GRADLE_USER_HOME to this workspace without system install or PATH edits, and how to avoid disturbing the machine-wide JDK 8 (e.g. org.gradle.java.home in gradle.properties, or a per-shell env file);',
      '(i) how to accept Android SDK licenses non-interactively (sdkmanager --licenses, or pre-writing licenses/ hashes) and where the license files live;',
      '(j) whether the JitPack dependency com.github.mik3y:usb-serial-for-android:3.8.0 resolves (curl https://jitpack.io/com/github/mik3y/usb-serial-for-android/3.8.0/).',
    ].join('\n'),
  },
  {
    key: 'apk-re-tools',
    title: 'APK/JAR reverse-engineering toolchain for Android 10 dex, portable on Windows',
    prompt: [
      'Research: (a) latest apktool release (GitHub API ibotpeaches/Apktool), its JDK requirement, how to run apktool.jar portably, its known limitations decoding Android 10 / Chinese OEM APKs, and whether it copes with the 46MB SmartisanDesktopSystemUI.apk (15 dex files);',
      '(b) latest jadx release (GitHub API skylot/jadx) — JDK requirement, portable zip, CLI usage, multi-dex handling, and how to feed it the smartisan-services-tnt.jar / smartisanos.jar / the three APKs;',
      '(c) baksmali/smali (baksmali 2.5.x by JesusFreke) — the workspace smali was produced with one of these; determine how to tell which, and how to re-run disassembly/assembly;',
      '(d) dex2jar / enjarify status in 2026 — still maintained? worth installing?',
      '(e) reading AndroidManifest.xml and resources.arsc WITHOUT a full decode: aapt2 dump, apkanalyzer, or Python pyaxmlparser;',
      '(f) Python-side compatibility with Python 3.13.5 — androguard, pyaxmlparser, apkutils, lief: fetch https://pypi.org/pypi/<pkg>/json for each and report latest version, Requires-Python, whether wheels exist for cp313/win_amd64, and whether it needs a C compiler;',
      '(g) a recommended portable layout under the workspace (toolchain/bin/*.jar + thin .sh/.bat wrappers) and the exact commands to install each without admin.',
    ].join('\n'),
  },
  {
    key: 'usb-cdc-tntgo',
    title: 'TNT GO hardware + USB CDC battery serial protocol',
    prompt: [
      'Research: (a) Smartisan TNT GO specs — wireless vs wired variant, internal SoC (Amlogic S905Y2?), RAM/storage, battery capacity, USB-C video input, and whether the internal Android system runs while in wired (DP Alt Mode) mode;',
      '(b) the reverse-engineered battery protocol: USB VID/PID 31ce:5101, command "at+adb", response containing "+BATCG=" whose SECOND comma-separated field is the battery percent; the baud rate(s) actually used (115200 / 9600?); find the ORIGINAL open-source implementation — search searxng engine github for "TNT GO battery", "+BATCG", "31ce:5101", "at+adb", and Chinese engines for "TNT GO 电量 串口";',
      '(c) usb-serial-for-android: compare 3.8.0 (pinned in the project) with the latest release (check https://api.github.com/repos/mik3y/usb-serial-for-android/releases/latest) — CDC-ACM driver support, how to probe a custom VID/PID not in the built-in table, JitPack coordinates, minSdk, and any Android 10 caveats;',
      '(d) can an Android phone act as USB host to TNT GO while simultaneously driving DP Alt Mode output to it — what is documented about USB-C role/alt-mode coexistence (this is the single biggest unknown in the project; be honest if it is undocumented);',
      '(e) fallbacks if CDC enumeration fails: BLE, ADB over TCP to TNT GO, PD/PPS vendor messages, sysfs — with feasibility assessment.',
      'Report the exact parse regex and, if you find it, known-good Kotlin/Java code.',
    ].join('\n'),
  },
  {
    key: 'display-rendering',
    title: 'Rendering UI on the TNT secondary display from a third-party app',
    prompt: [
      'Research: (a) Presentation API + createDisplayContext + DisplayManager.DisplayListener on Android 10+: permission model, behaviour when the display is marked private or is owned by a system window manager, and whether Presentation still works;',
      '(b) TYPE_APPLICATION_OVERLAY added through a WindowManager obtained from createDisplayContext(nonDefaultDisplay) — is adding an overlay to a non-default display allowed on API 29+, and what are the documented restrictions (search AOSP source / StackOverflow via searxng engine stackoverflow);',
      '(c) ActivityOptions.setLaunchDisplayId() / launching an Activity on a secondary display;',
      '(d) the testing trick: adb shell settings put global overlay_display_devices "1280x720/213" — exact semantics, how to clear it, caveats (does it fire DisplayManager.DisplayListener? do Presentation/overlays render?);',
      '(e) how Android desktop modes (Samsung DeX, Huawei EMUI desktop mode, Motorola ReadyFor) treat third-party Presentation/overlay windows — which lessons transfer to TNT;',
      '(f) TNT-specific: does Smartisan TntWindowManagerServiceImpl / TntDisplayContentImpl intercept or re-parent third-party windows on the TNT display? search Chinese sources (quark/360search: "TNT 悬浮窗 副屏", "锤子 TNT 外接显示器 应用", "Presentation 副屏").',
      'Deliverable: a ranked, evidence-backed recommendation of which rendering approach will actually work on TNT OS 8.0.4, with the fallbacks.',
    ].join('\n'),
  },
  {
    key: 'flashpill-original',
    title: '闪念胶囊 original app: SmartisanOS interfaces and porting map',
    prompt: [
      'FIRST, read the workspace archives read-only:',
      '  tar -xzOf "./FlashPill_package.tar.gz" FlashPill_dev/接口分析报告.md',
      '  tar -xzOf "./FlashPill_package.tar.gz" FlashPill_dev/可移植性分析.md',
      '  tar -xzOf "./FlashPill_package.tar.gz" FlashPill_dev/README.md',
      '  tar -xzOf "./FlashPill_package.tar.gz" FlashPill_dev/MANIFEST.md',
      '  unzip -p "./1788972003258_flash-pill-port-2026-09-10.zip" "flash-pill-port/docs/接口分析报告.md"',
      '  unzip -p "./1788972003258_flash-pill-port-2026-09-10.zip" "flash-pill-port/docs/可移植性分析.md"',
      'THEN research on the web: (a) the SmartisanOS-only dependencies of the original 闪念胶囊/IdeaPills (smartisan.action.VIEW_*_VOICE_INPUT_RECORD, sara voice bubble, sidebar sync, cloudsync, Lsmartisanos/* framework classes, signature-level permissions, bytedance plugin) and what each maps to on stock Android / HyperOS; (b) existing open-source 闪念胶囊 clones (searxng engine github + quark/360search: "闪念胶囊 开源", "flash capsule android", "IdeaPills github"); (c) the original interaction model; (d) any Android 16 / HyperOS 3 specific blockers for a floating-sidebar + overlay + foreground-microphone app.',
      'Deliverable: a concrete interface-replacement mapping table (original interface -> replacement on HyperOS 3 / Android 16), plus a gap list.',
    ].join('\n'),
  },
  {
    key: 'flashcapsule-and-asr',
    title: 'Reference project whd-1999/flash-capsule + ASR APIs (SiliconFlow, DashScope)',
    prompt: [
      'PART A — reference project whd-1999/flash-capsule. Fetch its docs (try https://cdn.jsdelivr.net/gh/whd-1999/flash-capsule@main/ARCHITECTURE.md , @master/, and https://ghproxy.net/https://raw.githubusercontent.com/whd-1999/flash-capsule/main/README.md ; list branches via https://api.github.com/repos/whd-1999/flash-capsule/branches). Report: module structure, tech stack, the sidebar-handle implementation notes, the Capsule data model, Source/Sink pluggable design, and LICENSE STATUS (the workspace notes say no LICENSE file — confirm via https://api.github.com/repos/whd-1999/flash-capsule/license and the repo file listing).',
      'PART B — ASR APIs, with exact request shapes:',
      '(1) SiliconFlow: POST https://api.siliconflow.cn/v1/audio/transcriptions — exact multipart form fields, model id FunAudioLLM/SenseVoiceSmall, auth header format, accepted audio formats/container requirements, max file size/duration, response JSON shape, and current pricing. Find the official docs page and mirror it if needed.',
      '(2) Alibaba DashScope Paraformer realtime WebSocket — endpoint URL, handshake/run-task message, audio frame format (PCM 16k mono?), auth, and the documented example repos (e.g. github.com/mikuh/dashscope-realtime).',
      'Report ready-to-use request/response shapes an Android OkHttp client can implement.',
    ].join('\n'),
  },
  {
    key: 'python-analysis-tools',
    title: 'Python analysis environment for this workspace',
    prompt: [
      'Research and specify the Python environment for analyzing this Android corpus (smali, jars, APKs, oat/vdex) on Windows + Python 3.13.5.',
      '(a) For each of: androguard, pyaxmlparser, apkutils, lief, capstone, networkx, jinja2, pytest, requests, lxml — fetch https://pypi.org/pypi/<pkg>/json and report latest version, Requires-Python, whether a cp313 win_amd64 wheel exists, and whether it needs a C compiler/Visual Studio Build Tools;',
      '(b) which of these are ACTUALLY useful for: parsing AndroidManifest.xml, enumerating smali classes/methods, extracting xrefs across ~19000 smali files, diffing APK versions, decoding .oat/.vdex, dumping resources.arsc;',
      '(c) Windows-specific gotchas (wheel availability, long-path issues on P:, unicode filenames like 接口分析报告.md, antivirus slowdowns on large file trees);',
      '(d) recommend: venv from the existing Python 3.13.5 vs a conda env — give exact `python -m venv` command for this workspace and a pinned requirements.txt;',
      '(e) note anything that is NOT pip-installable and must be a separate binary (aapt2, jadx, apktool are Java — list them as such).',
      'Deliver a final requirements.txt content and the exact setup commands.',
    ].join('\n'),
  },
]

const RESEARCH_SCHEMA = {
  type: 'object',
  properties: {
    topic: { type: 'string' },
    summary: { type: 'string', description: '3-6 sentence overview of the domain' },
    findings: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          claim: { type: 'string' },
          detail: { type: 'string' },
          sources: { type: 'array', items: { type: 'string' } },
          confidence: { type: 'string', enum: ['high', 'medium', 'low'] },
        },
        required: ['claim', 'detail', 'sources', 'confidence'],
      },
    },
    setup_implications: {
      type: 'array',
      items: { type: 'string' },
      description: 'Concrete actions, exact commands, exact versions, exact URLs needed to set up this workspace',
    },
    open_questions: { type: 'array', items: { type: 'string' } },
  },
  required: ['topic', 'summary', 'findings', 'setup_implications', 'open_questions'],
}

const VERIFY_SCHEMA = {
  type: 'object',
  properties: {
    verdicts: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          claim: { type: 'string' },
          verdict: { type: 'string', enum: ['confirmed', 'refuted', 'partially_correct', 'unverifiable'] },
          correction: { type: 'string', description: 'If refuted or partially_correct, the corrected claim with the right value. Empty string otherwise.' },
          evidence: { type: 'string' },
          sources: { type: 'array', items: { type: 'string' } },
        },
        required: ['claim', 'verdict', 'correction', 'evidence', 'sources'],
      },
    },
    critical_errors: { type: 'array', items: { type: 'string' } },
    missing_setup_steps: { type: 'array', items: { type: 'string' } },
  },
  required: ['verdicts', 'critical_errors', 'missing_setup_steps'],
}

const PLAN_SCHEMA = {
  type: 'object',
  properties: {
    overview: { type: 'string' },
    pinned_versions: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          component: { type: 'string' },
          version: { type: 'string' },
          reason: { type: 'string' },
          source: { type: 'string' },
        },
        required: ['component', 'version', 'reason', 'source'],
      },
    },
    steps: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          id: { type: 'string' },
          title: { type: 'string' },
          why: { type: 'string' },
          commands: { type: 'array', items: { type: 'string' } },
          verify: { type: 'string' },
        },
        required: ['id', 'title', 'why', 'commands', 'verify'],
      },
    },
    risks: { type: 'array', items: { type: 'string' } },
  },
  required: ['overview', 'pinned_versions', 'steps', 'risks'],
}

log('Researching ' + DOMAINS.length + ' domains in parallel...')

const researched = await pipeline(
  DOMAINS,
  (d) => agent(
    BRIEF + '\n\n===== YOUR DOMAIN: ' + d.title + ' =====\n\n' + d.prompt,
    { label: 'research:' + d.key, phase: 'Research', schema: RESEARCH_SCHEMA, agentType: 'general-purpose' }
  ),
  (r, d) => {
    if (!r) return null
    const claims = r.findings.map((f, i) => (i + 1) + '. [' + f.confidence + '] ' + f.claim + '\n   detail: ' + String(f.detail).slice(0, 500) + '\n   claimed sources: ' + (f.sources || []).join(' , ')).join('\n')
    const vprompt = [
      'You are an ADVERSARIAL VERIFIER. Domain: ' + d.title + '.',
      'Your job is to REFUTE or CONFIRM each load-bearing claim below, and to find setup steps the researcher MISSED.',
      '',
      'Method:',
      '- Re-derive each claim from a PRIMARY source yourself (official docs, Maven POM, PyPI JSON, GitHub release/tag API, SDK repository XML, source code, --help output). Do NOT trust the researcher\'s source list.',
      '- Version numbers, exact URLs, exact CLI syntax, exact API field names, and compatibility claims are the highest-risk items: verify them against real artifact metadata, never memory.',
      '- Default to "unverifiable" if you cannot find primary evidence. Do NOT rubber-stamp.',
      '- Use "partially_correct" with an explicit correction when the claim is close but wrong in a detail that would break the setup.',
      '- You may run read-only shell commands (curl, tar -tzf, python) to inspect artifacts. Do not install or extract anything into the workspace.',
      '',
      'SEARCH CHANNELS: WebSearch tool; SearxNG JSON at http://localhost:8080/search?format=json&engines=bing|github|stackoverflow|quark|360search (google/ddg/brave/startpage are CAPTCHA-blocked); direct curl. WebFetch is BLOCKED. raw.githubusercontent.com is BLOCKED (use cdn.jsdelivr.net/gh/ or ghproxy.net).',
      '',
      'CLAIMS TO VERIFY (highest-risk first — spend your effort on the ones that would break the setup):',
      claims,
      '',
      'ALSO: list any setup step the researcher should have surfaced but did not (missing_setup_steps).',
    ].join('\n')
    return agent(vprompt, { label: 'verify:' + d.key, phase: 'Verify', schema: VERIFY_SCHEMA, agentType: 'general-purpose' })
      .then((v) => ({ domain: d.key, title: d.title, research: r, verification: v }))
  }
)

const ok = researched.filter(Boolean)
log('Research+verify done for ' + ok.length + '/' + DOMAINS.length + ' domains. Synthesizing...')

const digest = ok.map((r) => {
  const vs = (r.verification && r.verification.verdicts) || []
  const bad = vs.filter((v) => v.verdict === 'refuted' || v.verdict === 'partially_correct')
  return [
    '## DOMAIN: ' + r.domain + ' — ' + r.title,
    'SUMMARY: ' + r.research.summary,
    'FINDINGS:',
    r.research.findings.map((f) => '- (' + f.confidence + ') ' + f.claim + ' | ' + String(f.detail).slice(0, 350) + ' | src: ' + (f.sources || []).slice(0, 2).join(', ')).join('\n'),
    'SETUP IMPLICATIONS:',
    r.research.setup_implications.map((s) => '- ' + s).join('\n'),
    'VERIFIER CORRECTIONS (authoritative — override the finding above):',
    bad.length ? bad.map((v) => '- [' + v.verdict + '] ' + v.claim + ' => ' + v.correction).join('\n') : '- none',
    'VERIFIER-REPORTED MISSING STEPS:',
    ((r.verification && r.verification.missing_setup_steps) || []).map((s) => '- ' + s).join('\n') || '- none',
    'OPEN QUESTIONS:',
    r.research.open_questions.map((q) => '- ' + q).join('\n'),
  ].join('\n')
}).join('\n\n')

const plan = await agent(
  [
    'You are the setup architect. Below is verified research across 9 domains for an Android secondary-development workspace on Windows 11 (no admin / no system installs).',
    '',
    'Produce ONE coherent, executable setup plan for this workspace.',
    'Requirements:',
    '- Resolve every conflict using the VERIFIER CORRECTIONS (they are authoritative).',
    '- Pin EXACT versions for every component (JDK, Gradle, AGP, Kotlin, KSP, Android SDK packages, apktool, jadx, Python packages) with the reason and source.',
    '- Every step must have runnable commands for Windows + Git Bash, and a concrete verification command that proves the step worked.',
    '- Everything must be portable/user-scoped: no MSI installers, no admin, no system PATH edits. Show the exact env-var scoping.',
    '- Include the workspace directory layout you recommend.',
    '- Include the Python venv creation and its pinned requirements.',
    '- Call out the risks and the genuinely-unresolved unknowns (do not paper over them).',
    '- Order steps so each is verifiable before the next depends on it.',
    '',
    '=== VERIFIED RESEARCH DIGEST ===',
    digest,
  ].join('\n'),
  { label: 'synthesize', phase: 'Synthesize', schema: PLAN_SCHEMA, agentType: 'general-purpose' }
)

return { domains: ok, plan }
