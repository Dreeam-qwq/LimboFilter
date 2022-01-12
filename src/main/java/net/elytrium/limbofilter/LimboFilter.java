/*
 * Copyright (C) 2021 Elytrium
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.elytrium.limbofilter;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.LimboFactory;
import net.elytrium.limboapi.api.chunk.Dimension;
import net.elytrium.limboapi.api.chunk.VirtualWorld;
import net.elytrium.limboapi.api.file.SchematicFile;
import net.elytrium.limboapi.api.file.WorldFile;
import net.elytrium.limbofilter.cache.CachedPackets;
import net.elytrium.limbofilter.cache.captcha.CachedCaptcha;
import net.elytrium.limbofilter.captcha.CaptchaGenerator;
import net.elytrium.limbofilter.commands.LimboFilterCommand;
import net.elytrium.limbofilter.handler.BotFilterSessionHandler;
import net.elytrium.limbofilter.listener.FilterListener;
import net.elytrium.limbofilter.stats.Statistics;
import net.elytrium.limbofilter.utils.UpdatesChecker;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;
import org.bstats.velocity.Metrics;
import org.slf4j.Logger;

@Plugin(
    id = "limbofilter",
    name = "LimboFilter",
    version = BuildConstants.FILTER_VERSION,
    url = "https://elytrium.net/",
    authors = {
        "hevav",
        "mdxd44"
    },
    dependencies = {
        @Dependency(id = "limboapi")
    }
)
public class LimboFilter {

  private final Map<String, CachedUser> cachedFilterChecks = new ConcurrentHashMap<>();

  private final Path dataDirectory;
  private final Logger logger;
  private final Metrics.Factory metricsFactory;
  private final ProxyServer server;
  private final CachedPackets packets;
  private final CaptchaGenerator generator;
  private final Statistics statistics;
  private final LimboFactory factory;

  private CachedCaptcha cachedCaptcha;
  private Limbo filterServer;
  private Metrics metrics;

  @Inject
  public LimboFilter(ProxyServer server, Logger logger, Metrics.Factory metricsFactory, @DataDirectory Path dataDirectory) {
    this.server = server;
    this.logger = logger;
    this.metricsFactory = metricsFactory;
    this.dataDirectory = dataDirectory;
    this.packets = new CachedPackets();
    this.generator = new CaptchaGenerator(this);
    this.statistics = new Statistics();

    this.factory = (LimboFactory) this.server.getPluginManager().getPlugin("limboapi").flatMap(PluginContainer::getInstance).orElseThrow();
  }

  @Subscribe
  public void onProxyInitialization(ProxyInitializeEvent event) {
    this.metrics = this.metricsFactory.make(this, 13699);

    this.reload();

    this.metrics.addCustomChart(new SimplePie("filter_type", () -> Settings.IMP.MAIN.CHECK_STATE));
    this.metrics.addCustomChart(new SimplePie("load_world", () -> String.valueOf(Settings.IMP.MAIN.LOAD_WORLD)));
    this.metrics.addCustomChart(new SimplePie("check_brand", () -> String.valueOf(Settings.IMP.MAIN.CHECK_CLIENT_BRAND)));
    this.metrics.addCustomChart(new SimplePie("check_settings", () -> String.valueOf(Settings.IMP.MAIN.CHECK_CLIENT_SETTINGS)));
    this.metrics.addCustomChart(new SimplePie("has_backplate", () ->
        String.valueOf(!Settings.IMP.MAIN.CAPTCHA_GENERATOR.BACKPLATE_PATH.isEmpty())));

    this.metrics.addCustomChart(new SingleLineChart("pings", () -> Math.toIntExact(this.statistics.getPings())));
    this.metrics.addCustomChart(new SingleLineChart("connections", () -> Math.toIntExact(this.statistics.getConnections())));

    UpdatesChecker.checkForUpdates(this.getLogger());
  }

  @SuppressWarnings("SwitchStatementWithTooFewBranches")
  public void reload() {
    Settings.IMP.reload(new File(this.dataDirectory.toFile().getAbsoluteFile(), "config.yml"));

    BotFilterSessionHandler.setFallingCheckTotalTime(Settings.IMP.MAIN.FALLING_CHECK_TICKS * 50L);

    this.statistics.startUpdating();

    this.cachedCaptcha = new CachedCaptcha(this);
    this.generator.generateCaptcha();

    this.packets.createPackets(this.getFactory());

    this.cachedFilterChecks.clear();

    Settings.MAIN.COORDS captchaCoords = Settings.IMP.MAIN.COORDS;
    VirtualWorld filterWorld = this.factory.createVirtualWorld(
        Dimension.valueOf(Settings.IMP.MAIN.BOTFILTER_DIMENSION),
        captchaCoords.CAPTCHA_X, captchaCoords.CAPTCHA_Y, captchaCoords.CAPTCHA_Z,
        (float) captchaCoords.CAPTCHA_YAW, (float) captchaCoords.CAPTCHA_PITCH
    );

    if (Settings.IMP.MAIN.LOAD_WORLD) {
      try {
        Path path = this.dataDirectory.resolve(Settings.IMP.MAIN.WORLD_FILE_PATH);
        WorldFile file;
        switch (Settings.IMP.MAIN.WORLD_FILE_TYPE) {
          case "schematic": {
            file = new SchematicFile(path);
            break;
          }
          default: {
            this.logger.error("Incorrect world file type.");
            this.server.shutdown();
            return;
          }
        }

        Settings.MAIN.WORLD_COORDS coords = Settings.IMP.MAIN.WORLD_COORDS;
        file.toWorld(this.factory, filterWorld, coords.X, coords.Y, coords.Z);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    this.filterServer = this.factory.createLimbo(filterWorld);

    CommandManager manager = this.server.getCommandManager();
    manager.unregister("limbofilter");
    manager.register("limbofilter", new LimboFilterCommand(this.server, this), "lf", "botfilter", "bf", "lfilter");

    this.server.getEventManager().unregisterListeners(this);
    this.server.getEventManager().register(this, new FilterListener(this));

    Executors.newScheduledThreadPool(1, task -> new Thread(task, "purge-cache")).scheduleAtFixedRate(() ->
        this.checkCache(this.cachedFilterChecks, Settings.IMP.MAIN.PURGE_CACHE_MILLIS),
        Settings.IMP.MAIN.PURGE_CACHE_MILLIS,
        Settings.IMP.MAIN.PURGE_CACHE_MILLIS,
        TimeUnit.MILLISECONDS
    );
  }

  public void cacheFilterUser(Player player) {
    String username = player.getUsername();
    this.cachedFilterChecks.remove(username);
    this.cachedFilterChecks.put(username, new CachedUser(player.getRemoteAddress().getAddress(), System.currentTimeMillis()));
  }

  public boolean shouldCheck(Player player) {
    if (!this.checkCpsLimit(Settings.IMP.MAIN.FILTER_AUTO_TOGGLE.ALL_BYPASS)) {
      return false;
    }

    if (player.isOnlineMode() && !this.checkCpsLimit(Settings.IMP.MAIN.FILTER_AUTO_TOGGLE.ONLINE_MODE_BYPASS)) {
      return false;
    }

    return this.shouldCheck(player.getUsername(), player.getRemoteAddress().getAddress());
  }

  public boolean shouldCheck(String nickname, InetAddress ip) {
    if (this.cachedFilterChecks.containsKey(nickname)) {
      return !ip.equals(this.cachedFilterChecks.get(nickname).getInetAddress());
    } else {
      return true;
    }
  }

  public void sendToFilterServer(Player player) {
    try {
      this.filterServer.spawnPlayer(player, new BotFilterSessionHandler(player, this));
    } catch (Throwable t) {
      this.logger.error("Error", t);
    }
  }

  private void checkCache(Map<String, CachedUser> userMap, long time) {
    userMap.entrySet().stream()
        .filter(u -> u.getValue().getCheckTime() + time <= System.currentTimeMillis())
        .map(Map.Entry::getKey)
        .forEach(userMap::remove);
  }

  public boolean checkCpsLimit(int limit) {
    if (limit == -1) {
      return false;
    }

    return limit <= this.statistics.getConnections();
  }

  public boolean checkPpsLimit(int limit) {
    if (limit == -1) {
      return false;
    }

    return limit <= this.statistics.getPings();
  }

  public ProxyServer getServer() {
    return this.server;
  }

  public Logger getLogger() {
    return this.logger;
  }

  public LimboFactory getFactory() {
    return this.factory;
  }

  public CachedPackets getPackets() {
    return this.packets;
  }

  public Statistics getStatistics() {
    return this.statistics;
  }

  public CachedCaptcha getCachedCaptcha() {
    return this.cachedCaptcha;
  }

  private static class CachedUser {

    private final InetAddress inetAddress;
    private final long checkTime;

    public CachedUser(InetAddress inetAddress, long checkTime) {
      this.inetAddress = inetAddress;
      this.checkTime = checkTime;
    }

    public InetAddress getInetAddress() {
      return this.inetAddress;
    }

    public long getCheckTime() {
      return this.checkTime;
    }
  }
}
