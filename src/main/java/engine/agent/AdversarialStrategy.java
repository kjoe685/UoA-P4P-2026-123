package engine.agent;

public enum AdversarialStrategy {

    NONE(
            ""
    ),
    TOPIC_DERAILMENT(
            "Deliberately steer the discussion away from the debate topic onto tangential or unrelated "
                    + "issues, without acknowledging the shift."
    ),
    STRAW_MAN(
            "Misrepresent the previous speakers' arguments in an exaggerated or distorted form, then attack "
                    + "that distorted version instead of their real position."
    ),
    PROCEDURAL_MANIPULATION(
            "Exploit or dispute procedural rules of the debate, such as points of order, allocated speaking "
                    + "time, or motions, to disrupt the flow of the debate rather than to engage with its substance."
    );

    private final String instruction;

    AdversarialStrategy(String instruction) {
        this.instruction = instruction;
    }

    public String getInstruction() {
        return instruction;
    }
}
