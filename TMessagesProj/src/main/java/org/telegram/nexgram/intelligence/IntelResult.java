package org.telegram.nexgram.intelligence;

/**
 * A single verified result produced by an intelligence module.
 * Every result carries its source, link, date and confidence level,
 * as required by the NEXGRAM master prompt (public data only).
 */
public final class IntelResult {

    /** Source-level confidence, not personal-data certainty. */
    public static final int CONFIRMED = 2;   // live public page fetched directly
    public static final int PROBABLE = 1;    // match in a public search index
    public static final int UNVERIFIED = 0;  // mention without a direct page

    public final String title;
    public final String summary;
    public final String url;
    public final String source;
    public final String dateText;
    public final int confidence;

    public IntelResult(String title, String summary, String url, String source, String dateText, int confidence) {
        this.title = title;
        this.summary = summary;
        this.url = url;
        this.source = source;
        this.dateText = dateText;
        this.confidence = confidence;
    }

    public static String confidenceLabel(int confidence) {
        switch (confidence) {
            case CONFIRMED:
                return "Підтверджено";
            case PROBABLE:
                return "Ймовірно";
            default:
                return "Непідтверджено";
        }
    }
}
