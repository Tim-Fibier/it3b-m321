package ch.bzz.m321.chatservice.config;

import ch.bzz.m321.chatservice.redis.ChatMessageSubscriber;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis Pub/Sub Konfiguration. Der Channel "chat.messages" verteilt neue
 * Nachrichten an alle chat-service-Instanzen und wird zusätzlich vom
 * Batch Service abonniert, der sie persistiert.
 */
@Configuration
public class RedisConfig {

    public static final String CHAT_MESSAGES_CHANNEL = "chat.messages";

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            ChatMessageSubscriber subscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(CHAT_MESSAGES_CHANNEL));
        return container;
    }
}
