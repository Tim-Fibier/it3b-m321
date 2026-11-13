package ch.benedict.m321.chatservice;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.testcontainers.containers.RabbitMQContainer;

/**
 * Startet in Tests eine Instanz des chat-service wie einen eigenen Container:
 * eigener zufälliger Port, eigene Instanz-ID, gemeinsamer Broker.
 *
 * Gebraucht von Tests, die mehrere Instanzen nebeneinander brauchen oder eine
 * Instanz gezielt herunterfahren, was mit @SpringBootTest nicht geht.
 */
public final class TestInstances {

    /** Reine Hilfsklasse, wird nie erzeugt. */
    private TestInstances() {
    }

    /**
     * Startet eine Instanz gegen den RabbitMQ-Container.
     *
     * Die Werte gehen als Kommandozeilen-Argumente hinein, weil diese Vorrang vor
     * application.yml haben. Als blosse Vorgabewerte würden sie dort überschrieben.
     */
    public static ConfigurableApplicationContext start(RabbitMQContainer rabbitMq, String instanceId) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(ChatServiceApplication.class);
        return builder.run(
                "--server.port=0",
                "--chat-service.instance-id=" + instanceId,
                "--spring.rabbitmq.host=" + rabbitMq.getHost(),
                "--spring.rabbitmq.port=" + rabbitMq.getAmqpPort(),
                "--spring.rabbitmq.username=" + rabbitMq.getAdminUsername(),
                "--spring.rabbitmq.password=" + rabbitMq.getAdminPassword());
    }

    /** Der zufällige Port, den die Instanz beim Start bekommen hat. */
    public static int portOf(ConfigurableApplicationContext instance) {
        Environment environment = instance.getEnvironment();
        String port = environment.getProperty("local.server.port");
        return Integer.parseInt(port);
    }
}
