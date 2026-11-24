package app.platform.identity.internal;

import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Counters in Redis, shared by every instance. The increment and the start of the window are one script, so two
 * instances cannot both see "first" or leave a counter without an expiry. Throws when Redis cannot be reached;
 * {@link ResilientCounters} decides what happens then.
 */
final class RedisCounters implements Counters {

    private static final String PREFIX = "platform:identity:";
    private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1]) "
                    + "if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
                    + "return c", Long.class);

    // Adding and starting the window are one script too, so a bulk call that is the first of its window cannot leave
    // a counter without an expiry.
    private static final DefaultRedisScript<Long> ADD = new DefaultRedisScript<>(
            "local c = redis.call('INCRBY', KEYS[1], ARGV[2]) "
                    + "if c == tonumber(ARGV[2]) or redis.call('PTTL', KEYS[1]) < 0 then "
                    + "redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
                    + "return c", Long.class);

    private final StringRedisTemplate redis;

    RedisCounters(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public long add(String key, Duration window, long amount) {
        Long count = redis.execute(ADD, List.of(PREFIX + key), Long.toString(window.toMillis()),
                Long.toString(amount));
        if (count == null) {
            throw new IllegalStateException("Redis returned no count");
        }
        return count;
    }

    @Override
    public long remainingMillis(String key) {
        Long left = redis.getExpire(PREFIX + key, java.util.concurrent.TimeUnit.MILLISECONDS);
        return left == null || left < 0 ? 0 : left;
    }

    @Override
    public long increment(String key, Duration window) {
        Long count = redis.execute(INCREMENT, List.of(PREFIX + key), Long.toString(window.toMillis()));
        if (count == null) {
            throw new IllegalStateException("Redis returned no count");
        }
        return count;
    }

    @Override
    public long current(String key) {
        String value = redis.opsForValue().get(PREFIX + key);
        return value == null ? 0 : Long.parseLong(value);
    }
}
