package org.telegram.nexgram.intelligence;

/**
 * A public-data search module for the NEXGRAM Autonomous Intelligence
 * coordinator. Implementations must only access public pages/indexes and
 * must never attempt to deanonymize private individuals.
 */
public interface IntelModule {

    /** Stable module id for the registry and reports. */
    String id();

    /** Short human-readable description used in the report. */
    String describe();

    /** Whether this module applies to the query at all. */
    boolean canHandle(String query);

    /**
     * Runs the search. Called on a background thread.
     * Implementations must not throw: return results and report failure
     * through the error message parameter instead.
     */
    void search(String query, Callback callback);

    interface Callback {
        void onDone(java.util.List<IntelResult> results, String error);
    }
}
