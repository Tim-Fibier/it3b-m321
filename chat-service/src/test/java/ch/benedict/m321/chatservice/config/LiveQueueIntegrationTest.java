package ch.benedict.m321.chatservice.config;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import ch.benedict.m321.chatservice.service.MessagePublisher;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Prüft gegen einen echten RabbitMQ, dass die Instanz ihre eigene Live-Queue
 * anlegt und an chat.delivery bindet. Ohne diese Queue ginge jede Kopie für
 * den Live-Weg verloren.
 */
@SpringBootTest(properties = "chat-service.instance-id=" + LiveQueueIntegrationTest.INSTANCE_ID)
@Testcontainers
class LiveQueueIntegrationTest {

    /** Feste Instanz-ID, damit der Test den Queue-Namen kennt. */
    static final String INSTANCE_ID = "test-instance";

    /** Der Name, den die Queue mit dieser Instanz-ID haben muss. */
    private static final String LIVE_QUEUE = QueueNames.LIVE_QUEUE_PREFIX + INSTANCE_ID;

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @Autowired
    private MessagePublisher messagePublisher;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    /** Beide Queues überleben die einzelne Testmethode, darum vor jedem Test leeren. */
    @BeforeEach
    void emptyQueues() {
        rabbitAdmin.purgeQueue(QueueNames.PERSIST_QUEUE);
        rabbitAdmin.purgeQueue(LIVE_QUEUE);
    }

    /** Der Name setzt sich aus "chat.live." und der Instanz-ID zusammen. */
    @Test
    void declaresLiveQueueWithInstanceId() {
        Properties properties = rabbitAdmin.getQueueProperties(LIVE_QUEUE);

        assertNotNull(properties);
    }

    /**
     * Der eigentliche Zweck: Eine veröffentlichte Nachricht liegt danach sowohl
     * im Schreibweg als auch in der Live-Queue, mit derselben ID.
     */
    @Test
    void publishedMessageReachesLiveQueueAndPersistQueue() {
        ChatMessage message = createMessage("Hallo live");

        messagePublisher.publish(message);

        ChatMessage live = receiveFrom(LIVE_QUEUE);
        ChatMessage persisted = receiveFrom(QueueNames.PERSIST_QUEUE);
        assertNotNull(live);
        assertNotNull(persisted);
        assertEquals(message.id(), live.id());
        assertEquals(message.id(), persisted.id());
    }

    /**
     * Exklusiv heisst: Nur die Verbindung dieser Instanz darf die Queue benutzen.
     * Darauf beruht, dass RabbitMQ die Queue löscht, wenn die Instanz verschwindet.
     * Eine fremde Verbindung muss deshalb abgewiesen werden.
     */
    @Test
    void otherConnectionCannotUseLiveQueue() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(rabbitMq.getHost());
        factory.setPort(rabbitMq.getAmqpPort());
        factory.setUsername(rabbitMq.getAdminUsername());
        factory.setPassword(rabbitMq.getAdminPassword());

        try (Connection connection = factory.newConnection()) {
            Channel channel = connection.createChannel();
            assertThrows(IOException.class, () -> channel.queueDeclarePassive(LIVE_QUEUE));
        }
    }

    /** Holt eine Nachricht als ChatMessage aus einer Queue, wartet höchstens 5 s. */
    private ChatMessage receiveFrom(String queueName) {
        ParameterizedTypeReference<ChatMessage> targetType = new ParameterizedTypeReference<>() {
        };
        return rabbitTemplate.receiveAndConvert(queueName, 5000, targetType);
    }

    /** Baut eine vollständige Nachricht, damit die Tests kurz bleiben. */
    private ChatMessage createMessage(String content) {
        UUID messageId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        Instant sentAt = Instant.now();
        return new ChatMessage(messageId, roomId, "anna", "Anna Muster", content, sentAt);
    }
}
