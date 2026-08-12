package engine.evaluation;

public final class NumericMetric implements MetricValue {
    private final double value;
    private final String explanation;

    public NumericMetric(double value, String explanation) {
        this.value = value;
        this.explanation = explanation;
    }

    public double getValue() {
        return value;
    }

    public String getExplanation() {
        return explanation;
    }
}
