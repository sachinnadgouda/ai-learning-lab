package dev.demo.traffic.adapter.out.redis;

import static org.junit.jupiter.api.Assertions.*;

import dev.demo.traffic.domain.policy.PolicyCandidate;
import dev.demo.traffic.domain.policy.PolicyMode;
import dev.demo.traffic.domain.policy.TrafficPolicy;
import dev.demo.traffic.domain.traffic.AdmissionState;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers(disabledWithoutDocker = true)
class RedisAdaptersIT {
    static GenericContainer<?> redisContainer;
    static LettuceConnectionFactory factory;
    StringRedisTemplate redis;
    final JsonMapper mapper = JsonMapper.builder().build();

    @BeforeAll static void start() {
        redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
        redisContainer.start();
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(redisContainer.getHost(),
                redisContainer.getMappedPort(6379)));
        factory.afterPropertiesSet();
    }

    @AfterAll static void stop() {
        factory.destroy();
        redisContainer.stop();
    }

    @BeforeEach void flush() {
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test void policyLifecycleActivateAndRollback() {
        var repo = new RedisPolicyRepository(redis, mapper);
        var initial = repo.active();
        assertNotNull(initial);
        var candidate = PolicyCandidate.of(initial, initial.admissionPerSecond() + 10, initial.maxQueue(), "AI", "r", "c1", Instant.now());
        repo.saveCandidate(candidate);
        assertEquals(candidate, repo.candidate().orElseThrow());

        repo.activate(candidate.policy());
        assertEquals(candidate.policy(), repo.active());
        assertTrue(repo.candidate().isEmpty());
        assertEquals(initial, repo.history().get(0));

        assertEquals(initial, repo.rollback().orElseThrow());
        assertEquals(initial, repo.active());
        assertTrue(repo.rollback().isEmpty());
    }

    @Test void flagsAreEmptyUntilSetAndPersist() {
        var flags = new RedisFeatureFlagRepository(redis);
        assertTrue(flags.aiEnabled().isEmpty());
        flags.setAiEnabled(true);
        flags.setAutomaticEvolution(false);
        flags.setModel("qwen2.5:3b");
        flags.setTriggerArrivals(333);
        assertEquals(true, flags.aiEnabled().orElseThrow());
        assertEquals(false, flags.automaticEvolution().orElseThrow());
        assertEquals("qwen2.5:3b", flags.model().orElseThrow());
        assertEquals(333, flags.triggerArrivals().orElseThrow());
    }

    @Test void staticAdmissionRejectsOverLimitAndNeverQueues() {
        var q = new RedisQueueRepository(redis);
        var policy = new TrafficPolicy(1, PolicyMode.STATIC, 2, 10, 30);
        long now = 10_000_000L;
        assertEquals(AdmissionState.ADMIT, q.admit(policy, null, "t1", now).state());
        assertEquals(AdmissionState.ADMIT, q.admit(policy, null, "t2", now + 1).state());
        assertEquals(AdmissionState.REJECT, q.admit(policy, null, "t3", now + 2).state());
        assertEquals(0, q.depth());
    }

    @Test void evolvedModeQueuesInFifoOrderAndAdmitsWhenCapacityReturns() {
        var q = new RedisQueueRepository(redis);
        var policy = new TrafficPolicy(2, PolicyMode.EVOLVED, 1, 2, 30);
        long now = 20_000_000L;
        assertEquals(AdmissionState.ADMIT, q.admit(policy, null, "a", now).state());
        var first = q.admit(policy, null, "b", now + 1);
        var second = q.admit(policy, null, "c", now + 2);
        assertEquals(AdmissionState.QUEUE, first.state());
        assertEquals(AdmissionState.QUEUE, second.state());
        assertEquals(AdmissionState.REJECT, q.admit(policy, null, "d", now + 3).state(), "queue full");
        assertEquals(2, q.depth());

        assertEquals(AdmissionState.WAIT, q.admit(policy, "b", null, now + 4).state(), "same second: capacity used");
        assertEquals(AdmissionState.WAIT, q.admit(policy, "c", null, now + 1004).state(), "not at head");
        var admitted = q.admit(policy, "b", null, now + 1005);
        assertEquals(AdmissionState.ADMIT, admitted.state());
        assertTrue(admitted.waitedMs() >= 1000);
        assertEquals(1, q.depth());
    }

    @Test void allTicketsWithinFreeCapacityAreAdmittedInTheSameSecond() {
        var q = new RedisQueueRepository(redis);
        var policy = new TrafficPolicy(2, PolicyMode.EVOLVED, 3, 10, 30);
        long now = 50_000_000L;
        for (int i = 0; i < 3; i++) assertEquals(AdmissionState.ADMIT, q.admit(policy, null, "x" + i, now).state());
        for (String t : new String[] {"a", "b", "c", "d"}) assertEquals(AdmissionState.QUEUE, q.admit(policy, null, t, now + 1).state());
        long next = now + 1000;
        assertEquals(AdmissionState.ADMIT, q.admit(policy, "c", null, next).state(), "within the first 3 positions");
        assertEquals(AdmissionState.ADMIT, q.admit(policy, "a", null, next + 1).state());
        assertEquals(AdmissionState.ADMIT, q.admit(policy, "b", null, next + 2).state());
        assertEquals(AdmissionState.WAIT, q.admit(policy, "d", null, next + 3).state(), "capacity for this second used");
    }

    @Test void abandonedHeadTicketExpiresAndDoesNotBlockTheQueue() throws Exception {
        var q = new RedisQueueRepository(redis, 1);
        var policy = new TrafficPolicy(2, PolicyMode.EVOLVED, 1, 5, 30);
        long now = 40_000_000L;
        assertEquals(AdmissionState.ADMIT, q.admit(policy, null, "a", now).state());
        assertEquals(AdmissionState.QUEUE, q.admit(policy, null, "gone", now + 1).state());
        assertEquals(AdmissionState.QUEUE, q.admit(policy, null, "alive", now + 2).state());
        for (int i = 1; i <= 3; i++) {
            Thread.sleep(500);
            q.admit(policy, "alive", null, now + 100 + i); // keeps polling; "gone" is never polled
        }
        assertEquals(AdmissionState.ADMIT, q.admit(policy, "alive", null, now + 2600).state(),
                "abandoned head ticket must expire instead of blocking the queue");
        assertEquals(AdmissionState.EXPIRED, q.admit(policy, "gone", null, now + 2601).state());
    }

    @Test void unknownTicketIsExpired() {
        var q = new RedisQueueRepository(redis);
        var policy = new TrafficPolicy(2, PolicyMode.EVOLVED, 1, 2, 30);
        assertEquals(AdmissionState.EXPIRED, q.admit(policy, "ghost", null, 30_000_000L).state());
    }

    @Test void legacyBareCandidateIsAdoptedAsUnevaluatedCandidate() {
        redis.opsForValue().set("demo:policy:candidate",
                "{\"version\":2,\"mode\":\"EVOLVED\",\"admissionPerSecond\":48,\"maxQueue\":500,\"simulatedWorkMs\":30}");
        var repo = new RedisPolicyRepository(redis, mapper);
        var candidate = repo.candidate().orElseThrow();
        assertEquals("LEGACY", candidate.source());
        assertEquals(48, candidate.policy().admissionPerSecond());
        assertTrue(repo.evaluation().isEmpty());
    }
}
