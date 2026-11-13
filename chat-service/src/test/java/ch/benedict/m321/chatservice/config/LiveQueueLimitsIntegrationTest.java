package ch.benedict.m321.chatservice.config;

import ch.benedict.m321.chatservice.TestInstances;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Szenarien S4 und S6 im Kleinen: Eine hängende Instanz füllt den Broker nicht
 * über 1000 Nachrichten, und die Queue einer verschwundenen Instanz ist weg.
 *
 * Jeder Test startet seine eigene Instanz, weil er sie anhalten oder ganz
 * herunterfahren muss.
 */
@Testcontainers
class LiveQueueLimitsIntegrationTest {

    /** Mehr als doppelt so viele Nachrichten, wie die Queue fassen darf. */
    private static final int PUBLISHED = 2000;

    /** So lange wartet ein Test höchstens, bis RabbitMQ den erwarteten Zustand zeigt. */
    private static final long WAIT_MILLIS = 10_000;

    /** S4: Spätestens nach dieser Zeit muss die Queue einer beendeten Instanz weg sein. */
    private static final long QUEUE_GONE_MILLIS = 30_000;

    @Container
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    /**
     * Stellt eine hängende Instanz nach: Ein Consumer mit Prefetch 50 nimmt
     * Nachrichten an, bestätigt aber nie. Von 2000 Nachrichten bleiben dann 50
     * unbestätigt und 950 bereit, zusammen 1000. Weggeworfen werden die ältesten:
     * In der Queue liegen genau die neuesten 950.
     */
    @Test
    void hangingInstanceHoldsAtMostThousandNewestMessages() throws Exception {
        ConfigurableApplicationContext instance = TestInstances.start(rabbitMq, "hanging");
        String queueName = QueueNames.LIVE_QUEUE_PREFIX + "hanging";
        try {
            RabbitListenerEndpointRegistry listenerRegistry = instance.getBean(RabbitListenerEndpointRegistry.class);
            listenerRegistry.stop();

            // Exklusive Queue: Nur die Verbindung der Instanz darf sie benutzen,
            // darum holt der Test seine Kanäle aus deren ConnectionFactory.
            ConnectionFactory connectionFactory = instance.getBean(ConnectionFactory.class);
            Connection connection = connectionFactory.createConnection();
            Channel hangingChannel = connection.createChannel(false);
            AtomicInteger unacknowledged = consumeWithoutAck(hangingChannel, queueName);

            Channel publishChannel = connection.createChannel(false);
            publishAndWaitForConfirms(publishChannel);
            waitUntilReceived(unacknowledged, RabbitConfig.LIVE_PREFETCH);

            long ready = publishChannel.messageCount(queueName);
            int unacked = unacknowledged.get();
            long total = ready + unacked;
            assertEquals(RabbitConfig.LIVE_PREFETCH, unacked);
            assertEquals(RabbitConfig.LIVE_QUEUE_MAX_LENGTH, ready);
            assertEquals(1000, total);

            int oldestKept = PUBLISHED - RabbitConfig.LIVE_QUEUE_MAX_LENGTH + 1;
            int firstReady = takeNumber(publishChannel, queueName);
            assertEquals(oldestKept, firstReady);
        } finally {
            instance.close();
        }
    }

    /**
     * S4: Fährt eine Instanz herunter, ist ihre Queue innert 30 s weg. Vorher hat
     * sie die verlangten Grenzen. chat.persist und chat.dlq bleiben bestehen.
     */
    @Test
    void queueOfStoppedInstanceDisappears() throws Exception {
        ConfigurableApplicationContext instance = TestInstances.start(rabbitMq, "short-lived");
        String queueName = QueueNames.LIVE_QUEUE_PREFIX + "short-lived";

        String before = listQueues();
        assertTrue(before.contains(queueName));
        String queueLine = lineOf(before, queueName);
        assertTrue(queueLine.contains("x-max-length") && queueLine.contains("950"), queueLine);
        assertTrue(queueLine.contains("x-message-ttl") && queueLine.contains("30000"), queueLine);
        assertTrue(queueLine.contains("drop-head"), queueLine);

        instance.close();

        long deadline = System.currentTimeMillis() + QUEUE_GONE_MILLIS;
        String after = listQueues();
        while (after.contains(queueName) && System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
            after = listQueues();
        }
        assertFalse(after.contains(queueName), "Live-Queue nach 30 s noch da");
        assertTrue(after.contains(QueueNames.PERSIST_QUEUE));
        assertTrue(after.contains(QueueNames.DEAD_LETTER_QUEUE));
    }

    /**
     * Meldet einen Consumer an, der Nachrichten nur zählt und nie bestätigt. Mit
     * Prefetch 50 schickt RabbitMQ ihm genau 50 und dann keine mehr.
     */
    private AtomicInteger consumeWithoutAck(Channel channel, String queueName) throws Exception {
        AtomicInteger received = new AtomicInteger();
        channel.basicQos(RabbitConfig.LIVE_PREFETCH);
        DefaultConsumer consumer = new DefaultConsumer(channel) {
            /** Zählt nur mit; ohne basicAck bleibt die Nachricht unbestätigt. */
            @Override
            public void handleDelivery(String consumerTag, Envelope envelope,
                                       AMQP.BasicProperties properties, byte[] body) {
                received.incrementAndGet();
            }
        };
        channel.basicConsume(queueName, false, consumer);
        return received;
    }

    /**
     * Veröffentlicht die Nummern 1 bis 2000 am Fanout-Exchange und wartet, bis
     * RabbitMQ jede bestätigt hat. Erst dann ist sicher, dass alle in der Queue
     * angekommen sind und die Zählung stimmt.
     */
    private void publishAndWaitForConfirms(Channel channel) throws Exception {
        channel.confirmSelect();
        for (int i = 1; i <= PUBLISHED; i++) {
            String text = Integer.toString(i);
            byte[] body = text.getBytes(StandardCharsets.UTF_8);
            channel.basicPublish(QueueNames.DELIVERY_EXCHANGE, "", null, body);
        }
        channel.waitForConfirmsOrDie(WAIT_MILLIS);
    }

    /** Wartet, bis der Consumer so viele Nachrichten bekommen hat; die Auslieferung läuft asynchron. */
    private void waitUntilReceived(AtomicInteger received, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT_MILLIS;
        while (received.get() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
    }

    /** Holt die vorderste bereitliegende Nachricht und gibt ihre Nummer zurück. */
    private int takeNumber(Channel channel, String queueName) throws Exception {
        GetResponse response = channel.basicGet(queueName, true);
        assertNotNull(response);
        byte[] body = response.getBody();
        String text = new String(body, StandardCharsets.UTF_8);
        return Integer.parseInt(text);
    }

    /** Fragt RabbitMQ wie in S4 nach allen Queues und ihren Argumenten. */
    private String listQueues() throws Exception {
        ExecResult result = rabbitMq.execInContainer("rabbitmqctl", "-q", "list_queues", "name", "arguments");
        return result.getStdout();
    }

    /** Die Zeile der Ausgabe, die zu einer Queue gehört. */
    private String lineOf(String output, String queueName) {
        String[] lines = output.split("\n");
        for (String line : lines) {
            if (line.startsWith(queueName)) {
                return line;
            }
        }
        return "";
    }
}
