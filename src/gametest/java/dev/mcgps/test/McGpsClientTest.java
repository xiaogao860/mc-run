package dev.mcgps.test;

import dev.mcgps.GeoMath;
import dev.mcgps.GeoMath.LatLon;
import dev.mcgps.MapScreen;
import dev.mcgps.McGpsClient;
import dev.mcgps.PhoneBridge;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

public class McGpsClientTest implements FabricClientGameTest {
	private static void check(boolean ok, String what) {
		if (!ok) throw new AssertionError(what);
		System.out.println("[mcgps-test] OK " + what);
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientWorld().waitForChunksRender();
			sp.getServer().runCommand("gamemode creative @a");
			sp.getServer().runCommand("tp @a 0 120 0");
			ctx.waitTicks(20);

			// Client command: block under the player = Tiananmen.
			ctx.runOnClient(mc -> mc.player.connection.sendCommand("mcgps here 39.9087 116.3975"));
			ctx.waitTicks(5);
			check(Math.abs(McGpsClient.CONFIG.anchorLat - 39.9087) < 1e-9, "here command sets anchor");
			LatLon here = ctx.computeOnClient(mc -> McGpsClient.FOLLOWER.target());
			check(GeoMath.distance(here, new LatLon(39.9087, 116.3975)) < 0.5, "player maps to anchor");

			// Walk 300 blocks north, 200 east.
			sp.getServer().runCommand("tp @a 200 120 -300");
			ctx.waitTicks(20);
			LatLon moved = ctx.computeOnClient(mc -> McGpsClient.FOLLOWER.target());
			double d = GeoMath.distance(new LatLon(39.9087, 116.3975), moved);
			check(Math.abs(d - Math.hypot(200, 300)) < 1, "300N/200E maps to " + Math.round(d) + " m");
			check(moved.lat() > 39.9087 && moved.lon() > 116.3975, "direction is north-east");
			check(ctx.computeOnClient(mc -> McGpsClient.FOLLOWER.current()) != null, "follower tracks position");

			ctx.runOnClient(mc -> mc.player.connection.sendCommand("mcgps status"));
			ctx.setScreen(MapScreen::new);
			ctx.waitForScreen(MapScreen.class);
			ctx.waitTicks(240);
			System.out.println("[mcgps-test] shot " + ctx.takeScreenshot("mcgps-map-satellite"));
			ctx.clickScreenButton("卫星");
			ctx.waitTicks(240);
			System.out.println("[mcgps-test] shot " + ctx.takeScreenshot("mcgps-map-street"));
			ctx.clickScreenButton("街道");

			// Speed panel and speed limit.
			ctx.clickScreenButton("速度");
			ctx.waitTicks(10);
			System.out.println("[mcgps-test] shot " + ctx.takeScreenshot("mcgps-speed-panel"));
			ctx.runOnClient(mc -> McGpsClient.rescale(10 / (McGpsClient.SPRINT_MS * 3.6)));
			LatLon kept = ctx.computeOnClient(mc -> McGpsClient.FOLLOWER.target());
			check(GeoMath.distance(kept, moved) < 0.5, "rescale keeps the real-world point under the player");
			ctx.runOnClient(mc -> { McGpsClient.CONFIG.maxSpeedKmh = 36; });
			sp.getServer().runCommand("tp @a 600 120 -300");
			ctx.waitTicks(60);
			double v = ctx.computeOnClient(mc -> McGpsClient.FOLLOWER.speedKmh());
			check(Math.abs(v - 36) < 2, "speed limit 36 km/h, measured " + Math.round(v));
			System.out.println("[mcgps-test] shot " + ctx.takeScreenshot("mcgps-speed-moving"));
			ctx.runOnClient(mc -> { McGpsClient.CONFIG.maxSpeedKmh = 0; });
			ctx.clickScreenButton("收起");

			// Talks to a real phone if one is plugged in, so only with MCGPS_TEST_CONNECT=1 and no iPhone attached.
			if (!"1".equals(System.getenv("MCGPS_TEST_CONNECT"))) {
				ctx.setScreen(() -> null);
				return;
			}
			ctx.clickScreenButton("连接手机");
			ctx.waitFor(mc -> McGpsClient.BRIDGE.state() == PhoneBridge.State.CONNECTING
					|| McGpsClient.BRIDGE.state() == PhoneBridge.State.IDLE, 20 * 60 * 10);
			System.out.println("[mcgps-test] after install: " + McGpsClient.BRIDGE.state() + " / " + McGpsClient.BRIDGE.status());
			ctx.waitFor(mc -> McGpsClient.BRIDGE.state() == PhoneBridge.State.IDLE, 20 * 60 * 4);
			System.out.println("[mcgps-test] final status: " + McGpsClient.BRIDGE.status());
			ctx.waitTicks(20);
			System.out.println("[mcgps-test] shot " + ctx.takeScreenshot("mcgps-map-noPhone"));
			ctx.setScreen(() -> null);
			ctx.waitTicks(10);
			System.out.println("[mcgps-test] shot " + ctx.takeScreenshot("mcgps-hud-chat"));
		}
	}
}
