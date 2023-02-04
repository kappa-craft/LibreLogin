package xyz.kyngs.librelogin.velocity;

import co.aikar.commands.CommandIssuer;
import co.aikar.commands.CommandManager;
import co.aikar.commands.VelocityCommandIssuer;
import co.aikar.commands.VelocityCommandManager;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginDescription;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.byteflux.libby.Library;
import net.byteflux.libby.LibraryManager;
import net.byteflux.libby.VelocityLibraryManager;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bstats.charts.CustomChart;
import org.bstats.charts.SimplePie;
import org.bstats.velocity.Metrics;
import org.jetbrains.annotations.Nullable;
import xyz.kyngs.librelogin.api.LibreLoginPlugin;
import xyz.kyngs.librelogin.api.Logger;
import xyz.kyngs.librelogin.api.PlatformHandle;
import xyz.kyngs.librelogin.api.configuration.CorruptedConfigurationException;
import xyz.kyngs.librelogin.api.configuration.PluginConfiguration;
import xyz.kyngs.librelogin.api.database.User;
import xyz.kyngs.librelogin.api.provider.LibreLoginProvider;
import xyz.kyngs.librelogin.common.AuthenticLibreLogin;
import xyz.kyngs.librelogin.common.SLF4JLogger;
import xyz.kyngs.librelogin.common.image.AuthenticImageProjector;
import xyz.kyngs.librelogin.common.image.protocolize.ProtocolizeImageProjector;
import xyz.kyngs.librelogin.common.util.CancellableTask;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;


@Plugin(
        id = "librelogin",
        name = "LibreLogin",
        version = "@version@",
        authors = "kyngs",
        dependencies = {
                @Dependency(id = "floodgate", optional = true),
                @Dependency(id = "protocolize", optional = true),
                @Dependency(id = "redisbungee", optional = true)
        }
)
public class VelocityLibreLogin extends AuthenticLibreLogin<Player, RegisteredServer> implements LibreLoginProvider<Player, RegisteredServer> {

    @Inject
    private org.slf4j.Logger logger;
    @Inject
    @DataDirectory
    private Path dataDir;
    @Inject
    private ProxyServer server;
    @Inject
    private Metrics.Factory factory;
    @Inject
    private PluginDescription description;
    @Nullable
    private VelocityRedisBungeeIntegration redisBungee;

    public ProxyServer getServer() {
        return server;
    }

    static {
        System.setProperty("auth.forceSecureProfiles", "false");
    }

    @Override
    protected PlatformHandle<Player, RegisteredServer> providePlatformHandle() {
        return new VelocityPlatformHandle(this);
    }

    @Override
    protected Logger provideLogger() {
        return new SLF4JLogger(logger, () -> getConfiguration().debug());
    }

    @Override
    public CommandManager<?, ?, ?, ?, ?, ?> provideManager() {
        return new VelocityCommandManager(server, this);
    }

    @Override
    public Player getPlayerFromIssuer(CommandIssuer issuer) {
        return ((VelocityCommandIssuer) issuer).getPlayer();
    }

    @Override
    public void validateConfiguration(PluginConfiguration configuration) throws CorruptedConfigurationException {
        if (configuration.getLimbo().isEmpty())
            throw new CorruptedConfigurationException("No limbo servers defined!");
        if (configuration.getPassThrough().isEmpty())
            throw new CorruptedConfigurationException("No pass-through servers defined!");
        for (String server : configuration.getPassThrough().values()) {
            if (this.server.getServer(server).isEmpty())
                throw new CorruptedConfigurationException("The supplied pass-through server %s is not configured in the proxy configuration!".formatted(server));
        }
        for (String server : configuration.getLimbo()) {
            if (this.server.getServer(server).isEmpty())
                throw new CorruptedConfigurationException("The supplied limbo server is not configured in the proxy configuration!");
        }
    }

    @Override
    public void authorize(Player player, User user, Audience audience) {
        try {
            var lobby = chooseLobby(user, player, true);
            if (lobby == null) throw new NoSuchElementException();
            player
                    .createConnectionRequest(
                            lobby
                    )
                    .connect()
                    .whenComplete((result, throwable) -> {
                        if (player.getCurrentServer().isEmpty()) return;
                        if (player.getCurrentServer().get().getServerInfo().getName().equals(result.getAttemptedConnection().getServerInfo().getName()))
                            return;
                        if (throwable != null || !result.isSuccessful())
                            player.disconnect(Component.text("Unable to connect"));
                    });
        } catch (NoSuchElementException e) {
            player.disconnect(getMessages().getMessage("kick-no-server"));
        }
    }

    @Override
    public CancellableTask delay(Runnable runnable, long delayInMillis) {
        var task = server.getScheduler()
                .buildTask(this, runnable)
                .delay(delayInMillis, TimeUnit.MILLISECONDS)
                .schedule();
        return task::cancel;
    }

    @Override
    public boolean pluginPresent(String pluginName) {
        return server.getPluginManager().getPlugin(pluginName).isPresent();
    }

    @Override
    protected AuthenticImageProjector<Player, RegisteredServer> provideImageProjector() {
        if (pluginPresent("protocolize")) {
            var projector = new ProtocolizeImageProjector<>(this);
            var maxProtocol = ProtocolVersion.MAXIMUM_VERSION.getProtocol();

            if (maxProtocol == 760) {
                // I hate this so much
                try {
                    var split = server.getVersion().getVersion().split("-");
                    var build = Integer.parseInt(split[split.length - 1].replace("b", ""));

                    if (build < 172) {
                        logger.warn("Detected protocolize, but in order for the integration to work properly, you must be running Velocity build 172 or newer!");
                        return null;
                    }
                } catch (Exception e) {
                    // I guess it's probably fine
                }
            }

            if (!projector.compatible()) {
                getLogger().warn("Detected protocolize, however, with incompatible version (2.2.2), please upgrade or downgrade.");
                return null;
            }
            getLogger().info("Detected Protocolize, enabling 2FA...");
            return projector;
        } else {
            logger.warn("Protocolize not found, some features (e.g. 2FA) will not work!");
            return null;
        }
    }

    @Override
    protected void enable() {
        if (pluginPresent("redisbungee")) {
            redisBungee = new VelocityRedisBungeeIntegration();
        }
        super.enable();
    }

    @Override
    public String getVersion() {
        return description.getVersion().orElseThrow();
    }

    @Override
    public boolean isPresent(UUID uuid) {
        return redisBungee != null ? redisBungee.isPlayerOnline(uuid) : getPlayerForUUID(uuid) != null;
    }

    @Override
    public boolean multiProxyEnabled() {
        return redisBungee != null;
    }

    @Override
    public Player getPlayerForUUID(UUID uuid) {
        return server.getPlayer(uuid).orElse(null);
    }

    @Override
    protected void initMetrics(CustomChart... charts) {
        var metrics = factory.make(this, 14805);

        for (CustomChart chart : charts) {
            metrics.addCustomChart(chart);
        }

        var isVelocity = new SimplePie("using_velocity", () -> "Yes");

        metrics.addCustomChart(isVelocity);
    }

    @Override
    public RegisteredServer chooseLobbyDefault(Player player) throws NoSuchElementException {
        var passThroughServers = getConfiguration().getPassThrough();
        var virt = player.getVirtualHost().orElse(null);

        getLogger().debug("Virtual host for player " + player.getUsername() + " is " + virt);

        var servers = virt == null ? passThroughServers.get("root") : passThroughServers.get(virt.getHostName());

        if (servers.isEmpty()) servers = passThroughServers.get("root");

        final var finalServers = servers;

        return server.getAllServers().stream()
                .filter(server -> finalServers.contains(server.getServerInfo().getName()))
                .filter(server -> {
                    var ping = getServerPinger().getLatestPing(server);

                    return ping != null && ping.maxPlayers() > server.getPlayersConnected().size();
                })
                .min(Comparator.comparingInt(o -> o.getPlayersConnected().size()))
                .orElse(null);
    }

    @Override
    public RegisteredServer chooseLimboDefault() {
        var limbos = getConfiguration().getLimbo();
        return server.getAllServers().stream()
                .filter(server -> limbos.contains(server.getServerInfo().getName()))
                .filter(server -> {
                    var ping = getServerPinger().getLatestPing(server);

                    return ping != null && ping.maxPlayers() > server.getPlayersConnected().size();
                })
                .min(Comparator.comparingInt(o -> o.getPlayersConnected().size()))
                .orElse(null);
    }

    @Override
    public Audience getAudienceFromIssuer(CommandIssuer issuer) {
        return ((VelocityCommandIssuer) issuer).getIssuer();
    }

    @Override
    protected List<Library> customDependencies() {
        return List.of(

        );
    }

    @Override
    protected List<String> customRepositories() {
        return List.of(

        );
    }

    @Override
    protected LibraryManager provideLibraryManager() {
        return new VelocityLibraryManager<>(logger, Path.of("plugins", "librelogin"), server.getPluginManager(), this);
    }

    @Subscribe
    public void onInitialization(ProxyInitializeEvent event) {
        enable();

        server.getEventManager().register(this, new Blockers(getAuthorizationProvider(), getConfiguration(), getMessages()));
        server.getEventManager().register(this, new VelocityListeners(this));

        var millis = getConfiguration().milliSecondsToRefreshNotification();

        if (millis > 0) {
            server.getScheduler().buildTask(this, () -> getAuthorizationProvider().notifyUnauthorized())
                    .repeat(getConfiguration().milliSecondsToRefreshNotification(), TimeUnit.MILLISECONDS)
                    .schedule();
        }
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        disable();
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        return getClass().getClassLoader().getResourceAsStream(name);
    }

    @Override
    public File getDataFolder() {
        return dataDir.toFile();
    }

    @Override
    public LibreLoginPlugin<Player, RegisteredServer> getLibreLogin() {
        return this;
    }
}