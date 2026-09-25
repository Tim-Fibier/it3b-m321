package ch.benedict.m321.batchwriter.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Richtet beim Start alles ein, was der batch-writer am Broker braucht:
 * die beiden Queues und die Art, wie Nachrichten zu Paketen gebündelt werden.
 */
@Configuration
public class RabbitConfig {

    /**
     * Dieselbe Queue wie im chat-service, mit GENAU denselben Argumenten.
     *
     * Warum auch hier? Dann ist egal, welcher Dienst zuerst startet. Existiert
     * die Queue schon, passiert nichts. Weicht ein Argument ab, lehnt RabbitMQ
     * die Deklaration mit PRECONDITION_FAILED ab (Spezifikation 3.8).
     *
     * Das Dead-Letter-Argument ist der einzige Weg in die DLQ: lehnt der
     * batch-writer eine Nachricht ohne Requeue ab, legt RabbitMQ sie über den
     * Standard-Exchange ("") nach chat.dlq.
     */
    @Bean
    public Queue persistQueue() {
        return QueueBuilder.durable(QueueNames.PERSIST_QUEUE)
                .deadLetterExchange("")
                .deadLetterRoutingKey(QueueNames.DEAD_LETTER_QUEUE)
                .build();
    }

    /** Das Abstellgleis für Nachrichten, die sich nie schreiben lassen. */
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(QueueNames.DEAD_LETTER_QUEUE).build();
    }

    /**
     * Stellt ein, wie der Listener seine Nachrichten bekommt: als Paket statt
     * einzeln (Spezifikation 3.1 und 4.4).
     *
     * - batchSize: höchstens so viele Nachrichten pro Paket (500).
     * - batchReceiveTimeout: so lange ab Beginn des Pakets wird höchstens
     *   gesammelt (200 ms). receiveTimeout allein würde nur die Pause ZWISCHEN
     *   zwei Nachrichten messen: kommt alle 100 ms eine, würde das Paket erst
     *   nach 500 Stück, also nach 50 Sekunden fertig.
     * - prefetchCount = batchSize: RabbitMQ schickt höchstens so viele
     *   unbestätigte Nachrichten. Wäre es weniger, käme nie ein volles Paket zusammen.
     * - MANUAL: wir bestätigen selbst, und zwar erst nach dem COMMIT.
     * - ein Consumer pro Instanz: skaliert wird über Instanzen, nicht über Threads.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory batchListenerFactory(
            ConnectionFactory connectionFactory,
            @Value("${batch-writer.batch-size}") int batchSize,
            @Value("${batch-writer.batch-timeout-ms}") long batchTimeoutMs) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setBatchListener(true);
        factory.setConsumerBatchEnabled(true);
        factory.setBatchSize(batchSize);
        factory.setReceiveTimeout(batchTimeoutMs);
        factory.setBatchReceiveTimeout(batchTimeoutMs);
        factory.setPrefetchCount(batchSize);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setConcurrentConsumers(1);
        return factory;
    }
}
