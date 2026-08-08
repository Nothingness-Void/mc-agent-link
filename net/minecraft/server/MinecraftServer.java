package net.minecraft.server;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.GameProfileRepository;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.datafixers.DataFixer;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.net.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.SharedConstants;
import net.minecraft.SystemReport;
import net.minecraft.Util;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.MiscOverworldFeatures;
import net.minecraft.gametest.framework.GameTestTicker;
import net.minecraft.network.chat.ChatDecorator;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.obfuscate.DontObfuscate;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.bossevents.CustomBossEvents;
import net.minecraft.server.level.DemoMode;
import net.minecraft.server.level.PlayerRespawnLogic;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.server.level.progress.ChunkProgressListenerFactory;
import net.minecraft.server.network.ServerConnectionListener;
import net.minecraft.server.network.TextFilter;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.server.players.UserWhiteList;
import net.minecraft.util.Crypt;
import net.minecraft.util.CryptException;
import net.minecraft.util.FrameTimer;
import net.minecraft.util.ModCheck;
import net.minecraft.util.Mth;
import net.minecraft.util.NativeModuleLister;
import net.minecraft.util.ProgressListener;
import net.minecraft.util.RandomSource;
import net.minecraft.util.SignatureValidator;
import net.minecraft.util.Unit;
import net.minecraft.util.profiling.EmptyProfileResults;
import net.minecraft.util.profiling.ProfileResults;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.profiling.ResultField;
import net.minecraft.util.profiling.SingleTickProfiler;
import net.minecraft.util.profiling.jfr.JvmProfiler;
import net.minecraft.util.profiling.jfr.callback.ProfiledDuration;
import net.minecraft.util.profiling.metrics.profiling.ActiveMetricsRecorder;
import net.minecraft.util.profiling.metrics.profiling.InactiveMetricsRecorder;
import net.minecraft.util.profiling.metrics.profiling.MetricsRecorder;
import net.minecraft.util.profiling.metrics.profiling.ServerMetricsSamplersProvider;
import net.minecraft.util.profiling.metrics.storage.MetricsPersister;
import net.minecraft.util.thread.ReentrantBlockableEventLoop;
import net.minecraft.world.Difficulty;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.village.VillageSiege;
import net.minecraft.world.entity.npc.CatSpawner;
import net.minecraft.world.entity.npc.WanderingTraderSpawner;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.ForcedChunksSavedData;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.PatrolSpawner;
import net.minecraft.world.level.levelgen.PhantomSpawner;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.CommandStorage;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PlayerDataStorage;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.WorldData;
import net.minecraft.world.level.storage.loot.LootDataManager;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

public abstract class MinecraftServer extends ReentrantBlockableEventLoop<TickTask> implements CommandSource, AutoCloseable {
   private static final Logger f_129750_ = LogUtils.getLogger();
   public static final String f_195495_ = "vanilla";
   private static final float f_177884_ = 0.8F;
   private static final int f_177885_ = 100;
   public static final int f_177878_ = 50;
   private static final int f_177887_ = 2000;
   private static final int f_177888_ = 15000;
   private static final long f_177889_ = 5000000000L;
   private static final int f_177890_ = 12;
   public static final int f_177882_ = 11;
   private static final int f_177891_ = 441;
   private static final int f_177892_ = 6000;
   private static final int f_177893_ = 3;
   public static final int f_177883_ = 29999984;
   public static final LevelSettings f_129743_ = new LevelSettings("Demo World", GameType.SURVIVAL, false, Difficulty.NORMAL, false, new GameRules(), WorldDataConfiguration.f_244649_);
   private static final long f_177871_ = 50L;
   public static final GameProfile f_195496_ = new GameProfile(Util.f_137441_, "Anonymous Player");
   protected final LevelStorageSource.LevelStorageAccess f_129744_;
   protected final PlayerDataStorage f_129745_;
   private final List<Runnable> f_129752_ = Lists.newArrayList();
   private MetricsRecorder f_177872_ = InactiveMetricsRecorder.f_146153_;
   private ProfilerFiller f_129754_ = this.f_177872_.m_142610_();
   private Consumer<ProfileResults> f_177873_ = (p_177903_) -> {
      this.m_177928_();
   };
   private Consumer<Path> f_177874_ = (p_177954_) -> {
   };
   private boolean f_177875_;
   @Nullable
   private MinecraftServer.TimeProfiler f_177876_;
   private boolean f_177877_;
   private final ServerConnectionListener f_129755_;
   private final ChunkProgressListenerFactory f_129756_;
   @Nullable
   private ServerStatus f_129757_;
   @Nullable
   private ServerStatus.Favicon f_271173_;
   private final RandomSource f_129758_ = RandomSource.m_216327_();
   private final DataFixer f_129759_;
   private String f_129760_;
   private int f_129761_ = -1;
   private final LayeredRegistryAccess<RegistryLayer> f_244176_;
   private final Map<ResourceKey<Level>, ServerLevel> f_129762_ = Maps.newLinkedHashMap();
   private PlayerList f_129763_;
   private volatile boolean f_129764_ = true;
   private boolean f_129765_;
   private int f_129766_;
   protected final Proxy f_129747_;
   private boolean f_129705_;
   private boolean f_129706_;
   private boolean f_129707_;
   private boolean f_129708_;
   @Nullable
   private String f_129709_;
   private int f_129711_;
   public final long[] f_129748_ = new long[100];
   @Nullable
   private KeyPair f_129712_;
   @Nullable
   private GameProfile f_236719_;
   private boolean f_129714_;
   private volatile boolean f_129717_;
   private long f_129718_;
   protected final Services f_236721_;
   private long f_129724_;
   private final Thread f_129725_;
   protected long f_129726_ = Util.m_137550_();
   private long f_129727_;
   private boolean f_129728_;
   private final PackRepository f_129730_;
   private final ServerScoreboard f_129731_ = new ServerScoreboard(this);
   @Nullable
   private CommandStorage f_129732_;
   private final CustomBossEvents f_129733_ = new CustomBossEvents();
   private final ServerFunctionManager f_129734_;
   private final FrameTimer f_129735_ = new FrameTimer();
   private boolean f_129736_;
   private float f_129737_;
   private final Executor f_129738_;
   @Nullable
   private String f_129739_;
   private MinecraftServer.ReloadableResources f_129740_;
   private final StructureTemplateManager f_236720_;
   protected final WorldData f_129749_;
   private volatile boolean f_195494_;

   public static <S extends MinecraftServer> S m_129872_(Function<Thread, S> p_129873_) {
      AtomicReference<S> atomicreference = new AtomicReference<>();
      Thread thread = new Thread(() -> {
         atomicreference.get().m_130011_();
      }, "Server thread");
      thread.setUncaughtExceptionHandler((p_177909_, p_177910_) -> {
         f_129750_.error("Uncaught exception in server thread", p_177910_);
      });
      if (Runtime.getRuntime().availableProcessors() > 4) {
         thread.setPriority(8);
      }

      S s = p_129873_.apply(thread);
      atomicreference.set(s);
      thread.start();
      return s;
   }

   public MinecraftServer(Thread p_236723_, LevelStorageSource.LevelStorageAccess p_236724_, PackRepository p_236725_, WorldStem p_236726_, Proxy p_236727_, DataFixer p_236728_, Services p_236729_, ChunkProgressListenerFactory p_236730_) {
      super("Server");
      this.f_244176_ = p_236726_.f_244542_();
      this.f_129749_ = p_236726_.f_206895_();
      if (!this.f_244176_.m_247579_().m_175515_(Registries.f_256862_).m_142003_(LevelStem.f_63971_)) {
         throw new IllegalStateException("Missing Overworld dimension data");
      } else {
         this.f_129747_ = p_236727_;
         this.f_129730_ = p_236725_;
         this.f_129740_ = new MinecraftServer.ReloadableResources(p_236726_.f_206892_(), p_236726_.f_206893_());
         this.f_236721_ = p_236729_;
         if (p_236729_.f_214336_() != null) {
            p_236729_.f_214336_().m_143974_(this);
         }

         this.f_129755_ = new ServerConnectionListener(this);
         this.f_129756_ = p_236730_;
         this.f_129744_ = p_236724_;
         this.f_129745_ = p_236724_.m_78301_();
         this.f_129759_ = p_236728_;
         this.f_129734_ = new ServerFunctionManager(this, this.f_129740_.f_206585_.m_206860_());
         HolderGetter<Block> holdergetter = this.f_244176_.m_247579_().m_175515_(Registries.f_256747_).m_255303_().m_245140_(this.f_129749_.m_247623_());
         this.f_236720_ = new StructureTemplateManager(p_236726_.f_206892_(), p_236724_, p_236728_, holdergetter);
         this.f_129725_ = p_236723_;
         this.f_129738_ = Util.m_183991_();
      }
   }

   private void m_129841_(DimensionDataStorage p_129842_) {
      p_129842_.m_164861_(this.m_129896_()::m_180013_, this.m_129896_()::m_180015_, "scoreboard");
   }

   protected abstract boolean m_7038_() throws IOException;

   protected void m_130006_() {
      if (!JvmProfiler.f_185340_.m_183608_()) {
      }

      boolean flag = false;
      ProfiledDuration profiledduration = JvmProfiler.f_185340_.m_183494_();
      this.f_129749_.m_7955_(this.getServerModName(), this.m_183471_().m_184597_());
      ChunkProgressListener chunkprogresslistener = this.f_129756_.m_9620_(11);
      this.m_129815_(chunkprogresslistener);
      this.m_7044_();
      this.m_129940_(chunkprogresslistener);
      if (profiledduration != null) {
         profiledduration.m_185413_();
      }

      if (flag) {
         try {
            JvmProfiler.f_185340_.m_183243_();
         } catch (Throwable throwable) {
            f_129750_.warn("Failed to stop JFR profiling", throwable);
         }
      }

   }

   protected void m_7044_() {
   }

   protected void m_129815_(ChunkProgressListener p_129816_) {
      ServerLevelData serverleveldata = this.f_129749_.m_5996_();
      boolean flag = this.f_129749_.m_7513_();
      Registry<LevelStem> registry = this.f_244176_.m_247579_().m_175515_(Registries.f_256862_);
      WorldOptions worldoptions = this.f_129749_.m_246337_();
      long i = worldoptions.m_245499_();
      long j = BiomeManager.m_47877_(i);
      List<CustomSpawner> list = ImmutableList.of(new PhantomSpawner(), new PatrolSpawner(), new CatSpawner(), new VillageSiege(), new WanderingTraderSpawner(serverleveldata));
      LevelStem levelstem = registry.m_6246_(LevelStem.f_63971_);
      ServerLevel serverlevel = new ServerLevel(this, this.f_129738_, this.f_129744_, serverleveldata, Level.f_46428_, levelstem, p_129816_, flag, j, list, true, (RandomSequences)null);
      this.f_129762_.put(Level.f_46428_, serverlevel);
      DimensionDataStorage dimensiondatastorage = serverlevel.m_8895_();
      this.m_129841_(dimensiondatastorage);
      this.f_129732_ = new CommandStorage(dimensiondatastorage);
      WorldBorder worldborder = serverlevel.m_6857_();
      if (!serverleveldata.m_6535_()) {
         try {
            m_177896_(serverlevel, serverleveldata, worldoptions.m_245100_(), flag);
            serverleveldata.m_5555_(true);
            if (flag) {
               this.m_129847_(this.f_129749_);
            }
         } catch (Throwable throwable1) {
            CrashReport crashreport = CrashReport.m_127521_(throwable1, "Exception initializing level");

            try {
               serverlevel.m_6026_(crashreport);
            } catch (Throwable throwable) {
            }

            throw new ReportedException(crashreport);
         }

         serverleveldata.m_5555_(true);
      }

      this.m_6846_().m_184209_(serverlevel);
      if (this.f_129749_.m_6587_() != null) {
         this.m_129901_().m_136295_(this.f_129749_.m_6587_());
      }

      RandomSequences randomsequences = serverlevel.m_288231_();

      for(Map.Entry<ResourceKey<LevelStem>, LevelStem> entry : registry.m_6579_()) {
         ResourceKey<LevelStem> resourcekey = entry.getKey();
         if (resourcekey != LevelStem.f_63971_) {
            ResourceKey<Level> resourcekey1 = ResourceKey.m_135785_(Registries.f_256858_, resourcekey.m_135782_());
            DerivedLevelData derivedleveldata = new DerivedLevelData(this.f_129749_, serverleveldata);
            ServerLevel serverlevel1 = new ServerLevel(this, this.f_129738_, this.f_129744_, derivedleveldata, resourcekey1, entry.getValue(), p_129816_, flag, j, ImmutableList.of(), false, randomsequences);
            worldborder.m_61929_(new BorderChangeListener.DelegateBorderChangeListener(serverlevel1.m_6857_()));
            this.f_129762_.put(resourcekey1, serverlevel1);
         }
      }

      worldborder.m_61931_(serverleveldata.m_5813_());
   }

   private static void m_177896_(ServerLevel p_177897_, ServerLevelData p_177898_, boolean p_177899_, boolean p_177900_) {
      if (p_177900_) {
         p_177898_.m_7250_(BlockPos.f_121853_.m_6630_(80), 0.0F);
      } else {
         ServerChunkCache serverchunkcache = p_177897_.m_7726_();
         ChunkPos chunkpos = new ChunkPos(serverchunkcache.m_214994_().m_224579_().m_183230_());
         int i = serverchunkcache.m_8481_().m_142051_(p_177897_);
         if (i < p_177897_.m_141937_()) {
            BlockPos blockpos = chunkpos.m_45615_();
            i = p_177897_.m_6924_(Heightmap.Types.WORLD_SURFACE, blockpos.m_123341_() + 8, blockpos.m_123343_() + 8);
         }

         p_177898_.m_7250_(chunkpos.m_45615_().m_7918_(8, i, 8), 0.0F);
         int k1 = 0;
         int j = 0;
         int k = 0;
         int l = -1;
         int i1 = 5;

         for(int j1 = 0; j1 < Mth.m_144944_(11); ++j1) {
            if (k1 >= -5 && k1 <= 5 && j >= -5 && j <= 5) {
               BlockPos blockpos1 = PlayerRespawnLogic.m_183932_(p_177897_, new ChunkPos(chunkpos.f_45578_ + k1, chunkpos.f_45579_ + j));
               if (blockpos1 != null) {
                  p_177898_.m_7250_(blockpos1, 0.0F);
                  break;
               }
            }

            if (k1 == j || k1 < 0 && k1 == -j || k1 > 0 && k1 == 1 - j) {
               int l1 = k;
               k = -l;
               l = l1;
            }

            k1 += k;
            j += l;
         }

         if (p_177899_) {
            p_177897_.m_9598_().m_6632_(Registries.f_256911_).flatMap((p_258226_) -> {
               return p_258226_.m_203636_(MiscOverworldFeatures.f_195021_);
            }).ifPresent((p_264729_) -> {
               p_264729_.m_203334_().m_224953_(p_177897_, serverchunkcache.m_8481_(), p_177897_.f_46441_, new BlockPos(p_177898_.m_6789_(), p_177898_.m_6527_(), p_177898_.m_6526_()));
            });
         }

      }
   }

   private void m_129847_(WorldData p_129848_) {
      p_129848_.m_6166_(Difficulty.PEACEFUL);
      p_129848_.m_5560_(true);
      ServerLevelData serverleveldata = p_129848_.m_5996_();
      serverleveldata.m_5565_(false);
      serverleveldata.m_5557_(false);
      serverleveldata.m_6393_(1000000000);
      serverleveldata.m_6247_(6000L);
      serverleveldata.m_5458_(GameType.SPECTATOR);
   }

   private void m_129940_(ChunkProgressListener p_129941_) {
      ServerLevel serverlevel = this.m_129783_();
      f_129750_.info("Preparing start region for dimension {}", (Object)serverlevel.m_46472_().m_135782_());
      BlockPos blockpos = serverlevel.m_220360_();
      p_129941_.m_7647_(new ChunkPos(blockpos));
      ServerChunkCache serverchunkcache = serverlevel.m_7726_();
      this.f_129726_ = Util.m_137550_();
      serverchunkcache.m_8387_(TicketType.f_9442_, new ChunkPos(blockpos), 11, Unit.INSTANCE);

      while(serverchunkcache.m_8427_() != 441) {
         this.f_129726_ = Util.m_137550_() + 10L;
         this.m_130012_();
      }

      this.f_129726_ = Util.m_137550_() + 10L;
      this.m_130012_();

      for(ServerLevel serverlevel1 : this.f_129762_.values()) {
         ForcedChunksSavedData forcedchunkssaveddata = serverlevel1.m_8895_().m_164858_(ForcedChunksSavedData::m_151483_, "chunks");
         if (forcedchunkssaveddata != null) {
            LongIterator longiterator = forcedchunkssaveddata.m_46116_().iterator();

            while(longiterator.hasNext()) {
               long i = longiterator.nextLong();
               ChunkPos chunkpos = new ChunkPos(i);
               serverlevel1.m_7726_().m_6692_(chunkpos, true);
            }
         }
      }

      this.f_129726_ = Util.m_137550_() + 10L;
      this.m_130012_();
      p_129941_.m_7646_();
      this.m_129962_();
   }

   public GameType m_130008_() {
      return this.f_129749_.m_5464_();
   }

   public boolean m_7035_() {
      return this.f_129749_.m_5466_();
   }

   public abstract int m_7022_();

   public abstract int m_7034_();

   public abstract boolean m_6983_();

   public boolean m_129885_(boolean p_129886_, boolean p_129887_, boolean p_129888_) {
      boolean flag = false;

      for(ServerLevel serverlevel : this.m_129785_()) {
         if (!p_129886_) {
            f_129750_.info("Saving chunks for level '{}'/{}", serverlevel, serverlevel.m_46472_().m_135782_());
         }

         serverlevel.m_8643_((ProgressListener)null, p_129887_, serverlevel.f_8564_ && !p_129888_);
         flag = true;
      }

      ServerLevel serverlevel2 = this.m_129783_();
      ServerLevelData serverleveldata = this.f_129749_.m_5996_();
      serverleveldata.m_7831_(serverlevel2.m_6857_().m_61970_());
      this.f_129749_.m_5917_(this.m_129901_().m_136307_());
      this.f_129744_.m_78290_(this.m_206579_(), this.f_129749_, this.m_6846_().m_6960_());
      if (p_129887_) {
         for(ServerLevel serverlevel1 : this.m_129785_()) {
            f_129750_.info("ThreadedAnvilChunkStorage ({}): All chunks are saved", (Object)serverlevel1.m_7726_().f_8325_.m_182285_());
         }

         f_129750_.info("ThreadedAnvilChunkStorage: All dimensions are saved");
      }

      return flag;
   }

   public boolean m_195514_(boolean p_195515_, boolean p_195516_, boolean p_195517_) {
      boolean flag;
      try {
         this.f_195494_ = true;
         this.m_6846_().m_11302_();
         flag = this.m_129885_(p_195515_, p_195516_, p_195517_);
      } finally {
         this.f_195494_ = false;
      }

      return flag;
   }

   public void close() {
      this.m_7041_();
   }

   public void m_7041_() {
      if (this.f_177872_.m_142763_()) {
         this.m_236737_();
      }

      f_129750_.info("Stopping server");
      if (this.m_129919_() != null) {
         this.m_129919_().m_9718_();
      }

      this.f_195494_ = true;
      if (this.f_129763_ != null) {
         f_129750_.info("Saving players");
         this.f_129763_.m_11302_();
         this.f_129763_.m_11313_();
      }

      f_129750_.info("Saving worlds");

      for(ServerLevel serverlevel : this.m_129785_()) {
         if (serverlevel != null) {
            serverlevel.f_8564_ = false;
         }
      }

      while(this.f_129762_.values().stream().anyMatch((p_202480_) -> {
         return p_202480_.m_7726_().f_8325_.m_201907_();
      })) {
         this.f_129726_ = Util.m_137550_() + 1L;

         for(ServerLevel serverlevel1 : this.m_129785_()) {
            serverlevel1.m_7726_().m_201915_();
            serverlevel1.m_7726_().m_201698_(() -> {
               return true;
            }, false);
         }

         this.m_130012_();
      }

      this.m_129885_(false, true, false);

      for(ServerLevel serverlevel2 : this.m_129785_()) {
         if (serverlevel2 != null) {
            try {
               serverlevel2.close();
            } catch (IOException ioexception1) {
               f_129750_.error("Exception closing the level", (Throwable)ioexception1);
            }
         }
      }

      this.f_195494_ = false;
      this.f_129740_.close();

      try {
         this.f_129744_.close();
      } catch (IOException ioexception) {
         f_129750_.error("Failed to unlock level {}", this.f_129744_.m_78277_(), ioexception);
      }

   }

   public String m_130009_() {
      return this.f_129760_;
   }

   public void m_129913_(String p_129914_) {
      this.f_129760_ = p_129914_;
   }

   public boolean m_130010_() {
      return this.f_129764_;
   }

   public void m_7570_(boolean p_129884_) {
      this.f_129764_ = false;
      if (p_129884_) {
         try {
            this.f_129725_.join();
         } catch (InterruptedException interruptedexception) {
            f_129750_.error("Error while shutting down", (Throwable)interruptedexception);
         }
      }

   }

   protected void m_130011_() {
      try {
         if (!this.m_7038_()) {
            throw new IllegalStateException("Failed to initialize server");
         }

         this.f_129726_ = Util.m_137550_();
         this.f_271173_ = this.m_272273_().orElse((ServerStatus.Favicon)null);
         this.f_129757_ = this.m_271988_();

         while(this.f_129764_) {
            long i = Util.m_137550_() - this.f_129726_;
            if (i > 2000L && this.f_129726_ - this.f_129718_ >= 15000L) {
               long j = i / 50L;
               f_129750_.warn("Can't keep up! Is the server overloaded? Running {}ms or {} ticks behind", i, j);
               this.f_129726_ += j * 50L;
               this.f_129718_ = this.f_129726_;
            }

            if (this.f_177877_) {
               this.f_177877_ = false;
               this.f_177876_ = new MinecraftServer.TimeProfiler(Util.m_137569_(), this.f_129766_);
            }

            this.f_129726_ += 50L;
            this.m_177945_();
            this.f_129754_.m_6180_("tick");
            this.m_5705_(this::m_129960_);
            this.f_129754_.m_6182_("nextTickWait");
            this.f_129728_ = true;
            this.f_129727_ = Math.max(Util.m_137550_() + 50L, this.f_129726_);
            this.m_130012_();
            this.f_129754_.m_7238_();
            this.m_177946_();
            this.f_129717_ = true;
            JvmProfiler.f_185340_.m_183597_(this.f_129737_);
         }
      } catch (Throwable throwable1) {
         f_129750_.error("Encountered an unexpected exception", throwable1);
         CrashReport crashreport = m_206568_(throwable1);
         this.m_177935_(crashreport.m_178626_());
         File file1 = new File(new File(this.m_6237_(), "crash-reports"), "crash-" + Util.m_241986_() + "-server.txt");
         if (crashreport.m_127512_(file1)) {
            f_129750_.error("This crash report has been saved to: {}", (Object)file1.getAbsolutePath());
         } else {
            f_129750_.error("We were unable to save this crash report to disk.");
         }

         this.m_7268_(crashreport);
      } finally {
         try {
            this.f_129765_ = true;
            this.m_7041_();
         } catch (Throwable throwable) {
            f_129750_.error("Exception stopping the server", throwable);
         } finally {
            if (this.f_236721_.f_214336_() != null) {
               this.f_236721_.f_214336_().m_196559_();
            }

            this.m_6988_();
         }

      }

   }

   private static CrashReport m_206568_(Throwable p_206569_) {
      ReportedException reportedexception = null;

      for(Throwable throwable = p_206569_; throwable != null; throwable = throwable.getCause()) {
         if (throwable instanceof ReportedException reportedexception1) {
            reportedexception = reportedexception1;
         }
      }

      CrashReport crashreport;
      if (reportedexception != null) {
         crashreport = reportedexception.m_134761_();
         if (reportedexception != p_206569_) {
            crashreport.m_127514_("Wrapped in").m_128162_("Wrapping exception", p_206569_);
         }
      } else {
         crashreport = new CrashReport("Exception in server tick loop", p_206569_);
      }

      return crashreport;
   }

   private boolean m_129960_() {
      return this.m_18767_() || Util.m_137550_() < (this.f_129728_ ? this.f_129727_ : this.f_129726_);
   }

   protected void m_130012_() {
      this.m_18699_();
      this.m_18701_(() -> {
         return !this.m_129960_();
      });
   }

   protected TickTask m_6681_(Runnable p_129852_) {
      return new TickTask(this.f_129766_, p_129852_);
   }

   protected boolean m_6362_(TickTask p_129883_) {
      return p_129883_.m_136254_() + 3 < this.f_129766_ || this.m_129960_();
   }

   public boolean m_7245_() {
      boolean flag = this.m_129961_();
      this.f_129728_ = flag;
      return flag;
   }

   private boolean m_129961_() {
      if (super.m_7245_()) {
         return true;
      } else {
         if (this.m_129960_()) {
            for(ServerLevel serverlevel : this.m_129785_()) {
               if (serverlevel.m_7726_().m_8466_()) {
                  return true;
               }
            }
         }

         return false;
      }
   }

   public void m_6367_(TickTask p_129957_) {
      this.m_129905_().m_6174_("runTask");
      super.m_6367_(p_129957_);
   }

   private Optional<ServerStatus.Favicon> m_272273_() {
      Optional<Path> optional = Optional.of(this.m_129971_("server-icon.png").toPath()).filter((p_272387_) -> {
         return Files.isRegularFile(p_272387_);
      }).or(() -> {
         return this.f_129744_.m_182514_().filter((p_272385_) -> {
            return Files.isRegularFile(p_272385_);
         });
      });
      return optional.flatMap((p_272386_) -> {
         try {
            BufferedImage bufferedimage = ImageIO.read(p_272386_.toFile());
            Preconditions.checkState(bufferedimage.getWidth() == 64, "Must be 64 pixels wide");
            Preconditions.checkState(bufferedimage.getHeight() == 64, "Must be 64 pixels high");
            ByteArrayOutputStream bytearrayoutputstream = new ByteArrayOutputStream();
            ImageIO.write(bufferedimage, "PNG", bytearrayoutputstream);
            return Optional.of(new ServerStatus.Favicon(bytearrayoutputstream.toByteArray()));
         } catch (Exception exception) {
            f_129750_.error("Couldn't load server icon", (Throwable)exception);
            return Optional.empty();
         }
      });
   }

   public Optional<Path> m_182649_() {
      return this.f_129744_.m_182514_();
   }

   public File m_6237_() {
      return new File(".");
   }

   public void m_7268_(CrashReport p_129874_) {
   }

   public void m_6988_() {
   }

   public void m_5705_(BooleanSupplier p_129871_) {
      long i = Util.m_137569_();
      ++this.f_129766_;
      this.m_5703_(p_129871_);
      if (i - this.f_129724_ >= 5000000000L) {
         this.f_129724_ = i;
         this.f_129757_ = this.m_271988_();
      }

      if (this.f_129766_ % 6000 == 0) {
         f_129750_.debug("Autosave started");
         this.f_129754_.m_6180_("save");
         this.m_195514_(true, false, false);
         this.f_129754_.m_7238_();
         f_129750_.debug("Autosave finished");
      }

      this.f_129754_.m_6180_("tallying");
      long j = this.f_129748_[this.f_129766_ % 100] = Util.m_137569_() - i;
      this.f_129737_ = this.f_129737_ * 0.8F + (float)j / 1000000.0F * 0.19999999F;
      long k = Util.m_137569_();
      this.f_129735_.m_13755_(k - i);
      this.f_129754_.m_7238_();
   }

   private ServerStatus m_271988_() {
      ServerStatus.Players serverstatus$players = this.m_271961_();
      return new ServerStatus(Component.m_130674_(this.f_129709_), Optional.of(serverstatus$players), Optional.of(ServerStatus.Version.m_272202_()), Optional.ofNullable(this.f_271173_), this.m_214005_());
   }

   private ServerStatus.Players m_271961_() {
      List<ServerPlayer> list = this.f_129763_.m_11314_();
      int i = this.m_7418_();
      if (this.m_183306_()) {
         return new ServerStatus.Players(i, list.size(), List.of());
      } else {
         int j = Math.min(list.size(), 12);
         ObjectArrayList<GameProfile> objectarraylist = new ObjectArrayList<>(j);
         int k = Mth.m_216271_(this.f_129758_, 0, list.size() - j);

         for(int l = 0; l < j; ++l) {
            ServerPlayer serverplayer = list.get(k + l);
            objectarraylist.add(serverplayer.m_184128_() ? serverplayer.m_36316_() : f_195496_);
         }

         Util.m_214673_(objectarraylist, this.f_129758_);
         return new ServerStatus.Players(i, list.size(), objectarraylist);
      }
   }

   public void m_5703_(BooleanSupplier p_129954_) {
      this.f_129754_.m_6180_("commandFunctions");
      this.m_129890_().m_136128_();
      this.f_129754_.m_6182_("levels");

      for(ServerLevel serverlevel : this.m_129785_()) {
         this.f_129754_.m_6521_(() -> {
            return serverlevel + " " + serverlevel.m_46472_().m_135782_();
         });
         if (this.f_129766_ % 20 == 0) {
            this.f_129754_.m_6180_("timeSync");
            this.m_276346_(serverlevel);
            this.f_129754_.m_7238_();
         }

         this.f_129754_.m_6180_("tick");

         try {
            serverlevel.m_8793_(p_129954_);
         } catch (Throwable throwable) {
            CrashReport crashreport = CrashReport.m_127521_(throwable, "Exception ticking world");
            serverlevel.m_6026_(crashreport);
            throw new ReportedException(crashreport);
         }

         this.f_129754_.m_7238_();
         this.f_129754_.m_7238_();
      }

      this.f_129754_.m_6182_("connection");
      this.m_129919_().m_9721_();
      this.f_129754_.m_6182_("players");
      this.f_129763_.m_11288_();
      if (SharedConstants.f_136183_) {
         GameTestTicker.f_177648_.m_127790_();
      }

      this.f_129754_.m_6182_("server gui refresh");

      for(int i = 0; i < this.f_129752_.size(); ++i) {
         this.f_129752_.get(i).run();
      }

      this.f_129754_.m_7238_();
   }

   private void m_276346_(ServerLevel p_276371_) {
      this.f_129763_.m_11270_(new ClientboundSetTimePacket(p_276371_.m_46467_(), p_276371_.m_46468_(), p_276371_.m_46469_().m_46207_(GameRules.f_46140_)), p_276371_.m_46472_());
   }

   public void m_276350_() {
      this.f_129754_.m_6180_("timeSync");

      for(ServerLevel serverlevel : this.m_129785_()) {
         this.m_276346_(serverlevel);
      }

      this.f_129754_.m_7238_();
   }

   public boolean m_7079_() {
      return true;
   }

   public void m_129946_(Runnable p_129947_) {
      this.f_129752_.add(p_129947_);
   }

   protected void m_129948_(String p_129949_) {
      this.f_129739_ = p_129949_;
   }

   public boolean m_129782_() {
      return !this.f_129725_.isAlive();
   }

   public File m_129971_(String p_129972_) {
      return new File(this.m_6237_(), p_129972_);
   }

   public final ServerLevel m_129783_() {
      return this.f_129762_.get(Level.f_46428_);
   }

   @Nullable
   public ServerLevel m_129880_(ResourceKey<Level> p_129881_) {
      return this.f_129762_.get(p_129881_);
   }

   public Set<ResourceKey<Level>> m_129784_() {
      return this.f_129762_.keySet();
   }

   public Iterable<ServerLevel> m_129785_() {
      return this.f_129762_.values();
   }

   public String m_7630_() {
      return SharedConstants.m_183709_().m_132493_();
   }

   public int m_7416_() {
      return this.f_129763_.m_11309_();
   }

   public int m_7418_() {
      return this.f_129763_.m_11310_();
   }

   public String[] m_7641_() {
      return this.f_129763_.m_11291_();
   }

   @DontObfuscate
   public String getServerModName() {
      return "vanilla";
   }

   public SystemReport m_177935_(SystemReport p_177936_) {
      p_177936_.m_143522_("Server Running", () -> {
         return Boolean.toString(this.f_129764_);
      });
      if (this.f_129763_ != null) {
         p_177936_.m_143522_("Player Count", () -> {
            return this.f_129763_.m_11309_() + " / " + this.f_129763_.m_11310_() + "; " + this.f_129763_.m_11314_();
         });
      }

      p_177936_.m_143522_("Data Packs", () -> {
         return this.f_129730_.m_10524_().stream().map((p_248087_) -> {
            return p_248087_.m_10446_() + (p_248087_.m_10443_().m_10489_() ? "" : " (incompatible)");
         }).collect(Collectors.joining(", "));
      });
      p_177936_.m_143522_("Enabled Feature Flags", () -> {
         return FeatureFlags.f_244280_.m_245829_(this.f_129749_.m_247623_()).stream().map(ResourceLocation::toString).collect(Collectors.joining(", "));
      });
      p_177936_.m_143522_("World Generation", () -> {
         return this.f_129749_.m_5754_().toString();
      });
      if (this.f_129739_ != null) {
         p_177936_.m_143522_("Server Id", () -> {
            return this.f_129739_;
         });
      }

      return this.m_142424_(p_177936_);
   }

   public abstract SystemReport m_142424_(SystemReport p_177901_);

   public ModCheck m_183471_() {
      return ModCheck.m_184600_("vanilla", this::getServerModName, "Server", MinecraftServer.class);
   }

   public void m_213846_(Component p_236736_) {
      f_129750_.info(p_236736_.getString());
   }

   public KeyPair m_129790_() {
      return this.f_129712_;
   }

   public int m_7010_() {
      return this.f_129761_;
   }

   public void m_129801_(int p_129802_) {
      this.f_129761_ = p_129802_;
   }

   @Nullable
   public GameProfile m_236731_() {
      return this.f_236719_;
   }

   public void m_236740_(@Nullable GameProfile p_236741_) {
      this.f_236719_ = p_236741_;
   }

   public boolean m_129792_() {
      return this.f_236719_ != null;
   }

   protected void m_129793_() {
      f_129750_.info("Generating keypair");

      try {
         this.f_129712_ = Crypt.m_13604_();
      } catch (CryptException cryptexception) {
         throw new IllegalStateException("Failed to generate key pair", cryptexception);
      }
   }

   public void m_129827_(Difficulty p_129828_, boolean p_129829_) {
      if (p_129829_ || !this.f_129749_.m_5474_()) {
         this.f_129749_.m_6166_(this.f_129749_.m_5466_() ? Difficulty.HARD : p_129828_);
         this.m_129962_();
         this.m_6846_().m_11314_().forEach(this::m_129938_);
      }
   }

   public int m_7186_(int p_129935_) {
      return p_129935_;
   }

   private void m_129962_() {
      for(ServerLevel serverlevel : this.m_129785_()) {
         serverlevel.m_46703_(this.m_7004_(), this.m_6998_());
      }

   }

   public void m_129958_(boolean p_129959_) {
      this.f_129749_.m_5560_(p_129959_);
      this.m_6846_().m_11314_().forEach(this::m_129938_);
   }

   private void m_129938_(ServerPlayer p_129939_) {
      LevelData leveldata = p_129939_.m_9236_().m_6106_();
      p_129939_.f_8906_.m_9829_(new ClientboundChangeDifficultyPacket(leveldata.m_5472_(), leveldata.m_5474_()));
   }

   public boolean m_7004_() {
      return this.f_129749_.m_5472_() != Difficulty.PEACEFUL;
   }

   public boolean m_129794_() {
      return this.f_129714_;
   }

   public void m_129975_(boolean p_129976_) {
      this.f_129714_ = p_129976_;
   }

   public Optional<MinecraftServer.ServerResourcePackInfo> m_214042_() {
      return Optional.empty();
   }

   public boolean m_142205_() {
      return this.m_214042_().filter(MinecraftServer.ServerResourcePackInfo::f_236745_).isPresent();
   }

   public abstract boolean m_6982_();

   public abstract int m_7032_();

   public boolean m_129797_() {
      return this.f_129705_;
   }

   public void m_129985_(boolean p_129986_) {
      this.f_129705_ = p_129986_;
   }

   public boolean m_129798_() {
      return this.f_129706_;
   }

   public void m_129993_(boolean p_129994_) {
      this.f_129706_ = p_129994_;
   }

   public boolean m_6998_() {
      return true;
   }

   public boolean m_6997_() {
      return true;
   }

   public abstract boolean m_6994_();

   public boolean m_129799_() {
      return this.f_129707_;
   }

   public void m_129997_(boolean p_129998_) {
      this.f_129707_ = p_129998_;
   }

   public boolean m_129915_() {
      return this.f_129708_;
   }

   public void m_129999_(boolean p_130000_) {
      this.f_129708_ = p_130000_;
   }

   public abstract boolean m_6993_();

   public String m_129916_() {
      return this.f_129709_;
   }

   public void m_129989_(String p_129990_) {
      this.f_129709_ = p_129990_;
   }

   public boolean m_129918_() {
      return this.f_129765_;
   }

   public PlayerList m_6846_() {
      return this.f_129763_;
   }

   public void m_129823_(PlayerList p_129824_) {
      this.f_129763_ = p_129824_;
   }

   public abstract boolean m_6992_();

   public void m_7835_(GameType p_129832_) {
      this.f_129749_.m_5458_(p_129832_);
   }

   @Nullable
   public ServerConnectionListener m_129919_() {
      return this.f_129755_;
   }

   public boolean m_129920_() {
      return this.f_129717_;
   }

   public boolean m_6370_() {
      return false;
   }

   public boolean m_7386_(@Nullable GameType p_129833_, boolean p_129834_, int p_129835_) {
      return false;
   }

   public int m_129921_() {
      return this.f_129766_;
   }

   public int m_6396_() {
      return 16;
   }

   public boolean m_7762_(ServerLevel p_129811_, BlockPos p_129812_, Player p_129813_) {
      return false;
   }

   public boolean m_6373_() {
      return true;
   }

   public boolean m_183306_() {
      return false;
   }

   public Proxy m_177930_() {
      return this.f_129747_;
   }

   public int m_129924_() {
      return this.f_129711_;
   }

   public void m_7196_(int p_129978_) {
      this.f_129711_ = p_129978_;
   }

   public MinecraftSessionService m_129925_() {
      return this.f_236721_.f_214333_();
   }

   @Nullable
   public SignatureValidator m_284385_() {
      return this.f_236721_.m_284133_();
   }

   public GameProfileRepository m_129926_() {
      return this.f_236721_.f_214335_();
   }

   @Nullable
   public GameProfileCache m_129927_() {
      return this.f_236721_.f_214336_();
   }

   @Nullable
   public ServerStatus m_129928_() {
      return this.f_129757_;
   }

   public void m_129929_() {
      this.f_129724_ = 0L;
   }

   public int m_6329_() {
      return 29999984;
   }

   public boolean m_5660_() {
      return super.m_5660_() && !this.m_129918_();
   }

   public void m_201446_(Runnable p_202482_) {
      if (this.m_129918_()) {
         throw new RejectedExecutionException("Server already shutting down");
      } else {
         super.m_201446_(p_202482_);
      }
   }

   public Thread m_6304_() {
      return this.f_129725_;
   }

   public int m_6328_() {
      return 256;
   }

   public boolean m_214005_() {
      return false;
   }

   public long m_129932_() {
      return this.f_129726_;
   }

   public DataFixer m_129933_() {
      return this.f_129759_;
   }

   public int m_129803_(@Nullable ServerLevel p_129804_) {
      return p_129804_ != null ? p_129804_.m_46469_().m_46215_(GameRules.f_46147_) : 10;
   }

   public ServerAdvancementManager m_129889_() {
      return this.f_129740_.f_206585_.m_206889_();
   }

   public ServerFunctionManager m_129890_() {
      return this.f_129734_;
   }

   public CompletableFuture<Void> m_129861_(Collection<String> p_129862_) {
      RegistryAccess.Frozen registryaccess$frozen = this.f_244176_.m_246035_(RegistryLayer.RELOADABLE);
      CompletableFuture<Void> completablefuture = CompletableFuture.supplyAsync(() -> {
         return p_129862_.stream().map(this.f_129730_::m_10507_).filter(Objects::nonNull).map(Pack::m_10445_).collect(ImmutableList.toImmutableList());
      }, this).thenCompose((p_248092_) -> {
         CloseableResourceManager closeableresourcemanager = new MultiPackResourceManager(PackType.SERVER_DATA, p_248092_);
         return ReloadableServerResources.m_247740_(closeableresourcemanager, registryaccess$frozen, this.f_129749_.m_247623_(), this.m_6982_() ? Commands.CommandSelection.DEDICATED : Commands.CommandSelection.INTEGRATED, this.m_7034_(), this.f_129738_, this).whenComplete((p_212907_, p_212908_) -> {
            if (p_212908_ != null) {
               closeableresourcemanager.close();
            }

         }).thenApply((p_212904_) -> {
            return new MinecraftServer.ReloadableResources(closeableresourcemanager, p_212904_);
         });
      }).thenAcceptAsync((p_248090_) -> {
         this.f_129740_.close();
         this.f_129740_ = p_248090_;
         this.f_129730_.m_10509_(p_129862_);
         WorldDataConfiguration worlddataconfiguration = new WorldDataConfiguration(m_129817_(this.f_129730_), this.f_129749_.m_247623_());
         this.f_129749_.m_245843_(worlddataconfiguration);
         this.f_129740_.f_206585_.m_206868_(this.m_206579_());
         this.m_6846_().m_11302_();
         this.m_6846_().m_11315_();
         this.f_129734_.m_136120_(this.f_129740_.f_206585_.m_206860_());
         this.f_236720_.m_230370_(this.f_129740_.f_206584_);
      }, this);
      if (this.m_18695_()) {
         this.m_18701_(completablefuture::isDone);
      }

      return completablefuture;
   }

   public static WorldDataConfiguration m_246048_(PackRepository p_248681_, DataPackConfig p_248920_, boolean p_249869_, FeatureFlagSet p_251243_) {
      p_248681_.m_10506_();
      if (p_249869_) {
         p_248681_.m_10509_(Collections.singleton("vanilla"));
         return WorldDataConfiguration.f_244649_;
      } else {
         Set<String> set = Sets.newLinkedHashSet();

         for(String s : p_248920_.m_45850_()) {
            if (p_248681_.m_10515_(s)) {
               set.add(s);
            } else {
               f_129750_.warn("Missing data pack {}", (Object)s);
            }
         }

         for(Pack pack : p_248681_.m_10519_()) {
            String s1 = pack.m_10446_();
            if (!p_248920_.m_45855_().contains(s1)) {
               FeatureFlagSet featureflagset = pack.m_245532_();
               boolean flag = set.contains(s1);
               if (!flag && pack.m_10453_().m_245251_()) {
                  if (featureflagset.m_247715_(p_251243_)) {
                     f_129750_.info("Found new data pack {}, loading it automatically", (Object)s1);
                     set.add(s1);
                  } else {
                     f_129750_.info("Found new data pack {}, but can't load it due to missing features {}", s1, FeatureFlags.m_245229_(p_251243_, featureflagset));
                  }
               }

               if (flag && !featureflagset.m_247715_(p_251243_)) {
                  f_129750_.warn("Pack {} requires features {} that are not enabled for this world, disabling pack.", s1, FeatureFlags.m_245229_(p_251243_, featureflagset));
                  set.remove(s1);
               }
            }
         }

         if (set.isEmpty()) {
            f_129750_.info("No datapacks selected, forcing vanilla");
            set.add("vanilla");
         }

         p_248681_.m_10509_(set);
         DataPackConfig datapackconfig = m_129817_(p_248681_);
         FeatureFlagSet featureflagset1 = p_248681_.m_245805_();
         return new WorldDataConfiguration(datapackconfig, featureflagset1);
      }
   }

   private static DataPackConfig m_129817_(PackRepository p_129818_) {
      Collection<String> collection = p_129818_.m_10523_();
      List<String> list = ImmutableList.copyOf(collection);
      List<String> list1 = p_129818_.m_10514_().stream().filter((p_212916_) -> {
         return !collection.contains(p_212916_);
      }).collect(ImmutableList.toImmutableList());
      return new DataPackConfig(list, list1);
   }

   public void m_129849_(CommandSourceStack p_129850_) {
      if (this.m_129902_()) {
         PlayerList playerlist = p_129850_.m_81377_().m_6846_();
         UserWhiteList userwhitelist = playerlist.m_11305_();

         for(ServerPlayer serverplayer : Lists.newArrayList(playerlist.m_11314_())) {
            if (!userwhitelist.m_11453_(serverplayer.m_36316_())) {
               serverplayer.f_8906_.m_9942_(Component.m_237115_("multiplayer.disconnect.not_whitelisted"));
            }
         }

      }
   }

   public PackRepository m_129891_() {
      return this.f_129730_;
   }

   public Commands m_129892_() {
      return this.f_129740_.f_206585_.m_206888_();
   }

   public CommandSourceStack m_129893_() {
      ServerLevel serverlevel = this.m_129783_();
      return new CommandSourceStack(this, serverlevel == null ? Vec3.f_82478_ : Vec3.m_82528_(serverlevel.m_220360_()), Vec2.f_82462_, serverlevel, 4, "Server", Component.m_237113_("Server"), this, (Entity)null);
   }

   public boolean m_6999_() {
      return true;
   }

   public boolean m_7028_() {
      return true;
   }

   public abstract boolean m_6102_();

   public RecipeManager m_129894_() {
      return this.f_129740_.f_206585_.m_206887_();
   }

   public ServerScoreboard m_129896_() {
      return this.f_129731_;
   }

   public CommandStorage m_129897_() {
      if (this.f_129732_ == null) {
         throw new NullPointerException("Called before server init");
      } else {
         return this.f_129732_;
      }
   }

   public LootDataManager m_278653_() {
      return this.f_129740_.f_206585_.m_278801_();
   }

   public GameRules m_129900_() {
      return this.m_129783_().m_46469_();
   }

   public CustomBossEvents m_129901_() {
      return this.f_129733_;
   }

   public boolean m_129902_() {
      return this.f_129736_;
   }

   public void m_130004_(boolean p_130005_) {
      this.f_129736_ = p_130005_;
   }

   public float m_129903_() {
      return this.f_129737_;
   }

   public int m_129944_(GameProfile p_129945_) {
      if (this.m_6846_().m_11303_(p_129945_)) {
         ServerOpListEntry serveroplistentry = this.m_6846_().m_11307_().m_11388_(p_129945_);
         if (serveroplistentry != null) {
            return serveroplistentry.m_11363_();
         } else if (this.m_7779_(p_129945_)) {
            return 4;
         } else if (this.m_129792_()) {
            return this.m_6846_().m_11316_() ? 4 : 0;
         } else {
            return this.m_7022_();
         }
      } else {
         return 0;
      }
   }

   public FrameTimer m_129904_() {
      return this.f_129735_;
   }

   public ProfilerFiller m_129905_() {
      return this.f_129754_;
   }

   public abstract boolean m_7779_(GameProfile p_129840_);

   public void m_142116_(Path p_177911_) throws IOException {
   }

   private void m_129859_(Path p_129860_) {
      Path path = p_129860_.resolve("levels");

      try {
         for(Map.Entry<ResourceKey<Level>, ServerLevel> entry : this.f_129762_.entrySet()) {
            ResourceLocation resourcelocation = entry.getKey().m_135782_();
            Path path1 = path.resolve(resourcelocation.m_135827_()).resolve(resourcelocation.m_135815_());
            Files.createDirectories(path1);
            entry.getValue().m_8786_(path1);
         }

         this.m_129983_(p_129860_.resolve("gamerules.txt"));
         this.m_129991_(p_129860_.resolve("classpath.txt"));
         this.m_129950_(p_129860_.resolve("stats.txt"));
         this.m_129995_(p_129860_.resolve("threads.txt"));
         this.m_142116_(p_129860_.resolve("server.properties.txt"));
         this.m_195521_(p_129860_.resolve("modules.txt"));
      } catch (IOException ioexception) {
         f_129750_.warn("Failed to save debug report", (Throwable)ioexception);
      }

   }

   private void m_129950_(Path p_129951_) throws IOException {
      try (Writer writer = Files.newBufferedWriter(p_129951_)) {
         writer.write(String.format(Locale.ROOT, "pending_tasks: %d\n", this.m_18696_()));
         writer.write(String.format(Locale.ROOT, "average_tick_time: %f\n", this.m_129903_()));
         writer.write(String.format(Locale.ROOT, "tick_times: %s\n", Arrays.toString(this.f_129748_)));
         writer.write(String.format(Locale.ROOT, "queue: %s\n", Util.m_183991_()));
      }

   }

   private void m_129983_(Path p_129984_) throws IOException {
      try (Writer writer = Files.newBufferedWriter(p_129984_)) {
         final List<String> list = Lists.newArrayList();
         final GameRules gamerules = this.m_129900_();
         GameRules.m_46164_(new GameRules.GameRuleTypeVisitor() {
            public <T extends GameRules.Value<T>> void m_6889_(GameRules.Key<T> p_195531_, GameRules.Type<T> p_195532_) {
               list.add(String.format(Locale.ROOT, "%s=%s\n", p_195531_.m_46328_(), gamerules.<T>m_46170_(p_195531_)));
            }
         });

         for(String s : list) {
            writer.write(s);
         }
      }

   }

   private void m_129991_(Path p_129992_) throws IOException {
      try (Writer writer = Files.newBufferedWriter(p_129992_)) {
         String s = System.getProperty("java.class.path");
         String s1 = System.getProperty("path.separator");

         for(String s2 : Splitter.on(s1).split(s)) {
            writer.write(s2);
            writer.write("\n");
         }
      }

   }

   private void m_129995_(Path p_129996_) throws IOException {
      ThreadMXBean threadmxbean = ManagementFactory.getThreadMXBean();
      ThreadInfo[] athreadinfo = threadmxbean.dumpAllThreads(true, true);
      Arrays.sort(athreadinfo, Comparator.comparing(ThreadInfo::getThreadName));

      try (Writer writer = Files.newBufferedWriter(p_129996_)) {
         for(ThreadInfo threadinfo : athreadinfo) {
            writer.write(threadinfo.toString());
            writer.write(10);
         }
      }

   }

   private void m_195521_(Path p_195522_) throws IOException {
      try (Writer writer = Files.newBufferedWriter(p_195522_)) {
         List<NativeModuleLister.NativeModuleInfo> list;
         try {
            list = Lists.newArrayList(NativeModuleLister.m_184666_());
         } catch (Throwable throwable) {
            f_129750_.warn("Failed to list native modules", throwable);
            return;
         }

         list.sort(Comparator.comparing((p_212910_) -> {
            return p_212910_.f_184690_;
         }));

         for(NativeModuleLister.NativeModuleInfo nativemodulelister$nativemoduleinfo : list) {
            writer.write(nativemodulelister$nativemoduleinfo.toString());
            writer.write(10);
         }

      }
   }

   private void m_177945_() {
      if (this.f_177875_) {
         this.f_177872_ = ActiveMetricsRecorder.m_146132_(new ServerMetricsSamplersProvider(Util.f_137440_, this.m_6982_()), Util.f_137440_, Util.m_183992_(), new MetricsPersister("server"), this.f_177873_, (p_212927_) -> {
            this.m_18709_(() -> {
               this.m_129859_(p_212927_.resolve("server"));
            });
            this.f_177874_.accept(p_212927_);
         });
         this.f_177875_ = false;
      }

      this.f_129754_ = SingleTickProfiler.m_18629_(this.f_177872_.m_142610_(), SingleTickProfiler.m_18632_("Server"));
      this.f_177872_.m_142759_();
      this.f_129754_.m_7242_();
   }

   private void m_177946_() {
      this.f_129754_.m_7241_();
      this.f_177872_.m_142758_();
   }

   public boolean m_177927_() {
      return this.f_177872_.m_142763_();
   }

   public void m_177923_(Consumer<ProfileResults> p_177924_, Consumer<Path> p_177925_) {
      this.f_177873_ = (p_212922_) -> {
         this.m_177928_();
         p_177924_.accept(p_212922_);
      };
      this.f_177874_ = p_177925_;
      this.f_177875_ = true;
   }

   public void m_177928_() {
      this.f_177872_ = InactiveMetricsRecorder.f_146153_;
   }

   public void m_177929_() {
      this.f_177872_.m_142760_();
   }

   public void m_236737_() {
      this.f_177872_.m_213832_();
      this.f_129754_ = this.f_177872_.m_142610_();
   }

   public Path m_129843_(LevelResource p_129844_) {
      return this.f_129744_.m_78283_(p_129844_);
   }

   public boolean m_6365_() {
      return true;
   }

   public StructureTemplateManager m_236738_() {
      return this.f_236720_;
   }

   public WorldData m_129910_() {
      return this.f_129749_;
   }

   public RegistryAccess.Frozen m_206579_() {
      return this.f_244176_.m_247579_();
   }

   public LayeredRegistryAccess<RegistryLayer> m_247573_() {
      return this.f_244176_;
   }

   public TextFilter m_7950_(ServerPlayer p_129814_) {
      return TextFilter.f_143703_;
   }

   public ServerPlayerGameMode m_177933_(ServerPlayer p_177934_) {
      return (ServerPlayerGameMode)(this.m_129794_() ? new DemoMode(p_177934_) : new ServerPlayerGameMode(p_177934_));
   }

   @Nullable
   public GameType m_142359_() {
      return null;
   }

   public ResourceManager m_177941_() {
      return this.f_129740_.f_206584_;
   }

   public boolean m_195518_() {
      return this.f_195494_;
   }

   public boolean m_177942_() {
      return this.f_177877_ || this.f_177876_ != null;
   }

   public void m_177943_() {
      this.f_177877_ = true;
   }

   public ProfileResults m_177944_() {
      if (this.f_177876_ == null) {
         return EmptyProfileResults.f_18441_;
      } else {
         ProfileResults profileresults = this.f_177876_.m_177960_(Util.m_137569_(), this.f_129766_);
         this.f_177876_ = null;
         return profileresults;
      }
   }

   public int m_213994_() {
      return 1000000;
   }

   public void m_241158_(Component p_241503_, ChatType.Bound p_241402_, @Nullable String p_241481_) {
      String s = p_241402_.m_240977_(p_241503_).getString();
      if (p_241481_ != null) {
         f_129750_.info("[{}] {}", p_241481_, s);
      } else {
         f_129750_.info("{}", (Object)s);
      }

   }

   public ChatDecorator m_236742_() {
      return ChatDecorator.f_236947_;
   }

   public static record ReloadableResources(CloseableResourceManager f_206584_, ReloadableServerResources f_206585_) implements AutoCloseable {
      public void close() {
         this.f_206584_.close();
      }
   }

   public static record ServerResourcePackInfo(String f_236743_, String f_236744_, boolean f_236745_, @Nullable Component f_236746_) {
   }

   static class TimeProfiler {
      final long f_177955_;
      final int f_177956_;

      TimeProfiler(long p_177958_, int p_177959_) {
         this.f_177955_ = p_177958_;
         this.f_177956_ = p_177959_;
      }

      ProfileResults m_177960_(final long p_177961_, final int p_177962_) {
         return new ProfileResults() {
            public List<ResultField> m_6412_(String p_177972_) {
               return Collections.emptyList();
            }

            public boolean m_142444_(Path p_177974_) {
               return false;
            }

            public long m_7229_() {
               return TimeProfiler.this.f_177955_;
            }

            public int m_7230_() {
               return TimeProfiler.this.f_177956_;
            }

            public long m_7236_() {
               return p_177961_;
            }

            public int m_7317_() {
               return p_177962_;
            }

            public String m_142368_() {
               return "";
            }
         };
      }
   }
}
