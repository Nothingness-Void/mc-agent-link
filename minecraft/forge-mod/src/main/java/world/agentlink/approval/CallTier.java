package world.agentlink.approval;

/**
 * Per-call privilege tier inferred from the bearer token used. Set by the HTTP / WS transport
 * before invoking the dispatcher; read by {@link AgentToolApproval#request} to short-circuit
 * the in-game approval flow for {@link Tier#CONSOLE} tokens.
 *
 * <p>Implemented as a {@link ThreadLocal} because the dispatcher hops to the server thread via
 * {@code mc.execute(...)}, which preserves the calling thread's TL only when {@code dispatch.invoke}
 * captures it before scheduling. To cover that case, callers should always {@code with(tier, () ->
 * dispatcher.invoke(...))} the entire async chain — and {@link AgentToolApproval#request} reads
 * it on the originating thread before the {@code mc.execute} bounce, so the TL is still set.
 *
 * <p>If no tier is set, the default is {@link Tier#GUEST} — the same behavior as legacy 0.3.x and
 * earlier (the master token is implicitly GUEST).
 */
public final class CallTier {

    public enum Tier { CONSOLE, GUEST }

    private static final ThreadLocal<Tier> CURRENT = ThreadLocal.withInitial(() -> Tier.GUEST);

    private CallTier() {}

    public static Tier current() {
        return CURRENT.get();
    }

    public static boolean is(Tier tier) {
        return CURRENT.get() == tier;
    }

    public static void set(Tier tier) {
        CURRENT.set(tier == null ? Tier.GUEST : tier);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Run {@code body} with {@link Tier} pinned, restore on return. */
    public static <T> T with(Tier tier, java.util.function.Supplier<T> body) {
        Tier prev = CURRENT.get();
        CURRENT.set(tier == null ? Tier.GUEST : tier);
        try {
            return body.get();
        } finally {
            CURRENT.set(prev);
        }
    }

    public static void with(Tier tier, Runnable body) {
        Tier prev = CURRENT.get();
        CURRENT.set(tier == null ? Tier.GUEST : tier);
        try {
            body.run();
        } finally {
            CURRENT.set(prev);
        }
    }
}
