package engine.application;

import engine.agent.*;
import engine.config.*;
import engine.transcript.Topic;
import java.util.*;

/** One validated settings contract, shared by both interfaces. No credentials. */
public record RunSpec(List<Topic> topics, int rounds, List<Member> members, String agentModelPreset,
                      String evaluatorModelPreset) {
    public record Member(Party party, AdversarialStrategy strategy, String modelPreset) { }
    public RunSpec { topics=List.copyOf(topics); members=List.copyOf(members); }
    public static RunSpec resolve(Map<String,Object> body, EngineConfig config) {
        List<Topic> topics=new ArrayList<>();
        for (Object item:list(body,"topics")) {
            String title, target=null;
            if (item instanceof String text) title=text;
            else if (item instanceof Map<?,?> value && value.get("title") instanceof String text) {
                title=text; Object rawTarget=value.get("policyTarget");
                if (rawTarget != null && !(rawTarget instanceof String)) throw new IllegalArgumentException("Policy target must be text");
                target=(String) rawTarget;
            } else throw new IllegalArgumentException("Each topic must be text or a title/policyTarget object");
            title=title.trim();
            if (title.length()>300 || target!=null && target.length()>2000) throw new IllegalArgumentException("Topic or policy target is too long");
            if (!title.isEmpty()) topics.add(new Topic("topic-"+(topics.size()+1),title,target));
        }
        if (topics.isEmpty()) topics.add(new Topic("topic-1",config.defaultTopic(),null));
        if (topics.size()>10) throw new IllegalArgumentException("Choose at most 10 topics");
        Object roundValue=body.getOrDefault("rounds",config.defaultRounds());
        if (!(roundValue instanceof Number number) || number.doubleValue()!=number.intValue() || number.intValue()<1 || number.intValue()>10)
            throw new IllegalArgumentException("Rounds must be an integer between 1 and 10");
        String preset=string(body,"agentModelPreset",config.agentModelPreset());
        // Compatibility with T02 setup. Model preset selection supersedes this in T05.
        if (body.containsKey("provider") && !body.containsKey("agentModelPreset")) {
            String provider=string(body,"provider","demo");
            if (!Set.of("demo","openai").contains(provider)) throw new IllegalArgumentException("Choose demo or openai");
            preset=provider.equals("demo") ? "demo" : "gpt-5-nano";
        }
        String judge=string(body,"evaluatorModelPreset",config.evaluatorModelPreset());
        requirePreset(config,preset); requirePreset(config,judge);
        Set<Party> seen=EnumSet.noneOf(Party.class); List<Member> members=new ArrayList<>();
        for (Object item:list(body,"members")) {
            if (!(item instanceof Map<?,?> raw)) throw new IllegalArgumentException("Each member must be an object");
            Party party=enumValue(Party.class,raw.get("party"),"party");
            AdversarialStrategy strategy=raw.get("strategy")==null ? AdversarialStrategy.NONE
                    : enumValue(AdversarialStrategy.class,raw.get("strategy"),"strategy");
            if (!seen.add(party)) throw new IllegalArgumentException("Party selected more than once");
            Object modelValue=raw.get("modelPreset"); String modelPreset=preset;
            if (modelValue!=null) {
                if (!(modelValue instanceof String)) throw new IllegalArgumentException("Member modelPreset must be text");
                modelPreset=(String)modelValue;
            }
            requirePreset(config,modelPreset);
            members.add(new Member(party,strategy,modelPreset));
        }
        if (members.isEmpty()) throw new IllegalArgumentException("Select at least one party");
        return new RunSpec(topics,number.intValue(),members,preset,judge);
    }
    private static void requirePreset(EngineConfig config,String preset) {
        if (!config.models().containsKey(preset)) throw new IllegalArgumentException("Unknown model preset");
    }
    private static String string(Map<String,Object> body,String field,String fallback) {
        Object value=body.getOrDefault(field,fallback);
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException(field+" must be text");
        return text;
    }
    private static List<?> list(Map<String,Object> body,String field) {
        Object value=body.get(field); if (value==null) return List.of();
        if (!(value instanceof List<?> entries)) throw new IllegalArgumentException(field+" must be a list");
        return entries;
    }
    private static <E extends Enum<E>> E enumValue(Class<E> type,Object value,String field) {
        if (value instanceof String text) {
            try { return Enum.valueOf(type,text); } catch (IllegalArgumentException ignored) { }
        }
        throw new IllegalArgumentException("Unknown "+field);
    }
    @Override public String toString() { return "RunSpec[private assignments redacted]"; }
}
