package io.dockstore.webservice.core;

public class DescriptionMetrics {

    private final long descriptionLength;

    private final long entropy;

    private final long wordCount;

    public DescriptionMetrics(String descriptionContent) {
        assert descriptionContent != null;

        this.descriptionLength = descriptionContent.length();
        this.entropy = this.calculateEntropy(descriptionContent);
        this.wordCount = this.calculateWordCount(descriptionContent);
    }

    public long calculateEntropy(String descriptionContent) {
        // Represent entropy as the number of distinct characters in a string
        return descriptionContent.chars().distinct().count();
    }

    public long calculateWordCount(String descriptionContent) {

        // trim leading and trailing spaces
        String trimmed = descriptionContent.trim();
        if (trimmed.length() == 0) {
            return 0;
        }

        // split into a String[] by spaces and return the length.. Make sure to split by any length of spaces.
        return trimmed.split("\\s+").length;
    }

    public long getDescriptionLength() {
        return this.descriptionLength;
    }

    public long getCalculatedEntropy() {
        return this.entropy;
    }

    public long getCalculatedWordCount() {
        return this.wordCount;
    }

}
