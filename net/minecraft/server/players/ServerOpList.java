package net.minecraft.server.players;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import java.io.File;
import java.util.Objects;

public class ServerOpList extends StoredUserList<GameProfile, ServerOpListEntry> {
   public ServerOpList(File p_11345_) {
      super(p_11345_);
   }

   protected StoredUserEntry<GameProfile> m_6666_(JsonObject p_11348_) {
      return new ServerOpListEntry(p_11348_);
   }

   public String[] m_5875_() {
      return this.m_11395_().stream().map(StoredUserEntry::m_11373_).filter(Objects::nonNull).map(GameProfile::getName).toArray((p_143997_) -> {
         return new String[p_143997_];
      });
   }

   public boolean m_11351_(GameProfile p_11352_) {
      ServerOpListEntry serveroplistentry = this.m_11388_(p_11352_);
      return serveroplistentry != null ? serveroplistentry.m_11366_() : false;
   }

   protected String m_5981_(GameProfile p_11354_) {
      return p_11354_.getId().toString();
   }
}