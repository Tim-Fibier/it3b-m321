package ch.bzz.m321.batchservice.config;

import ch.bzz.m321.batchservice.redis.MessageSubscriber;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Abonniert denselben Redis-Channel "chat.messages" wie die chat-service-Instanzen.
 */
@Configuration
public class RedisConfig {

    public static final String CHAT_MESSAGES_CHANNEL = "chat.messages";

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            MessageSubscriber subscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(CHAT_MESSAGES_CHANNEL));
        return container;
    }
}
