package dev.mcgps;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcgps.GeoMath.LatLon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/** Real-world map: shows where the player maps to and where the phone is. */
public class MapScreen extends Screen {
	private static final TileCache CACHE = new TileCache();
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private static final int TOP = 26;
	private static final int BOTTOM = 14;

	// View state survives closing and reopening the screen.
	private static int zoom = 17;
	private static double centerLat = Double.NaN, centerLon = Double.NaN;
	private static boolean follow = true;
	private static boolean settingsOpen;
	private static String searchText = "";

	private static final double MIN_KMH = 1, MAX_KMH = 300, LIMIT_MAX = 150;
	private static final int PANEL_W = 220, PANEL_H = 74;

	private EditBox search;
	private Button connectButton, styleButton, followButton, settingsButton;
	private SprintSlider sprintSlider;
	private LimitSlider limitSlider;
	private boolean dragging;
	private String notice;
	private long noticeUntil;

	/** Points the map at a place without following the player. */
	public static void viewAt(double lat, double lon, int z) {
		centerLat = lat;
		centerLon = lon;
		zoom = Math.max(2, Math.min(19, z));
		follow = false;
	}

	public MapScreen() {
		super(Component.literal("真实地图"));
	}

	@Override
	protected void init() {
		search = new EditBox(font, 8, 4, 150, 18, Component.literal("搜索地点"));
		search.setHint(Component.literal("搜索地点，回车定位"));
		search.setMaxLength(120);
		search.setValue(searchText);
		search.setResponder(v -> searchText = v);
		addRenderableWidget(search);
		int x = width - 8;
		followButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
			follow = !follow;
			updateLabels();
		}).bounds(x -= 64, 4, 64, 18).build());
		styleButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
			McGpsClient.CONFIG.mapStyle = "street".equals(McGpsClient.CONFIG.mapStyle) ? "satellite" : "street";
			McGpsClient.CONFIG.save();
			updateLabels();
		}).bounds(x -= 52, 4, 48, 18).build());
		connectButton = addRenderableWidget(Button.builder(Component.empty(), b -> McGpsClient.connectOrDisconnect())
				.bounds(x -= 76, 4, 72, 18).build());
		settingsButton = addRenderableWidget(Button.builder(Component.literal("速度"), b -> {
			settingsOpen = !settingsOpen;
			rebuildWidgets();
		}).bounds(x -= 44, 4, 40, 18).build());
		if (settingsOpen) {
			int px = panelX() + 8, py = TOP + 8;
			sprintSlider = addRenderableWidget(new SprintSlider(px, py, PANEL_W - 16));
			limitSlider = addRenderableWidget(new LimitSlider(px, py + 44, PANEL_W - 16));
		} else {
			sprintSlider = null;
			limitSlider = null;
		}
		updateLabels();
		if (Double.isNaN(centerLat)) {
			LatLon p = playerPoint();
			if (p == null) p = new LatLon(McGpsClient.CONFIG.anchorLat, McGpsClient.CONFIG.anchorLon);
			centerLat = p.lat();
			centerLon = p.lon();
		}
	}

	private int panelX() {
		return width - 8 - PANEL_W;
	}

	private boolean inPanel(double x, double y) {
		return settingsOpen && x >= panelX() && x < width - 8 && y >= TOP + 2 && y < TOP + 4 + PANEL_H;
	}

	private static double sprintKmh() {
		return McGpsClient.CONFIG.scale * McGpsClient.SPRINT_MS * 3.6;
	}

	private static double nice(double kmh) {
		return kmh < 10 ? Math.round(kmh * 10) / 10.0 : Math.round(kmh);
	}

	private static String num(double v) {
		return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
	}

	/** Phone speed while sprinting in Minecraft; changes the blocks-to-meters scale. */
	private static final class SprintSlider extends AbstractSliderButton {
		SprintSlider(int x, int y, int w) {
			super(x, y, w, 20, Component.empty(),
					Math.max(0, Math.min(1, Math.log(sprintKmh() / MIN_KMH) / Math.log(MAX_KMH / MIN_KMH))));
			updateMessage();
		}

		private double kmh() {
			return nice(MIN_KMH * Math.pow(MAX_KMH / MIN_KMH, value));
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal("疾跑时手机速度：" + num(kmh()) + " km/h"));
		}

		/** Follows changes made elsewhere (commands), unless the user is holding the slider. */
		void sync() {
			if (isFocused() || Math.abs(kmh() - nice(sprintKmh())) < 0.05) return;
			value = Math.max(0, Math.min(1, Math.log(sprintKmh() / MIN_KMH) / Math.log(MAX_KMH / MIN_KMH)));
			updateMessage();
		}

		@Override
		protected void applyValue() {
			McGpsClient.rescale(kmh() / (McGpsClient.SPRINT_MS * 3.6));
		}
	}

	/** Upper bound for the phone's speed; 0 means unlimited. */
	private static final class LimitSlider extends AbstractSliderButton {
		LimitSlider(int x, int y, int w) {
			super(x, y, w, 20, Component.empty(), Math.min(1, McGpsClient.CONFIG.maxSpeedKmh / LIMIT_MAX));
			updateMessage();
		}

		private double kmh() {
			return Math.round(value * LIMIT_MAX);
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal(kmh() == 0 ? "最高速度：不限" : "最高速度：" + num(kmh()) + " km/h"));
		}

		void sync() {
			if (isFocused() || kmh() == Math.round(McGpsClient.CONFIG.maxSpeedKmh)) return;
			value = Math.min(1, McGpsClient.CONFIG.maxSpeedKmh / LIMIT_MAX);
			updateMessage();
		}

		@Override
		protected void applyValue() {
			McGpsClient.CONFIG.maxSpeedKmh = kmh();
			McGpsClient.CONFIG.save();
		}
	}

	private void updateLabels() {
		if (connectButton == null) return;
		connectButton.setMessage(Component.literal(switch (McGpsClient.BRIDGE.state()) {
			case IDLE -> "连接手机";
			case STOPPING -> "正在断开";
			case INSTALLING -> "取消";
			default -> "断开手机";
		}));
		connectButton.active = McGpsClient.BRIDGE.state() != PhoneBridge.State.STOPPING;
		styleButton.setMessage(Component.literal("street".equals(McGpsClient.CONFIG.mapStyle) ? "街道" : "卫星"));
		followButton.setMessage(Component.literal(follow ? "跟随：开" : "跟随：关"));
		settingsButton.setMessage(Component.literal(settingsOpen ? "收起" : "速度"));
	}

	@Override
	public void tick() {
		updateLabels();
		if (sprintSlider != null) sprintSlider.sync();
		if (limitSlider != null) limitSlider.sync();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private static LatLon playerPoint() {
		LatLon c = McGpsClient.FOLLOWER.current();
		return c != null ? c : McGpsClient.FOLLOWER.target();
	}

	private float tileSize() {
		return (float) (256.0 / Minecraft.getInstance().getWindow().getGuiScale());
	}

	private float sx(double lon) {
		return width / 2f + (float) ((GeoMath.tileX(lon, zoom) - GeoMath.tileX(centerLon, zoom)) * tileSize());
	}

	private float sy(double lat) {
		int mapCenter = TOP + (height - TOP - BOTTOM) / 2;
		return mapCenter + (float) ((GeoMath.tileY(lat, zoom) - GeoMath.tileY(centerLat, zoom)) * tileSize());
	}

	private LatLon geoAt(double x, double y) {
		int mapCenter = TOP + (height - TOP - BOTTOM) / 2;
		double tx = GeoMath.tileX(centerLon, zoom) + (x - width / 2.0) / tileSize();
		double ty = GeoMath.tileY(centerLat, zoom) + (y - mapCenter) / tileSize();
		double n = 1 << zoom;
		ty = Math.max(0, Math.min(n, ty));
		double lon = ((GeoMath.lonOf(tx, zoom) + 540) % 360) - 180;
		return new LatLon(Math.max(-85, Math.min(85, GeoMath.latOf(ty, zoom))), lon);
	}

	private boolean inMap(double x, double y) {
		return y >= TOP && y < height - BOTTOM;
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
		// The map covers the whole screen.
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		if (follow) {
			LatLon p = playerPoint();
			if (p != null) {
				centerLat = p.lat();
				centerLon = p.lon();
			}
		}
		g.fill(0, 0, width, height, 0xFF1B1E22);
		g.enableScissor(0, TOP, width, height - BOTTOM);
		drawTiles(g);
		drawMarkers(g);
		g.disableScissor();

		if (settingsOpen) {
			int px = panelX(), py = TOP + 2;
			g.fill(px, py, px + PANEL_W, py + PANEL_H + 2, 0xE0101215);
			double scale = McGpsClient.CONFIG.scale;
			String info = "步行 ≈ " + num(nice(scale * McGpsClient.WALK_MS * 3.6)) + " km/h   1 格 = "
					+ (scale < 10 ? String.format(Locale.ROOT, "%.2f", scale) : num(Math.round(scale))) + " 米";
			g.drawString(font, info, px + 9, py + 32, 0xFFB8BEC6, false);
		}
		g.fill(0, 0, width, TOP, 0xE0101215);
		g.fill(0, height - BOTTOM, width, height, 0xE0101215);
		String style = McGpsClient.CONFIG.mapStyle;
		String credit = "street".equals(style) ? "© OpenStreetMap contributors" : "Imagery © Esri";
		g.drawString(font, credit, width - 6 - font.width(credit), height - 11, 0xFF9AA0A6, false);
		String status = notice != null && System.currentTimeMillis() < noticeUntil ? notice : statusLine();
		int maxStatus = width - 16 - font.width(credit);
		g.drawString(font, font.plainSubstrByWidth(status, maxStatus), 6, height - 11, 0xFFE6E6E6, false);
		if (inMap(mouseX, mouseY)) {
			String hint = "拖动平移 · 滚轮缩放 · 右键把脚下设为这里";
			g.drawString(font, hint, (width - font.width(hint)) / 2, height - BOTTOM - 12, 0xCCFFFFFF, true);
		}
		super.render(g, mouseX, mouseY, delta);
	}

	private String statusLine() {
		PhoneBridge b = McGpsClient.BRIDGE;
		String s = b.status();
		if (b.ready()) {
			s = (McGpsClient.FOLLOWER.paused() ? "同步已暂停" : b.stalled() ? "手机未响应"
					: String.format(Locale.ROOT, "同步中 %.1f km/h", McGpsClient.FOLLOWER.speedKmh()))
					+ "   手机：" + McGpsClient.fmt(b.confirmed());
		}
		String hold = McGpsClient.FOLLOWER.hold();
		return hold != null && b.ready() ? s + "   （" + hold + "）" : s;
	}

	private void drawTiles(GuiGraphics g) {
		String style = McGpsClient.CONFIG.mapStyle;
		float ts = tileSize();
		int n = 1 << zoom;
		double cx = GeoMath.tileX(centerLon, zoom), cy = GeoMath.tileY(centerLat, zoom);
		int mapCenter = TOP + (height - TOP - BOTTOM) / 2;
		int x0 = (int) Math.floor(cx - (width / 2f) / ts), x1 = (int) Math.floor(cx + (width / 2f) / ts);
		int y0 = (int) Math.floor(cy - (mapCenter - TOP) / ts), y1 = (int) Math.floor(cy + (height - BOTTOM - mapCenter) / ts);
		Matrix3x2fStack pose = g.pose();
		for (int ty = y0; ty <= y1; ty++) {
			if (ty < 0 || ty >= n) continue;
			for (int tx = x0; tx <= x1; tx++) {
				int wx = Math.floorMod(tx, n);
				float px = (float) (width / 2f + (tx - cx) * ts);
				float py = (float) (mapCenter + (ty - cy) * ts);
				Identifier id = CACHE.get(style, zoom, wx, ty);
				int u = 0, v = 0, region = 256;
				if (id == null) {
					// Show a lower-zoom tile scaled up until this one arrives.
					for (int k = 1; k <= 5 && zoom - k >= 0 && id == null; k++) {
						int f = 1 << k;
						id = CACHE.peek(style, zoom - k, wx >> k, ty >> k);
						if (id != null) {
							region = 256 / f;
							u = (wx & (f - 1)) * region;
							v = (ty & (f - 1)) * region;
						}
					}
				}
				if (id == null) {
					g.fill((int) px, (int) py, (int) Math.ceil(px + ts), (int) Math.ceil(py + ts), 0xFF2A2E33);
					continue;
				}
				pose.pushMatrix();
				pose.translate(px, py);
				pose.scale(ts / 256f, ts / 256f);
				g.blit(RenderPipelines.GUI_TEXTURED, id, 0, 0, u, v, 257, 257, region, region, 256, 256);
				pose.popMatrix();
			}
		}
	}

	private void drawMarkers(GuiGraphics g) {
		LatLon phone = McGpsClient.BRIDGE.confirmed();
		if (phone != null) {
			int x = Math.round(sx(phone.lon())), y = Math.round(sy(phone.lat()));
			g.fill(x - 6, y - 6, x + 7, y + 7, 0xFF0E5A2C);
			g.fill(x - 5, y - 5, x + 6, y + 6, 0xFF34C759);
			g.fill(x - 3, y - 3, x + 4, y + 4, 0xFF0E5A2C);
		}
		LatLon me = playerPoint();
		Minecraft mc = Minecraft.getInstance();
		if (me != null && mc.player != null) {
			float fx = sx(me.lon()), fy = sy(me.lat());
			int x = Math.round(fx), y = Math.round(fy);
			// Facing: Minecraft yaw 180 is north (-Z).
			double b = Math.toRadians(mc.player.getYRot() + 180 + McGpsClient.CONFIG.rotation);
			for (int i = 5; i <= 12; i++) {
				int hx = Math.round(fx + (float) Math.sin(b) * i), hy = Math.round(fy - (float) Math.cos(b) * i);
				g.fill(hx - 1, hy - 1, hx + 2, hy + 2, 0xFFFFFFFF);
			}
			g.fill(x - 4, y - 4, x + 5, y + 5, 0xFFFFFFFF);
			g.fill(x - 3, y - 3, x + 4, y + 4, 0xFF2F80ED);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (super.mouseClicked(e, doubleClick)) return true;
		if (inPanel(e.x(), e.y())) return true;
		if (!inMap(e.x(), e.y())) return false;
		setFocused(null);
		if (e.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			dragging = true;
			return true;
		}
		if (e.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
			LatLon p = geoAt(e.x(), e.y());
			if (McGpsClient.anchorHere(p)) {
				say("脚下已对应 " + McGpsClient.fmt(p));
			} else {
				say("进入世界后才能设置位置");
			}
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		dragging = false;
		return super.mouseReleased(e);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		if (dragging && e.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			follow = false;
			updateLabels();
			int mapCenter = TOP + (height - TOP - BOTTOM) / 2;
			LatLon c = geoAt(width / 2.0 - dx, mapCenter - dy);
			centerLat = c.lat();
			centerLon = c.lon();
			return true;
		}
		return super.mouseDragged(e, dx, dy);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double h, double v) {
		if (!inMap(x, y) || inPanel(x, y) || v == 0) return super.mouseScrolled(x, y, h, v);
		int next = Math.max(2, Math.min(19, zoom + (v > 0 ? 1 : -1)));
		if (next == zoom) return true;
		if (follow) {
			zoom = next;
			return true;
		}
		LatLon under = geoAt(x, y);
		zoom = next;
		// Keep the point under the cursor fixed.
		int mapCenter = TOP + (height - TOP - BOTTOM) / 2;
		double tx = GeoMath.tileX(under.lon(), zoom) - (x - width / 2.0) / tileSize();
		double ty = GeoMath.tileY(under.lat(), zoom) - (y - mapCenter) / tileSize();
		centerLon = ((GeoMath.lonOf(tx, zoom) + 540) % 360) - 180;
		centerLat = Math.max(-85, Math.min(85, GeoMath.latOf(Math.max(0, Math.min(1 << zoom, ty)), zoom)));
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (search != null && search.isFocused()
				&& (e.key() == GLFW.GLFW_KEY_ENTER || e.key() == GLFW.GLFW_KEY_KP_ENTER)) {
			runSearch(search.getValue().trim());
			return true;
		}
		return super.keyPressed(e);
	}

	private void runSearch(String q) {
		if (q.isEmpty()) return;
		say("正在搜索 " + q + "…");
		String url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&accept-language=zh-CN&q="
				+ URLEncoder.encode(q, StandardCharsets.UTF_8);
		HttpRequest req = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(15))
				.header("User-Agent", TileCache.USER_AGENT)
				.build();
		HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).whenComplete((res, err) ->
				Minecraft.getInstance().execute(() -> {
					try {
						if (err != null || res.statusCode() != 200) throw new IllegalStateException();
						JsonArray arr = JsonParser.parseString(res.body()).getAsJsonArray();
						if (arr.isEmpty()) {
							say("没有找到 " + q);
							return;
						}
						JsonObject o = arr.get(0).getAsJsonObject();
						LatLon p = new LatLon(Double.parseDouble(o.get("lat").getAsString()), Double.parseDouble(o.get("lon").getAsString()));
						String name = o.get("display_name").getAsString().split(",")[0].trim();
						centerLat = p.lat();
						centerLon = p.lon();
						zoom = Math.max(zoom, 16);
						if (McGpsClient.anchorHere(p)) {
							follow = true;
							say("已定位到 " + name + "，脚下对应这里");
							McGpsClient.chat("脚下已对应 " + name + "（" + McGpsClient.fmt(p) + "）");
						} else {
							follow = false;
							say("找到 " + name);
						}
						updateLabels();
					} catch (Exception ex) {
						say("搜索失败，请检查网络");
					}
				}));
	}

	private void say(String s) {
		notice = s;
		noticeUntil = System.currentTimeMillis() + 5000;
	}
}
