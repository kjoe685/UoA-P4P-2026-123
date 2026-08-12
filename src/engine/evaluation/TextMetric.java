package engine.evaluation;

import java.util.Objects;

public final class TextMetric implements MetricValue {
    private final String value;

    public TextMetric(String value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public String getValue() {
        return value;
    }
}
