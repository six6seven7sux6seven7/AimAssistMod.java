package com.example.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

public class AimAssistMod implements ClientModInitializer {

    public static boolean enabled = true;
    public static float fov = 60.0f;
    public static float smoothness = 0.15f;
    public static double maxDistance = 15.0;

    private static KeyBinding toggleKey;
    private static KeyBinding menuKey;
    private static final Path CONFIG_PATH = Path.of("config", "aimassist.json");

    @Override
    public void onInitializeClient() {
        loadConfig();

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.aimassist.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                "category.aimassist"
        ));

        menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.aimassist.menu",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                "category.aimassist"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;

            while (toggleKey.wasPressed()) {
                enabled = !enabled;
                client.player.sendMessage(Text.of("AimAssist: " + (enabled ? "ON" : "OFF")), true);
                saveConfig();
            }

            while (menuKey.wasPressed()) {
                client.setScreen(new ConfigScreen());
            }

            if (enabled && client.currentScreen == null) {
                tickAim(client);
            }
        });
    }

    private void tickAim(MinecraftClient client) {
        PlayerEntity player = client.player;
        Vec3d cameraPos = player.getCameraPosVec(1.0F);
        Vec3d lookVec = player.getRotationVec(1.0F);

        PlayerEntity target = null;
        double closestAngle = fov;

        Box searchBox = player.getBoundingBox().expand(maxDistance);
        for (Entity entity : client.world.getEntitiesByClass(PlayerEntity.class, searchBox, e -> e != player && e.isAlive())) {
            PlayerEntity other = (PlayerEntity) entity;
            Vec3d targetPos = other.getBoundingBox().getCenter();
            Vec3d toTarget = targetPos.subtract(cameraPos).normalize();

            double dot = lookVec.dotProduct(toTarget);
            double angle = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));

            if (angle < closestAngle && player.distanceTo(other) <= maxDistance) {
                closestAngle = angle;
                target = other;
            }
        }

        if (target != null) {
            Vec3d targetPos = target.getBoundingBox().getCenter();
            Vec3d dir = targetPos.subtract(cameraPos).normalize();

            double targetYaw = Math.toDegrees(Math.atan2(-dir.x, dir.z));
            double targetPitch = Math.toDegrees(-Math.asin(dir.y));

            double deltaYaw = wrapDegrees(targetYaw - player.getYaw());
            double deltaPitch = targetPitch - player.getPitch();

            player.setYaw((float) (player.getYaw() + deltaYaw * smoothness));
            player.setPitch((float) (player.getPitch() + deltaPitch * smoothness));
        }
    }

    private double wrapDegrees(double degrees) {
        degrees %= 360.0;
        if (degrees >= 180.0) degrees -= 360.0;
        if (degrees < -180.0) degrees += 360.0;
        return degrees;
    }

    private static void saveConfig() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(CONFIG_PATH))) {
                writer.println("{");
                writer.println("  \"enabled\": " + enabled + ",");
                writer.println("  \"fov\": " + fov + ",");
                writer.println("  \"smoothness\": " + smoothness + ",");
                writer.println("  \"maxDistance\": " + maxDistance);
                writer.println("}");
            }
        } catch (IOException ignored) {}
    }

    private static void loadConfig() {
        if (!Files.exists(CONFIG_PATH)) return;
        try (BufferedReader reader = Files.newBufferedReader(CONFIG_PATH)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("\"enabled\":")) enabled = Boolean.parseBoolean(extractValue(line));
                if (line.contains("\"fov\":")) fov = Float.parseFloat(extractValue(line));
                if (line.contains("\"smoothness\":")) smoothness = Float.parseFloat(extractValue(line));
                if (line.contains("\"maxDistance\":")) maxDistance = Double.parseDouble(extractValue(line));
            }
        } catch (Exception ignored) {}
    }

    private static String extractValue(String line) {
        return line.split(":")[1].replace(",", "").trim();
    }

    public static class ConfigScreen extends Screen {
        protected ConfigScreen() {
            super(Text.of("AimAssist Configuration"));
        }

        @Override
        protected void init() {
            int y = this.height / 4;

            this.addDrawableChild(ButtonWidget.builder(Text.of("AimAssist: " + (enabled ? "ON" : "OFF")), button -> {
                enabled = !enabled;
                button.setMessage(Text.of("AimAssist: " + (enabled ? "ON" : "OFF")));
                saveConfig();
            }).dimensions(this.width / 2 - 100, y, 200, 20).build());

            this.addDrawableChild(ButtonWidget.builder(Text.of("FOV: " + (int) fov + "°"), button -> {
                fov += 15.0f;
                if (fov > 120.0f) fov = 30.0f;
                button.setMessage(Text.of("FOV: " + (int) fov + "°"));
                saveConfig();
            }).dimensions(this.width / 2 - 100, y + 30, 200, 20).build());

            this.addDrawableChild(ButtonWidget.builder(Text.of("Smoothness: " + (int)(smoothness * 100) + "%"), button -> {
                smoothness += 0.05f;
                if (smoothness > 0.5f) smoothness = 0.05f;
                button.setMessage(Text.of("Smoothness: " + (int)(smoothness * 100) + "%"));
                saveConfig();
            }).dimensions(this.width / 2 - 100, y + 60, 200, 20).build());

            this.addDrawableChild(ButtonWidget.builder(Text.of("Done"), button -> {
                this.close();
            }).dimensions(this.width / 2 - 100, y + 100, 200, 20).build());
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            this.renderBackground(context, mouseX, mouseY, delta);
            context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 20, 0xFFFFFF);
            super.render(context, mouseX, mouseY, delta);
        }
    }
				}  
