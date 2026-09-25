package ch.benedict.m321.batchwriter.config;

/**
 * Die Namen der Queues an genau EINER Stelle.
 *
 * Eine eigene Kopie der Namen aus dem chat-service. Die beiden Dienste
 * teilen keinen Code, nur die Namen und das Format auf dem Broker.
 */
public final class QueueNames {

    /** Schreibweg: hier legt der chat-service jede Nachricht ab. */
    public static final String PERSIST_QUEUE = "chat.persist";

    /** Dead Letter: was der batch-writer endgültig nicht schreiben kann. */
    public static final String DEAD_LETTER_QUEUE = "chat.dlq";

    /** Diese Klasse ist eine reine Namenssammlung und wird nie erzeugt. */
    private QueueNames() {
    }
}
