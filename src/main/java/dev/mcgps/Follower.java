package dev.mcgps;

import dev.mcgps.GeoMath.LatLon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;

/** Turns the player's block position into the phone position, every client tick. */
public final class Follower {
	private final GeoConfig config;
	private final PhoneBridge bridge;
	private LatLon current;
	private LatLon lastSent;
	private long lastSendMs;
	private boolean paused;
	private String hold;
	private double speedKmh;

	public Follower(GeoConfig config, PhoneBridge bridge) {
		this.config = config;
		this.bridge = bridge;
	}

	public LatLon current() { return current; }
	public boolean paused() { return paused; }
	/** Smoothed speed of the phone position, km/h. */
	public double speedKmh() { return speedKmh < 0.05 ? 0 : speedKmh; }
	/** Why the phone position is held in place, or null. */
	public String hold() { return hold; }

	public void setPaused(boolean paused) {
		this.paused = paused;
		if (!paused) bridge.retry();
	}

	/** Next update jumps straight to the new mapping instead of walking there. */
	public void reset() {
		current = null;
		lastSent = null;
	}

	/** The real-world point the player's feet map to right now, or null. */
	public LatLon target() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) return null;
		double x = player.getX(), z = player.getZ();
		var dim = player.level().dimension();
		if (dim == Level.NETHER) {
			if ("pause".equals(config.nether)) return null;
			x *= 8;
			z *= 8;
		} else if (dim == Level.END) {
			return null;
		}
		return GeoMath.toGeo(config, x, z);
	}

	public void tick() {
		// Game time: one client tick lasts 1/tickrate seconds (20 unless /tick rate changed it).
		Minecraft mc = Minecraft.getInstance();
		double dt = 1.0 / (mc.level != null ? Math.max(1f, mc.level.tickRateManager().tickrate()) : 20f);
		LatLon t = target();
		if (mc.player == null) {
			hold = "未进入世界";
		} else if (t == null) {
			hold = mc.player.level().dimension() == Level.END ? "末地中保持位置" : "下界中保持位置";
		} else {
			hold = null;
			LatLon prev = current;
			if (current == null || config.maxSpeedKmh <= 0) {
				current = t;
			} else {
				current = GeoMath.approach(current, t, config.maxSpeedKmh / 3.6 * dt);
			}
			double inst = prev == null ? 0 : GeoMath.distance(prev, current) / dt * 3.6;
			speedKmh = speedKmh * 0.85 + inst * 0.15;
		}
		if (hold != null) speedKmh *= 0.85;

		if (!bridge.ready() || paused || current == null) return;
		long now = System.currentTimeMillis();
		long interval = (long) (1000 / config.sendHz);
		if (now - lastSendMs < interval) return;
		boolean moved = lastSent == null || GeoMath.distance(current, lastSent) > 0.05;
		if (moved || now - lastSendMs >= 1000) {
			if (bridge.send(current)) {
				lastSent = current;
				lastSendMs = now;
			}
		}
	}
}
