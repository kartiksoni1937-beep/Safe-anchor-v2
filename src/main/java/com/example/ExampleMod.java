package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
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
    private static int originalSlot = 0;

    @Override
    public void onInitializeClient() {
        LOGGER.info("Fast 2-Tick Safe Anchor Initialized!");

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.examplemod.toggle_anchor",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_Z,
            "category.examplemod.general"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.wasPressed()) {
                isActive = !isActive;
                if (client.player != null) {
                    String status = isActive ? "§aENABLED (2-TICK FAST)" : "§cDISABLED";
                    client.player.sendMessage(Text.literal("§6[AutoSafeAnchor] §fMod is now " + status), true);
                }
            }

            if (!isActive || client.player == null || client.world == null || client.interactionManager == null) {
                return;
            }

            if (executionStep >= 0) {
                processFastSequence(client);
                return;
            }

            if (isEnemyNearby(client)) {
                originalSlot = client.player.getInventory().selectedSlot;
                executionStep = 0;
            }
        });
    }

    private boolean isEnemyNearby(net.minecraft.client.MinecraftClient client) {
        for (PlayerEntity player : client.world.getPlayers()) {
            if (player != client.player && client.player.distanceTo(player) <= 9.0F) {
                return true;
            }
        }
        return false;
    }

    private void processFastSequence(net.minecraft.client.MinecraftClient client) {
        BlockPos targetPos = client.player.getBlockPos().down();
        BlockHitResult hitResult = new BlockHitResult(
            new Vec3d(targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5),
            Direction.UP,
            targetPos,
            false
        );

        switch (executionStep) {
            case 0:
                // Tick 1: Place Anchor and Charge with Glowstone back-to-back in the same tick
                int anchorSlot = findItem(client, Items.RESPAWN_ANCHOR);
                int glowstoneSlot = findItem(client, Items.GLOWSTONE);

                if (anchorSlot == -1 || glowstoneSlot == -1) {
                    resetSequence(client);
                    return;
                }

                // Place
                client.player.getInventory().selectedSlot = anchorSlot;
                client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hitResult);

                // Charge immediately
                client.player.getInventory().selectedSlot = glowstoneSlot;
                client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hitResult);

                executionStep = 1; // Move to explosion on the next tick
                break;

            case 1:
                // Tick 2: Explode using Slot 7 (index 6)
                client.player.getInventory().selectedSlot = 6;
                client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hitResult);
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
    }
}
