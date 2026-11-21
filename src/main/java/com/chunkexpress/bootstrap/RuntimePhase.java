package com.chunkexpress.bootstrap;

/**
 * The life of one server as Chunk Express sees it. A singleplayer session creates a new server, and
 * so a new runtime, every time a world is opened.
 *
 * <pre>
 *   STARTING --ServerStarted--> RUNNING --ServerStopping--> STOPPING --ServerStopped--> STOPPED
 *       \                          \_____________________________________________________/^
 *        \______________________________ ServerStopped (crash) ________________________/
 * </pre>
 *
 * Reaching STOPPED without passing STOPPING means the server did not shut down normally (a crash,
 * or a failure during startup); NeoForge still fires ServerStopped from a finally block.
 */
public enum RuntimePhase {
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED;

    public boolean canMoveTo(RuntimePhase next) {
        return switch (this) {
            case STARTING -> next == RUNNING || next == STOPPING || next == STOPPED;
            case RUNNING -> next == STOPPING || next == STOPPED;
            case STOPPING -> next == STOPPED;
            case STOPPED -> false;
        };
    }
}
