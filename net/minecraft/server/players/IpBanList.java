package net.minecraft.server.players;

import com.google.gson.JsonObject;
import java.io.File;
import java.net.SocketAddress;
import javax.annotation.Nullable;

public class IpBanList extends StoredUserList<String, IpBanListEntry> {
   public IpBanList(File p_11036_) {
      super(p_11036_);
   }

   protected StoredUserEntry<String> m_6666_(JsonObject p_11038_) {
      return new IpBanListEntry(p_11038_);
   }

   public boolean m_11041_(SocketAddress p_11042_) {
      String s = this.m_11045_(p_11042_);
      return this.m_11396_(s);
   }

   public boolean m_11039_(String p_11040_) {
      return this.m_11396_(p_11040_);
   }

   @Nullable
   public IpBanListEntry m_11043_(SocketAddress p_11044_) {
      String s = this.m_11045_(p_11044_);
      return this.m_11388_(s);
   }

   private String m_11045_(SocketAddress p_11046_) {
      String s = p_11046_.toString();
      if (s.contains("/")) {
         s = s.substring(s.indexOf(47) + 1);
      }

      if (s.contains(":")) {
         s = s.substring(0, s.indexOf(58));
      }

      return s;
   }
}