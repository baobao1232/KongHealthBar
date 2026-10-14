package com.gamelife.healthbar;

import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.zip.*;

final class ResourcePackManager implements Listener {
    private final JavaPlugin plugin;
    private HttpServer server;
    private String delivery, url, publicUrl, path, hash, prompt, kickMessage;
    private boolean sendOnJoin, required, first;
    private List<Extra> extras = List.of();
    private record Extra(UUID id, URI uri, String hash) {}
    ResourcePackManager(JavaPlugin plugin) { this.plugin = plugin; }
    void start() {
        try {
            loadConfig(); buildPack();
            if (delivery.equals("host")) startHost();
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            if (sendOnJoin) plugin.getServer().getOnlinePlayers().forEach(this::send);
        } catch (Exception e) { plugin.getLogger().severe("Không thể khởi tạo resource pack: " + e.getMessage()); }
    }
    private void loadConfig() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("resource-pack");
        delivery = c == null ? "none" : c.getString("delivery", "none").toLowerCase(Locale.ROOT);
        if (!Set.of("none","url","host").contains(delivery)) delivery = "none";
        url = c == null ? "" : c.getString("url", ""); publicUrl = c == null ? "" : c.getString("host.public-url", "");
        path = c == null ? "/gamelifehealthbar.zip" : c.getString("host.path", "/gamelifehealthbar.zip");
        sendOnJoin = c == null || c.getBoolean("send-on-join", true); required = c != null && c.getBoolean("required", false);
        prompt = c == null ? "" : c.getString("prompt", ""); kickMessage = c == null ? "" : c.getString("kick-message", "");
        first = c != null && c.getString("position", "last").equalsIgnoreCase("first");
        List<Extra> parsed = new ArrayList<>(); if (c != null) for (Map<?,?> item : c.getMapList("extra-packs")) try { String u=String.valueOf(item.get("url")); Object hv=item.get("hash"); String h=hv == null ? "" : String.valueOf(hv); parsed.add(new Extra(UUID.nameUUIDFromBytes(u.getBytes()), URI.create(u), h)); } catch (Exception ignored) { plugin.getLogger().warning("Bỏ qua extra-packs không hợp lệ."); }
        extras = List.copyOf(parsed);
    }
    private void buildPack() throws IOException {
        Path zip = plugin.getDataFolder().toPath().resolve("gamelifehealthbar.zip"); Files.createDirectories(zip.getParent());
        Path source = plugin.getDataFolder().toPath().resolve("pack"); Files.createDirectories(source);
        Path meta = source.resolve("pack.mcmeta");
        Files.writeString(meta, "{\"pack\":{\"pack_format\":46,\"description\":\"GamelifeHealthBar\"}}\n");
        Path texture = source.resolve("assets/gamelifehealthbar/textures/font/healthbar.png"); Files.createDirectories(texture.getParent());
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(160, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = image.createGraphics();
        for (int frame=0; frame<10; frame++) { int x=frame*16; g.setColor(new java.awt.Color(35,35,35,220)); g.fillRect(x,1,15,6); g.setColor(new java.awt.Color(10,10,10,255)); g.drawRect(x,1,14,5); int fill=(int)Math.round(12*(frame+1)/10.0); g.setColor(frame<3?new java.awt.Color(220,55,55):frame<6?new java.awt.Color(230,190,45):new java.awt.Color(65,210,75)); g.fillRect(x+2,3,fill,2); }
        g.dispose(); javax.imageio.ImageIO.write(image,"png",texture.toFile());
        Path font = source.resolve("assets/gamelifehealthbar/font/healthbar.json"); Files.createDirectories(font.getParent());
        Files.writeString(font, "{\"providers\":[{\"type\":\"bitmap\",\"file\":\"gamelifehealthbar:font/healthbar.png\",\"ascent\":7,\"height\":8,\"chars\":[\"\\uE000\\uE001\\uE002\\uE003\\uE004\\uE005\\uE006\\uE007\\uE008\\uE009\"]}]}\n");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) { try (var files = Files.walk(source)) { for (Path f : files.filter(Files::isRegularFile).toList()) { out.putNextEntry(new ZipEntry(source.relativize(f).toString().replace('\\','/'))); Files.copy(f,out); out.closeEntry(); } } }
        try { hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(zip))); } catch (Exception e) { throw new IOException(e); }
    }
    private void startHost() throws IOException {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("resource-pack.host"); String bind = c == null ? "0.0.0.0" : c.getString("bind", "0.0.0.0"); int port = c == null ? 12001 : c.getInt("port",12001);
        String route = path.startsWith("/") ? path : "/"+path;
        server = HttpServer.create(new InetSocketAddress(bind,port),0); server.createContext(route, exchange -> {
            Path zip = plugin.getDataFolder().toPath().resolve("gamelifehealthbar.zip");
            if (!exchange.getRequestMethod().equals("GET") && !exchange.getRequestMethod().equals("HEAD")) { exchange.sendResponseHeaders(405,-1); exchange.close(); return; }
            if (exchange.getRequestMethod().equals("HEAD")) { exchange.getResponseHeaders().set("Content-Type","application/zip"); exchange.sendResponseHeaders(200,-1); exchange.close(); return; }
            exchange.getResponseHeaders().set("Content-Type","application/zip"); exchange.sendResponseHeaders(200,Files.size(zip)); try (OutputStream os=exchange.getResponseBody()) { Files.copy(zip,os); }
        }); server.setExecutor(Executors.newCachedThreadPool(r -> { Thread t=new Thread(r,"GamelifeHealthBar-PackHost"); t.setDaemon(true); return t; })); server.start();
        plugin.getLogger().info("Resource pack host: " + (publicUrl.isBlank() ? "http://<server-ip>:"+port+route : publicUrl));
    }
    void stop() { if (server != null) { server.stop(1); server=null; } }
    private void send(Player player) {
        if (delivery.equals("none")) return;
        try {
            URI uri = URI.create(delivery.equals("url") ? url : publicUrl);
            if (uri.getHost() == null || uri.toString().isBlank()) { plugin.getLogger().warning("Resource pack URL chưa hợp lệ; kiểm tra config.yml"); return; }
            ResourcePackInfo own = ResourcePackInfo.resourcePackInfo(UUID.nameUUIDFromBytes(uri.toString().getBytes()), uri, HexFormat.of().parseHex(hash));
            List<ResourcePackInfo> packs = new ArrayList<>();
            if (first) packs.add(own);
            for (Extra x : extras) packs.add(ResourcePackInfo.resourcePackInfo(x.id(), x.uri(), x.hash().isBlank() ? new byte[0] : HexFormat.of().parseHex(x.hash())));
            if (!first) packs.add(own);
            ResourcePackRequest.Builder request = ResourcePackRequest.resourcePackRequest().packs(packs).required(required);
            if (!prompt.isBlank()) request.prompt(legacy(prompt));
            player.sendResourcePacks(request.build());
        } catch (Exception e) { plugin.getLogger().warning("Gửi resource pack thất bại cho " + player.getName() + ": " + e.getMessage()); }
    }
    private Component legacy(String s) { return LegacyComponentSerializer.legacyAmpersand().deserialize(s); }
    @EventHandler public void onJoin(PlayerJoinEvent e) { if (sendOnJoin) send(e.getPlayer()); }
    @EventHandler public void onPackStatus(PlayerResourcePackStatusEvent e) { if (required && (e.getStatus() == PlayerResourcePackStatusEvent.Status.DECLINED || e.getStatus() == PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD)) e.getPlayer().kick(legacy(kickMessage.isBlank() ? "Bạn phải tải resource pack để vào server." : kickMessage)); }
}
