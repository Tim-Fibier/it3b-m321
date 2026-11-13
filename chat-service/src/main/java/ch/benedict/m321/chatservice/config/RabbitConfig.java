package ch.benedict.m321.chatservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Legt beim Start alles an, was der Broker braucht.
 *
 * Spring meldet diese Beans beim Verbindungsaufbau an RabbitMQ. Existiert
 * eine Queue schon, passiert nichts — das Anlegen ist wiederholbar.
 */
@Configuration
public class RabbitConfig {

    /**
     * Höchstens so viele Nachrichten liegen in der Live-Queue bereit.
     *
     * Die Grenze für eine hängende Instanz ist 1000. x-max-length zählt aber nur
     * bereitliegende Nachrichten. Die bis zu LIVE_PREFETCH Nachrichten, die schon
     * an die Instanz ausgeliefert, aber noch nicht bestätigt sind, kommen dazu, und
     * rabbitmqctl zeigt beide zusammen. 950 + 50 = 1000.
     */
    static final int LIVE_QUEUE_MAX_LENGTH = 950;

    /** So viele Nachrichten darf RabbitMQ der Instanz ohne Bestätigung vorausschicken. */
    static final int LIVE_PREFETCH = 50;

    /**
     * Nach 30 s verwirft RabbitMQ eine Live-Nachricht, die noch nicht abgeholt
     * wurde. Eine so alte Live-Nachricht ist wertlos; wer sie braucht, holt sie
     * über Last-Event-ID aus der Datenbank.
     */
    static final int LIVE_MESSAGE_TTL_MILLIS = 30_000;

    /**
     * Der Schreibweg. "durable" heisst: die Queue überlebt einen Neustart
     * des Brokers. Was der Verbraucher endgültig ablehnt, landet über den
     * Standard-Exchange ("") in der Dead-Letter-Queue.
     */
    @Bean
    public Queue persistQueue() {
        return QueueBuilder.durable(QueueNames.PERSIST_QUEUE)
                .deadLetterExchange("")
                .deadLetterRoutingKey(QueueNames.DEAD_LETTER_QUEUE)
                .build();
    }

    /** Das Abstellgleis für Nachrichten, die niemand verarbeiten konnte. */
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(QueueNames.DEAD_LETTER_QUEUE).build();
    }

    /**
     * Der Zustellweg. Fanout heisst: JEDE gebundene Queue bekommt eine
     * Kopie. Das ist genau richtig, weil jede chat-service-Instanz jede
     * Nachricht sehen muss — nur sie weiss, welche ihrer Stream-Clients
     * im betroffenen Raum sitzen.
     */
    @Bean
    public FanoutExchange deliveryExchange() {
        return new FanoutExchange(QueueNames.DELIVERY_EXCHANGE, true, false);
    }

    /**
     * Die Live-Queue dieser Instanz, z. B. "chat.live.a2d450aeb2a5".
     *
     * Eine gemeinsame Queue für alle Instanzen ginge nicht: Dort teilen sich
     * die Verbraucher die Nachrichten, und jede käme nur bei einer Instanz an.
     * Nicht dauerhaft und exklusiv: Die Queue gehört zur Verbindung dieser
     * Instanz, und RabbitMQ löscht sie, sobald diese Verbindung weg ist.
     * Bewusst kein x-expires: RabbitMQ sagt nicht zu, wie schnell eine
     * abgelaufene Queue gelöscht wird.
     *
     * Ist die Queue voll, wirft RabbitMQ die älteste bereitliegende Nachricht weg
     * (drop-head). Für eine Live-Anzeige ist das richtig: Die neueste zählt, und
     * der Verlauf steht in der Datenbank.
     *
     * Geht die Verbindung verloren, legt Spring die Queue beim Wiederverbinden
     * aus dieser Bean neu an, mit denselben Grenzen.
     */
    @Bean
    public Queue liveQueue(@Value("${chat-service.instance-id}") String instanceId) {
        String queueName = QueueNames.LIVE_QUEUE_PREFIX + instanceId;
        return QueueBuilder.nonDurable(queueName)
                .exclusive()
                .maxLength(LIVE_QUEUE_MAX_LENGTH)
                .overflow(QueueBuilder.Overflow.dropHead)
                .ttl(LIVE_MESSAGE_TTL_MILLIS)
                .build();
    }

    /** Hängt die Live-Queue an den Fanout-Exchange, sonst verwirft RabbitMQ jede Kopie. */
    @Bean
    public Binding liveQueueBinding(@Qualifier("liveQueue") Queue liveQueue,
                                    FanoutExchange deliveryExchange) {
        return BindingBuilder.bind(liveQueue).to(deliveryExchange);
    }

    /**
     * Nachrichten gehen als JSON über die Leitung, nicht als serialisiertes
     * Java-Objekt. Nur so kann später ein Dienst in einer anderen Sprache
     * mitlesen.
     *
     * Der ObjectMapper kommt von Spring Boot und kann bereits Instant im
     * ISO-8601-Format schreiben.
     */
    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    /**
     * Die Einstellungen für den Listener auf der Live-Queue.
     *
     * Genau ein Consumer: Mit mehreren könnten zwei Nachrichten desselben
     * Absenders gleichzeitig verarbeitet werden und vertauscht beim Client
     * ankommen. Wirft der Listener doch einmal eine Exception, wird die Nachricht
     * verworfen statt zurückgelegt, sonst käme sie endlos wieder.
     *
     * Prefetch 50 statt der Vorgabe 250: Zusammen mit LIVE_QUEUE_MAX_LENGTH
     * bleibt eine hängende Instanz so bei höchstens 1000 Nachrichten.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory liveListenerContainerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        factory.setPrefetchCount(LIVE_PREFETCH);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
