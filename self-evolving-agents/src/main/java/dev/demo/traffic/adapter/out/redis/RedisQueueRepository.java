package dev.demo.traffic.adapter.out.redis;

import dev.demo.traffic.application.port.out.QueueRepository;
import dev.demo.traffic.domain.policy.TrafficPolicy;
import dev.demo.traffic.domain.traffic.AdmissionDecision;
import dev.demo.traffic.domain.traffic.AdmissionState;
import dev.demo.traffic.domain.traffic.QueueTicket;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Atomic rate window + FIFO waiting room implemented as one Lua script. */
public class RedisQueueRepository implements QueueRepository {
    static final String QUEUE = "demo:queue";
    private static final String SCRIPT = """
        local cap=tonumber(ARGV[1]); local maxq=tonumber(ARGV[2]); local token=ARGV[3]; local mode=ARGV[4]
        local now=tonumber(ARGV[6]); local ttl=tonumber(ARGV[7]); local purged=0
        local count=tonumber(redis.call('GET',KEYS[1]) or '0')
        local head=redis.call('LINDEX',KEYS[2],0)
        while head and redis.call('EXISTS',KEYS[3]..head) == 0 do
          redis.call('LPOP',KEYS[2]); purged=purged+1; head=redis.call('LINDEX',KEYS[2],0)
        end
        local qlen=redis.call('LLEN',KEYS[2])
        if token ~= '' then
          local enq=redis.call('GET',KEYS[3]..token)
          if not enq then return {'EXPIRED',0,purged} end
          redis.call('EXPIRE',KEYS[3]..token,ttl)
          local free=cap-count
          if free <= 0 then return {'WAIT',0,purged} end
          local win=redis.call('LRANGE',KEYS[2],0,free-1)
          for _,t in ipairs(win) do
            if redis.call('EXISTS',KEYS[3]..t) == 0 then redis.call('LREM',KEYS[2],1,t); purged=purged+1 end
          end
          if not redis.call('LPOS',KEYS[2],token,'MAXLEN',free) then return {'WAIT',0,purged} end
          redis.call('LREM',KEYS[2],1,token); redis.call('DEL',KEYS[3]..token)
          redis.call('INCR',KEYS[1]); redis.call('EXPIRE',KEYS[1],3)
          return {'ADMIT',now-tonumber(enq),purged}
        end
        if count < cap and (mode == 'STATIC' or qlen == 0) then
          redis.call('INCR',KEYS[1]); redis.call('EXPIRE',KEYS[1],3)
          return {'ADMIT',0,purged}
        end
        if mode == 'STATIC' or qlen >= maxq then return {'REJECT',0,purged} end
        redis.call('RPUSH',KEYS[2],ARGV[5]); redis.call('SET',KEYS[3]..ARGV[5],ARGV[6],'EX',ttl)
        return {'QUEUE',0,purged}
        """;

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<List> script = new DefaultRedisScript<>(SCRIPT, List.class);

    /** Tickets not polled within this many seconds are abandoned and expire, so they cannot block the queue head. */
    private final long ticketTtlSeconds;

    public RedisQueueRepository(StringRedisTemplate redis) {
        this(redis, 10);
    }

    public RedisQueueRepository(StringRedisTemplate redis, long ticketTtlSeconds) {
        this.redis = redis;
        this.ticketTtlSeconds = ticketTtlSeconds;
    }

    @Override
    @SuppressWarnings("unchecked")
    public AdmissionDecision admit(TrafficPolicy policy, String ticketId, String newTicketId, long nowMillis) {
        String key = "demo:admission:" + (nowMillis / 1000);
        List<Object> reply = redis.execute(script, List.of(key, QUEUE, "demo:ticket:"),
                String.valueOf(policy.admissionPerSecond()), String.valueOf(policy.maxQueue()),
                ticketId == null ? "" : ticketId, policy.mode().name(), newTicketId, String.valueOf(nowMillis), String.valueOf(ticketTtlSeconds));
        AdmissionState state = AdmissionState.valueOf(String.valueOf(reply.get(0)));
        long waited = ((Number) reply.get(1)).longValue();
        int purged = ((Number) reply.get(2)).intValue();
        String ticket = state == AdmissionState.QUEUE ? newTicketId : ticketId;
        return new AdmissionDecision(state, ticket == null ? null : new QueueTicket(ticket), depth(), waited, purged);
    }

    @Override
    public long depth() {
        Long size = redis.opsForList().size(QUEUE);
        return size == null ? 0 : size;
    }
}
