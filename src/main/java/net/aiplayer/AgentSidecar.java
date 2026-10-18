package net.aiplayer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import net.neoforged.fml.loading.FMLPaths;

/**
 * agent sidecar：把内置的 agent 包（源码 + 运行时，见 assets/aiplayer/agent_bundle.zip）
 * 首次启动时部署到 {@code <游戏目录>/<deployDir>/<版本>/}，供后续拉起为独立进程。
 *
 * <p>职责边界（按 2026-10-16 与用户确定的方向）：
 * <ul>
 *   <li>模组只做「笨基础设施」：<strong>部署 / 拉起 / 看门狗</strong>——不重写大脑，agent 的
 *       唤起与运行行为由 agent 内部（daemon）自管</li>
 *   <li>版本标记：目标目录有 {@code aiplayer.version} 且内容等于当前 mod 版本 → 跳过（幂等）</li>
 *   <li>升级保留：重解压前保留 {@code keys.yaml}、{@code config/config.yaml}、{@code logs/}</li>
 *   <li>★密钥绝不入包：bundle 里没有 keys.yaml——首装由 {@link #ensureKeysTemplate} 生成模板</li>
 *   <li>全程防御式：任何失败只记日志，绝不让 mod 加载失败</li>
 * </ul>
 */
public final class AgentSidecar {

    private static final String BUNDLE_RESOURCE = "assets/aiplayer/agent_bundle.zip";
    private static final String MARKER_FILENAME = "aiplayer.version";
    /** ★与 agent 加载器一致：keys 在 config/ 子目录（config_loader.KEYS_PATH = <root>/config/keys.yaml）。
     *  曾因写成根目录 keys.yaml 导致加载器找不到→自动生成模板→占位 key 当真→LLM 全崩。 */
    private static final String KEYS_FILENAME = "config/keys.yaml";
    private static final String KEYS_TEMPLATE = """
            # keys.yaml —— API 密钥（★此文件绝不能提交/分发/打包）
            # 填入真实 api_key 后重启 agent 生效；api_key 也支持 $ENV_VAR 形式引用环境变量
            providers:
              - name: deepseek
                base_url: https://api.deepseek.com/v1
                api_key: "在此填入密钥"
                balance_api: https://api.deepseek.com/user/balance

            model_prices: {}
            """;

    private AgentSidecar() {
    }

    /** 部署入口（AiPlayer 初始化时调用；幂等，可重复调用）。 */
    public static void deploy() {
        boolean enabled;
        try {
            enabled = AiPlayerConfig.sidecarEnabled();
        } catch (Throwable t) {
            AiPlayer.LOG.warn("[sidecar] 配置未就绪，跳过部署: {}", t.toString());
            return;
        }
        if (!enabled) {
            AiPlayer.LOG.info("[sidecar] sidecar.enabled=false，跳过 agent 部署");
            return;
        }
        String version = AiPlayer.MOD_VERSION == null ? "unknown" : AiPlayer.MOD_VERSION;
        Path target = FMLPaths.GAMEDIR.get().resolve(AiPlayerConfig.sidecarDir()).resolve(version);
        try {
            if (isDeployed(target, version)) {
                AiPlayer.LOG.info("[sidecar] agent 已部署且版本一致（{}），跳过: {}", version, target);
                return;
            }
            deployTo(target, version);
            AiPlayer.LOG.info("[sidecar] agent 部署完成: {}", target);
        } catch (Throwable t) {
            AiPlayer.LOG.error("[sidecar] 部署失败（不影响游戏运行）: {}", t.toString(), t);
        }
    }

    private static boolean isDeployed(Path target, String version) {
        Path marker = target.resolve(MARKER_FILENAME);
        if (!Files.isRegularFile(marker)) {
            return false;
        }
        try {
            return version.equals(Files.readString(marker, StandardCharsets.UTF_8).trim());
        } catch (IOException e) {
            return false;
        }
    }

    private static void deployTo(Path target, String version) throws IOException {
        Path zipFile = extractBundleToTemp();
        Path stash = null;
        boolean userDataRestored = false;
        try {
            boolean fresh = !Files.isDirectory(target);
            if (!fresh) {
                // ★运行守卫（2026-10-18）：agent 正从该目录运行时重部署会把目录
                //   删一半/解压一半（deleteRecursively 对被占用文件静默跳过），
                //   最后 marker 照写 → 得到一个"标记为已部署"的混合损坏安装。
                if (isAgentAlive()) {
                    AiPlayer.LOG.warn("[sidecar] ★agent 正在运行（8770 在线），跳过重部署以免损坏安装：{}。"
                            + "先停止 agent（或升级 mod 版本号）后重启。", target);
                    return;
                }
                // 同版本重部署：先把用户数据挪到临时区，清空目录后再解压
                stash = stashUserData(target);
                deleteRecursively(target);
                Files.createDirectories(target);
            }
            unzip(zipFile, target);
            // ★★恢复用户数据必须在 unzip **之后**（2026-10-18 实测 bug）：
            //   bundle 内含 agent/config/config.yaml 的默认值。若先恢复再解压，
            //   用户的 roots.shared / bot_names 会被默认值覆盖 →
            //   文件工具指向不存在的 <GAME_DIR> 占位目录、@提及完全失灵。
            //   （keys.yaml 与 logs 因不在 bundle 内，才侥幸没中招。）
            if (fresh) {
                // 跨版本升级：从最近的旧版本目录继承 keys/config/logs
                inheritFromPrevious(target.getParent(), target);
            } else if (stash != null) {
                restoreUserData(stash, target);
                userDataRestored = true;
            }
            ensureKeysTemplate(target);
            Files.createDirectories(target);   // 目录必然已存在；防呆
            warnIfPlaceholderConfig(target);
            Files.writeString(target.resolve(MARKER_FILENAME), version, StandardCharsets.UTF_8);
        } finally {
            if (stash != null) {
                if (userDataRestored) {
                    cleanupStash(stash);
                } else {
                    // ★stash 是用户数据（keys/config/logs）的**唯一副本**（原件已随
                    //   deleteRecursively 删除）：部署中途失败时绝不能清理，否则
                    //   keys.yaml 里的 API 密钥与真实配置会永久丢失。留目录 + 写日志。
                    AiPlayer.LOG.error("[sidecar] ★部署未完成，用户数据备份保留在: {}（keys/config/logs 未丢失）",
                            stash);
                }
            }
            try {
                Files.deleteIfExists(zipFile);
            } catch (IOException ignored) {
            }
        }
    }

    /** 需要跨部署保留的用户数据（相对 target 的路径）。 */
    private static final String[] USER_DATA = {KEYS_FILENAME, "config/config.yaml", "logs"};

    /** 跨版本继承：把 base 下版本号最新的兄弟目录里的 keys/config/logs 拷过来（best-effort）。
     *  ★按目录 mtime 取最新——版本字符串字典序不可靠（"0.2.1.9" > "0.2.1.10"）。 */
    private static void inheritFromPrevious(Path base, Path target) {
        try (Stream<Path> dirs = Files.list(base)) {
            Path prev = dirs
                    .filter(Files::isDirectory)
                    .filter(d -> !d.equals(target))
                    .filter(d -> Files.isRegularFile(d.resolve("run.py")))
                    .max(Comparator.comparingLong(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            return 0L;
                        }
                    }))
                    .orElse(null);
            if (prev == null) {
                return;
            }
            for (String rel : new String[]{KEYS_FILENAME, "config/config.yaml", "logs"}) {
                Path src = prev.resolve(rel);
                if (!Files.exists(src)) {
                    continue;
                }
                Path dst = target.resolve(rel);
                Files.createDirectories(dst.getParent());
                copyTree(src, dst);
            }
            AiPlayer.LOG.info("[sidecar] 已从上一版本目录继承用户数据: {}", prev.getFileName());
        } catch (Throwable t) {
            AiPlayer.LOG.warn("[sidecar] 继承上一版本数据失败（继续全新部署）: {}", t.toString());
        }
    }

    private static void copyTree(Path src, Path dst) throws IOException {
        if (Files.isRegularFile(src)) {
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path out = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(p, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** 从 mod jar 把 bundle 拷到临时文件（ZipInputStream 流式解压不需要 ZipFile，但临时文件便于重试）。 */
    private static Path extractBundleToTemp() throws IOException {
        try (InputStream in = AgentSidecar.class.getClassLoader().getResourceAsStream(BUNDLE_RESOURCE)) {
            if (in == null) {
                throw new IOException("bundle 资源缺失: " + BUNDLE_RESOURCE);
            }
            Path tmp = Files.createTempFile("aiplayer-agent-bundle", ".zip");
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            return tmp;
        }
    }

    /** 把用户数据（keys/config/logs）挪到临时区并返回该临时目录。
     *
     * <p>★只 stash 不恢复——恢复必须由调用方在 {@code unzip} 之后做（见 deployTo 注释）。
     * 旧实现 {@code preserveAndClear} 把"挪走+清空+放回"三步绑在一起，放回发生在解压之前，
     * 于是 bundle 里的默认 config.yaml 又把用户配置覆盖回去了。
     *
     * <p>★备份目录建在 target 的父目录下（与游戏目录同一文件系统）：跨卷
     * {@code Files.move} 搬非空 logs 目录会直接失败；同卷则是原子 rename。
     * 失败残留时用户也能在 aiplayer-agent/ 下直接看到并手动恢复。
     */
    private static Path stashUserData(Path target) throws IOException {
        Path backup = Files.createTempDirectory(target.getParent(), "aiplayer-agent-keep");
        for (String rel : USER_DATA) {
            Path src = target.resolve(rel);
            if (!Files.exists(src)) {
                continue;
            }
            Path dst = backup.resolve(rel);
            Files.createDirectories(dst.getParent());
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
        return backup;
    }

    /** 把 stashUserData 备份的用户数据放回 target；目标已存在则先删（解压出来的默认文件）。 */
    private static void restoreUserData(Path backup, Path target) throws IOException {
        for (String rel : USER_DATA) {
            Path src = backup.resolve(rel);
            if (!Files.exists(src)) {
                continue;
            }
            Path dst = target.resolve(rel);
            Files.createDirectories(dst.getParent());
            if (Files.exists(dst)) {
                deleteRecursively(dst);
            }
            moveTree(src, dst);
        }
    }

    private static void cleanupStash(Path backup) {
        try (Stream<Path> walk = Files.walk(backup)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
        }
    }

    /** 防呆：部署后若 config.yaml 仍带脱敏占位符，说明继承/恢复没生效，必须让它在日志里可见。
     *
     * <p>这两行占位符是"静默致命"的典型：{@code roots.shared=<GAME_DIR>/aiplayer} 会让所有文件工具
     * 指向不存在的目录；{@code bot_names=["ExampleBot"]} 会让 agent 完全不再响应真实 @提及。
     * 两者都不会报错，只会表现成"功能突然不工作"。
     */
    private static void warnIfPlaceholderConfig(Path target) {
        Path cfg = target.resolve("config/config.yaml");
        if (!Files.isRegularFile(cfg)) {
            return;
        }
        try {
            String txt = Files.readString(cfg, StandardCharsets.UTF_8);
            if (txt.contains("<GAME_DIR>")) {
                AiPlayer.LOG.warn("[sidecar] ★config.yaml 里 roots.shared 仍是脱敏占位符 <GAME_DIR>——"
                        + "文件工具会指向不存在的目录。请改成你本机的 <游戏目录>/aiplayer");
            }
            if (txt.contains("bot_names: [\"ExampleBot\"]")) {
                AiPlayer.LOG.warn("[sidecar] ★config.yaml 里 bot_names 仍是示例值 ExampleBot——"
                        + "agent 不会响应真实的 @提及。请改成你 bot 的游戏内名字");
            }
        } catch (IOException ignored) {
        }
    }

    /** zip 解压（带 zip-slip 防护：entry 规范化后必须仍在目标目录内）。 */
    private static void unzip(Path zipFile, Path target) throws IOException {
        Files.createDirectories(target);
        String prefix = "agent/";   // bundle 内统一前缀，解开后落到 target 根
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                if (prefix != null && !prefix.isEmpty()) {
                    if (!name.startsWith(prefix)) {
                        continue;   // 不属于 agent/ 前缀的条目忽略
                    }
                    name = name.substring(prefix.length());
                }
                if (name.isEmpty()) {
                    continue;
                }
                Path out = target.resolve(name).normalize();
                if (!out.startsWith(target.normalize())) {
                    throw new IOException("zip-slip 检测: " + e.getName());
                }
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zin, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void ensureKeysTemplate(Path target) throws IOException {
        Path keys = target.resolve(KEYS_FILENAME);
        if (!Files.isRegularFile(keys)) {
            Files.writeString(keys, KEYS_TEMPLATE, StandardCharsets.UTF_8);
        }
    }

    private static void moveTree(Path src, Path dst) throws IOException {
        try {
            Files.move(src, dst);
            return;
        } catch (AtomicMoveNotSupportedException ignored) {
            // 同卷一般不会；跨卷/跨文件系统时走复制
        }
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path rel = src.relativize(p);
                Path out = dst.resolve(rel.toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(p, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }

    // ================= 进程拉起 / 看门狗 / 关停挂钩（任务 #37） =================

    /** 自己拉起的 agent 进程（null=没有——复用的外部实例绝不持有，"只杀自己拉起的"）。 */
    private static volatile Process ownedProcess;
    private static volatile Path deployTarget;
    private static volatile Thread watchdogThread;
    private static volatile boolean shutdownHookInstalled;

    /** 控制口（agent --daemon 的 8770）。 */
    private static final String CONTROL_URL = "http://127.0.0.1:8770/status";

    /** ensure_running 语义：8770 已有实例 → 复用；否则拉起 --daemon。
     *  waitForReady=true 时同步等 8770 就绪（≤20s，仅游戏启动路径用）；
     *  动作调用一律 false（★动作在主线程执行，绝不阻塞——就绪与否由看门狗盯）。 */
    public static synchronized String ensureRunning(boolean waitForReady) {
        try {
            if (!AiPlayerConfig.sidecarEnabled() || !AiPlayerConfig.sidecarAutoStart()) {
                return "sidecar 未启用或 autoStart=false（enabled=%s）"
                        .formatted(AiPlayerConfig.sidecarEnabled());
            }
            if (isAgentAlive()) {
                startWatchdog();   // 看门狗只看自己拉起的；外部实例由其启动方负责
                return "agent 已在运行（8770 在线）——复用现有实例";
            }
            launch(false);
            if (waitForReady) {
                for (int i = 0; i < 10; i++) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    if (isAgentAlive()) {
                        return "agent 已启动（8770 就绪）";
                    }
                }
                return "agent 已拉起，20s 内 8770 未就绪——看门狗会继续盯";
            }
            return "agent 拉起中（异步，就绪与否看门狗会盯；再次 agent_status 查询）";
        } catch (Throwable t) {
            AiPlayer.LOG.error("[sidecar] ensureRunning 失败（不影响游戏运行）: {}", t.toString(), t);
            return "拉起失败: " + t.toString();
        }
    }

    /** 兼容游戏启动路径（同步等就绪）。 */
    public static void ensureRunning() {
        ensureRunning(true);
    }

    private static boolean isAgentAlive() {
        try {
            java.net.HttpURLConnection c =
                    (java.net.HttpURLConnection) new java.net.URI(CONTROL_URL).toURL().openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            int code = c.getResponseCode();
            c.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static void launch(boolean waitForReady) throws IOException {
        Path target = deployTarget();
        if (target == null || !Files.isRegularFile(target.resolve("run.py"))) {
            AiPlayer.LOG.warn("[sidecar] 部署目录无 run.py，跳过拉起: {}", target);
            return;
        }
        Files.createDirectories(target.resolve("logs"));
        // ★python 选择：内嵌运行时（runtime/python/python.exe，整合包零依赖）优先；
        //   不存在（如 --skip-runtime 的快速迭代包）才回退配置 pythonPath（走 PATH）
        String python = AiPlayerConfig.sidecarPython();
        Path embedded = target.resolve("runtime").resolve("python").resolve("python.exe");
        if (Files.isRegularFile(embedded)) {
            python = embedded.toString();
            AiPlayer.LOG.info("[sidecar] 使用内嵌 Python: {}", python);
        }
        ProcessBuilder pb = new ProcessBuilder(python, "run.py", "--daemon");
        pb.directory(target.toFile());
        pb.environment().put("AIPLAYER_HOME", target.toString());
        pb.redirectErrorStream(true);
        pb.redirectOutput(target.resolve("logs/process.log").toFile());
        AiPlayer.LOG.info("[sidecar] 拉起 agent: {} run.py --daemon （AIPLAYER_HOME={}）", python, target);
        ownedProcess = pb.start();
        deployTarget = target;
        installShutdownHook();
        if (!waitForReady) {
            return;   // 异步路径：不做同步等待（主线程保护），看门狗会盯
        }
        // 等 8770 就绪（最多 20s），只做日志汇报，不阻塞游戏
        for (int i = 0; i < 10; i++) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (isAgentAlive()) {
                AiPlayer.LOG.info("[sidecar] agent 控制口就绪（8770），启动完成");
                break;
            }
            if (i == 9) {
                AiPlayer.LOG.warn("[sidecar] 20s 内 8770 未就绪（agent 可能仍在初始化，看门狗会继续盯）");
            }
        }
        startWatchdog();
    }

    private static Path deployTarget() {
        String version = AiPlayer.MOD_VERSION == null ? "unknown" : AiPlayer.MOD_VERSION;
        return FMLPaths.GAMEDIR.get().resolve(AiPlayerConfig.sidecarDir()).resolve(version);
    }

    /** 看门狗：周期探活，死亡且是自己拉起的 → 带退避重启（1/2/4...上限60s）。 */
    private static synchronized void startWatchdog() {
        int interval = AiPlayerConfig.sidecarWatchdog();
        if (interval <= 0 || watchdogThread != null && watchdogThread.isAlive()) {
            return;
        }
        watchdogThread = new Thread(() -> {
            long backoff = 1000;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(interval * 1000L);
                } catch (InterruptedException e) {
                    return;
                }
                Process p = ownedProcess;
                if (p == null) {
                    return;   // 只看自己拉起的实例
                }
                if (isAgentAlive()) {
                    backoff = 1000;
                    continue;
                }
                AiPlayer.LOG.warn("[sidecar] 看门狗：agent 探活失败，{}ms 后重启（退避 {}ms）",
                        backoff, backoff);
                try {
                    Thread.sleep(backoff);
                    backoff = Math.min(backoff * 2, 60_000);
                    if (ownedProcess != null && !isAgentAlive()) {
                        launch(false);
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    AiPlayer.LOG.error("[sidecar] 看门狗重启失败: {}", e.toString());
                }
            }
        }, "aiplayer-sidecar-watchdog");
        watchdogThread.setDaemon(true);
        watchdogThread.start();
        AiPlayer.LOG.info("[sidecar] 看门狗已启动（间隔 {}s，只监控本模组拉起的实例）",
                AiPlayerConfig.sidecarWatchdog());
    }

    /** 游戏退出钩子：attach 模式杀掉自己拉起的 agent（含子进程树）；detach 模式放行。 */
    private static synchronized void installShutdownHook() {
        if (shutdownHookInstalled) {
            return;
        }
        shutdownHookInstalled = true;
        Runtime.getRuntime().addShutdownHook(new Thread(AgentSidecar::onGameExit,
                "aiplayer-sidecar-shutdown"));
    }

    /** ★动作侧的停止：立即返回（主线程保护），后台线程做优雅停。
     *  流程：8770/stop（daemon 优雅收轮）→ 轮询 15s → 未退则强杀自己拉起的进程树。 */
    public static String stopAsync() {
        if (!isAgentAlive()) {
            return "agent 未在运行（8770 不在线）";
        }
        Thread t = new Thread(() -> {
            try {
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                            new java.net.URI("http://127.0.0.1:8770/stop").toURL().openConnection();
                    c.setRequestMethod("POST");
                    c.setConnectTimeout(2000);
                    c.setReadTimeout(5000);
                    c.getResponseCode();
                    c.disconnect();
                } catch (Exception e) {
                    AiPlayer.LOG.warn("[sidecar] 发送 stop 失败: {}", e.toString());
                }
                for (int i = 0; i < 15; i++) {
                    Thread.sleep(1000);
                    if (!isAgentAlive()) {
                        AiPlayer.LOG.info("[sidecar] agent 已优雅退出（{}s）", i + 1);
                        return;
                    }
                }
                Process p = ownedProcess;
                if (p != null) {
                    AiPlayer.LOG.warn("[sidecar] 优雅停超时，强杀自己拉起的进程树");
                    p.descendants().forEach(ProcessHandle::destroyForcibly);
                    p.destroyForcibly();
                } else {
                    AiPlayer.LOG.warn("[sidecar] 优雅停超时；实例非本模组拉起，不强杀（可用 停止agent.bat）");
                }
            } catch (Throwable e) {
                AiPlayer.LOG.error("[sidecar] 停止流程异常: {}", e.toString());
            }
        }, "aiplayer-sidecar-stop");
        t.setDaemon(true);
        t.start();
        return "已发送停止指令，agent 优雅收轮中（最多 15s，超时强杀自己拉起的实例）";
    }

    /** 状态一览（动作侧用；探活均为短超时，主线程安全）。 */
    public static String statusLine() {
        boolean c8770 = isAgentAlive();
        Process p = ownedProcess;
        return "8770=" + (c8770 ? "在线" : "离线")
                + " | owned=" + (p == null ? "无(复用或未拉起)" : "PID " + p.pid())
                + " | launchMode=" + AiPlayerConfig.sidecarLaunchMode()
                + " | autoStart=" + AiPlayerConfig.sidecarAutoStart()
                + " | watchdog=" + AiPlayerConfig.sidecarWatchdog() + "s";
    }

    private static void onGameExit() {
        Process p = ownedProcess;
        if (p == null) {
            return;   // 没拉起过（复用的外部实例）→ 不动
        }
        if ("detach".equals(AiPlayerConfig.sidecarLaunchMode())) {
            AiPlayer.LOG.info("[sidecar] launchMode=detach，游戏退出后 agent 继续独立运行");
            return;
        }
        AiPlayer.LOG.info("[sidecar] launchMode=attach，游戏退出，关闭自己拉起的 agent 进程树");
        try {
            p.descendants().forEach(ProcessHandle::destroy);
            p.destroy();
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
            }
        } catch (Throwable t) {
            AiPlayer.LOG.warn("[sidecar] 关停异常: {}", t.toString());
        }
        ownedProcess = null;
    }
}
