package ch.benedict.m321.batchwriter.message;

/**
 * Diese Nachricht wird nie lesbar, egal wie oft man es versucht.
 *
 * Der Listener braucht genau diese eine Unterscheidung: eine unlesbare
 * Nachricht gehört sofort in die Dead-Letter-Queue, statt immer wieder
 * zugestellt zu werden (Spezifikation 3.4).
 */
public class InvalidMessageException extends RuntimeException {

    /** Mit einem Grund für das Log, ohne ursprüngliche Ausnahme. */
    public InvalidMessageException(String reason) {
        super(reason);
    }

    /** Mit einem Grund für das Log und der Ausnahme, die ihn ausgelöst hat. */
    public InvalidMessageException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
