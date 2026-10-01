package engine.config;

public record InterruptionConfig(double cooperativeChance, double adversarialChance, long seed) {
    public InterruptionConfig {
        if (!valid(cooperativeChance) || !valid(adversarialChance)) {
            throw new IllegalArgumentException("Interruption probabilities must be between 0 and 1");
        }
    }

    private static boolean valid(double chance) {
        return Double.isFinite(chance) && chance >= 0 && chance <= 1;
    }
}
