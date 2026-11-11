package dev.mcgps;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcgps.GeoMath.LatLon;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Owns the Python worker process. Same protocol and rules as the desktop app:
 * one "set" in flight at a time, 12 s without confirmation pauses sending,
 * "stop" always makes the worker clear the simulated location.
 */
public final class PhoneBridge {
	public enum State { IDLE, INSTALLING, CONNECTING, READY, STOPPING }

	private static final long ACK_TIMEOUT_MS = 12_000;
	private static final long CONNECT_TIMEOUT_MS = 180_000;

	private final Consumer<String> notify;
	private volatile State state = State.IDLE;
	private volatile String status = "手机未连接";
	private volatile LatLon confirmed;
	private volatile boolean pending;
	private volatile long pendingSince;
	private volatile long connectStarted;
	private volatile boolean stalled;
	private volatile boolean cleared;
	private volatile boolean workerError;

	private Process process;
	private OutputStream stdin;
	private WorkerRuntime.Cancel cancel;

	public PhoneBridge(Consumer<String> notify) {
		this.notify = notify;
	}

	public State state() { return state; }
	public String status() { return status; }
	public LatLon confirmed() { return confirmed; }
	public boolean ready() { return state == State.READY; }
	public boolean stalled() { return stalled; }

	public synchronized void connect(String python) {
		if (state != State.IDLE) {
			notify.accept(status);
			return;
		}
		state = State.INSTALLING;
		cleared = false;
		workerError = false;
		stalled = false;
		confirmed = null;
		pending = false;
		connectStarted = System.currentTimeMillis();
		WorkerRuntime.Cancel c = new WorkerRuntime.Cancel();
		cancel = c;
		setStatus(WorkerRuntime.installed() ? "正在启动手机连接…" : "首次使用：正在准备运行环境…", true);
		Thread t = new Thread(() -> launch(python, c), "mcgps-connect");
		t.setDaemon(true);
		t.start();
	}

	private void launch(String python, WorkerRuntime.Cancel c) {
		try {
			List<String> cmd = WorkerRuntime.prepare(python, s -> setStatus(s, false), c);
			setStatus("正在启动手机连接…", false);
			synchronized (this) {
				if (c.cancelled()) throw new InterruptedException();
				ProcessBuilder pb = new ProcessBuilder(cmd);
				pb.environment().put("PYTHONIOENCODING", "utf-8");
				pb.environment().put("PYTHONUNBUFFERED", "1");
				pb.environment().put("PYTHONWARNINGS", "ignore");
				process = pb.start();
				stdin = process.getOutputStream();
				state = State.CONNECTING;
				connectStarted = System.currentTimeMillis();
			}
			Process p = process;
			Thread err = new Thread(() -> drain(p), "mcgps-stderr");
			err.setDaemon(true);
			err.start();
			readLoop(p);
		} catch (InterruptedException e) {
			finish("已取消连接");
		} catch (Exception e) {
			McGpsClient.LOG.warn("Phone worker failed", e);
			finish("连接失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
		}
	}

	private void readLoop(Process p) throws IOException, InterruptedException {
		try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				JsonObject j;
				try {
					j = JsonParser.parseString(line).getAsJsonObject();
				} catch (Exception e) {
					continue;
				}
				String event = j.has("event") ? j.get("event").getAsString() : "";
				switch (event) {
					case "status" -> setStatus(j.get("message").getAsString(), false);
					case "ready" -> {
						if (state == State.CONNECTING) state = State.READY;
						setStatus("手机已连接，正在同步位置", true);
					}
					case "position" -> {
						confirmed = new LatLon(j.get("lat").getAsDouble(), j.get("lon").getAsDouble());
						pending = false;
						if (stalled) {
							stalled = false;
							setStatus("手机已恢复响应", true);
						}
					}
					case "cleared" -> {
						cleared = true;
						McGpsClient.LOG.info("Phone location restored to real GPS");
						setStatus("已恢复真实定位，请在手机地图上确认", true);
					}
					case "error" -> {
						workerError = true;
						setStatus(j.get("message").getAsString(), true);
					}
					default -> {}
				}
			}
		}
		p.waitFor(5, TimeUnit.SECONDS);
		finish(null);
	}

	private void drain(Process p) {
		try {
			StringBuilder tail = new StringBuilder();
			try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = r.readLine()) != null) {
					tail.append(line).append('\n');
					if (tail.length() > 4000) tail.delete(0, tail.length() - 4000);
				}
			}
			if (!tail.isEmpty()) McGpsClient.LOG.info("Phone worker stderr:\n{}", tail);
		} catch (IOException ignored) {
		}
	}

	private synchronized void finish(String message) {
		State before = state;
		state = State.IDLE;
		process = null;
		stdin = null;
		cancel = null;
		pending = false;
		confirmed = null;
		stalled = false;
		if (message != null) {
			setStatus(message, true);
		} else if (!cleared && !workerError && before != State.INSTALLING) {
			setStatus("手机连接已断开，恢复定位未确认。请重新连接后再断开，或重启手机。", true);
		} else if (!workerError) {
			status = cleared ? "手机未连接（已恢复真实定位）" : "手机未连接";
		}
	}

	/** Sends a position if nothing is waiting for confirmation. */
	public synchronized boolean send(LatLon p) {
		if (state != State.READY || pending || stdin == null || stalled) return false;
		if (!p.valid()) return false;
		String line = String.format(Locale.ROOT, "{\"action\":\"set\",\"lat\":%.10f,\"lon\":%.10f}\n", p.lat(), p.lon());
		try {
			stdin.write(line.getBytes(StandardCharsets.UTF_8));
			stdin.flush();
		} catch (IOException e) {
			return false;
		}
		pending = true;
		pendingSince = System.currentTimeMillis();
		return true;
	}

	/** Called every client tick. */
	public void tick() {
		long now = System.currentTimeMillis();
		if (pending && !stalled && now - pendingSince > ACK_TIMEOUT_MS) {
			stalled = true;
			setStatus("手机 12 秒未确认更新，已暂停同步。可用 /mcrun resume 重试，或断开连接。", true);
		}
		if ((state == State.CONNECTING) && now - connectStarted > CONNECT_TIMEOUT_MS) {
			setStatus("连接超时，请确认手机已解锁、信任电脑并开启开发者模式。", true);
			kill();
		}
	}

	/** Releases a stalled send so the next update is tried again. */
	public void retry() {
		if (stalled) {
			stalled = false;
			pending = false;
			setStatus("重新尝试同步…", true);
		}
	}

	public synchronized void disconnect() {
		switch (state) {
			case IDLE -> notify.accept(status);
			case INSTALLING -> {
				if (cancel != null) cancel.cancel();
				setStatus("正在取消…", true);
			}
			case CONNECTING, READY -> {
				state = State.STOPPING;
				setStatus("正在恢复真实定位…", true);
				writeStop();
				Process p = process;
				Thread t = new Thread(() -> {
					try {
						if (p != null && !p.waitFor(20, TimeUnit.SECONDS)) p.destroyForcibly();
					} catch (InterruptedException ignored) {
					}
				}, "mcgps-stop");
				t.setDaemon(true);
				t.start();
			}
			case STOPPING -> notify.accept(status);
		}
	}

	private void writeStop() {
		try {
			if (stdin != null) {
				stdin.write("{\"action\":\"stop\"}\n".getBytes(StandardCharsets.UTF_8));
				stdin.flush();
			}
		} catch (IOException ignored) {
		}
	}

	private synchronized void kill() {
		if (cancel != null) cancel.cancel();
		if (process != null) process.destroyForcibly();
	}

	/** Game is closing: ask the worker to clear the location and wait briefly. */
	public void shutdown() {
		Process p;
		synchronized (this) {
			if (cancel != null) cancel.cancel();
			p = process;
			if (p == null) return;
			writeStop();
			try {
				if (stdin != null) stdin.close();
			} catch (IOException ignored) {
			}
		}
		try {
			if (!p.waitFor(15, TimeUnit.SECONDS)) p.destroyForcibly();
		} catch (InterruptedException ignored) {
			p.destroyForcibly();
		}
	}

	private void setStatus(String s, boolean chat) {
		status = s;
		if (chat) notify.accept(s);
	}
}
