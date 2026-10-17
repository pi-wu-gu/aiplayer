package net.aiplayer;

import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLPaths;

/**
 * AI Player —— 把 MC AI 玩家的「控制组件」收进单个模组。
 *
 * <p>当前里程碑（v0.1）：打通<strong>运行数据目录</strong>机制——
 * 在游戏目录下维护 {@code <gamedir>/aiplayer/}，用于存放状态、行为日志、聊天缓冲、会话快照。
 * 这一步不依赖任何外部运行时，可在任意 1.21.1 NeoForge 整合包里直接验证。
 *
 * <p>后续里程碑见 {@code README.md}：动作层 → 内嵌桥接 → GraalPy 内嵌大脑。
 *
 * <p><strong>纯客户端 mod</strong>：专用服务器上不加载（dist = CLIENT）。
 */
@Mod(value = AiPlayer.MODID, dist = Dist.CLIENT)
public class AiPlayer {

    /** mod id，必须与 gradle.properties 的 mod_id、neoforge.mods.toml 的 ${mod_id} 一致 */
    public static final String MODID = "aiplayer";

    public static final Logger LOG = LogUtils.getLogger();

    /** 真实 mod 版本（从 ModContainer 读，避免手写版本号写飘——v0.1 就写飘过一次）。 */
    public static volatile String MOD_VERSION = "(unknown)";

    public AiPlayer(IEventBus modEventBus, ModContainer modContainer) {
        // 用户配置走 NeoForge 标准位置：<gamedir>/config/aiplayer-common.toml（可在 Mods→Config 界面里改）
        modContainer.registerConfig(ModConfig.Type.COMMON, AiPlayerConfig.SPEC);
        modEventBus.addListener(this::onCommonSetup);

        try {
            MOD_VERSION = modContainer.getModInfo().getVersion().toString();
        } catch (Exception ignored) {
            // 取不到就保持 unknown
        }

        // ★坑（实测踩到，v0.1 构建因此加载失败）：
        //   构造函数执行时配置**尚未加载**，此时读 ModConfigSpec 会抛
        //   IllegalStateException: Cannot get config value before config is loaded.
        //   所有配置读取都必须放到 FMLCommonSetupEvent / ModConfigEvent 之后。
        LOG.info("[AI Player] 正在加载（mod 版本 {}）", MOD_VERSION);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        // 建目录/写文件属于 IO，放 enqueueWork 里在主线程队列执行，避免并行加载阶段抢资源
        event.enqueueWork(this::initRuntimeDataDir);
    }

    /** 初始化运行数据目录。全程防御式：任何失败都只记日志，绝不让 mod 加载失败。 */
    private void initRuntimeDataDir() {
        boolean enabled;
        try {
            enabled = AiPlayerConfig.enabled();
        } catch (Exception e) {
            LOG.error("[AI Player] 配置尚未就绪，跳过数据目录初始化: {}", e.toString());
            return;
        }
        if (!enabled) {
            LOG.info("[AI Player] enabled=false，本次不初始化运行数据目录");
            return;
        }
        try {
            LOG.info("[AI Player] 游戏目录 = {}", FMLPaths.GAMEDIR.get());
            List<Path> created = AiPlayerPaths.ensureLayout();
            LOG.info("[AI Player] 运行数据目录就绪: {}", AiPlayerPaths.dataDir());
            if (created.isEmpty()) {
                LOG.info("[AI Player]   （目录已存在，无需新建）");
            } else {
                for (Path p : created) {
                    LOG.info("[AI Player]   + {}", FMLPaths.GAMEDIR.get().relativize(p));
                }
            }
        } catch (Exception e) {
            LOG.error("[AI Player] 创建运行数据目录失败: {}", e.toString());
        }
        try {
            AiPlayerPaths.warnIfAnotherInstanceLooksAlive();
        } catch (Exception e) {
            LOG.warn("[AI Player] 实例锁检查失败: {}", e.toString());
        }
        // agent sidecar：首次启动部署 agent 包（幂等；防御式，失败不影响游戏）
        try {
            AgentSidecar.deploy();
        } catch (Exception e) {
            LOG.warn("[AI Player] sidecar 部署异常: {}", e.toString());
        }
        // autoStart：ensure_running 语义拉起 agent --daemon（已跑则复用；防御式）
        try {
            AgentSidecar.ensureRunning();
        } catch (Exception e) {
            LOG.warn("[AI Player] sidecar 拉起异常: {}", e.toString());
        }
    }
}
