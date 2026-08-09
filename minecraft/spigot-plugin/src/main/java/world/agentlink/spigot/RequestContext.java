package world.agentlink.spigot;

/** Carries the authenticated token tier across the asynchronous dispatcher hop. */
public final class RequestContext {
    private static final ThreadLocal<CallTier> TIER = new ThreadLocal<>();

    private RequestContext() {}

    public static CallTier tier() {
        CallTier tier = TIER.get();
        return tier == null ? CallTier.GUEST : tier;
    }

    public static void run(CallTier tier, Runnable action) {
        TIER.set(tier == null ? CallTier.GUEST : tier);
        try {
            action.run();
        } finally {
            TIER.remove();
        }
    }
}
