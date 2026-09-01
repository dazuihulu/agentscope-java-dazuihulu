package io.agentscope.dataagent.web.config;

import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.RedisClusterClient;

import java.util.Collections;

/**
 * 类描述:
 * <br>
 *
 * @author Terry.Jiang
 * @fileName AgentStateStoreConfig
 * @date 2026年08月26日 18:02
 * @history
 */
@Configuration
public class AgentStateStoreConfig {


    @Bean
    public RedisAgentStateStore redisAgentStateStore() {

        RedisClusterClient clusterClient = RedisClusterClient.create(
                Collections.singleton(new HostAndPort("uatredis.libertymutual.com.cn", 80)),
                null,
                "Asdcvb!10");
        return RedisAgentStateStore.builder().jedisClient(clusterClient).build();

    }

}
