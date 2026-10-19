package com.liuxingyu.liusignin.sync;

import com.liuxingyu.liulib.storage.RedisClient;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * 跨服缓存失效广播。
 *
 * <p>Redis Pub/Sub 只负责“通知他服丢弃缓存”，权威数据始终在 MySQL。
 * 消息格式 {@code serverId;uuid}，发送方过滤自己的消息，避免本地缓存被误删。</p>
 *
 * <p>Redis 不可用时自动降级为“只依赖下次登录 / 手动重载”，不影响签到本身。</p>
 */
public final class CrossServerSync {

    private static final String CHANNEL = "sync";

    private final RedisClient redis;
    private final Logger logger;
    private final Consumer<UUID> invalidator;
    private volatile boolean started;

    public CrossServerSync(RedisClient redis, Logger logger, Consumer<UUID> invalidator) {
        this.redis = redis;
        this.logger = logger;
        this.invalidator = invalidator;
    }

    /** 返回是否启用成功。 */
    public boolean start() {
        if (redis == null || !redis.isReady()) {
            logger.info("Redis 未启用，跨服缓存失效广播不可用（将依赖重新登录刷新）");
            return false;
        }
        try {
            redis.subscribe(CHANNEL, this::onMessage);
            started = true;
            logger.info("跨服缓存同步已启用（channel=" + redis.channel(CHANNEL)
                    + " server=" + redis.serverId() + "）");
            return true;
        } catch (RuntimeException error) {
            logger.warning("订阅跨服同步失败：" + error.getMessage());
            return false;
        }
    }

    public void stop() {
        if (!started) {
            return;
        }
        try {
            redis.unsubscribe(CHANNEL);
        } catch (RuntimeException ignored) {
            // 停服阶段忽略
        }
        started = false;
    }

    /** 通知其它子服丢弃该玩家缓存。 */
    public void publishInvalidate(UUID uuid) {
        if (!started || uuid == null || !redis.isReady()) {
            return;
        }
        try {
            redis.publish(CHANNEL, redis.serverId() + ";" + uuid);
        } catch (RuntimeException error) {
            logger.fine("发布跨服同步消息失败：" + error.getMessage());
        }
    }

    public boolean started() {
        return started;
    }

    private void onMessage(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        String[] parts = message.split(";", 2);
        if (parts.length < 2) {
            return;
        }
        if (parts[0].equals(String.valueOf(redis.serverId()))) {
            return;
        }
        try {
            invalidator.accept(UUID.fromString(parts[1].trim()));
        } catch (IllegalArgumentException error) {
            logger.fine("忽略格式错误的跨服消息：" + message);
        }
    }
}
