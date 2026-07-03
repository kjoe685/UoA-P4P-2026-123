package engine.agent;

public enum Party {

    LABOUR(
            "Labour",
            "centre-left, emphasising social welfare, workers' rights, and public services"
    ),
    NATIONAL(
            "National",
            "centre-right, emphasising fiscal responsibility, business growth, and personal responsibility"
    ),
    GREEN(
            "Green",
            "progressive, emphasising environmental sustainability, climate action, and social justice"
    ),
    ACT(
            "ACT",
            "classical liberal, emphasising free markets, individual liberty, and small government"
    ),
    NZ_FIRST(
            "NZ First",
            "populist and nationalist, emphasising national sovereignty, immigration control, and provincial New Zealand"
    );

    private final String displayName;
    private final String ideology;

    Party(String displayName, String ideology) {
        this.displayName = displayName;
        this.ideology = ideology;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getIdeology() {
        return ideology;
    }
}
