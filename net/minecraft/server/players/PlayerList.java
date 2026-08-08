package net.minecraft.server.players;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Dynamic;
import io.netty.buffer.Unpooled;
import java.io.File;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.FileUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderLerpSizePacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderSizePacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderWarningDelayPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderWarningDistancePacket;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetSimulationDistancePacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateEnabledFeaturesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stats;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.PlayerDataStorage;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import org.slf4j.Logger;

public abstract class PlayerList {
   public static final File f_11189_ = new File("banned-players.json");
   public static final File f_11190_ = new File("banned-ips.json");
   public static final File f_11191_ = new File("ops.json");
   public static final File f_11192_ = new File("whitelist.json");
   public static final Component f_243017_ = Component.m_237115_("chat.filtered_full");
   private static final Logger f_11188_ = LogUtils.getLogger();
   private static final int f_143987_ = 600;
   private static final SimpleDateFormat f_11194_ = new SimpleDateFormat("yyyy-MM-dd 'at' HH:mm:ss z");
   private final MinecraftServer f_11195_;
   private final List<ServerPlayer> f_11196_ = Lists.newArrayList();
   private final Map<UUID, ServerPlayer> f_11197_ = Maps.newHashMap();
   private final UserBanList f_11198_ = new UserBanList(f_11189_);
   private final IpBanList f_11199_ = new IpBanList(f_11190_);
   private final ServerOpList f_11200_ = new ServerOpList(f_11191_);
   private final UserWhiteList f_11201_ = new UserWhiteList(f_11192_);
   private final Map<UUID, ServerStatsCounter> f_11202_ = Maps.newHashMap();
   private final Map<UUID, PlayerAdvancements> f_11203_ = Maps.newHashMap();
   private final PlayerDataStorage f_11204_;
   private boolean f_11205_;
   private final LayeredRegistryAccess<RegistryLayer> f_243858_;
   private final RegistryAccess.Frozen f_256838_;
   protected final int f_11193_;
   private int f_11207_;
   private int f_184208_;
   private boolean f_11209_;
   private static final boolean f_143988_ = false;
   private int f_11210_;

   public PlayerList(MinecraftServer p_203842_, LayeredRegistryAccess<RegistryLayer> p_251844_, PlayerDataStorage p_203844_, int p_203845_) {
      this.f_11195_ = p_203842_;
      this.f_243858_ = p_251844_;
      this.f_256838_ = (new RegistryAccess.ImmutableRegistryAccess(RegistrySynchronization.m_257599_(p_251844_))).m_203557_();
      this.f_11193_ = p_203845_;
      this.f_11204_ = p_203844_;
   }

   public void m_11261_(Connection p_11262_, ServerPlayer p_11263_) {
      GameProfile gameprofile = p_11263_.m_36316_();
      GameProfileCache gameprofilecache = this.f_11195_.m_129927_();
      String s;
      if (gameprofilecache != null) {
         Optional<GameProfile> optional = gameprofilecache.m_11002_(gameprofile.getId());
         s = optional.map(GameProfile::getName).orElse(gameprofile.getName());
         gameprofilecache.m_10991_(gameprofile);
      } else {
         s = gameprofile.getName();
      }

      CompoundTag compoundtag = this.m_11224_(p_11263_);
      ResourceKey<Level> resourcekey = compoundtag != null ? DimensionType.m_63911_(new Dynamic<>(NbtOps.f_128958_, compoundtag.m_128423_("Dimension"))).resultOrPartial(f_11188_::error).orElse(Level.f_46428_) : Level.f_46428_;
      ServerLevel serverlevel = this.f_11195_.m_129880_(resourcekey);
      ServerLevel serverlevel1;
      if (serverlevel == null) {
         f_11188_.warn("Unknown respawn dimension {}, defaulting to overworld", (Object)resourcekey);
         serverlevel1 = this.f_11195_.m_129783_();
      } else {
         serverlevel1 = serverlevel;
      }

      p_11263_.m_284127_(serverlevel1);
      String s1 = "local";
      if (p_11262_.m_129523_() != null) {
         s1 = p_11262_.m_129523_().toString();
      }

      f_11188_.info("{}[{}] logged in with entity id {} at ({}, {}, {})", p_11263_.m_7755_().getString(), s1, p_11263_.m_19879_(), p_11263_.m_20185_(), p_11263_.m_20186_(), p_11263_.m_20189_());
      LevelData leveldata = serverlevel1.m_6106_();
      p_11263_.m_143427_(compoundtag);
      ServerGamePacketListenerImpl servergamepacketlistenerimpl = new ServerGamePacketListenerImpl(this.f_11195_, p_11262_, p_11263_);
      GameRules gamerules = serverlevel1.m_46469_();
      boolean flag = gamerules.m_46207_(GameRules.f_46156_);
      boolean flag1 = gamerules.m_46207_(GameRules.f_46145_);
      servergamepacketlistenerimpl.m_9829_(new ClientboundLoginPacket(p_11263_.m_19879_(), leveldata.m_5466_(), p_11263_.f_8941_.m_9290_(), p_11263_.f_8941_.m_9293_(), this.f_11195_.m_129784_(), this.f_256838_, serverlevel1.m_220362_(), serverlevel1.m_46472_(), BiomeManager.m_47877_(serverlevel1.m_7328_()), this.m_11310_(), this.f_11207_, this.f_184208_, flag1, !flag, serverlevel1.m_46659_(), serverlevel1.m_8584_(), p_11263_.m_219759_(), p_11263_.m_287157_()));
      servergamepacketlistenerimpl.m_9829_(new ClientboundUpdateEnabledFeaturesPacket(FeatureFlags.f_244280_.m_245829_(serverlevel1.m_246046_())));
      servergamepacketlistenerimpl.m_9829_(new ClientboundCustomPayloadPacket(ClientboundCustomPayloadPacket.f_132012_, (new FriendlyByteBuf(Unpooled.buffer())).m_130070_(this.m_7873_().getServerModName())));
      servergamepacketlistenerimpl.m_9829_(new ClientboundChangeDifficultyPacket(leveldata.m_5472_(), leveldata.m_5474_()));
      servergamepacketlistenerimpl.m_9829_(new ClientboundPlayerAbilitiesPacket(p_11263_.m_150110_()));
      servergamepacketlistenerimpl.m_9829_(new ClientboundSetCarriedItemPacket(p_11263_.m_150109_().f_35977_));
      servergamepacketlistenerimpl.m_9829_(new ClientboundUpdateRecipesPacket(this.f_11195_.m_129894_().m_44051_()));
      servergamepacketlistenerimpl.m_9829_(new ClientboundUpdateTagsPacket(TagNetworkSerialization.m_245799_(this.f_243858_)));
      this.m_11289_(p_11263_);
      p_11263_.m_8951_().m_12850_();
      p_11263_.m_8952_().m_12789_(p_11263_);
      this.m_11273_(serverlevel1.m_6188_(), p_11263_);
      this.f_11195_.m_129929_();
      MutableComponent mutablecomponent;
      if (p_11263_.m_36316_().getName().equalsIgnoreCase(s)) {
         mutablecomponent = Component.m_237110_("multiplayer.player.joined", p_11263_.m_5446_());
      } else {
         mutablecomponent = Component.m_237110_("multiplayer.player.joined.renamed", p_11263_.m_5446_(), s);
      }

      this.m_240416_(mutablecomponent.m_130940_(ChatFormatting.YELLOW), false);
      servergamepacketlistenerimpl.m_9774_(p_11263_.m_20185_(), p_11263_.m_20186_(), p_11263_.m_20189_(), p_11263_.m_146908_(), p_11263_.m_146909_());
      ServerStatus serverstatus = this.f_11195_.m_129928_();
      if (serverstatus != null) {
         p_11263_.m_215109_(serverstatus);
      }

      p_11263_.f_8906_.m_9829_(ClientboundPlayerInfoUpdatePacket.m_247122_(this.f_11196_));
      this.f_11196_.add(p_11263_);
      this.f_11197_.put(p_11263_.m_20148_(), p_11263_);
      this.m_11268_(ClientboundPlayerInfoUpdatePacket.m_247122_(List.of(p_11263_)));
      this.m_11229_(p_11263_, serverlevel1);
      serverlevel1.m_8834_(p_11263_);
      this.f_11195_.m_129901_().m_136293_(p_11263_);
      this.f_11195_.m_214042_().ifPresent((p_215606_) -> {
         p_11263_.m_143408_(p_215606_.f_236743_(), p_215606_.f_236744_(), p_215606_.f_236745_(), p_215606_.f_236746_());
      });

      for(MobEffectInstance mobeffectinstance : p_11263_.m_21220_()) {
         servergamepacketlistenerimpl.m_9829_(new ClientboundUpdateMobEffectPacket(p_11263_.m_19879_(), mobeffectinstance));
      }

      if (compoundtag != null && compoundtag.m_128425_("RootVehicle", 10)) {
         CompoundTag compoundtag1 = compoundtag.m_128469_("RootVehicle");
         Entity entity1 = EntityType.m_20645_(compoundtag1.m_128469_("Entity"), serverlevel1, (p_215603_) -> {
            return !serverlevel1.m_8847_(p_215603_) ? null : p_215603_;
         });
         if (entity1 != null) {
            UUID uuid;
            if (compoundtag1.m_128403_("Attach")) {
               uuid = compoundtag1.m_128342_("Attach");
            } else {
               uuid = null;
            }

            if (entity1.m_20148_().equals(uuid)) {
               p_11263_.m_7998_(entity1, true);
            } else {
               for(Entity entity : entity1.m_146897_()) {
                  if (entity.m_20148_().equals(uuid)) {
                     p_11263_.m_7998_(entity, true);
                     break;
                  }
               }
            }

            if (!p_11263_.m_20159_()) {
               f_11188_.warn("Couldn't reattach entity to player");
               entity1.m_146870_();

               for(Entity entity2 : entity1.m_146897_()) {
                  entity2.m_146870_();
               }
            }
         }
      }

      p_11263_.m_143429_();
   }

   protected void m_11273_(ServerScoreboard p_11274_, ServerPlayer p_11275_) {
      Set<Objective> set = Sets.newHashSet();

      for(PlayerTeam playerteam : p_11274_.m_83491_()) {
         p_11275_.f_8906_.m_9829_(ClientboundSetPlayerTeamPacket.m_179332_(playerteam, true));
      }

      for(int i = 0; i < 19; ++i) {
         Objective objective = p_11274_.m_83416_(i);
         if (objective != null && !set.contains(objective)) {
            for(Packet<?> packet : p_11274_.m_136229_(objective)) {
               p_11275_.f_8906_.m_9829_(packet);
            }

            set.add(objective);
         }
      }

   }

   public void m_184209_(ServerLevel p_184210_) {
      p_184210_.m_6857_().m_61929_(new BorderChangeListener() {
         public void m_6312_(WorldBorder p_11321_, double p_11322_) {
            PlayerList.this.m_11268_(new ClientboundSetBorderSizePacket(p_11321_));
         }

         public void m_6689_(WorldBorder p_11328_, double p_11329_, double p_11330_, long p_11331_) {
            PlayerList.this.m_11268_(new ClientboundSetBorderLerpSizePacket(p_11328_));
         }

         public void m_7721_(WorldBorder p_11324_, double p_11325_, double p_11326_) {
            PlayerList.this.m_11268_(new ClientboundSetBorderCenterPacket(p_11324_));
         }

         public void m_5904_(WorldBorder p_11333_, int p_11334_) {
            PlayerList.this.m_11268_(new ClientboundSetBorderWarningDelayPacket(p_11333_));
         }

         public void m_5903_(WorldBorder p_11339_, int p_11340_) {
            PlayerList.this.m_11268_(new ClientboundSetBorderWarningDistancePacket(p_11339_));
         }

         public void m_6315_(WorldBorder p_11336_, double p_11337_) {
         }

         public void m_6313_(WorldBorder p_11342_, double p_11343_) {
         }
      });
   }

   @Nullable
   public CompoundTag m_11224_(ServerPlayer p_11225_) {
      CompoundTag compoundtag = this.f_11195_.m_129910_().m_6614_();
      CompoundTag compoundtag1;
      if (this.f_11195_.m_7779_(p_11225_.m_36316_()) && compoundtag != null) {
         compoundtag1 = compoundtag;
         p_11225_.m_20258_(compoundtag);
         f_11188_.debug("loading single player");
      } else {
         compoundtag1 = this.f_11204_.m_78435_(p_11225_);
      }

      return compoundtag1;
   }

   protected void m_6765_(ServerPlayer p_11277_) {
      this.f_11204_.m_78433_(p_11277_);
      ServerStatsCounter serverstatscounter = this.f_11202_.get(p_11277_.m_20148_());
      if (serverstatscounter != null) {
         serverstatscounter.m_12818_();
      }

      PlayerAdvancements playeradvancements = this.f_11203_.get(p_11277_.m_20148_());
      if (playeradvancements != null) {
         playeradvancements.m_135991_();
      }

   }

   public void m_11286_(ServerPlayer p_11287_) {
      ServerLevel serverlevel = p_11287_.m_284548_();
      p_11287_.m_36220_(Stats.f_12989_);
      this.m_6765_(p_11287_);
      if (p_11287_.m_20159_()) {
         Entity entity = p_11287_.m_20201_();
         if (entity.m_146898_()) {
            f_11188_.debug("Removing player mount");
            p_11287_.m_8127_();
            entity.m_142429_().forEach((p_215620_) -> {
               p_215620_.m_142467_(Entity.RemovalReason.UNLOADED_WITH_PLAYER);
            });
         }
      }

      p_11287_.m_19877_();
      serverlevel.m_143261_(p_11287_, Entity.RemovalReason.UNLOADED_WITH_PLAYER);
      p_11287_.m_8960_().m_135978_();
      this.f_11196_.remove(p_11287_);
      this.f_11195_.m_129901_().m_136305_(p_11287_);
      UUID uuid = p_11287_.m_20148_();
      ServerPlayer serverplayer = this.f_11197_.get(uuid);
      if (serverplayer == p_11287_) {
         this.f_11197_.remove(uuid);
         this.f_11202_.remove(uuid);
         this.f_11203_.remove(uuid);
      }

      this.m_11268_(new ClientboundPlayerInfoRemovePacket(List.of(p_11287_.m_20148_())));
   }

   @Nullable
   public Component m_6418_(SocketAddress p_11257_, GameProfile p_11258_) {
      if (this.f_11198_.m_11406_(p_11258_)) {
         UserBanListEntry userbanlistentry = this.f_11198_.m_11388_(p_11258_);
         MutableComponent mutablecomponent1 = Component.m_237110_("multiplayer.disconnect.banned.reason", userbanlistentry.m_10962_());
         if (userbanlistentry.m_10961_() != null) {
            mutablecomponent1.m_7220_(Component.m_237110_("multiplayer.disconnect.banned.expiration", f_11194_.format(userbanlistentry.m_10961_())));
         }

         return mutablecomponent1;
      } else if (!this.m_5764_(p_11258_)) {
         return Component.m_237115_("multiplayer.disconnect.not_whitelisted");
      } else if (this.f_11199_.m_11041_(p_11257_)) {
         IpBanListEntry ipbanlistentry = this.f_11199_.m_11043_(p_11257_);
         MutableComponent mutablecomponent = Component.m_237110_("multiplayer.disconnect.banned_ip.reason", ipbanlistentry.m_10962_());
         if (ipbanlistentry.m_10961_() != null) {
            mutablecomponent.m_7220_(Component.m_237110_("multiplayer.disconnect.banned_ip.expiration", f_11194_.format(ipbanlistentry.m_10961_())));
         }

         return mutablecomponent;
      } else {
         return this.f_11196_.size() >= this.f_11193_ && !this.m_5765_(p_11258_) ? Component.m_237115_("multiplayer.disconnect.server_full") : null;
      }
   }

   public ServerPlayer m_215624_(GameProfile p_215625_) {
      UUID uuid = UUIDUtil.m_235875_(p_215625_);
      List<ServerPlayer> list = Lists.newArrayList();

      for(int i = 0; i < this.f_11196_.size(); ++i) {
         ServerPlayer serverplayer = this.f_11196_.get(i);
         if (serverplayer.m_20148_().equals(uuid)) {
            list.add(serverplayer);
         }
      }

      ServerPlayer serverplayer2 = this.f_11197_.get(p_215625_.getId());
      if (serverplayer2 != null && !list.contains(serverplayer2)) {
         list.add(serverplayer2);
      }

      for(ServerPlayer serverplayer1 : list) {
         serverplayer1.f_8906_.m_9942_(Component.m_237115_("multiplayer.disconnect.duplicate_login"));
      }

      return new ServerPlayer(this.f_11195_, this.f_11195_.m_129783_(), p_215625_);
   }

   public ServerPlayer m_11236_(ServerPlayer p_11237_, boolean p_11238_) {
      this.f_11196_.remove(p_11237_);
      p_11237_.m_284548_().m_143261_(p_11237_, Entity.RemovalReason.DISCARDED);
      BlockPos blockpos = p_11237_.m_8961_();
      float f = p_11237_.m_8962_();
      boolean flag = p_11237_.m_8964_();
      ServerLevel serverlevel = this.f_11195_.m_129880_(p_11237_.m_8963_());
      Optional<Vec3> optional;
      if (serverlevel != null && blockpos != null) {
         optional = Player.m_36130_(serverlevel, blockpos, f, flag, p_11238_);
      } else {
         optional = Optional.empty();
      }

      ServerLevel serverlevel1 = serverlevel != null && optional.isPresent() ? serverlevel : this.f_11195_.m_129783_();
      ServerPlayer serverplayer = new ServerPlayer(this.f_11195_, serverlevel1, p_11237_.m_36316_());
      serverplayer.f_8906_ = p_11237_.f_8906_;
      serverplayer.m_9015_(p_11237_, p_11238_);
      serverplayer.m_20234_(p_11237_.m_19879_());
      serverplayer.m_36163_(p_11237_.m_5737_());

      for(String s : p_11237_.m_19880_()) {
         serverplayer.m_20049_(s);
      }

      boolean flag2 = false;
      if (optional.isPresent()) {
         BlockState blockstate = serverlevel1.m_8055_(blockpos);
         boolean flag1 = blockstate.m_60713_(Blocks.f_50724_);
         Vec3 vec3 = optional.get();
         float f1;
         if (!blockstate.m_204336_(BlockTags.f_13038_) && !flag1) {
            f1 = f;
         } else {
            Vec3 vec31 = Vec3.m_82539_(blockpos).m_82546_(vec3).m_82541_();
            f1 = (float)Mth.m_14175_(Mth.m_14136_(vec31.f_82481_, vec31.f_82479_) * (double)(180F / (float)Math.PI) - 90.0D);
         }

         serverplayer.m_7678_(vec3.f_82479_, vec3.f_82480_, vec3.f_82481_, f1, 0.0F);
         serverplayer.m_9158_(serverlevel1.m_46472_(), blockpos, f, flag, false);
         flag2 = !p_11238_ && flag1;
      } else if (blockpos != null) {
         serverplayer.f_8906_.m_9829_(new ClientboundGameEventPacket(ClientboundGameEventPacket.f_132153_, 0.0F));
      }

      while(!serverlevel1.m_45786_(serverplayer) && serverplayer.m_20186_() < (double)serverlevel1.m_151558_()) {
         serverplayer.m_6034_(serverplayer.m_20185_(), serverplayer.m_20186_() + 1.0D, serverplayer.m_20189_());
      }

      byte b0 = (byte)(p_11238_ ? 1 : 0);
      LevelData leveldata = serverplayer.m_9236_().m_6106_();
      serverplayer.f_8906_.m_9829_(new ClientboundRespawnPacket(serverplayer.m_9236_().m_220362_(), serverplayer.m_9236_().m_46472_(), BiomeManager.m_47877_(serverplayer.m_284548_().m_7328_()), serverplayer.f_8941_.m_9290_(), serverplayer.f_8941_.m_9293_(), serverplayer.m_9236_().m_46659_(), serverplayer.m_284548_().m_8584_(), b0, serverplayer.m_219759_(), serverplayer.m_287157_()));
      serverplayer.f_8906_.m_9774_(serverplayer.m_20185_(), serverplayer.m_20186_(), serverplayer.m_20189_(), serverplayer.m_146908_(), serverplayer.m_146909_());
      serverplayer.f_8906_.m_9829_(new ClientboundSetDefaultSpawnPositionPacket(serverlevel1.m_220360_(), serverlevel1.m_220361_()));
      serverplayer.f_8906_.m_9829_(new ClientboundChangeDifficultyPacket(leveldata.m_5472_(), leveldata.m_5474_()));
      serverplayer.f_8906_.m_9829_(new ClientboundSetExperiencePacket(serverplayer.f_36080_, serverplayer.f_36079_, serverplayer.f_36078_));
      this.m_11229_(serverplayer, serverlevel1);
      this.m_11289_(serverplayer);
      serverlevel1.m_8845_(serverplayer);
      this.f_11196_.add(serverplayer);
      this.f_11197_.put(serverplayer.m_20148_(), serverplayer);
      serverplayer.m_143429_();
      serverplayer.m_21153_(serverplayer.m_21223_());
      if (flag2) {
         serverplayer.f_8906_.m_9829_(new ClientboundSoundPacket(SoundEvents.f_12377_, SoundSource.BLOCKS, (double)blockpos.m_123341_(), (double)blockpos.m_123342_(), (double)blockpos.m_123343_(), 1.0F, 1.0F, serverlevel1.m_213780_().m_188505_()));
      }

      return serverplayer;
   }

   public void m_11289_(ServerPlayer p_11290_) {
      GameProfile gameprofile = p_11290_.m_36316_();
      int i = this.f_11195_.m_129944_(gameprofile);
      this.m_11226_(p_11290_, i);
   }

   public void m_11288_() {
      if (++this.f_11210_ > 600) {
         this.m_11268_(new ClientboundPlayerInfoUpdatePacket(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY), this.f_11196_));
         this.f_11210_ = 0;
      }

   }

   public void m_11268_(Packet<?> p_11269_) {
      for(ServerPlayer serverplayer : this.f_11196_) {
         serverplayer.f_8906_.m_9829_(p_11269_);
      }

   }

   public void m_11270_(Packet<?> p_11271_, ResourceKey<Level> p_11272_) {
      for(ServerPlayer serverplayer : this.f_11196_) {
         if (serverplayer.m_9236_().m_46472_() == p_11272_) {
            serverplayer.f_8906_.m_9829_(p_11271_);
         }
      }

   }

   public void m_215621_(Player p_215622_, Component p_215623_) {
      Team team = p_215622_.m_5647_();
      if (team != null) {
         for(String s : team.m_6809_()) {
            ServerPlayer serverplayer = this.m_11255_(s);
            if (serverplayer != null && serverplayer != p_215622_) {
               serverplayer.m_213846_(p_215623_);
            }
         }

      }
   }

   public void m_215649_(Player p_215650_, Component p_215651_) {
      Team team = p_215650_.m_5647_();
      if (team == null) {
         this.m_240416_(p_215651_, false);
      } else {
         for(int i = 0; i < this.f_11196_.size(); ++i) {
            ServerPlayer serverplayer = this.f_11196_.get(i);
            if (serverplayer.m_5647_() != team) {
               serverplayer.m_213846_(p_215651_);
            }
         }

      }
   }

   public String[] m_11291_() {
      String[] astring = new String[this.f_11196_.size()];

      for(int i = 0; i < this.f_11196_.size(); ++i) {
         astring[i] = this.f_11196_.get(i).m_36316_().getName();
      }

      return astring;
   }

   public UserBanList m_11295_() {
      return this.f_11198_;
   }

   public IpBanList m_11299_() {
      return this.f_11199_;
   }

   public void m_5749_(GameProfile p_11254_) {
      this.f_11200_.m_11381_(new ServerOpListEntry(p_11254_, this.f_11195_.m_7022_(), this.f_11200_.m_11351_(p_11254_)));
      ServerPlayer serverplayer = this.m_11259_(p_11254_.getId());
      if (serverplayer != null) {
         this.m_11289_(serverplayer);
      }

   }

   public void m_5750_(GameProfile p_11281_) {
      this.f_11200_.m_11393_(p_11281_);
      ServerPlayer serverplayer = this.m_11259_(p_11281_.getId());
      if (serverplayer != null) {
         this.m_11289_(serverplayer);
      }

   }

   private void m_11226_(ServerPlayer p_11227_, int p_11228_) {
      if (p_11227_.f_8906_ != null) {
         byte b0;
         if (p_11228_ <= 0) {
            b0 = 24;
         } else if (p_11228_ >= 4) {
            b0 = 28;
         } else {
            b0 = (byte)(24 + p_11228_);
         }

         p_11227_.f_8906_.m_9829_(new ClientboundEntityEventPacket(p_11227_, b0));
      }

      this.f_11195_.m_129892_().m_82095_(p_11227_);
   }

   public boolean m_5764_(GameProfile p_11294_) {
      return !this.f_11205_ || this.f_11200_.m_11396_(p_11294_) || this.f_11201_.m_11396_(p_11294_);
   }

   public boolean m_11303_(GameProfile p_11304_) {
      return this.f_11200_.m_11396_(p_11304_) || this.f_11195_.m_7779_(p_11304_) && this.f_11195_.m_129910_().m_5468_() || this.f_11209_;
   }

   @Nullable
   public ServerPlayer m_11255_(String p_11256_) {
      for(ServerPlayer serverplayer : this.f_11196_) {
         if (serverplayer.m_36316_().getName().equalsIgnoreCase(p_11256_)) {
            return serverplayer;
         }
      }

      return null;
   }

   public void m_11241_(@Nullable Player p_11242_, double p_11243_, double p_11244_, double p_11245_, double p_11246_, ResourceKey<Level> p_11247_, Packet<?> p_11248_) {
      for(int i = 0; i < this.f_11196_.size(); ++i) {
         ServerPlayer serverplayer = this.f_11196_.get(i);
         if (serverplayer != p_11242_ && serverplayer.m_9236_().m_46472_() == p_11247_) {
            double d0 = p_11243_ - serverplayer.m_20185_();
            double d1 = p_11244_ - serverplayer.m_20186_();
            double d2 = p_11245_ - serverplayer.m_20189_();
            if (d0 * d0 + d1 * d1 + d2 * d2 < p_11246_ * p_11246_) {
               serverplayer.f_8906_.m_9829_(p_11248_);
            }
         }
      }

   }

   public void m_11302_() {
      for(int i = 0; i < this.f_11196_.size(); ++i) {
         this.m_6765_(this.f_11196_.get(i));
      }

   }

   public UserWhiteList m_11305_() {
      return this.f_11201_;
   }

   public String[] m_11306_() {
      return this.f_11201_.m_5875_();
   }

   public ServerOpList m_11307_() {
      return this.f_11200_;
   }

   public String[] m_11308_() {
      return this.f_11200_.m_5875_();
   }

   public void m_7542_() {
   }

   public void m_11229_(ServerPlayer p_11230_, ServerLevel p_11231_) {
      WorldBorder worldborder = this.f_11195_.m_129783_().m_6857_();
      p_11230_.f_8906_.m_9829_(new ClientboundInitializeBorderPacket(worldborder));
      p_11230_.f_8906_.m_9829_(new ClientboundSetTimePacket(p_11231_.m_46467_(), p_11231_.m_46468_(), p_11231_.m_46469_().m_46207_(GameRules.f_46140_)));
      p_11230_.f_8906_.m_9829_(new ClientboundSetDefaultSpawnPositionPacket(p_11231_.m_220360_(), p_11231_.m_220361_()));
      if (p_11231_.m_46471_()) {
         p_11230_.f_8906_.m_9829_(new ClientboundGameEventPacket(ClientboundGameEventPacket.f_132154_, 0.0F));
         p_11230_.f_8906_.m_9829_(new ClientboundGameEventPacket(ClientboundGameEventPacket.f_132160_, p_11231_.m_46722_(1.0F)));
         p_11230_.f_8906_.m_9829_(new ClientboundGameEventPacket(ClientboundGameEventPacket.f_132161_, p_11231_.m_46661_(1.0F)));
      }

   }

   public void m_11292_(ServerPlayer p_11293_) {
      p_11293_.f_36095_.m_150429_();
      p_11293_.m_9233_();
      p_11293_.f_8906_.m_9829_(new ClientboundSetCarriedItemPacket(p_11293_.m_150109_().f_35977_));
   }

   public int m_11309_() {
      return this.f_11196_.size();
   }

   public int m_11310_() {
      return this.f_11193_;
   }

   public boolean m_11311_() {
      return this.f_11205_;
   }

   public void m_6628_(boolean p_11276_) {
      this.f_11205_ = p_11276_;
   }

   public List<ServerPlayer> m_11282_(String p_11283_) {
      List<ServerPlayer> list = Lists.newArrayList();

      for(ServerPlayer serverplayer : this.f_11196_) {
         if (serverplayer.m_9239_().equals(p_11283_)) {
            list.add(serverplayer);
         }
      }

      return list;
   }

   public int m_11312_() {
      return this.f_11207_;
   }

   public int m_184213_() {
      return this.f_184208_;
   }

   public MinecraftServer m_7873_() {
      return this.f_11195_;
   }

   @Nullable
   public CompoundTag m_6960_() {
      return null;
   }

   public void m_11284_(boolean p_11285_) {
      this.f_11209_ = p_11285_;
   }

   public void m_11313_() {
      for(int i = 0; i < this.f_11196_.size(); ++i) {
         (this.f_11196_.get(i)).f_8906_.m_9942_(Component.m_237115_("multiplayer.disconnect.server_shutdown"));
      }

   }

   public void m_240416_(Component p_240618_, boolean p_240644_) {
      this.m_240502_(p_240618_, (p_215639_) -> {
         return p_240618_;
      }, p_240644_);
   }

   public void m_240502_(Component p_240526_, Function<ServerPlayer, Component> p_240594_, boolean p_240648_) {
      this.f_11195_.m_213846_(p_240526_);

      for(ServerPlayer serverplayer : this.f_11196_) {
         Component component = p_240594_.apply(serverplayer);
         if (component != null) {
            serverplayer.m_240418_(component, p_240648_);
         }
      }

   }

   public void m_243063_(PlayerChatMessage p_243229_, CommandSourceStack p_243254_, ChatType.Bound p_243255_) {
      this.m_245148_(p_243229_, p_243254_::m_243061_, p_243254_.m_230896_(), p_243255_);
   }

   public void m_243049_(PlayerChatMessage p_243264_, ServerPlayer p_243234_, ChatType.Bound p_243204_) {
      this.m_245148_(p_243264_, p_243234_::m_143421_, p_243234_, p_243204_);
   }

   private void m_245148_(PlayerChatMessage p_249952_, Predicate<ServerPlayer> p_250784_, @Nullable ServerPlayer p_249623_, ChatType.Bound p_250276_) {
      boolean flag = this.m_247528_(p_249952_);
      this.f_11195_.m_241158_(p_249952_.m_245692_(), p_250276_, flag ? null : "Not Secure");
      OutgoingChatMessage outgoingchatmessage = OutgoingChatMessage.m_247282_(p_249952_);
      boolean flag1 = false;

      for(ServerPlayer serverplayer : this.f_11196_) {
         boolean flag2 = p_250784_.test(serverplayer);
         serverplayer.m_245069_(outgoingchatmessage, flag2, p_250276_);
         flag1 |= flag2 && p_249952_.m_243059_();
      }

      if (flag1 && p_249623_ != null) {
         p_249623_.m_213846_(f_243017_);
      }

   }

   private boolean m_247528_(PlayerChatMessage p_251384_) {
      return p_251384_.m_245272_() && !p_251384_.m_240431_(Instant.now());
   }

   public ServerStatsCounter m_11239_(Player p_11240_) {
      UUID uuid = p_11240_.m_20148_();
      ServerStatsCounter serverstatscounter = this.f_11202_.get(uuid);
      if (serverstatscounter == null) {
         File file1 = this.f_11195_.m_129843_(LevelResource.f_78175_).toFile();
         File file2 = new File(file1, uuid + ".json");
         if (!file2.exists()) {
            File file3 = new File(file1, p_11240_.m_7755_().getString() + ".json");
            Path path = file3.toPath();
            if (FileUtil.m_133728_(path) && FileUtil.m_133734_(path) && path.startsWith(file1.getPath()) && file3.isFile()) {
               file3.renameTo(file2);
            }
         }

         serverstatscounter = new ServerStatsCounter(this.f_11195_, file2);
         this.f_11202_.put(uuid, serverstatscounter);
      }

      return serverstatscounter;
   }

   public PlayerAdvancements m_11296_(ServerPlayer p_11297_) {
      UUID uuid = p_11297_.m_20148_();
      PlayerAdvancements playeradvancements = this.f_11203_.get(uuid);
      if (playeradvancements == null) {
         Path path = this.f_11195_.m_129843_(LevelResource.f_78174_).resolve(uuid + ".json");
         playeradvancements = new PlayerAdvancements(this.f_11195_.m_129933_(), this, this.f_11195_.m_129889_(), path, p_11297_);
         this.f_11203_.put(uuid, playeradvancements);
      }

      playeradvancements.m_135979_(p_11297_);
      return playeradvancements;
   }

   public void m_11217_(int p_11218_) {
      this.f_11207_ = p_11218_;
      this.m_11268_(new ClientboundSetChunkCacheRadiusPacket(p_11218_));

      for(ServerLevel serverlevel : this.f_11195_.m_129785_()) {
         if (serverlevel != null) {
            serverlevel.m_7726_().m_8354_(p_11218_);
         }
      }

   }

   public void m_184211_(int p_184212_) {
      this.f_184208_ = p_184212_;
      this.m_11268_(new ClientboundSetSimulationDistancePacket(p_184212_));

      for(ServerLevel serverlevel : this.f_11195_.m_129785_()) {
         if (serverlevel != null) {
            serverlevel.m_7726_().m_184026_(p_184212_);
         }
      }

   }

   public List<ServerPlayer> m_11314_() {
      return this.f_11196_;
   }

   @Nullable
   public ServerPlayer m_11259_(UUID p_11260_) {
      return this.f_11197_.get(p_11260_);
   }

   public boolean m_5765_(GameProfile p_11298_) {
      return false;
   }

   public void m_11315_() {
      for(PlayerAdvancements playeradvancements : this.f_11203_.values()) {
         playeradvancements.m_135981_(this.f_11195_.m_129889_());
      }

      this.m_11268_(new ClientboundUpdateTagsPacket(TagNetworkSerialization.m_245799_(this.f_243858_)));
      ClientboundUpdateRecipesPacket clientboundupdaterecipespacket = new ClientboundUpdateRecipesPacket(this.f_11195_.m_129894_().m_44051_());

      for(ServerPlayer serverplayer : this.f_11196_) {
         serverplayer.f_8906_.m_9829_(clientboundupdaterecipespacket);
         serverplayer.m_8952_().m_12789_(serverplayer);
      }

   }

   public boolean m_11316_() {
      return this.f_11209_;
   }
}