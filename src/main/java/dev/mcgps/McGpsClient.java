package dev.mcgps;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.mcgps.GeoMath.LatLon;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class McGpsClient implements ClientModInitializer {
	public static final String MOD_ID = "mcgps";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

	public static GeoConfig CONFIG;
	public static PhoneBridge BRIDGE;
	public static Follower FOLLOWER;

	private static KeyMapping mapKey;
	private static KeyMapping pauseKey;
	private static boolean openMapNextTick;

	@Override
	public void onInitializeClient() {
		CONFIG = GeoConfig.load();
		BRIDGE = new PhoneBridge(McGpsClient::chat);
		FOLLOWER = new Follower(CONFIG, BRIDGE);

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		mapKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.mcgps.map", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, category));
		pauseKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.mcgps.pause", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (mapKey.consumeClick()) {
				if (mc.screen == null) mc.setScreen(new MapScreen());
			}
			while (pauseKey.consumeClick()) togglePause();
			if (openMapNextTick) {
				openMapNextTick = false;
				mc.setScreen(new MapScreen());
			}
			BRIDGE.tick();
			FOLLOWER.tick();
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> BRIDGE.shutdown());
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> registerCommands(dispatcher));
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "status"), (g, delta) -> renderHud(g));
	}

	public static void chat(String message) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> mc.gui.getChat().addMessage(
				Component.literal("[MC Run] ").withStyle(ChatFormatting.AQUA).append(Component.literal(message).withStyle(ChatFormatting.WHITE))));
	}

	public static void togglePause() {
		FOLLOWER.setPaused(!FOLLOWER.paused());
		chat(FOLLOWER.paused() ? "同步已暂停，手机停在当前位置" : "同步已继续");
	}

	public static void connectOrDisconnect() {
		if (BRIDGE.state() == PhoneBridge.State.IDLE) BRIDGE.connect(CONFIG.python);
		else BRIDGE.disconnect();
	}

	/** Makes the block the player stands on map to the given real-world point. */
	public static boolean anchorHere(LatLon point) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !point.valid()) return false;
		double x = mc.player.getX(), z = mc.player.getZ();
		if (mc.player.level().dimension() == net.minecraft.world.level.Level.NETHER) {
			x *= 8;
			z *= 8;
		}
		CONFIG.anchorX = x;
		CONFIG.anchorZ = z;
		CONFIG.anchorLat = point.lat();
		CONFIG.anchorLon = point.lon();
		CONFIG.save();
		FOLLOWER.reset();
		return true;
	}

	public static String fmt(LatLon p) {
		return p == null ? "—" : String.format(Locale.ROOT, "%.6f, %.6f", p.lat(), p.lon());
	}

	private static void renderHud(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		if (!CONFIG.hud || mc.options.hideGui || mc.screen instanceof MapScreen) return;
		PhoneBridge.State s = BRIDGE.state();
		if (s == PhoneBridge.State.IDLE) return;
		String line1;
		int color;
		switch (s) {
			case READY -> {
				if (BRIDGE.stalled()) { line1 = "● 手机未响应"; color = 0xFFFF5555; }
				else if (FOLLOWER.paused()) { line1 = "● 同步已暂停"; color = 0xFFFFCC55; }
				else if (FOLLOWER.hold() != null) { line1 = "● " + FOLLOWER.hold(); color = 0xFFFFCC55; }
				else { line1 = String.format(Locale.ROOT, "● 手机定位同步中  %.1f km/h", FOLLOWER.speedKmh()); color = 0xFF55FF88; }
			}
			case STOPPING -> { line1 = "● 正在恢复真实定位"; color = 0xFFFFCC55; }
			default -> { line1 = "● " + BRIDGE.status(); color = 0xFFFFCC55; }
		}
		String line2 = s == PhoneBridge.State.READY ? fmt(BRIDGE.confirmed() != null ? BRIDGE.confirmed() : FOLLOWER.current()) : null;
		var font = mc.font;
		int w = font.width(line1);
		if (line2 != null) w = Math.max(w, font.width(line2));
		int h = line2 == null ? 12 : 22;
		g.fill(4, 4, 10 + w, 4 + h + 2, 0x88000000);
		g.drawString(font, line1, 7, 7, color, false);
		if (line2 != null) g.drawString(font, line2, 7, 17, 0xFFDDDDDD, false);
	}

	private static int ok(CommandContext<FabricClientCommandSource> ctx, String msg) {
		ctx.getSource().sendFeedback(Component.literal(msg));
		return 1;
	}

	private static void registerCommands(CommandDispatcher<FabricClientCommandSource> d) {
		var root = d.register(literal("mcrun")
				.executes(ctx -> status(ctx))
				.then(literal("status").executes(McGpsClient::status))
				.then(literal("connect").executes(ctx -> { BRIDGE.connect(CONFIG.python); return 1; }))
				.then(literal("disconnect").executes(ctx -> { BRIDGE.disconnect(); return 1; }))
				.then(literal("map").executes(ctx -> { openMapNextTick = true; return 1; }))
				.then(literal("pause").executes(ctx -> { if (!FOLLOWER.paused()) togglePause(); return 1; }))
				.then(literal("resume").executes(ctx -> { FOLLOWER.setPaused(false); return ok(ctx, "同步已继续"); }))
				.then(literal("here")
						.then(argument("lat", DoubleArgumentType.doubleArg(-85, 85))
								.then(argument("lon", DoubleArgumentType.doubleArg(-180, 180))
										.executes(ctx -> {
											LatLon p = new LatLon(DoubleArgumentType.getDouble(ctx, "lat"), DoubleArgumentType.getDouble(ctx, "lon"));
											return anchorHere(p) ? ok(ctx, "脚下已对应 " + fmt(p)) : 0;
										}))))
				.then(literal("scale")
						.then(argument("meters", DoubleArgumentType.doubleArg(0.001, 100000)).executes(ctx -> {
							double v = DoubleArgumentType.getDouble(ctx, "meters");
							rescale(v);
							return ok(ctx, String.format(Locale.ROOT, "比例：1 格 = %s 米", trim(v)));
						})))
				.then(literal("rotation")
						.then(argument("degrees", DoubleArgumentType.doubleArg(-360, 360)).executes(ctx -> {
							double v = DoubleArgumentType.getDouble(ctx, "degrees");
							rotate(v);
							return ok(ctx, "朝向：MC 北方顺时针转 " + trim(v) + "° 对应真北");
						})))
				.then(literal("maxspeed")
						.then(argument("kmh", DoubleArgumentType.doubleArg(0, 100000)).executes(ctx -> {
							CONFIG.maxSpeedKmh = DoubleArgumentType.getDouble(ctx, "kmh");
							CONFIG.save();
							return ok(ctx, CONFIG.maxSpeedKmh == 0 ? "最高速度：不限" : "最高速度：" + trim(CONFIG.maxSpeedKmh) + " km/h");
						})))
				.then(literal("rate")
						.then(argument("hz", DoubleArgumentType.doubleArg(0.5, 10)).executes(ctx -> {
							CONFIG.sendHz = DoubleArgumentType.getDouble(ctx, "hz");
							CONFIG.save();
							return ok(ctx, "发送频率：每秒 " + trim(CONFIG.sendHz) + " 次");
						})))
				.then(literal("nether")
						.then(literal("scale8").executes(ctx -> { CONFIG.nether = "scale8"; CONFIG.save(); FOLLOWER.reset(); return ok(ctx, "下界：坐标 ×8"); }))
						.then(literal("pause").executes(ctx -> { CONFIG.nether = "pause"; CONFIG.save(); return ok(ctx, "下界：保持位置"); })))
				.then(literal("hud")
						.then(literal("on").executes(ctx -> { CONFIG.hud = true; CONFIG.save(); return ok(ctx, "状态角标：开"); }))
						.then(literal("off").executes(ctx -> { CONFIG.hud = false; CONFIG.save(); return ok(ctx, "状态角标：关"); })))
				.then(literal("python")
						.then(argument("path", StringArgumentType.greedyString()).executes(ctx -> {
							CONFIG.python = StringArgumentType.getString(ctx, "path");
							CONFIG.save();
							return ok(ctx, "Python：" + CONFIG.python);
						})))
				.then(literal("reinstall").executes(ctx -> {
					if (BRIDGE.state() != PhoneBridge.State.IDLE) return ok(ctx, "请先断开手机");
					try {
						WorkerRuntime.reinstall();
						return ok(ctx, "已删除运行环境，下次连接时重新安装");
					} catch (Exception e) {
						return ok(ctx, "删除失败：" + e.getMessage());
					}
				})));
		// Old name keeps working.
		d.register(literal("mcgps").executes(McGpsClient::status).redirect(root));
	}

	/** Minecraft walking / sprinting speed on flat ground, m/s. */
	public static final double WALK_MS = 4.317, SPRINT_MS = 5.612;

	/** Keeps the player's current real-world point fixed while changing scale. */
	public static void rescale(double scale) {
		LatLon here = FOLLOWER.target();
		CONFIG.scale = scale;
		if (here != null) anchorHere(here);
		else CONFIG.save();
		FOLLOWER.reset();
	}

	private static void rotate(double degrees) {
		LatLon here = FOLLOWER.target();
		CONFIG.rotation = degrees;
		if (here != null) anchorHere(here);
		else CONFIG.save();
		FOLLOWER.reset();
	}

	private static String trim(double v) {
		return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
	}

	private static int status(CommandContext<FabricClientCommandSource> ctx) {
		LatLon t = FOLLOWER.target();
		ok(ctx, "状态：" + BRIDGE.status() + (FOLLOWER.paused() ? "（同步已暂停）" : ""));
		ok(ctx, "脚下对应：" + fmt(t) + "    手机已确认：" + fmt(BRIDGE.confirmed()));
		ok(ctx, String.format(Locale.ROOT, "锚点：方块 (%.1f, %.1f) = %s    1 格 = %s 米    朝向 %s°    最高速度 %s",
				CONFIG.anchorX, CONFIG.anchorZ, fmt(new LatLon(CONFIG.anchorLat, CONFIG.anchorLon)),
				trim(CONFIG.scale), trim(CONFIG.rotation), CONFIG.maxSpeedKmh == 0 ? "不限" : trim(CONFIG.maxSpeedKmh) + " km/h"));
		return 1;
	}
}
