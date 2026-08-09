package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import world.agentlink.dispatch.Tool;
import world.agentlink.transport.ClientSession;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

/**
 * JVM thread dump. Pairs with {@link TickProfileTool}: when p99 mspt is bad, dump
 * threads to see what the server thread (and any heavy worker) is currently doing.
 */
public class ThreadDumpTool implements Tool {

    private static final int DEFAULT_FRAMES = 30;
    private static final int HARD_MAX_FRAMES = 200;

    @Override
    public String name() {
        return "thread_dump";
    }

    @Override
    public boolean offThread() {
        // JVM introspection can pause while collecting monitor data; never run it on the tick
        // thread, especially when an agent asks for a full diagnosis.
        return true;
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) {
        int maxFrames = args.has("max_frames") ? args.get("max_frames").getAsInt() : DEFAULT_FRAMES;
        if (maxFrames <= 0) maxFrames = DEFAULT_FRAMES;
        if (maxFrames > HARD_MAX_FRAMES) maxFrames = HARD_MAX_FRAMES;
        boolean onlyServer = args.has("only_server") && args.get("only_server").getAsBoolean();

        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        ThreadInfo[] infos = mx.dumpAllThreads(true, true);

        JsonArray arr = new JsonArray();
        for (ThreadInfo info : infos) {
            if (info == null) continue;
            String name = info.getThreadName();
            if (onlyServer && !name.contains("Server")) continue;

            JsonObject o = new JsonObject();
            o.addProperty("id", info.getThreadId());
            o.addProperty("name", name);
            o.addProperty("state", info.getThreadState().name());
            if (info.getLockName() != null) o.addProperty("lock", info.getLockName());
            if (info.getLockOwnerName() != null) o.addProperty("lock_owner", info.getLockOwnerName());

            StackTraceElement[] stack = info.getStackTrace();
            JsonArray frames = new JsonArray();
            int limit = Math.min(stack.length, maxFrames);
            for (int i = 0; i < limit; i++) {
                frames.add(stack[i].toString());
            }
            o.add("stack", frames);
            o.addProperty("stack_truncated", stack.length > limit);
            arr.add(o);
        }

        JsonObject r = new JsonObject();
        r.add("threads", arr);
        r.addProperty("count", arr.size());
        return r;
    }
}
