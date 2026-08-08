package net.minecraft.server.players;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import java.io.File;
import java.util.Objects;

public class UserBanList extends StoredUserList<GameProfile, UserBanListEntry> {
   public UserBanList(File p_11402_) {
      super(p_11402_);
   }

   protected StoredUserEntry<GameProfile> m_6666_(JsonObject p_11405_) {
      return new UserBanListEntry(p_11405_);
   }

   public boolean m_11406_(GameProfile p_11407_) {
      return this.m_11396_(p_11407_);
   }

   public String[] m_5875_() {
      return this.m_11395_().stream().map(StoredUserEntry::m_11373_).filter(Objects::nonNull).map(GameProfile::getName).toArray((p_144013_) -> {
         return new String[p_144013_];
      });
   }

   protected String m_5981_(GameProfile p_11411_) {
      return p_11411_.getId().toString();
   }
}