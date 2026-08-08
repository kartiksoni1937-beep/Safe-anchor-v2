package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExampleMod implements ClientModInitializer {
    public static final String MOD_ID = "examplemod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static KeyBinding toggleKey;
    private static boolean isActive = false;
    private static int executionStep = -1;
    private static int tickCounter = 0;
    private static int originalSlot = 0;
    private static BlockPos targetAnchorPos = null;
    private static String currentEnemyName = "None";

    @Override
    public void onInitializeClient() {
        LOGGER.info("Insane Auto Safe Anchor Initialized!");

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.examplemod.toggle_anchor",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_Z,
            "category.examplemod.general"
        ));

        // On-screen HUD Overlay matching utility hack clients
        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
            if (client.player == null) return;

            int x = 10;
            int y = 10;
            String statusText = "§6[AutoSafeAnchor] §fStatus: " + (isActive ? "§aENABLED" : "§cDISABLED");
            drawContext.drawText(client.textRenderer, Text.literal(statusText), x, y, 0xFFFFFFFF, true);

            if (isActive) {
                String targetText = "§6Target: §f" + currentEnemyName;
                drawContext.drawText(client.textRenderer, Text.literal(targetText), x, y + 12, 0xFFFFFFFF, true);
                
                String stepText = "§6Step: §e" + (executionStep == -1 ? "Idle" : "Executing (" + executionStep + ")");
                drawContext.drawText(client.textRenderer, Text.literal(stepText), x, y + 24, 0xFFFFFFFF, true);
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.wasPressed()) {
                isActive = !isActive;
                if (client.player != null) {
                    String status = isActive ? "§aENABLED" : "§cDISABLED";
                    client.player.sendMessage(Text.literal("§6[AutoSafeAnchor] §fMod is now " + status), true);
                }
            }

            if (!isActive || client.player == null || client.world == null || client.interactionManager == null) {
                currentEnemyName = "None";
                return;
            }

            if (executionStep >= 0) {
                tickCounter++;
                // Smooth 2-tick delay to prevent server desync/glitching
                if (tickCounter >= 2) {
                    tickCounter = 0;
                    processAnchorSequence(client);
                }
                return;
            }

            PlayerEntity enemy = getNearestEnemy(client);
            if (enemy != null) {
                originalSlot = client.player.getInventory().selectedSlot;
                targetAnchorPos = enemy.getBlockPos();
                currentEnemyName = enemy.getName().getString();
                executionStep = 0;
                tickCounter = 0;
            } else {
                currentEnemyName = "Searching...";
            }
        });
    }

    private PlayerEntity getNearestEnemy(net.minecraft.client.MinecraftClient client) {
        PlayerEntity nearest = null;
        float minDistance = 9.0F;
        for (PlayerEntity player : client.world.getPlayers()) {
            if (player != client.player) {
                float dist = client.player.distanceTo(player);
                if (dist <= minDistance) {
                    minDistance = dist;
                    nearest = player;
                }
            }
        }
        return nearest;
    }

    private void processAnchorSequence(net.minecraft.client.MinecraftClient client) {
        if (targetAnchorPos == null) {
            resetSequence(client);
            return;
        }

        BlockPos playerBasePos = client.player.getBlockPos();

        switch (executionStep) {
            case 0: // Step 1: Place Glowstone Protection block above player
                int glowstoneSlot1 = findItem(client, Items.GLOWSTONE);
                if (glowstoneSlot1 == -1) {
                    resetSequence(client);
                    return;
                }
                client.player.getInventory().selectedSlot = glowstoneSlot1;
                BlockPos protPos = playerBasePos.up(2);
                BlockHitResult protHit = new BlockHitResult(
                    new Vec3d(protPos.getX() + 0.5, protPos.getY(), protPos.getZ() + 0.5),
                    Direction.DOWN,
                    protPos,
                    false
                );
                client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, protHit);
                executionStep = 1;
                break;

            case 1: // Step 2: Place Respawn Anchor at enemy's feet
                int anchorSlot = findItem(client, Items.RESPAWN_ANCHOR);
                if (anchorSlot == -1) {
                    resetSequence(client);
                    return;
                }
                client.player.getInventory().selectedSlot = anchorSlot;
                BlockHitResult anchorHit = new BlockHitResult(
                    new Vec3d(targetAnchorPos.getX() + 0.5, targetAnchorPos.getY(), targetAnchorPos.getZ() + 0.5),
                    Direction.UP,
                    targetAnchorPos,
                    false
                );
                client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, anchorHit);
                executionStep = 2;
                break;

            case 2: // Step 3: Charge Respawn Anchor with Glowstone
                int glowstoneSlot2 = findItem(client, Items.GLOWSTONE);
                if (glowstoneSlot2 == -1) {
                    resetSequence(client);
                    return;
                }
                client.player.getInventory().selectedSlot = glowstoneSlot2;
                BlockHitResult glowHit = new BlockHitResult(
                    new Vec3d(targetAnchorPos.getX() + 0.5, targetAnchorPos.getY(), targetAnchorPos.getZ() + 0.5),
                    Direction.UP,
                    targetAnchorPos,
                    false
                );
                client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, glowHit);
                executionStep = 3;
                break;

            case 3: // Step 4: Explode using Slot 7 (index 6)
                client.player.getInventory().selectedSlot = 6;
                BlockHitResult explodeHit = new BlockHitResult(
                    new Vec3d(targetAnchorPos.getX() + 0.5, targetAnchorPos.getY(), targetAnchorPos.getZ() + 0.5),
                    Direction.UP,
                    targetAnchorPos,
                    false
                );
                client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, explodeHit);
                resetSequence(client);
                break;
        }
    }

    private int findItem(net.minecraft.client.MinecraftClient client, net.minecraft.item.Item item) {
        for (int i = 0; i < 9; i++) {
            if (client.player.getInventory().getStack(i).isOf(item)) {
                return i;
            }
        }
        return -1;
    }

    private void resetSequence(net.minecraft.client.MinecraftClient client) {
        client.player.getInventory().selectedSlot = originalSlot;
        executionStep = -1;
        tickCounter = 0;
        targetAnchorPos = null;
        currentEnemyName = "Searching...";
    }
                               }
