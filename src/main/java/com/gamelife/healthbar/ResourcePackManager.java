
package com.gamelife.healthbar;

import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

final class ResourcePackManager implements Listener {

    private final JavaPlugin plugin;

    private HttpServer server;
    private ExecutorService executor;

    private String delivery;
    private String url;
    private String publicUrl;
    private String path;
    private String hash;
    private String prompt;
    private String kickMessage;

    private boolean sendOnJoin;
    private boolean required;
    private boolean first;
    private boolean listenerRegistered;

    private List<Extra> extras = List.of();

    private record Extra(UUID id, URI uri, String hash) {}

    ResourcePackManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    void start() {
        try {
            loadConfig();
            buildPack();

            if (delivery.equals("host")) {
                startHost();
            }

            if (!listenerRegistered) {
                plugin.getServer().getPluginManager()
                        .registerEvents(this, plugin);
                listenerRegistered = true;
            }

            if (sendOnJoin) {
                plugin.getServer().getOnlinePlayers().forEach(this::send);
            }
        } catch (Exception e) {
            plugin.getLogger().severe(
                    "Không thể khởi tạo resource pack: " + e.getMessage()
            );
            plugin.getLogger().log(
                    java.util.logging.Level.SEVERE,
                    "Chi tiết lỗi resource pack",
                    e
            );
            stopHost();
        }
    }

    /**
     * Đọc lại config, nén lại pack, cập nhật SHA-1
     * và gửi pack mới cho toàn bộ người chơi online.
     */
    void reloadAndSend() {
        try {
            stopHost();
            loadConfig();
            buildPack();

            if (delivery.equals("host")) {
                startHost();
            }

            if (delivery.equals("none")) {
                plugin.getLogger().warning(
                        "Resource pack đã được nén nhưng delivery=none, không gửi pack."
                );
                return;
            }

            plugin.getServer().getOnlinePlayers().forEach(this::send);

            plugin.getLogger().info(
                    "Đã nén lại resource pack và gửi yêu cầu tải pack mới."
            );
        } catch (Exception e) {
            plugin.getLogger().severe(
                    "Không thể reload resource pack: " + e.getMessage()
            );
            plugin.getLogger().log(
                    java.util.logging.Level.SEVERE,
                    "Chi tiết lỗi reload resource pack",
                    e
            );
            stopHost();
        }
    }

    private void loadConfig() {
        ConfigurationSection c =
                plugin.getConfig().getConfigurationSection("resource-pack");

        delivery = c == null
                ? "none"
                : c.getString("delivery", "none")
                .toLowerCase(Locale.ROOT);

        if (!Set.of("none", "url", "host").contains(delivery)) {
            delivery = "none";
        }

        url = c == null ? "" : c.getString("url", "");
        publicUrl = c == null
                ? ""
                : c.getString("host.public-url", "");

        path = c == null
                ? "/gamelifehealthbar.zip"
                : c.getString("host.path", "/gamelifehealthbar.zip");

        sendOnJoin = c == null || c.getBoolean("send-on-join", true);
        required = c != null && c.getBoolean("required", false);
        prompt = c == null ? "" : c.getString("prompt", "");
        kickMessage = c == null ? "" : c.getString("kick-message", "");

        first = c != null
                && c.getString("position", "last")
                .equalsIgnoreCase("first");

        List<Extra> parsed = new ArrayList<>();

        if (c != null) {
            for (Map<?, ?> item : c.getMapList("extra-packs")) {
                try {
                    Object urlValue = item.get("url");

                    if (urlValue == null) {
                        throw new IllegalArgumentException(
                                "Thiếu URL của extra-pack"
                        );
                    }

                    String extraUrl = String.valueOf(urlValue);
                    URI uri = URI.create(extraUrl);

                    if (!uri.isAbsolute()
                            || uri.getHost() == null
                            || !("http".equalsIgnoreCase(uri.getScheme())
                            || "https".equalsIgnoreCase(uri.getScheme()))) {
                        throw new IllegalArgumentException(
                                "URL extra-pack không hợp lệ"
                        );
                    }

                    Object hashValue = item.get("hash");
                    String extraHash = hashValue == null
                            ? ""
                            : String.valueOf(hashValue).trim();

                    if (!extraHash.isEmpty()
                            && !extraHash.matches("(?i)[0-9a-f]{40}")) {
                        throw new IllegalArgumentException(
                                "Hash extra-pack phải là SHA-1 gồm 40 ký tự hex"
                        );
                    }

                    UUID id = UUID.nameUUIDFromBytes(
                            extraUrl.getBytes(StandardCharsets.UTF_8)
                    );

                    parsed.add(new Extra(id, uri, extraHash));

                } catch (Exception e) {
                    plugin.getLogger().warning(
                            "Bỏ qua extra-pack không hợp lệ: " + e.getMessage()
                    );
                }
            }
        }

        extras = List.copyOf(parsed);
    }

    private void buildPack() throws IOException {
        Path dataFolder = plugin.getDataFolder().toPath();
        Path zip = dataFolder.resolve("gamelifehealthbar.zip");
        Path source = dataFolder.resolve("pack");

        Files.createDirectories(dataFolder);
        Files.createDirectories(source);

        // Chỉ tạo file mặc định nếu chưa tồn tại.
        // Không ghi đè PNG/JSON mà người dùng đã chỉnh sửa.
        Path meta = source.resolve("pack.mcmeta");
        if (!Files.exists(meta))
            Files.writeString(meta, "{\"pack\":{\"pack_format\":46,\"description\":\"GamelifeHealthBar\"}}\n");


        Path texture = source.resolve("assets/gamelifehealthbar/textures/font/healthbar.png");
        Files.createDirectories(texture.getParent());
        if (!Files.exists(texture)) {
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(160, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = image.createGraphics();
            for (int frame = 0; frame < 10; frame++) {
                int x = frame * 16;
                g.setColor(new java.awt.Color(35,35,35,220)); g.fillRect(x,1,15,6);
                g.setColor(new java.awt.Color(10,10,10,255)); g.drawRect(x,1,14,5);
                int fill = (int)Math.round(12*(frame+1)/10.0);
                g.setColor(frame<3 ? new java.awt.Color(220,55,55) : frame<6 ? new java.awt.Color(230,190,45) : new java.awt.Color(65,210,75));
                g.fillRect(x+2,3,fill,2);
            }
            g.dispose();
            javax.imageio.ImageIO.write(image, "png", texture.toFile());
        }

        Path font = source.resolve("assets/gamelifehealthbar/font/healthbar.json");
        Files.createDirectories(font.getParent());

        int frames = Math.max(1, Math.min(256, plugin.getConfig().getInt("bar.frames", 10)));
        BufferedImage img = ImageIO.read(texture.toFile());
        if (img == null) throw new IOException("healthbar.png không đọc được (không phải PNG hợp lệ)");

        int width = img.getWidth();
        int height = img.getHeight();
        if (width % frames != 0) {
            plugin.getLogger().warning("Chiều rộng ảnh (" + width + ") không chia hết cho bar.frames (" + frames + "), thanh máu có thể bị lệch pixel.");
        }
        int ascent = Math.max(0, Math.min(height, plugin.getConfig().getInt("bar.ascent", Math.max(1, height - 1))));

        StringBuilder chars = new StringBuilder();
        for (int i = 0; i < frames; i++) chars.append(String.format("\\u%04X", 0xE000 + i));

        Files.writeString(font,
                "{\"providers\":[{\"type\":\"bitmap\",\"file\":\"gamelifehealthbar:font/healthbar.png\","
                        + "\"ascent\":" + ascent + ",\"height\":" + height + ",\"chars\":[\"" + chars + "\"]}]}\n");

        // Nén ra file tạm rồi thay ZIP cũ.
        Path tempZip = dataFolder.resolve("gamelifehealthbar.tmp.zip");

        try {
            try (ZipOutputStream out = new ZipOutputStream(
                    Files.newOutputStream(tempZip)
            );
                 var files = Files.walk(source)) {

                for (Path file : files
                        .filter(Files::isRegularFile)
                        .sorted()
                        .toList()) {

                    String entryName = source.relativize(file)
                            .toString()
                            .replace('\\', '/');

                    out.putNextEntry(new ZipEntry(entryName));
                    Files.copy(file, out);
                    out.closeEntry();
                }
            }

            try {
                Files.move(
                        tempZip,
                        zip,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(
                        tempZip,
                        zip,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
        } finally {
            Files.deleteIfExists(tempZip);
        }

        try {
            hash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-1")
                            .digest(Files.readAllBytes(zip))
            );
        } catch (Exception e) {
            throw new IOException(
                    "Không thể tính SHA-1 cho resource pack",
                    e
            );
        }
    }

    private void startHost() throws IOException {
        ConfigurationSection c =
                plugin.getConfig().getConfigurationSection(
                        "resource-pack.host"
                );

        String bind = c == null
                ? "0.0.0.0"
                : c.getString("bind", "0.0.0.0");

        int port = c == null
                ? 12001
                : c.getInt("port", 12001);

        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(
                    "Port resource-pack không hợp lệ: " + port
            );
        }

        String route = path.startsWith("/") ? path : "/" + path;

        server = HttpServer.create(
                new InetSocketAddress(bind, port),
                0
        );

        server.createContext(route, exchange -> {
            try {
                String method = exchange.getRequestMethod();

                if (!method.equals("GET") && !method.equals("HEAD")) {
                    exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }

                Path zip = plugin.getDataFolder().toPath()
                        .resolve("gamelifehealthbar.zip");

                if (!Files.isRegularFile(zip)) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }

                exchange.getResponseHeaders().set(
                        "Content-Type", "application/zip"
                );

                exchange.getResponseHeaders().set(
                        "Cache-Control", "no-cache"
                );

                if (method.equals("HEAD")) {
                    exchange.getResponseHeaders().set(
                            "Content-Length",
                            String.valueOf(Files.size(zip))
                    );

                    exchange.sendResponseHeaders(200, -1);
                    return;
                }

                exchange.sendResponseHeaders(200, Files.size(zip));

                try (OutputStream os = exchange.getResponseBody()) {
                    Files.copy(zip, os);
                }

            } catch (IOException e) {
                plugin.getLogger().warning(
                        "Lỗi phục vụ resource pack: " + e.getMessage()
                );
            } finally {
                exchange.close();
            }
        });

        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "GamelifeHealthBar-PackHost");
            t.setDaemon(true);
            return t;
        });

        server.setExecutor(executor);
        server.start();

        plugin.getLogger().info(
                "Resource pack host: "
                        + (publicUrl.isBlank()
                        ? "http://<server-ip>:" + port + route
                        : publicUrl)
        );
    }

    private void stopHost() {
        if (server != null) {
            server.stop(1);
            server = null;
        }

        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    void stop() {
        stopHost();

        if (listenerRegistered) {
            HandlerList.unregisterAll(this);
            listenerRegistered = false;
        }
    }

    private void send(Player player) {
        if (delivery.equals("none")) {
            return;
        }

        try {
            String packUrl = delivery.equals("url") ? url : publicUrl;
            URI uri = URI.create(packUrl);

            if (!uri.isAbsolute()
                    || uri.getHost() == null
                    || !("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))) {
                plugin.getLogger().warning(
                        "Resource pack URL chưa hợp lệ; kiểm tra config.yml"
                );
                return;
            }

            // URL hoặc nội dung pack thay đổi thì ID cũng thay đổi.
            ResourcePackInfo own = ResourcePackInfo.resourcePackInfo(
                    UUID.nameUUIDFromBytes(
                            (uri.toString() + ":" + hash)
                                    .getBytes(StandardCharsets.UTF_8)
                    ),
                    uri,
                    hash
            );

            List<ResourcePackInfo> packs = new ArrayList<>();

            if (first) {
                packs.add(own);
            }

            for (Extra extra : extras) {
                packs.add(ResourcePackInfo.resourcePackInfo(
                        extra.id(),
                        extra.uri(),
                        extra.hash()
                ));
            }

            if (!first) {
                packs.add(own);
            }

            ResourcePackRequest.Builder request =
                    ResourcePackRequest.resourcePackRequest()
                            .packs(packs)
                            .required(required);

            if (!prompt.isBlank()) {
                request.prompt(legacy(prompt));
            }

            player.sendResourcePacks(request.build());

        } catch (Exception e) {
            plugin.getLogger().warning(
                    "Gửi resource pack thất bại cho "
                            + player.getName() + ": " + e.getMessage()
            );
        }
    }

    private Component legacy(String text) {
        return LegacyComponentSerializer.legacyAmpersand()
                .deserialize(text);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (sendOnJoin) {
            send(event.getPlayer());
        }
    }

    @EventHandler
    public void onPackStatus(PlayerResourcePackStatusEvent event) {
        if (!required) {
            return;
        }

        PlayerResourcePackStatusEvent.Status status = event.getStatus();

        if (status == PlayerResourcePackStatusEvent.Status.DECLINED
                || status == PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD) {

            String message = kickMessage.isBlank()
                    ? "Bạn phải tải resource pack để vào server."
                    : kickMessage;

            event.getPlayer().kick(legacy(message));
        }
    }
}
