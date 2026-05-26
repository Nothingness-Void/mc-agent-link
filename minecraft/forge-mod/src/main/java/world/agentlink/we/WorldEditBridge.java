package world.agentlink.we;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import world.agentlink.dispatch.ToolException;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Bridge to WorldEdit / FastAsyncWorldEdit. Same model as {@link world.agentlink.spark.SparkBridge}:
 * the WE classes are {@code compileOnly} dependencies, so this class is the only place that
 * actually touches them, and it does so via reflection so the mod still compiles and runs when
 * WE isn't installed.
 *
 * <p>Concurrency: every WE edit happens on the server thread (caller responsibility — tools
 * dispatch via {@code mc.execute}). FAWE schedules its actual world writes asynchronously
 * internally; we don't try to interfere with that.
 *
 * <p>Undo model: we keep a single shared "agent" undo stack — every {@link #performEdit}
 * pushes the closed {@code EditSession} so {@link #performUndo} can pop it. There is no
 * per-MCP-session isolation; the assumption is that one agent is talking to one server.
 */
public final class WorldEditBridge {

    private static final Logger LOG = LogUtils.getLogger();
    private static final int UNDO_STACK_MAX = 50;

    private static volatile Boolean apiPresent;
    private static volatile String cachedImpl;
    private static volatile String cachedFaweVersion;

    private static final Deque<Object> UNDO_STACK = new ArrayDeque<>();
    private static final Object UNDO_LOCK = new Object();

    private WorldEditBridge() {}

    /** True if {@code com.sk89q.worldedit.WorldEdit} can be loaded AND its singleton resolves. */
    public static boolean isAvailable() {
        if (Boolean.FALSE.equals(apiPresent)) return false;
        try {
            getInstance();
            return true;
        } catch (Throwable t) {
            apiPresent = Boolean.FALSE;
            return false;
        }
    }

    /** {@code "FastAsyncWorldEdit"} when FAWE classes are present, otherwise {@code "WorldEdit"}. */
    public static String implementation() {
        if (cachedImpl != null) return cachedImpl;
        // FAWE ships its own classes alongside WE; presence of FaweAPI is the signal.
        for (String fq : new String[]{
                "com.fastasyncworldedit.core.Fawe",
                "com.fastasyncworldedit.core.FaweAPI",
                "com.boydti.fawe.Fawe"
        }) {
            try {
                Class.forName(fq);
                cachedImpl = "FastAsyncWorldEdit";
                return cachedImpl;
            } catch (Throwable ignored) {}
        }
        cachedImpl = "WorldEdit";
        return cachedImpl;
    }

    /** WorldEdit core version — calls {@code WorldEdit.getVersion()}. Returns null on error. */
    public static String version() {
        try {
            Class<?> we = Class.forName("com.sk89q.worldedit.WorldEdit");
            Method m = we.getMethod("getVersion");
            Object v = m.invoke(null);
            return v == null ? null : v.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** FAWE version when FAWE is present. Best-effort across versions. */
    public static String faweVersion() {
        if (cachedFaweVersion != null) return cachedFaweVersion;
        for (String fq : new String[]{"com.fastasyncworldedit.core.Fawe", "com.boydti.fawe.Fawe"}) {
            try {
                Class<?> c = Class.forName(fq);
                try {
                    Object inst = c.getMethod("instance").invoke(null);
                    Object v = c.getMethod("getVersion").invoke(inst);
                    if (v != null) {
                        cachedFaweVersion = v.toString();
                        return cachedFaweVersion;
                    }
                } catch (Throwable ignored) {}
                try {
                    Object v = c.getMethod("getVersion").invoke(null);
                    if (v != null) {
                        cachedFaweVersion = v.toString();
                        return cachedFaweVersion;
                    }
                } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** Number of edit sessions currently stored in the agent undo stack. */
    public static int undoDepth() {
        synchronized (UNDO_LOCK) {
            return UNDO_STACK.size();
        }
    }

    /** Functional handle handed to {@link #performEdit}. Receives the live EditSession. */
    @FunctionalInterface
    public interface EditOp {
        void run(EditContext ctx) throws Exception;
    }

    /**
     * Result returned to a caller after {@link #performEdit}. {@code changed} is the
     * EditSession's reported {@code getBlockChangeCount()} at close time.
     */
    public record EditResult(int changed, int undoDepth) {}

    /** Contextual handle exposing the reflective WE primitives the tools need. */
    public static final class EditContext {
        private final Object editSession;
        private final ServerLevel level;
        private final WorldEditBridge bridge;

        EditContext(Object editSession, ServerLevel level) {
            this.editSession = editSession;
            this.level = level;
            this.bridge = null;
        }

        public Object editSession() { return editSession; }
        public ServerLevel level() { return level; }

        /**
         * {@code editSession.setBlock(BlockVector3.at(x,y,z), pattern)}.
         * @return whether the block changed (WE returns false when identical).
         */
        public boolean setBlock(int x, int y, int z, Object pattern) throws ToolException {
            try {
                Object pos = blockVector3At(x, y, z);
                Class<?> esCls = editSession.getClass();
                Class<?> blockHolder = Class.forName("com.sk89q.worldedit.world.block.BlockStateHolder");
                Method m = findMethod(esCls, "setBlock", new Class<?>[]{getBlockVector3Class(), blockHolder});
                Object out = m.invoke(editSession, pos, pattern);
                return out instanceof Boolean ? (Boolean) out : false;
            } catch (Throwable t) {
                throw bridgeError("setBlock", t);
            }
        }

        public int makeCuboidFaces(int x1, int y1, int z1, int x2, int y2, int z2, Object pattern) throws ToolException {
            // We implement this by walking the region ourselves; not all WE versions expose a fill
            // for arbitrary cuboid regions through a single method that accepts a pattern.
            int min_x = Math.min(x1, x2), max_x = Math.max(x1, x2);
            int min_y = Math.min(y1, y2), max_y = Math.max(y1, y2);
            int min_z = Math.min(z1, z2), max_z = Math.max(z1, z2);
            int changed = 0;
            for (int x = min_x; x <= max_x; x++) {
                for (int y = min_y; y <= max_y; y++) {
                    for (int z = min_z; z <= max_z; z++) {
                        if (setBlock(x, y, z, pattern)) changed++;
                    }
                }
            }
            return changed;
        }

        /** {@code editSession.makeSphere(BlockVector3 pos, Pattern, double radius, boolean filled)}. */
        public int makeSphere(int cx, int cy, int cz, double radius, Object pattern, boolean filled) throws ToolException {
            try {
                Object pos = blockVector3At(cx, cy, cz);
                Class<?> esCls = editSession.getClass();
                Class<?> patternCls = Class.forName("com.sk89q.worldedit.function.pattern.Pattern");
                Method m = findMethod(esCls, "makeSphere",
                        new Class<?>[]{getBlockVector3Class(), patternCls, double.class, boolean.class});
                Object out = m.invoke(editSession, pos, pattern, radius, filled);
                return ((Number) out).intValue();
            } catch (Throwable t) {
                throw bridgeError("makeSphere", t);
            }
        }

        /** {@code editSession.makeCylinder(BlockVector3, Pattern, double radiusX, double radiusZ, int height, boolean filled)}. */
        public int makeCylinder(int cx, int cy, int cz, double rx, double rz, int height, Object pattern, boolean filled) throws ToolException {
            try {
                Object pos = blockVector3At(cx, cy, cz);
                Class<?> esCls = editSession.getClass();
                Class<?> patternCls = Class.forName("com.sk89q.worldedit.function.pattern.Pattern");
                Method m = findMethod(esCls, "makeCylinder",
                        new Class<?>[]{getBlockVector3Class(), patternCls, double.class, double.class, int.class, boolean.class});
                Object out = m.invoke(editSession, pos, pattern, rx, rz, height, filled);
                return ((Number) out).intValue();
            } catch (Throwable t) {
                throw bridgeError("makeCylinder", t);
            }
        }

        /**
         * Replace blocks matching {@code mask} with {@code pattern} inside the cuboid region.
         * {@code mask} may be {@code null} to mean "any block" — in that case we route through
         * {@code EditSession.setBlocks(Region, Pattern)} since WE refuses a null Mask.
         */
        public int replaceBlocks(int x1, int y1, int z1, int x2, int y2, int z2,
                                 Object mask, Object pattern) throws ToolException {
            try {
                Object min = blockVector3At(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2));
                Object max = blockVector3At(Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
                Class<?> regionCls = Class.forName("com.sk89q.worldedit.regions.Region");
                Class<?> cuboidCls = Class.forName("com.sk89q.worldedit.regions.CuboidRegion");
                Object region = cuboidCls.getConstructor(getBlockVector3Class(), getBlockVector3Class())
                        .newInstance(min, max);
                Class<?> esCls = editSession.getClass();
                Class<?> patternCls = Class.forName("com.sk89q.worldedit.function.pattern.Pattern");
                if (mask == null) {
                    Method m = findMethod(esCls, "setBlocks",
                            new Class<?>[]{regionCls, patternCls});
                    if (m == null) throw new ToolException("WE_ERROR", "EditSession.setBlocks(Region,Pattern) not found");
                    Object out = m.invoke(editSession, region, pattern);
                    return ((Number) out).intValue();
                }
                Class<?> maskCls = Class.forName("com.sk89q.worldedit.function.mask.Mask");
                Method m = findMethod(esCls, "replaceBlocks",
                        new Class<?>[]{regionCls, maskCls, patternCls});
                if (m == null) throw new ToolException("WE_ERROR", "EditSession.replaceBlocks(Region,Mask,Pattern) not found");
                Object out = m.invoke(editSession, region, mask, pattern);
                return ((Number) out).intValue();
            } catch (ToolException te) {
                throw te;
            } catch (Throwable t) {
                throw bridgeError("replaceBlocks", t);
            }
        }
    }

    /**
     * Run an edit with a fresh EditSession. The session is closed on success and pushed onto
     * the undo stack so {@link #performUndo} can pop it. Caller must already be on the server
     * thread.
     */
    public static EditResult performEdit(ServerLevel level, EditOp op) throws ToolException {
        if (!isAvailable()) throw new ToolException("WE_NOT_AVAILABLE", "WorldEdit / FAWE is not installed.");
        Object weWorld = adaptForgeWorld(level);
        Object editSession = newEditSession(weWorld);
        EditContext ctx = new EditContext(editSession, level);
        int changed;
        try {
            op.run(ctx);
            changed = blockChangeCount(editSession);
        } catch (ToolException te) {
            closeQuiet(editSession);
            throw te;
        } catch (Throwable t) {
            closeQuiet(editSession);
            throw bridgeError("performEdit", t);
        }
        try {
            close(editSession);
        } catch (Throwable t) {
            throw bridgeError("close", t);
        }
        synchronized (UNDO_LOCK) {
            UNDO_STACK.push(editSession);
            while (UNDO_STACK.size() > UNDO_STACK_MAX) UNDO_STACK.pollLast();
            return new EditResult(changed, UNDO_STACK.size());
        }
    }

    /** Pop {@code steps} EditSessions off the agent undo stack and undo each. */
    public static UndoResult performUndo(ServerLevel level, int steps) throws ToolException {
        if (!isAvailable()) throw new ToolException("WE_NOT_AVAILABLE", "WorldEdit / FAWE is not installed.");
        if (steps <= 0) throw new ToolException("INVALID_ARGS", "steps must be > 0");
        Object weWorld = adaptForgeWorld(level);
        int undone = 0;
        int totalChanged = 0;
        for (int i = 0; i < steps; i++) {
            Object oldSession;
            synchronized (UNDO_LOCK) {
                oldSession = UNDO_STACK.pollFirst();
            }
            if (oldSession == null) break;
            Object newSession = newEditSession(weWorld);
            try {
                Class<?> esCls = oldSession.getClass();
                Method undo = findMethod(esCls, "undo", new Class<?>[]{esCls.getSuperclass() == null ? esCls : esCls});
                if (undo == null) {
                    // EditSession.undo(EditSession) — try by exact name match where the param type
                    // is also EditSession (it might appear as a superclass).
                    Class<?> editSessionType = Class.forName("com.sk89q.worldedit.EditSession");
                    undo = editSessionType.getMethod("undo", editSessionType);
                }
                undo.invoke(oldSession, newSession);
                totalChanged += blockChangeCount(newSession);
                undone++;
            } catch (Throwable t) {
                closeQuiet(newSession);
                throw bridgeError("undo", t);
            }
            try {
                close(newSession);
            } catch (Throwable ignored) {}
        }
        return new UndoResult(undone, totalChanged, undoDepth());
    }

    public record UndoResult(int undone, int changed, int remainingDepth) {}

    /** Resolve a block id ({@code "minecraft:stone"}) to a BlockState usable as a Pattern. */
    public static Object resolveBlockState(String id) throws ToolException {
        try {
            Class<?> blockTypes = Class.forName("com.sk89q.worldedit.world.block.BlockTypes");
            Method get = blockTypes.getMethod("get", String.class);
            Object type = get.invoke(null, id);
            if (type == null) throw new ToolException("INVALID_ARGS", "Unknown block id: " + id);
            Method def = type.getClass().getMethod("getDefaultState");
            Object state = def.invoke(type);
            if (state == null) throw new ToolException("INVALID_ARGS", "Block has no default state: " + id);
            return state;
        } catch (ToolException te) {
            throw te;
        } catch (Throwable t) {
            throw bridgeError("resolveBlockState(" + id + ")", t);
        }
    }

    /** Resolve a list of block ids into a {@code BlockMask} matching any of them. */
    public static Object resolveBlockMask(java.util.List<String> ids, Object editSession) throws ToolException {
        if (ids == null || ids.isEmpty()) return null;
        try {
            Class<?> blockMaskCls = Class.forName("com.sk89q.worldedit.function.mask.BlockMask");
            Class<?> extentCls = Class.forName("com.sk89q.worldedit.extent.Extent");
            // Prefer the (Extent, BaseBlock...) constructor.
            Class<?> baseBlockCls = Class.forName("com.sk89q.worldedit.world.block.BaseBlock");
            Object[] baseBlocks = (Object[]) java.lang.reflect.Array.newInstance(baseBlockCls, ids.size());
            for (int i = 0; i < ids.size(); i++) {
                Object state = resolveBlockState(ids.get(i));
                Method toBaseBlock = state.getClass().getMethod("toBaseBlock");
                baseBlocks[i] = toBaseBlock.invoke(state);
            }
            return blockMaskCls.getConstructor(extentCls, baseBlocks.getClass())
                    .newInstance(editSession, baseBlocks);
        } catch (Throwable t) {
            throw bridgeError("resolveBlockMask", t);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Object getInstance() throws ReflectiveOperationException {
        Class<?> we = Class.forName("com.sk89q.worldedit.WorldEdit");
        return we.getMethod("getInstance").invoke(null);
    }

    private static Object adaptForgeWorld(ServerLevel level) throws ToolException {
        // Try the well-known Forge / NeoForge / FAWE adapter classes in order.
        String[] candidates = {
                "com.sk89q.worldedit.forge.ForgeAdapter",
                "com.sk89q.worldedit.neoforge.NeoForgeAdapter",
                "com.fastasyncworldedit.forge.FaweForgeAdapter"
        };
        Throwable last = null;
        for (String fq : candidates) {
            try {
                Class<?> cls = Class.forName(fq);
                for (Method m : cls.getMethods()) {
                    if (!"adapt".equals(m.getName()) || m.getParameterCount() != 1) continue;
                    Class<?> p = m.getParameterTypes()[0];
                    // We're looking for an adapt(Level) where Level is net.minecraft.world.level.Level
                    // or one of its subclasses.
                    if (p.isAssignableFrom(level.getClass())) {
                        Object out = m.invoke(null, level);
                        if (out != null) return out;
                    }
                }
            } catch (Throwable t) {
                last = t;
            }
        }
        throw bridgeError("adapt(level)",
                last == null ? new ClassNotFoundException("no Forge adapter found") : last);
    }

    private static Object newEditSession(Object weWorld) throws ToolException {
        try {
            Object instance = getInstance();
            // Modern API: WorldEdit.newEditSessionBuilder().world(W).maxBlocks(-1).build()
            Method newBuilder = instance.getClass().getMethod("newEditSessionBuilder");
            Object builder = newBuilder.invoke(instance);
            Class<?> worldCls = Class.forName("com.sk89q.worldedit.world.World");
            Method world = builder.getClass().getMethod("world", worldCls);
            builder = world.invoke(builder, weWorld);
            try {
                Method maxBlocks = builder.getClass().getMethod("maxBlocks", int.class);
                builder = maxBlocks.invoke(builder, -1);
            } catch (NoSuchMethodException ignored) {}
            Method build = builder.getClass().getMethod("build");
            return build.invoke(builder);
        } catch (Throwable t) {
            throw bridgeError("newEditSession", t);
        }
    }

    private static int blockChangeCount(Object editSession) {
        try {
            Method m = editSession.getClass().getMethod("getBlockChangeCount");
            Object out = m.invoke(editSession);
            return out instanceof Number ? ((Number) out).intValue() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void close(Object editSession) throws Exception {
        // EditSession implements AutoCloseable in modern WE.
        try {
            Method close = editSession.getClass().getMethod("close");
            close.invoke(editSession);
        } catch (NoSuchMethodException nsme) {
            // Fall back to flushSession() / flushQueue() for very old versions.
            try {
                Method flush = editSession.getClass().getMethod("flushSession");
                flush.invoke(editSession);
            } catch (NoSuchMethodException ignored) {
                Method flush = editSession.getClass().getMethod("flushQueue");
                flush.invoke(editSession);
            }
        }
    }

    private static void closeQuiet(Object editSession) {
        try {
            close(editSession);
        } catch (Throwable t) {
            LOG.debug("agent-link WE: close failed: {}", t.toString());
        }
    }

    private static Class<?> getBlockVector3Class() throws ClassNotFoundException {
        return Class.forName("com.sk89q.worldedit.math.BlockVector3");
    }

    private static Object blockVector3At(int x, int y, int z) throws ReflectiveOperationException {
        Class<?> bv3 = getBlockVector3Class();
        Method at = bv3.getMethod("at", int.class, int.class, int.class);
        return at.invoke(null, x, y, z);
    }

    /**
     * Find a method by name + exact parameter types, walking the class hierarchy. Returns null
     * if not found (no exception). EditSession's setBlock has been polymorphic across versions
     * so we keep this defensive.
     */
    private static Method findMethod(Class<?> cls, String name, Class<?>[] params) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                return c.getMethod(name, params);
            } catch (NoSuchMethodException ignored) {}
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != params.length) continue;
                Class<?>[] mp = m.getParameterTypes();
                boolean ok = true;
                for (int i = 0; i < params.length; i++) {
                    if (!params[i].isAssignableFrom(mp[i]) && !mp[i].isAssignableFrom(params[i])) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    private static ToolException bridgeError(String op, Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String msg = root.getMessage();
        return new ToolException("WE_ERROR",
                "WE " + op + " failed: " + root.getClass().getSimpleName() + (msg == null ? "" : ": " + msg));
    }
}
