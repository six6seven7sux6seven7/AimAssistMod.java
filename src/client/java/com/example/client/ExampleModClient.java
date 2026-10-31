package com.example.aimassist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Random;

public class AimAssistMod implements ClientModInitializer {
    public static final String MOD_ID = "aimassist";
    private static final String CATEGORY = "category.aimassist.general";

    private static KeyBinding toggleKey;
    private static KeyBinding menuKey;

    // Класс конфигурации с автосохранением через Gson
    public static class Config {
        public boolean enabled = false;
        public double speed = 4.0;      
        public double range = 4.5;      
        public double fov = 45.0;       

        private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("aimassist.json");
        private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

        public void save() {
            try {
                File file = CONFIG_PATH.toFile();
                file.getParentFile().mkdirs();
                try (FileWriter writer = new FileWriter(file)) {
                    GSON.toJson(this, writer);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public static Config load() {
            File file = CONFIG_PATH.toFile();
            if (file.exists()) {
                try (FileReader reader = new FileReader(file)) {
                    Config config = GSON.fromJson(reader, Config.class);
                    if (config != null) return config;
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            Config defaultConfig = new Config();
            defaultConfig.save();
            return defaultConfig;
        }
    }

    public static final Config CONFIG = Config.load();
    private static final Random RANDOM = new Random();

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.aimassist.toggle", 
                InputUtil.Type.KEYSYM, 
                GLFW.GLFW_KEY_G, 
                CATEGORY
        ));

        menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.aimassist.menu", 
                InputUtil.Type.KEYSYM, 
                GLFW.GLFW_KEY_H, 
                CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;

            while (toggleKey.wasPressed()) {
                CONFIG.enabled = !CONFIG.enabled;
                CONFIG.save(); // Сохраняем состояние при переключении клавишей
                
                String statusText = CONFIG.enabled ? "§aAim-Assist ВКЛ" : "§cAim-Assist ВЫКЛ";
                client.player.sendMessage(Text.literal(statusText), true);
            }

            while (menuKey.wasPressed()) {
                client.setScreen(new ConfigScreen(client.currentScreen));
            }

            if (CONFIG.enabled) {
                Entity target = findBestTarget(client);
                if (target != null) {
                    smartBodyAimAt(client, target);
                }
            }
        });
    }

    private Entity findBestTarget(MinecraftClient client) {
        Entity bestTarget = null;
        Vec3d cameraPos = client.player.getCameraPosVec(1.0F);
        Vec3d rotationVec = client.player.getRotationVec(1.0F);

        for (Entity entity : client.world.getEntities()) {
            if (entity == client.player || !(entity instanceof LivingEntity) || !entity.isAlive()) {
                continue;
            }

            if (client.player.distanceTo(entity) > CONFIG.range) continue;

            Vec3d toEntity = entity.getPos().subtract(cameraPos).normalize();
            double angle = Math.toDegrees(Math.acos(rotationVec.dotProduct(toEntity)));

            if (angle <= CONFIG.fov / 2.0) {
                bestTarget = entity;
                break;
            }
        }
        return bestTarget;
    }

    private void smartBodyAimAt(MinecraftClient client, Entity target) {
        Vec3d eyesPos = client.player.getCameraPosVec(1.0F);
        Box box = target.getBoundingBox();

        Vec3d trueCenter = new Vec3d(
                (box.minX + box.maxX) / 2.0,
                box.minY + (box.maxY - box.minY) * 0.5,
                (box.minZ + box.maxZ) / 2.0
        );

        Vec3d targetPos = new Vec3d(
                trueCenter.x + (target.getPos().x - trueCenter.x) * 0.2,
                box.minY + (box.maxY - box.minY) * 0.4,
                trueCenter.z + (target.getPos().z - trueCenter.z) * 0.2
        );

        double diffX = targetPos.x - eyesPos.x;
        double diffY = targetPos.y - eyesPos.y;
        double diffZ = targetPos.z - eyesPos.z;

        double dist = Math.sqrt(diffX * diffX + diffZ * diffZ);

        float targetYaw = (float) (Math.atan2(diffZ, diffX) * (180 / Math.PI)) - 90.0F;
        float targetPitch = (float) -(Math.atan2(diffY, dist) * (180 / Math.PI));

        float currentYaw = client.player.getYaw();
        float currentPitch = client.player.getPitch();

        float yawDiff = MathHelper.wrapDegrees(targetYaw - currentYaw);
        float pitchDiff = targetPitch - currentPitch;

        if (isCrosshairInsideBox(client, box)) {
            return; 
        }

        float factor = (float) (CONFIG.speed * 0.08);
        float smoothYaw = currentYaw + yawDiff * Math.min(Math.abs(yawDiff) * 0.1f + 0.3f, 1.0f) * factor;
        float smoothPitch = currentPitch + pitchDiff * Math.min(Math.abs(pitchDiff) * 0.1f + 0.3f, 1.0f) * factor;

        float microNoise = (float) ((RANDOM.nextDouble() - 0.5) * 0.15);

        client.player.setYaw(smoothYaw + microNoise);
        client.player.setPitch(MathHelper.clamp(smoothPitch + microNoise, -90.0F, 90.0F));
    }

    private boolean isCrosshairInsideBox(MinecraftClient client, Box box) {
        Vec3d eyes = client.player.getCameraPosVec(1.0F);
        Vec3d rotation = client.player.getRotationVec(1.0F);
        Vec3d lookPoint = eyes.add(rotation.x * 3.0, rotation.y * 3.0, rotation.z * 3.0);
        
        Box innerBox = box.contract(0.2); 
        return innerBox.contains(lookPoint);
    }

    public static class ConfigScreen extends Screen {
        private final Screen parent;

        public ConfigScreen(Screen parent) {
            super(Text.literal("Aim-Assist Настройки"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            int centerX = this.width / 2;
            int centerY = this.height / 2;

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Статус: " + (CONFIG.enabled ? "§aВКЛ" : "§cВЫКЛ")),
                    button -> {
                        CONFIG.enabled = !CONFIG.enabled;
                        CONFIG.save(); // Сохраняем в файл при клике в меню
                        button.setMessage(Text.literal("Статус: " + (CONFIG.enabled ? "§aВКЛ" : "§cВЫКЛ")));
                    })
                    .dimensions(centerX - 100, centerY - 60, 200, 20)
                    .build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Скорость: " + CONFIG.speed),
                    button -> {
                        CONFIG.speed += 1.0;
                        if (CONFIG.speed > 8.0) CONFIG.speed = 1.0;
                        CONFIG.save();
                        button.setMessage(Text.literal("Скорость: " + CONFIG.speed));
                    })
                    .dimensions(centerX - 100, centerY - 30, 200, 20)
                    .build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Дальность: " + CONFIG.range + " бл."),
                    button -> {
                        CONFIG.range += 0.5;
                        if (CONFIG.range > 6.0) CONFIG.range = 3.0;
                        CONFIG.save();
                        button.setMessage(Text.literal("Дальность: " + CONFIG.range + " бл."));
                    })
                    .dimensions(centerX - 100, centerY, 200, 20)
                    .build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("FOV (Угол): " + CONFIG.fov + "°"),
                    button -> {
                        CONFIG.fov += 15.0;
                        if (CONFIG.fov > 90.0) CONFIG.fov = 15.0;
                        CONFIG.save();
                        button.setMessage(Text.literal("FOV (Угол): " + CONFIG.fov + "°"));
                    })
                    .dimensions(centerX - 100, centerY + 30, 200, 20)
                    .build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Готово"),
                    button -> this.close())
                    .dimensions(centerX - 100, centerY + 70, 200, 20)
                    .build());
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            super.render(context, mouseX, mouseY, delta);
            context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, this.height / 2 - 90, 0xFFFFFF);
        }

        @Override
        public void close() {
            assert this.client != null;
            this.client.setScreen(this.parent);
        }
    }
						}
				
