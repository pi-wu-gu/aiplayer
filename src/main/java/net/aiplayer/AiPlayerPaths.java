package net.aiplayer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.neoforged.fml.loading.FMLPaths;

/**
 * 运行数据目录：{@code <gamedir>/aiplayer/}。
 *
 * <p>设计要点（按 2026-10-15 讨论确定）：
 * <ul>
 *   <li>放<strong>游戏目录下</strong>而不是 {@code config/}：{@code config/} 常被整合包同步/清理工具动到</li>
 *   <li>路径可通过配置的 {@code dataDirectory} 改到游戏目录之外（防整合包重装误删）</li>
 *   <li>全程用 {@link Path} 操作，不做字符串拼接 —— 游戏目录常含中文与空格</li>
 *   <li>写文件一律"先写 .tmp 再移动"，避免崩溃时留下半截 JSON 把状态写坏</li>
 *   <li>带实例锁：同游戏目录跑两个客户端时提示互踩风险</li>
 * </ul>
 */
public final class AiPlayerPaths {

    /** 默认目录名（相对游戏目录） */
    public static final String DEFAULT_DIR_NAME = "aiplayer";

    /** 运行数据目录骨架：logs/chat/sessions/capabilities + 自建工具区 tools/ + 自建文档区 docs/ */
    private static final String[] SUBDIRS = {"logs", "chat", "sessions", "capabilities", "tools", "docs"};

    private static final String README = """
            AI Player 运行数据目录
            ======================

            这个目录由 AI Player 模组创建，用来存放 AI 玩家的运行数据：
              logs/          逐步行为日志（思考 / 工具调用 / 错误）
              chat/          游戏聊天栏缓冲（供"只注入新消息"用）
              sessions/      每轮运行的会话快照（崩溃后可用于恢复）
              capabilities/  当前整合包的能力探测结果缓存（有哪些动作可用）
              tools/         ★ 自建工具区：AI 自己写的工具，index.json 是注册表
              docs/          ★ 自建文档区：AI 自己维护的笔记 / SOP / 踩坑记录（INDEX.md 是入口）
              state.json     运行状态（游标 / 积压 / 统计），重启不丢

            说明：
              * 整个目录可以安全删除 —— 下次启动会重新创建，只是会丢掉历史记录与统计。
              * 整合包更新通常不会动这里；但"重装/清缓存"可能带走它，
                要长期保留请在 config/aiplayer-common.toml 里把 dataDirectory 改成游戏目录之外的绝对路径。
              * 同一个游戏目录不要同时运行两个 AI 客户端，否则会互相覆盖 state.json。
              * 本目录不含任何密钥；API Key 在 config/aiplayer-common.toml 里。
            """;

    /** 自建文档区入口的初始内容：告诉 agent 这块地方是给它用的。 */
    private static final String DOCS_INDEX = """
            # 自建文档区（AI 自己维护）

            这里归 AI 玩家自己用：笔记、SOP、踩坑记录、环境说明都可以写在这里。

            ## 建议用法
            - `INDEX.md` 是入口，自己维护目录，别让它过期
            - 每种事情一个文件，例如：
              - `环境.md`          —— 这个服务器/整合包的实际情况（清理规则、反作弊、插件）
              - `动作备忘.md`      —— 哪些动作在这个整合包里可用、坑在哪
              - `本能_<名字>.md`   —— 自己写的本能流程（进食 / 睡觉 / 避险 …）
              - `踩坑.md`          —— 失败案例与根因

            ## 约束
            - 只能读写 `<gamedir>/aiplayer/` 之内的路径（模组会拒绝目录穿越）
            - 别在这里放密钥；API Key 在 `config/aiplayer-common.toml`
            """;

    private AiPlayerPaths() {
    }

    /**
     * 把"相对路径"解析到运行数据目录内，并做**目录穿越防护**。
     *
     * @return 合法路径；越界（含 `..`）返回 {@code null}
     */
    public static Path resolveInside(String rel) {
        try {
            Path base = dataDir();
            Path target = base.resolve(rel == null ? "" : rel).toAbsolutePath().normalize();
            if (!inside(base, target)) {
                return null;                       // ① 字面穿越（..、绝对路径）
            }
            // ② 符号链接 / junction 逃逸：工作区里放一个指向外部的链接，normalize 看不出来。
            //    做"尽力而为"的真实路径校验——文件可能尚不存在（写新文件）或权限受限，
            //    因此 toRealPath 失败必须回退，绝不能因它失败就拒绝合法路径。
            Path realBase = realOrNull(base);
            Path realTarget = realOrNull(target);
            if (realBase != null && realTarget != null) {
                if (!inside(realBase, realTarget)) {
                    return null;
                }
            } else if (hasSymlinkAncestor(base, target)) {
                return null;                       // ③ 真实路径拿不到但路径上确实有链接 → 保守拒绝
            }
            return target;
        } catch (Exception e) {
            return null;
        }
    }

    /** 是否 Windows（路径大小写不敏感，需统一小写后比较）。 */
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /** 包含判定：target 是否在 base 之内（含相等）。 */
    private static boolean inside(Path base, Path target) {
        if (base.equals(target)) {
            return true;
        }
        String b = base.toString();
        String t = target.toString();
        if (WINDOWS) {
            b = b.toLowerCase(Locale.ROOT);
            t = t.toLowerCase(Locale.ROOT);
        }
        String sep = java.io.File.separator;
        return t.startsWith(b.endsWith(sep) ? b : b + sep);
    }

    private static Path realOrNull(Path p) {
        try {
            return p.toRealPath();
        } catch (Exception e) {
            return null;
        }
    }

    /** base→target 之间（含 base 自身）是否存在符号链接 / junction。判不了就保守返回 true。 */
    private static boolean hasSymlinkAncestor(Path base, Path target) {
        try {
            if (Files.isSymbolicLink(base)) {
                return true;
            }
            Path cur = base;
            for (Path seg : base.relativize(target)) {
                cur = cur.resolve(seg);
                if (Files.isSymbolicLink(cur)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    /** 解析运行数据目录（相对路径基于游戏目录）。 */
    public static Path dataDir() {
        String raw = AiPlayerConfig.dataDirectory();
        if (raw == null || raw.isBlank()) {
            raw = DEFAULT_DIR_NAME;
        }
        Path p = Path.of(raw);
        if (!p.isAbsolute()) {
            p = FMLPaths.GAMEDIR.get().resolve(raw);
        }
        return p.toAbsolutePath().normalize();
    }

    public static Path of(String first, String... more) {
        return dataDir().resolve(Path.of(first, more));
    }

    /** 建立目录骨架（幂等）。返回本次新建的路径列表，供日志展示。 */
    public static List<Path> ensureLayout() throws IOException {
        List<Path> created = new ArrayList<>();
        if (Files.notExists(dataDir())) {
            Files.createDirectories(dataDir());
            created.add(dataDir());
        }
        for (String sub : SUBDIRS) {
            Path d = dataDir().resolve(sub);
            if (Files.notExists(d)) {
                Files.createDirectories(d);
                created.add(d);
            }
        }
        Path readme = dataDir().resolve("README.txt");
        if (Files.notExists(readme)) {
            Files.writeString(readme, README, StandardCharsets.UTF_8);
            created.add(readme);
        }
        // 自建文档区入口
        Path docsIndex = dataDir().resolve("docs").resolve("INDEX.md");
        if (Files.notExists(docsIndex)) {
            Files.writeString(docsIndex, DOCS_INDEX, StandardCharsets.UTF_8);
            created.add(docsIndex);
        }
        // 自建工具区注册表（空表，由 agent 自己往里加条目）
        Path toolsIndex = dataDir().resolve("tools").resolve("index.json");
        if (Files.notExists(toolsIndex)) {
            writeAtomic(toolsIndex, "{\n  \"version\": 1,\n  \"tools\": []\n}\n");
            created.add(toolsIndex);
        }
        Path state = dataDir().resolve("state.json");
        if (Files.notExists(state)) {
            writeAtomic(state, "{\n"
                    + "  \"note\": \"AI Player 运行状态；守护/大脑会把游标、积压、统计写在这里\",\n"
                    + "  \"version\": 1,\n"
                    + "  \"runs\": 0\n"
                    + "}\n");
            created.add(state);
        }
        return created;
    }

    /**
     * 原子写：先写同目录的 .tmp，再移动到目标。避免崩溃时留下半截文件。
     *
     * @return 是否成功
     */
    public static boolean writeAtomic(Path target, String content) {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            AiPlayer.LOG.warn("[AI Player] 写入失败 {}: {}", target, e.toString());
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 清理失败无所谓
            }
            return false;
        }
    }

    /** 实例锁文件路径。 */
    public static Path lockFile() {
        return dataDir().resolve("instance.lock");
    }

    /**
     * 写实例锁，并在发现"另一个实例最近还活着"时告警。
     *
     * <p>不做强制互斥（避免误锁把用户挡在游戏外），只提示 + 记录，
     * 因为两个客户端同目录互踩的后果是状态覆盖，不是数据损坏。
     */
    public static void warnIfAnotherInstanceLooksAlive() {
        Path lock = lockFile();
        try {
            if (Files.exists(lock)) {
                Duration age = Duration.between(Files.getLastModifiedTime(lock).toInstant(), Instant.now());
                if (age.abs().toMinutes() < 10) {
                    AiPlayer.LOG.warn("[AI Player] 检测到同一数据目录近期有另一个实例在运行（{} 分钟前），"
                            + "同时跑两个客户端会互相覆盖 state.json：{}", age.toMinutes(), lock);
                }
            }
            writeAtomic(lock, "pid=" + ProcessHandle.current().pid()
                    + "\nstarted=" + Instant.now() + "\n");
        } catch (IOException e) {
            AiPlayer.LOG.warn("[AI Player] 写实例锁失败: {}", e.toString());
        }
    }
}
