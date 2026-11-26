package dev.zephyr;

import dev.zephyr.gui.ClickGuiScreen;

import java.io.File;

/**
 * SELFTEST ONLY - activated by -Dzephyr.selftest=1 (never set in production).
 *
 * Drives a scripted session on the real client so the render/input pipeline can be
 * verified headlessly:
 *   t+10s  : screenshot 00 (title screen)
 *   t+11s  : open the ClickGui
 *   t+13s  : screenshot 01 (the GUI - this is the artifact that shows any glitch)
 *   t+14s  : left-click a module row through the real Screen input path
 *   t+15s  : screenshot 02 (post-click state) + module state dump
 *   t+16s  : exit
 */
public final class ZephyrSelfTest {
    private static boolean enabled;
    private static long startMs;
    private static int step;
    private static int shots;

    public static void init() {
        enabled = "1".equals(System.getProperty("zephyr.selftest"));
        startMs = System.currentTimeMillis();
        if (enabled) {
            System.out.println("[SELFTEST] armed");
        }
    }

    /** Called every client tick from ZephyrClient. */
    public static void tick(net.minecraft.client.MinecraftClient client) {
        if (!enabled) {
            return;
        }
        long t = (System.currentTimeMillis() - startMs) / 1000;
        if (t == 10 && step == 0) {
            step = 1;
            shot(client, "00-title");
        } else if (t == 11 && step == 1) {
            step = 2;
            System.out.println("[SELFTEST] opening ClickGui");
            client.execute(() -> client.setScreen(new ClickGuiScreen()));
        } else if (t == 13 && step == 2) {
            step = 3;
            shot(client, "01-gui");
        } else if (t == 14 && step == 3) {
            step = 4;
            clickFirstRow(client);
        } else if (t == 15 && step == 4) {
            step = 5;
            shot(client, "02-after-click");
            for (dev.zephyr.core.Module m : ZephyrClient.modules().all()) {
                System.out.println("[SELFTEST] " + m.name() + " = " + m.isEnabled());
            }
        } else if (t == 17 && step == 5) {
            step = 6;
            System.out.println("[SELFTEST] done - exiting");
            client.scheduleStop();
        }
    }

    /** Captures the main framebuffer to /tmp/selftest-NN-name.png. */
    private static void shot(net.minecraft.client.MinecraftClient client, String name) {
        try {
            net.minecraft.client.util.ScreenshotRecorder.takeScreenshot(
                    client.getFramebuffer(),
                    img -> {
                        try {
                            File dir = new File("selftest-out");
                            dir.mkdirs();
                            File out = new File(dir, "selftest-" + (shots++) + "-" + name + ".png");
                            img.writeTo(out);
                            System.out.println("[SELFTEST] wrote " + out + " (" + img.getWidth() + "x" + img.getHeight() + ")");
                        } catch (Exception e) {
                            System.out.println("[SELFTEST] write failed: " + e);
                        }
                    });
        } catch (Throwable t) {
            System.out.println("[SELFTEST] shot failed: " + t);
        }
    }

    /**
     * Left-click through the REAL Screen.mouseClicked path (the exact entry point the
     * GLFW mouse handler calls). Target: first module row of the first column.
     */
    private static void clickFirstRow(net.minecraft.client.MinecraftClient client) {
        if (!(client.currentScreen instanceof ClickGuiScreen screen)) {
            System.out.println("[SELFTEST] no ClickGuiScreen open!");
            return;
        }
        // Read the LIVE scale from the screen so a persisted config cannot desync us.
        float scale = ClickGuiScreen.currentScale();
        // first column x=12..140, first module row y=46..66 (scaled space)
        double cx = 70 * scale;
        double cy = 56 * scale;
        try {
            boolean handled = screen.mouseClicked(
                    new net.minecraft.client.gui.Click(cx, cy,
                            new net.minecraft.client.input.MouseInput(0, 0)), false);
            System.out.println("[SELFTEST] mouseClicked at gui(" + cx + "," + cy + ") -> " + handled);
        } catch (Throwable t) {
            System.out.println("[SELFTEST] click failed: " + t);
        }
    }
}
