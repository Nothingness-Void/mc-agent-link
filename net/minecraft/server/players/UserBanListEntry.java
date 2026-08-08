package net.minecraft.server.players;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.network.chat.Component;

public class UserBanListEntry extends BanListEntry<GameProfile> {
   public UserBanListEntry(GameProfile p_11436_) {
      this(p_11436_, (Date)null, (String)null, (Date)null, (String)null);
   }

   public UserBanListEntry(GameProfile p_11438_, @Nullable Date p_11439_, @Nullable String p_11440_, @Nullable Date p_11441_, @Nullable String p_11442_) {
      super(p_11438_, p_11439_, p_11440_, p_11441_, p_11442_);
   }

   public UserBanListEntry(JsonObject p_11434_) {
      super(m_11445_(p_11434_), p_11434_);
   }

   protected void m_6009_(JsonObject p_11444_) {
      if (this.m_11373_() != null) {
         p_11444_.addProperty("uuid", this.m_11373_().getId() == null ? "" : this.m_11373_().getId().toString());
         p_11444_.addProperty("name", this.m_11373_().getName());
         super.m_6009_(p_11444_);
      }
   }

   public Component m_8003_() {
      GameProfile gameprofile = this.m_11373_();
      return Component.m_237113_(gameprofile.getName() != null ? gameprofile.getName() : Objects.toString(gameprofile.getId(), "(Unknown)"));
   }

   private static GameProfile m_11445_(JsonObject p_11446_) {
      if (p_11446_.has("uuid") && p_11446_.has("name")) {
         String s = p_11446_.get("uuid").getAsString();

         UUID uuid;
         try {
            uuid = UUID.fromString(s);
         } catch (Throwable throwable) {
            return null;
         }

         return new GameProfile(uuid, p_11446_.get("name").getAsString());
      } else {
         return null;
      }
   }
}