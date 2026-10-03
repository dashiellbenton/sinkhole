package dev.sinkhole;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Swaps the Bedrock (Xbox gamertag) name for the Java username in text going to the Bedrock client. */
public final class NameRewriter {
    private final String bedrockName;
    private final String javaName;
    private final Pattern pattern;

    public NameRewriter(String bedrockName, String javaName) {
        this.bedrockName = bedrockName;
        this.javaName = javaName;
        this.pattern = bedrockName == null || bedrockName.isBlank()
                ? null
                : Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(bedrockName) + "(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE);
    }

    public String javaName() {
        return javaName;
    }

    /** Replace every occurrence of the Bedrock name with the Java name. */
    public String toJava(String text) {
        return pattern == null || text == null ? text : pattern.matcher(text).replaceAll(Matcher.quoteReplacement(javaName));
    }
}
