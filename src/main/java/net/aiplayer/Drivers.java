package net.aiplayer;

import java.util.ArrayList;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 长时程动作的 tick 驱动器（v0.2.1.35）。
 *
 * <p><b>为什么需要这个类</b>：{@code ControlServer.runOnClientThread} 把每个动作派发到客户端主线程
 * 并等它返回——动作体<strong>不能阻塞等待游戏推进</strong>（主线程被占住，tick 就不会来，
 * 等的东西永远完不成）。凡是"要跨多个 tick 才能完成"的语义，都必须拆成：
 * <ol>
 *   <li>动作体（主线程）：只做<b>发起</b>，立刻返回（参照 sign_edit 的 pending 模式）；</li>
 *   <li>{@code ClientTickEvent.Post} 钩子（{@code AiPlayerClient.onClientTick}）：每 tick 推进一小步；</li>
 *   <li>终态写进任务对象 + {@code ChatBuffer}（模型下一轮从聊天尾部就能看到），查询走独立动作。</li>
 * </ol>
 *
 * <p>本类承载三个驱动器：
 * <ul>
 *   <li><b>dig</b>：破坏指定坐标的方块。走 {@code MultiPlayerGameMode.startDestroyBlock} +
 *       逐 tick {@code continueDestroyBlock}——<b>不经过按键注入链路</b>，因此不受
 *       "鼠标未抓取 / 窗口未激活"门禁影响（2026-10-17 实锤的挖方块失效根因，见
 *       {@code docs/记忆/2026-10-17-挖方块诊断.md}）。</li>
 *   <li><b>look_lock</b>：把视角锁定朝向指定方块（可选锁某面而非中心，v0.2.1.36 起），限时；任何外部视角操作立即中断。</li>
 *   <li><b>place</b>（v0.2.1.39）：放置手持方块到指定空气格。走
 *       {@code MultiPlayerGameMode.useItemOn} 发包路径（与真实玩家右键同一条链路），
 *       同样不经按键注入——2026-10-17/18 实锤按键放置（press/keys key.use）在窗口非前台时
 *       完全失效（use 依赖的 {@code mc.hitResult} 与 looking_at 的 {@code p.pick} 自算不是一套）。
 *       ★放置有<b>实体碰撞箱</b>约束：玩家 AABB（宽 0.6 高 1.8）与目标格重叠 → 服务端必然驳回，
 *       发起前必须预检（见 {@link #startPlace}）。</li>
 * </ul>
 *
 * <p>线程模型：本类所有方法都只在客户端主线程被调用（动作派发与 tick 钩子同线程），
 * 任务字段仍标 {@code volatile} 以防将来有读取方跨线程。
 */
final class Drivers {

    /** 破坏距离上限（与 {@code state.looking_at} 的 pick 距离一致；服务端也有自己的距离校验）。 */
    static final double MAX_DIG_REACH = 6.0;

    /** 乐观破块的回滚防护：客户端破块是先斩后奏（预测发包），服务端可能驳回 → 连续 N tick 都是空气才算真破。 */
    private static final int AIR_CONFIRM_TICKS = 3;

    /** 视角锁定中断检测阈值（度）。我们每 tick 写精确值，任何真实鼠标/动作转向都远大于此。 */
    private static final float TURN_EPS = 0.05f;

    /** 实体锁定的距离上限（超出 = 目标脱离，结束锁定）。比 dig 的 6 格宽，视角跟随不需要交互距离。 */
    static final double MAX_LOCK_REACH = 8.0;

    private Drivers() {
    }

    // ================================================================ dig

    /** 挖掘任务。{@code state}: running → broken / timeout / failed / cancelled（终态即墓碑，供事后查询）。 */
    static final class DigTask {
        final BlockPos pos;
        final Direction dir;
        final String blockId;
        final long startedAtMs;
        final long deadlineMs;
        volatile String state = "running";
        volatile String terminal = null;
        volatile int airTicks = 0;
        volatile int lastStage = -1;
        volatile long endedAtMs = 0;

        DigTask(BlockPos pos, Direction dir, String blockId, long startedAtMs, long deadlineMs) {
            this.pos = pos;
            this.dir = dir;
            this.blockId = blockId;
            this.startedAtMs = startedAtMs;
            this.deadlineMs = deadlineMs;
        }
    }

    private static volatile DigTask dig = null;

    /**
     * 发起破坏（主线程，立即返回）。
     *
     * @param pos       目标方块坐标
     * @param timeoutMs 超时；超时把任务置为 timeout 终态并给模型明确报错
     * @return JSON：{@code digging:true}（开始）/ {@code dig_already_air}（目标已是空气）
     *         / 各类 {@code dig_err:}（发起前校验失败）
     */
    static String startDig(BlockPos pos, long timeoutMs) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null) {
            return errJson("dig_err: 玩家/世界未就绪（主菜单或重连中）");
        }
        // 同一时刻只允许一个挖掘任务：旧任务直接取消（自愈，避免上个任务卡死占位）
        DigTask cur = dig;
        if (cur != null && "running".equals(cur.state)) {
            cancelDig("被新的 dig 取代");
        }
        BlockState st = mc.level.getBlockState(pos);
        if (st.isAir()) {
            return Json.obj(Json.kv("ok", Json.bool(true)),
                    Json.kv("result", Json.str("dig_already_air")),
                    Json.kv("pos", posArr(pos)),
                    Json.kv("hint", Json.str("目标位置已是空气，无需破坏")));
        }
        if (!st.getFluidState().isEmpty()) {
            return errJson("dig_err: 目标是流体（" + Actions.blockId(st) + "），不可破坏");
        }
        double dx = pos.getX() + 0.5 - p.getX();
        double dy = pos.getY() + 0.5 - p.getEyeY();
        double dz = pos.getZ() + 0.5 - p.getZ();
        double d2 = dx * dx + dy * dy + dz * dz;
        if (d2 > MAX_DIG_REACH * MAX_DIG_REACH) {
            return errJson(String.format("dig_err: 目标超出破坏距离（%.1f > %.1f 格）——先走近再挖",
                    Math.sqrt(d2), MAX_DIG_REACH));
        }
        // 打击面 = 朝向玩家的那一面（与准星命中时的 face 语义一致）
        Direction dir = Direction.getNearest(-dx, -dy, -dz);
        long now = System.currentTimeMillis();
        DigTask t = new DigTask(pos.immutable(), dir, Actions.blockId(st), now, now + timeoutMs);
        dig = t;
        boolean started;
        try {
            started = mc.gameMode.startDestroyBlock(pos, dir);
        } catch (Throwable e) {
            t.state = "failed";
            t.endedAtMs = System.currentTimeMillis();
            t.terminal = errJson("dig_error: 发起破坏异常: " + e);
            return t.terminal;
        }
        if (!started) {
            // startDestroyBlock 内部拒绝：领地保护/冒险模式/世界边界
            t.state = "failed";
            t.endedAtMs = System.currentTimeMillis();
            t.terminal = errJson("dig_start_rejected: 客户端拒绝发起破坏"
                    + "（可能被领地保护/冒险模式限制/超出世界边界）");
            return t.terminal;
        }
        return Json.obj(Json.kv("ok", Json.bool(true)),
                Json.kv("result", Json.str("dig_started")),
                Json.kv("block", Json.str(t.blockId)),
                Json.kv("pos", posArr(pos)),
                Json.kv("timeout_ms", Json.num(timeoutMs)),
                Json.kv("hint", Json.str("破坏进行中（跨 tick 驱动）；必须轮询 dig_status 直至"
                        + " dig_broken / dig_timeout / dig_failed 终态，勿重复发起")));
    }

    /** 取消进行中的挖掘（供新 dig 接管或模型 stop=true 调用）。无任务时静默。 */
    static void cancelDig(String reason) {
        DigTask t = dig;
        if (t == null || !"running".equals(t.state)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        try {
            if (mc.gameMode != null) {
                mc.gameMode.stopDestroyBlock();
            }
        } catch (Throwable ignored) {
            // 取消失败也要让任务进入终态
        }
        t.state = "cancelled";
        t.endedAtMs = System.currentTimeMillis();
        long ms = t.endedAtMs - t.startedAtMs;
        t.terminal = Json.obj(Json.kv("ok", Json.bool(false)),
                Json.kv("error", Json.str("dig_cancelled: " + reason)),
                Json.kv("block", Json.str(t.blockId)),
                Json.kv("pos", posArr(t.pos)),
                Json.kv("elapsed_ms", Json.num(ms)));
        ChatBuffer.add("[dig] 已取消：" + reason + "（" + t.blockId + " @ "
                + t.pos.getX() + "," + t.pos.getY() + "," + t.pos.getZ() + "，已进行 " + ms + "ms）");
    }

    /** 查询挖掘任务：idle / running（带进度）/ 终态原样返回。 */
    static String digStatus() {
        DigTask t = dig;
        if (t == null) {
            return Json.obj(Json.kv("ok", Json.bool(true)),
                    Json.kv("result", Json.str("dig_idle")),
                    Json.kv("hint", Json.str("当前没有挖掘任务")));
        }
        if ("running".equals(t.state)) {
            return Json.obj(Json.kv("ok", Json.bool(true)),
                    Json.kv("result", Json.str("dig_running")),
                    Json.kv("block", Json.str(t.blockId)),
                    Json.kv("pos", posArr(t.pos)),
                    Json.kv("elapsed_ms", Json.num(System.currentTimeMillis() - t.startedAtMs)),
                    Json.kv("progress_stage", Json.num(t.lastStage)),
                    Json.kv("timeout_ms", Json.num(t.deadlineMs - t.startedAtMs)),
                    Json.kv("hint", Json.str("继续轮询 dig_status 直至终态")));
        }
        // 终态：附带结束至今的时长，避免模型拿到陈旧结果误判（0 = 异常未记录结束时间）
        String endedAge;
        if (t.endedAtMs == 0) {
            endedAge = "null";
        } else {
            endedAge = Json.num(System.currentTimeMillis() - t.endedAtMs);
        }
        return Json.obj(Json.kv("ok", Json.bool(true)),
                Json.kv("result", Json.str("dig_" + t.state)),
                Json.kv("ended_age_ms", endedAge),
                Json.kv("terminal", Json.maybeJson(t.terminal)));
    }

    /** 每 tick 推进挖掘（由 {@code AiPlayerClient.onClientTick} 调用，主线程）。 */
    static void tickDig() {
        DigTask t = dig;
        if (t == null || !"running".equals(t.state)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null) {
            failDig(t, "dig_aborted: 玩家/世界丢失（可能已断线）");
            return;
        }
        if (p.isDeadOrDying()) {
            failDig(t, "dig_aborted: 玩家死亡");
            return;
        }
        if (mc.screen != null) {
            failDig(t, "dig_aborted: 界面打开（" + mc.screen.getClass().getSimpleName()
                    + "）——先 close_screen");
            return;
        }
        if (p.isUsingItem()) {
            failDig(t, "dig_aborted: 玩家开始使用物品（进食/举盾等）");
            return;
        }
        // 距离复检：玩家可能已经走远/被传送
        double dx = t.pos.getX() + 0.5 - p.getX();
        double dy = t.pos.getY() + 0.5 - p.getEyeY();
        double dz = t.pos.getZ() + 0.5 - p.getZ();
        double limit = MAX_DIG_REACH + 0.5;
        if (dx * dx + dy * dy + dz * dz > limit * limit) {
            failDig(t, "dig_aborted: 走出破坏距离（先走近再重新 dig）");
            return;
        }
        long now = System.currentTimeMillis();
        if (now >= t.deadlineMs) {
            timeoutDig(t);
            return;
        }
        try {
            mc.gameMode.continueDestroyBlock(t.pos, t.dir);
            t.lastStage = mc.gameMode.getDestroyStage();
        } catch (Throwable e) {
            failDig(t, "dig_error: 推进破坏异常: " + e);
            return;
        }
        // 完成判定：连续 N tick 都是空气才算数（防服务端驳回导致的预测回滚误报）
        if (mc.level.getBlockState(t.pos).isAir()) {
            t.airTicks++;
            if (t.airTicks >= AIR_CONFIRM_TICKS) {
                breakDig(t);
            }
        } else {
            t.airTicks = 0;
        }
    }

    private static void breakDig(DigTask t) {
        t.state = "broken";
        t.endedAtMs = System.currentTimeMillis();
        long ms = t.endedAtMs - t.startedAtMs;
        t.terminal = Json.obj(Json.kv("ok", Json.bool(true)),
                Json.kv("result", Json.str("dig_broken")),
                Json.kv("block", Json.str(t.blockId)),
                Json.kv("pos", posArr(t.pos)),
                Json.kv("elapsed_ms", Json.num(ms)));
        ChatBuffer.add("[dig] 已破坏 " + t.blockId + " @ " + t.pos.getX() + "," + t.pos.getY() + ","
                + t.pos.getZ() + "，用时 " + String.format("%.1f", ms / 1000.0) + "s");
    }

    private static void timeoutDig(DigTask t) {
        try {
            Minecraft.getInstance().gameMode.stopDestroyBlock();
        } catch (Throwable ignored) {
            // 忽略
        }
        t.state = "timeout";
        t.endedAtMs = System.currentTimeMillis();
        long ms = t.endedAtMs - t.startedAtMs;
        String mode = "?";
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.gameMode != null) {
                mode = mc.gameMode.getPlayerMode().getName();
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        t.terminal = Json.obj(Json.kv("ok", Json.bool(false)),
                Json.kv("error", Json.str("dig_timeout: 目标在 " + ms + "ms 内未被破坏")),
                Json.kv("block", Json.str(t.blockId)),
                Json.kv("pos", posArr(t.pos)),
                Json.kv("elapsed_ms", Json.num(ms)),
                Json.kv("progress_stage", Json.num(t.lastStage)),
                Json.kv("gamemode", Json.str(mode)),
                Json.kv("hint", Json.str("progress_stage=0 且耗时明显低于徒手工时 → 多半工具不对"
                        + "（先 select 合适工具）或方块受保护/不可破坏（基岩、液体、硬度-1）；"
                        + "进度有累积但没到顶 → 加大 timeout_ms 重试")));
        ChatBuffer.add("[dig] 超时未破坏 " + t.blockId + " @ " + t.pos.getX() + "," + t.pos.getY()
                + "," + t.pos.getZ() + "，进度 " + t.lastStage + "/10（已 " + ms + "ms）");
    }

    private static void failDig(DigTask t, String error) {
        try {
            Minecraft.getInstance().gameMode.stopDestroyBlock();
        } catch (Throwable ignored) {
            // 忽略
        }
        t.state = "failed";
        t.endedAtMs = System.currentTimeMillis();
        long ms = t.endedAtMs - t.startedAtMs;
        t.terminal = Json.obj(Json.kv("ok", Json.bool(false)),
                Json.kv("error", Json.str(error)),
                Json.kv("block", Json.str(t.blockId)),
                Json.kv("pos", posArr(t.pos)),
                Json.kv("elapsed_ms", Json.num(ms)));
        ChatBuffer.add("[dig] 中断：" + error + "（" + t.blockId + "，已 " + ms + "ms）");
    }

    // ================================================================ look_lock

    /** 视角锁定任务。{@code active=false} 即已结束（原因见 {@code endReason}）。 */
    static final class LockTask {
        final double tx;
        final double ty;
        final double tz;
        /** 锁定面（{@code center} 或 {@code up/down/north/south/east/west}；仅用于状态回显，瞄准点已在 tx/ty/tz）。 */
        final String face;
        /** 实体锁定目标 id（{@code -1} = 静态瞄准点模式；≥0 = 每 tick 跟随该实体）。 */
        final int targetEntityId;
        /** 实体类型 id（{@code target_type} 模式锁定用；{@code null} = 直接按 id 锁定）。仅回显。 */
        final String targetType;
        /** 实体瞄准部位：{@code center}（碰撞箱中心，默认）/ {@code eyes}（眼睛高度）。 */
        final String targetAim;
        final long startedAtMs;
        final long deadlineMs;
        volatile boolean active = true;
        volatile float lastYaw;
        volatile float lastPitch;
        volatile String endReason = null;
        volatile long endedAtMs = 0;

        LockTask(double tx, double ty, double tz, String face, int targetEntityId,
                 String targetType, String targetAim, long startedAtMs, long deadlineMs) {
            this.tx = tx;
            this.ty = ty;
            this.tz = tz;
            this.face = face;
            this.targetEntityId = targetEntityId;
            this.targetType = targetType;
            this.targetAim = targetAim;
            this.startedAtMs = startedAtMs;
            this.deadlineMs = deadlineMs;
        }
    }

    private static volatile LockTask lock = null;

    /**
     * 发起视角锁定（主线程）。立即转向目标一次，之后由 {@link #tickLookLock()} 每 tick 维持。
     *
     * @param ax,ay,az    瞄准点（世界坐标；由 Actions.lookLock 按 center/face 规则算好：
     *                    方块中心或该方块某面的面中心）
     * @param face        锁定面（{@code center} 或六面名；仅用于状态回显）
     * @param durationMs  最长锁定时长（到期自动结束，非错误）
     */
    static String startLookLock(LocalPlayer p, double ax, double ay, double az, long durationMs, String face) {
        long now = System.currentTimeMillis();
        LockTask t = new LockTask(ax, ay, az, face, -1, null, "center", now, now + durationMs);
        float[] yp = yawPitch(p, ax, ay, az);
        applyRotation(p, yp[0], yp[1]);
        t.lastYaw = yp[0];
        t.lastPitch = yp[1];
        lock = t;   // 直接替换旧锁定（旧锁定若有 active，静默让其自然过期即可，不额外播报）
        return Json.obj(Json.kv("ok", Json.bool(true)),
                Json.kv("result", Json.str("look_lock_started")),
                Json.kv("aim", Json.arr(java.util.List.of(Json.num(ax), Json.num(ay), Json.num(az)))),
                Json.kv("face", Json.str(face)),
                Json.kv("yaw", Json.num(yp[0])),
                Json.kv("pitch", Json.num(yp[1])),
                Json.kv("duration_ms", Json.num(durationMs)),
                Json.kv("hint", Json.str("锁定中：任何视角操作（look/turn/mouse_move/真实鼠标移动）"
                        + "都会立即中断；look_lock 不带参数可查状态")));
    }

    /** 供 look/turn/mouse_move 调用：显式中断锁定（比等下一 tick 的差值检测更即时、原因更明确）。 */
    static void cancelLookLock(String reason) {
        LockTask t = lock;
        if (t == null || !t.active) {
            return;
        }
        endLock(t, "interrupted: " + reason);
    }

    // ---------------------------------------------------------------- 实体锁定（v0.2.1.37）

    /**
     * 发起实体视角锁定（主线程）。立即转向目标一次，之后由 {@link #tickLookLock()} 每 tick 跟随。
     *
     * @param id        实体网络 id（{@code level.getEntity(id)} 直取）；{@code null} 时按 typeId 找最近
     * @param typeId    实体类型 id（如 {@code minecraft:cow}）；只在与 id 二选一时有效
     * @param aim       {@code center}（碰撞箱中心，默认）/ {@code eyes}（眼睛高度，玩家类实体更准）
     * @param durationMs 最长锁定时长
     * @return 失败原因字符串（找不到实体等）或成功 JSON
     */
    static String startLookLockEntity(LocalPlayer p, Integer id, String typeId, String aim, long durationMs) {
        Level level = p.level();
        Entity e = findTargetEntity(p, level, id, typeId);
        if (e == null) {
            return id != null
                    ? "look_lock_err: 找不到实体 id=" + id + "（已卸载/超距/维度不同；先用 entities 动作拿有效 id）"
                    : "look_lock_err: 半径内找不到类型为 " + typeId + " 的实体（换类型或先靠近）";
        }
        double[] ap = aimPointOf(e, aim);
        long now = System.currentTimeMillis();
        // v0.2.1.38：id 直锁模式回填真实类型（typeId 为 null 时）——查询回显/tick 分支都不再看到 null
        String resolvedType = typeId != null ? typeId : entityTypeId(e);
        LockTask t = new LockTask(ap[0], ap[1], ap[2], "center", e.getId(), resolvedType, aim, now, now + durationMs);
        float[] yp = yawPitch(p, ap[0], ap[1], ap[2]);
        applyRotation(p, yp[0], yp[1]);
        t.lastYaw = yp[0];
        t.lastPitch = yp[1];
        lock = t;
        return Json.obj(Json.kv("ok", Json.bool(true)),
                Json.kv("result", Json.str("look_lock_started")),
                Json.kv("target", Json.obj(
                        Json.kv("id", Json.num(e.getId())),
                        Json.kv("type", Json.str(entityTypeId(e))),
                        Json.kv("name", Json.str(e.getName().getString())),
                        Json.kv("aim", Json.str(aim)),
                        Json.kv("dist", Json.num(Math.round(Math.sqrt(e.distanceToSqr(p)) * 10) / 10.0)))),
                Json.kv("yaw", Json.num(yp[0])),
                Json.kv("pitch", Json.num(yp[1])),
                Json.kv("duration_ms", Json.num(durationMs)),
                Json.kv("hint", Json.str("实体锁定中：每 tick 跟随目标（移动/超距8格/死亡即结束）；"
                        + "任何视角操作会立即中断；look_lock 不带参数可查状态")));
    }

    /** 按 id 直取；无 id 时在 64 格内找最近的指定类型实体（排除玩家自身）。 */
    private static Entity findTargetEntity(LocalPlayer p, Level level, Integer id, String typeId) {
        if (id != null) {
            return level.getEntity(id);
        }
        Entity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : level.getEntities(p, p.getBoundingBox().inflate(64.0))) {
            if (e == p || !e.isAlive()) {
                continue;
            }
            if (typeId != null && !typeId.equals(entityTypeId(e))) {
                continue;
            }
            double d = e.distanceToSqr(p);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    /** 实体瞄准点：{@code eyes}=眼睛高度；{@code center}=碰撞箱竖向中心（默认）。 */
    private static double[] aimPointOf(Entity e, String aim) {
        if ("eyes".equals(aim)) {
            return new double[] {e.getX(), e.getEyeY(), e.getZ()};
        }
        return new double[] {e.getX(), e.getY() + e.getBbHeight() * 0.5, e.getZ()};
    }

    private static String entityTypeId(Entity e) {
        return String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()));
    }

    /** 查询锁定状态：idle / running（带剩余时间）/ ended（带原因）。 */
    static String lockStatus() {
        LockTask t = lock;
        if (t == null) {
            return Json.obj(Json.kv("ok", Json.bool(true)),
                    Json.kv("result", Json.str("look_lock_idle")),
                    Json.kv("hint", Json.str("当前没有锁定；用 look_lock {x,y,z,duration_ms} 发起")));
        }
        if (t.active) {
            var parts = new ArrayList<String>();
            parts.add(Json.kv("ok", Json.bool(true)));
            parts.add(Json.kv("result", Json.str("look_lock_running")));
            if (t.targetEntityId >= 0) {
                parts.add(Json.kv("target", Json.obj(
                        Json.kv("id", Json.num(t.targetEntityId)),
                        Json.kv("type", t.targetType == null ? Json.str("null") : Json.str(t.targetType)),
                        Json.kv("aim", Json.str(t.targetAim)))));
            } else {
                parts.add(Json.kv("aim", Json.arr(java.util.List.of(Json.num(t.tx), Json.num(t.ty), Json.num(t.tz)))));
            }
            parts.add(Json.kv("face", Json.str(t.face)));
            parts.add(Json.kv("yaw", Json.num(t.lastYaw)));
            parts.add(Json.kv("pitch", Json.num(t.lastPitch)));
            parts.add(Json.kv("remaining_ms", Json.num(Math.max(0, t.deadlineMs - System.currentTimeMillis()))));
            return Json.obj(parts.toArray(new String[0]));
        }
        var parts = new ArrayList<String>();
        parts.add(Json.kv("ok", Json.bool(true)));
        parts.add(Json.kv("result", Json.str("look_lock_ended")));
        parts.add(Json.kv("end_reason", Json.str(t.endReason)));
        parts.add(Json.kv("locked_ms", Json.num(t.endedAtMs - t.startedAtMs)));
        if (t.targetEntityId >= 0) {
            parts.add(Json.kv("target", Json.obj(
                    Json.kv("id", Json.num(t.targetEntityId)),
                    Json.kv("type", t.targetType == null ? Json.str("null") : Json.str(t.targetType)),
                    Json.kv("aim", Json.str(t.targetAim)))));
        } else {
            parts.add(Json.kv("face", Json.str(t.face)));
            parts.add(Json.kv("aim", Json.arr(java.util.List.of(Json.num(t.tx), Json.num(t.ty), Json.num(t.tz)))));
        }
        return Json.obj(parts.toArray(new String[0]));
    }

    /** 每 tick 维持锁定 / 检测中断（由 {@code AiPlayerClient.onClientTick} 调用，主线程）。 */
    static void tickLookLock() {
        LockTask t = lock;
        if (t == null || !t.active) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            endLock(t, "aborted: 玩家/世界丢失");
            return;
        }
        if (p.isDeadOrDying()) {
            endLock(t, "aborted: 玩家死亡");
            return;
        }
        long now = System.currentTimeMillis();
        if (now >= t.deadlineMs) {
            endLock(t, "duration_end");
            return;
        }
        // 中断检测：本 tick 的朝向若不是我们上 tick 写的值 → 有外部操作转过视角
        // （真实鼠标/物理按键/look/turn/mouse_move 全覆盖；yaw 用 wrapDegrees 防 360° 回绕误判）
        float dyaw = Math.abs(Mth.wrapDegrees(p.getYRot() - t.lastYaw));
        float dpitch = Math.abs(p.getXRot() - t.lastPitch);
        if (dyaw > TURN_EPS || dpitch > TURN_EPS) {
            endLock(t, String.format("interrupted: 视角被外部操作改变(dyaw=%.2f, dpitch=%.2f)", dyaw, dpitch));
            return;
        }
        double ax = t.tx;
        double ay = t.ty;
        double az = t.tz;
        if (t.targetEntityId >= 0) {
            // 实体锁定：每 tick 按 id 重新取实体（id 稳定；实体对象可能被卸载/复用，不能缓存引用）
            Entity e = mc.level.getEntity(t.targetEntityId);
            if (e == null || !e.isAlive()) {
                endLock(t, "target_lost: 实体消失或死亡（id=" + t.targetEntityId + "）");
                return;
            }
            double d = Math.sqrt(e.distanceToSqr(p));
            if (d > MAX_LOCK_REACH) {
                endLock(t, String.format("target_lost: 实体超距(%.1f > %.1f)", d, MAX_LOCK_REACH));
                return;
            }
            double[] ap = aimPointOf(e, t.targetAim);
            ax = ap[0];
            ay = ap[1];
            az = ap[2];
        }
        float[] yp = yawPitch(p, ax, ay, az);
        applyRotation(p, yp[0], yp[1]);
        t.lastYaw = yp[0];
        t.lastPitch = yp[1];
    }

    private static void endLock(LockTask t, String reason) {
        t.active = false;
        t.endReason = reason;
        t.endedAtMs = System.currentTimeMillis();
        ChatBuffer.add("[look_lock] 结束：" + reason + "（持续 "
                + (t.endedAtMs - t.startedAtMs) + "ms）");
    }

    /** 写旋转（连带 O 字段，避免渲染插值把锁定中的画面抖一下）。 */
    private static void applyRotation(LocalPlayer p, float yaw, float pitch) {
        p.setYRot(yaw);
        p.setXRot(pitch);
        p.yRotO = yaw;
        p.xRotO = pitch;
    }

    /** 眼睛 → 目标点 的 yaw/pitch（与 {@code look} 动作同一套数学，pitch 夹到 ±90）。 */
    static float[] yawPitch(LocalPlayer p, double tx, double ty, double tz) {
        double dx = tx - p.getX();
        double dy = ty - p.getEyeY();
        double dz = tz - p.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
        float pitch = Mth.clamp((float) (-(Math.atan2(dy, flat) * 180.0 / Math.PI)), -90.0f, 90.0f);
        return new float[] {yaw, pitch};
    }

    // ================================================================ place（v0.2.1.39）

    /**
     * 放置任务。两段式协议（与 dig 同型）：{@code startPlace} 发起即返回，
     * {@link #tickPlace()} 在 2-3 tick 后写确认结果，{@link #placeStatus()} 查询。
     *
     * <p>{@code state}: running → placed / failed / unverified（终态即墓碑）。
     */
    static final class PlaceTask {
        final BlockPos target;
        final BlockPos support;
        /** 被点击的支撑面（支撑方块朝向目标格的那一面，up/down/north/south/east/west）。 */
        final String face;
        final String hand;      // main / off
        final String itemId;    // 发起时手持方块 id
        /** 放置前目标格状态——{@code placed} 判定基线（目标格可能是水/草等可替换物）。 */
        final BlockState before;
        final long startedAtMs;
        /** 第几个 {@code placeTickSeq} 之后执行确认。 */
        final long verifyAtTick;
        volatile String state = "running";
        volatile String landed = null;
        volatile String detail = null;
        volatile long endedAtMs = 0;

        PlaceTask(BlockPos target, BlockPos support, String face, String hand, String itemId,
                  BlockState before, long startedAtMs, long verifyAtTick) {
            this.target = target;
            this.support = support;
            this.face = face;
            this.hand = hand;
            this.itemId = itemId;
            this.before = before;
            this.startedAtMs = startedAtMs;
            this.verifyAtTick = verifyAtTick;
        }
    }

    private static volatile PlaceTask place = null;

    /** 放置确认的 tick 计数（每 client tick +1，与 dig/look_lock 的推进同源）。 */
    private static long placeTickSeq = 0;

    /** 发起后隔几 tick 做落块确认（发包 → 服务端处理 → 状态回传，3 tick≈150ms 留足余量）。 */
    private static final long PLACE_VERIFY_TICKS = 3;

    /** 超过这么久还没确认 → unverified（游戏暂停/界面开着/tick 停滞），不无限挂起。 */
    private static final long PLACE_VERIFY_MAX_MS = 10_000;

    /** 自动选支撑面的候选顺序：先地板（最常见），再四面墙，最后天花板。 */
    private static final Direction[] PLACE_FACE_ORDER = {
            Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN};

    /**
     * 常见非方块实体类可交互方块（右键会先触发交互而非放置）——自动选支撑面时避开。
     * 带 BlockEntity 的（箱子/工作台/熔炉…）另行用 {@code hasBlockEntity()} 排除；
     * 本表只收无 BE 的高频交互方块，漏网之鱼由 {@code place_status} 的失败诊断兜底。
     */
    private static final Set<String> INTERACTIVE_HINTS = Set.of(
            "button", "lever", "door", "trapdoor", "note_block", "cake", "bed", "bell",
            "comparator", "repeater", "daylight_detector", "tripwire_hook", "flower_pot",
            "command_block", "dragon_egg", "end_portal_frame", "respawn_anchor");

    /**
     * 发起放置（主线程，立即返回）。
     *
     * <p>★与 {@code keys key.use} 的本质区别：本动作直接走
     * {@code MultiPlayerGameMode.useItemOn} 发包路径——与真实玩家右键<b>同一条链路</b>，
     * 不经过按键注入（不受鼠标抓取/窗口前台门禁影响；2026-10-17/18 实锤按键放置在窗口
     * 非前台时完全失效）。服务端照常跑完整放置流程（事件/领地保护/距离/实体碰撞全部生效）。
     *
     * <p>参数：{@code target} 要放入方块的目标格（空气或可替换物）；
     * {@code side} 支撑方块的被点击面（{@code null}=自动选：优先下方地板→四周→上方）；
     * {@code hand} 主手/副手。
     *
     * <p>协议：立即返回 {@code place_sent}，约 2-3 tick 后用 {@link #placeStatus()} 收确认
     * （placed / failed+诊断 / unverified），也可用 {@code block_at} 复核。
     *
     * @return 成功：{@code place_sent} JSON；失败：{@code place_err:} / {@code place_error:}
     */
    static String startPlace(BlockPos target, Direction side, InteractionHand hand) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null) {
            return errJson("place_err: 玩家/世界未就绪（主菜单或重连中）");
        }
        if (mc.screen != null) {
            return errJson("place_err: 界面打开中（" + mc.screen.getClass().getSimpleName()
                    + "）——先 close_screen 再放置");
        }
        if (p.isUsingItem()) {
            return errJson("place_err: 玩家正在使用物品（进食/举盾/拉弓等）——先取消再放置");
        }
        if (p.isDeadOrDying()) {
            return errJson("place_err: 玩家已死亡");
        }
        Level level = p.level();
        ItemStack held = p.getItemInHand(hand);
        if (held.isEmpty() || !(held.getItem() instanceof BlockItem)) {
            return errJson("place_err: 手持物品不是可放置方块（当前 "
                    + (held.isEmpty() ? "空手" : Actions.itemId(held)) + "）——先 select 拿上方块");
        }
        if (p.getCooldowns().isOnCooldown(held.getItem())) {
            return errJson("place_err: 手持物品在冷却中（如末影珍珠/紫颂果）——稍等再放");
        }
        if (!p.getAbilities().mayBuild) {
            return errJson("place_err: 当前游戏模式不允许放置（冒险/旁观模式）");
        }
        BlockState before = level.getBlockState(target);
        if (!(before.isAir() || before.canBeReplaced())) {
            return errJson("place_err: 目标格已有方块（" + Actions.blockId(before)
                    + "）——先 dig 挖掉或换个目标格");
        }
        // ★★ 玩家碰撞箱预检（2026-10-18 用户提醒后固化）：玩家不是质点——AABB 宽 0.6 高 1.8。
        //    服务端放置时有实体碰撞校验（CollisionContext.of(player)），目标格与玩家身体重叠
        //    必然驳回。发起前先说清楚，别让服务端静默拒绝后模型干等。
        AABB playerBox = p.getBoundingBox();
        AABB targetBox = new AABB(target);
        if (playerBox.intersects(targetBox)) {
            return errJson(String.format(
                    "place_err: 目标格与玩家碰撞箱重叠，放不进去"
                            + "（目标格 [%d,%d,%d]~[%d,%d,%d]，玩家 AABB [%.2f,%.2f,%.2f]~[%.2f,%.2f,%.2f]，"
                            + "脚底 %.2f,%.2f,%.2f）——先 move 走开/跳起，或换个不压在身体上的目标格",
                    target.getX(), target.getY(), target.getZ(),
                    target.getX() + 1, target.getY() + 1, target.getZ() + 1,
                    playerBox.minX, playerBox.minY, playerBox.minZ,
                    playerBox.maxX, playerBox.maxY, playerBox.maxZ,
                    p.getX(), p.getY(), p.getZ()));
        }
        // 服务端 canInteractWithBlockAt 同款尺子：脚底 → 支撑格底中心 ≤ block_interaction_range + 1
        double reach = p.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE) + 1.0;
        Direction chosenFace;
        BlockPos support;
        if (side != null) {
            // 显式指定：不避开可交互方块，但回显警告（右键可能先开界面）
            support = target.relative(side.getOpposite());
            BlockState ss = level.getBlockState(support);
            if (ss.isAir() || ss.getCollisionShape(level, support).isEmpty()) {
                return errJson("place_err: 指定 side=" + side.getName() + " 的支撑格（"
                        + posStr(support) + "）是空气/无碰撞体积，垫不住方块");
            }
            chosenFace = side;
        } else {
            chosenFace = null;
            support = null;
            for (Direction f : PLACE_FACE_ORDER) {
                BlockPos sp = target.relative(f.getOpposite());
                BlockState ss = level.getBlockState(sp);
                if (ss.isAir() || !ss.getFluidState().isEmpty()) {
                    continue;
                }
                if (ss.getCollisionShape(level, sp).isEmpty()) {
                    continue;
                }
                if (ss.hasBlockEntity()) {
                    continue;   // 箱子/工作台/熔炉——右键开界面
                }
                String sid = Actions.blockId(ss);
                boolean interactive = INTERACTIVE_HINTS.stream().anyMatch(sid::contains);
                if (interactive) {
                    continue;   // 按钮/拉杆/门——右键触发交互
                }
                if (p.position().distanceToSqr(Vec3.atBottomCenterOf(sp)) > reach * reach) {
                    continue;
                }
                chosenFace = f;
                support = sp;
                break;
            }
            if (chosenFace == null) {
                return errJson("place_err: 目标格周围找不到可用的支撑面——需要相邻一格有已放置的"
                        + "实心方块（非箱子/工作台/按钮等可交互方块）且在放置距离内（脚到底中心约 "
                        + String.format("%.1f", reach) + " 格）；先走近、在旁边先垫一块，"
                        + "或用 side= 显式指定支撑面");
            }
        }
        double supportDist = Math.sqrt(p.position().distanceToSqr(Vec3.atBottomCenterOf(support)));
        if (supportDist > reach) {
            return errJson(String.format("place_err: 支撑格太远（脚底到格底中心 %.1f > %.1f 格）——先走近再放",
                    supportDist, reach));
        }
        // 点击点 = 支撑格被点击面的面中心；方向 = 该面法线（服务端据此算放置位 = clickedPos.relative(face)）
        Vec3 n = Vec3.atLowerCornerOf(chosenFace.getNormal());
        Vec3 hit = new Vec3(support.getX() + 0.5 + n.x * 0.5,
                support.getY() + 0.5 + n.y * 0.5,
                support.getZ() + 0.5 + n.z * 0.5);
        BlockHitResult bhr = new BlockHitResult(hit, chosenFace, support, false);
        try {
            mc.gameMode.useItemOn(p, hand, bhr);
        } catch (Throwable e) {
            return errJson("place_error: 发起放置异常: " + e);
        }
        PlaceTask prev = place;
        if (prev != null && "running".equals(prev.state)) {
            ChatBuffer.add("[place] 上一个放置（" + posStr(prev.target) + "）尚未确认即被新放置取代");
        }
        long now = System.currentTimeMillis();
        PlaceTask t = new PlaceTask(target.immutable(), support.immutable(), chosenFace.getName(),
                hand == InteractionHand.OFF_HAND ? "off" : "main", Actions.itemId(held), before, now,
                placeTickSeq + PLACE_VERIFY_TICKS);
        place = t;
        String supportId = Actions.blockId(level.getBlockState(support));
        boolean supportInteractive = level.getBlockState(support).hasBlockEntity()
                || INTERACTIVE_HINTS.stream().anyMatch(supportId::contains);
        ChatBuffer.add("[place] 已发起：" + t.itemId + " → " + posStr(target)
                + "（支撑 " + posStr(support) + " " + supportId + " 的 " + chosenFace.getName() + " 面）");
        var parts = new ArrayList<String>();
        parts.add(Json.kv("ok", Json.bool(true)));
        parts.add(Json.kv("action", Json.str("place")));
        parts.add(Json.kv("result", Json.str("place_sent")));
        parts.add(Json.kv("item", Json.str(t.itemId)));
        parts.add(Json.kv("target", posArr(target)));
        parts.add(Json.kv("support", posArr(support)));
        parts.add(Json.kv("support_block", Json.str(supportId)));
        parts.add(Json.kv("face", Json.str(chosenFace.getName())));
        parts.add(Json.kv("hand", Json.str(t.hand)));
        parts.add(Json.kv("support_dist", Json.num(Math.round(supportDist * 10) / 10.0)));
        if (supportInteractive) {
            parts.add(Json.kv("support_interactive", Json.bool(true)));
        }
        // 玩家位姿（脚底 + AABB）随响应回传：放置成败与身体占位强相关，给模型自我校正的据
        parts.add(Json.kv("player", Json.obj(
                Json.kv("pos", Json.arr(java.util.List.of(
                        Json.num(r2(p.getX())), Json.num(r2(p.getY())), Json.num(r2(p.getZ()))))),
                Json.kv("aabb", Json.arr(java.util.List.of(
                        Json.arr(java.util.List.of(Json.num(r2(playerBox.minX)), Json.num(r2(playerBox.minY)), Json.num(r2(playerBox.minZ)))),
                        Json.arr(java.util.List.of(Json.num(r2(playerBox.maxX)), Json.num(r2(playerBox.maxY)), Json.num(r2(playerBox.maxZ))))))))));
        parts.add(Json.kv("hint", Json.str("已发起（发包路径，服务端照常校验事件/保护/距离/碰撞）；"
                + "约 2-3 tick 后用 place_status 收确认，或 block_at 复核目标格"
                + (supportInteractive ? "；★支撑面是可交互方块，右键可能先开界面而非放置，失败就换 side=" : "")
                + "；★紧接着 block_at 可能尚未落块，place_pending 时稍等再查")));
        return Json.obj(parts.toArray(new String[0]));
    }

    /** 查询上次放置：idle / pending / placed / failed+诊断 / unverified。 */
    static String placeStatus() {
        PlaceTask t = place;
        if (t == null) {
            return Json.obj(Json.kv("ok", Json.bool(true)),
                    Json.kv("result", Json.str("place_idle")),
                    Json.kv("hint", Json.str("当前没有放置记录；用 place x,y,z 发起")));
        }
        if ("running".equals(t.state)) {
            long age = System.currentTimeMillis() - t.startedAtMs;
            if (age > PLACE_VERIFY_MAX_MS) {
                // 超龄未确认（游戏暂停/界面开着/tick 停滞）——降级为 unverified，交模型手动复核
                t.state = "unverified";
                t.detail = "超过 " + (PLACE_VERIFY_MAX_MS / 1000) + "s 未确认（可能游戏暂停/界面打开/卡 tick）";
                t.endedAtMs = System.currentTimeMillis();
            } else {
                return Json.obj(Json.kv("ok", Json.bool(true)),
                        Json.kv("result", Json.str("place_pending")),
                        Json.kv("target", posArr(t.target)),
                        Json.kv("item", Json.str(t.itemId)),
                        Json.kv("elapsed_ms", Json.num(age)),
                        Json.kv("hint", Json.str("确认中（约2-3 tick）；稍后重试 place_status，或用 block_at 复核")));
            }
        }
        var parts = new ArrayList<String>();
        parts.add(Json.kv("ok", Json.bool("placed".equals(t.state))));
        parts.add(Json.kv("result", Json.str("place_" + t.state)));
        parts.add(Json.kv("target", posArr(t.target)));
        parts.add(Json.kv("support", posArr(t.support)));
        parts.add(Json.kv("face", Json.str(t.face)));
        parts.add(Json.kv("item", Json.str(t.itemId)));
        parts.add(Json.kv("elapsed_ms", Json.num(
                (t.endedAtMs > 0 ? t.endedAtMs : System.currentTimeMillis()) - t.startedAtMs)));
        if (t.landed != null) {
            parts.add(Json.kv("landed", Json.str(t.landed)));
        }
        if (t.detail != null) {
            parts.add(Json.kv("detail", Json.str(t.detail)));
        }
        String hint = switch (t.state) {
            case "placed" -> "已落块（用 block_at 可再核实）；需要别的格子继续 place";
            case "failed" -> "未生效——按 detail 排查：换 side= 指定非交互支撑面/走近/移开碰撞箱/换目标格";
            default -> "未能自动确认——用 block_at 手动复核目标格";
        };
        parts.add(Json.kv("hint", Json.str(hint)));
        return Json.obj(parts.toArray(new String[0]));
    }

    /** 每 tick 推进放置确认（由 {@code AiPlayerClient.onClientTick} 调用，主线程）。 */
    static void tickPlace() {
        placeTickSeq++;
        PlaceTask t = place;
        if (t == null || !"running".equals(t.state)) {
            return;
        }
        if (placeTickSeq < t.verifyAtTick) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            t.state = "unverified";
            t.detail = "玩家/世界丢失（断线或回主菜单）";
            t.endedAtMs = System.currentTimeMillis();
            return;
        }
        BlockState nowSt = mc.level.getBlockState(t.target);
        if (!nowSt.isAir() && !nowSt.equals(t.before)) {
            t.state = "placed";
            t.landed = Actions.blockId(nowSt);
            t.endedAtMs = System.currentTimeMillis();
            ChatBuffer.add("[place] 已落块 " + t.landed + " @ " + posStr(t.target)
                    + "，确认用时 " + (t.endedAtMs - t.startedAtMs) + "ms");
            return;
        }
        // 未生效：收集诊断信号（失败也把原因说清楚，别让模型瞎猜重试烧 token）
        StringBuilder why = new StringBuilder();
        ItemStack heldNow = p.getItemInHand(handOf(t.hand));
        if (heldNow.isEmpty() || !(heldNow.getItem() instanceof BlockItem)) {
            why.append("手持物品已不是方块（现在=")
                    .append(heldNow.isEmpty() ? "空手" : Actions.itemId(heldNow)).append("）；");
        } else if (p.getCooldowns().isOnCooldown(heldNow.getItem())) {
            why.append("手持物品在冷却中；");
        }
        if (!p.getAbilities().mayBuild) {
            why.append("当前游戏模式不允许放置；");
        }
        if (mc.screen != null) {
            why.append("界面打开中（").append(mc.screen.getClass().getSimpleName()).append("）——放置期间界面应关闭；");
        }
        BlockState ssNow = mc.level.getBlockState(t.support);
        if (ssNow.isAir()) {
            why.append("支撑格已变成空气（被挖了/换了？）；");
        } else if (ssNow.hasBlockEntity()) {
            why.append("支撑面是带界面的方块（").append(Actions.blockId(ssNow))
                    .append("）——右键会先开界面而非放置，换 side= 指定实心非交互支撑面；");
        }
        AABB pb = p.getBoundingBox();
        if (pb.intersects(new AABB(t.target))) {
            why.append("确认时玩家碰撞箱已与目标格重叠（放置期间移动过？）；");
        }
        if (why.length() == 0) {
            why.append("未见明确客户端侧原因——多半是服务端驳回（距离/碰撞/领地保护，"
                    + "或特殊放置规则如草径要草方块、火把/灯笼要实心顶面、雪层要叠放）");
        }
        t.state = "failed";
        t.detail = why + "。目标格现在：" + Actions.blockId(nowSt);
        t.endedAtMs = System.currentTimeMillis();
        ChatBuffer.add("[place] 未生效：" + t.detail + "（" + posStr(t.target) + "）");
    }

    private static InteractionHand handOf(String hand) {
        return "off".equals(hand) ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    }

    // ================================================================ 小工具

    private static String posStr(BlockPos pos) {
        return "(" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ")";
    }

    private static double r2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static String posArr(BlockPos pos) {
        return Json.arr(java.util.List.of(Json.num(pos.getX()), Json.num(pos.getY()), Json.num(pos.getZ())));
    }

    private static String errJson(String error) {
        return Json.obj(Json.kv("ok", Json.bool(false)), Json.kv("error", Json.str(error)));
    }
}
