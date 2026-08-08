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

    @Override
    public void onInitializeClient() {
        LOGGER.info("Ultra-Fast Auto Safe Anchor Initialized!");

        // Register the 'Z' keybind to toggle the mod
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.examplemod.toggle_anchor",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_Z,
            "category.examplemod.general"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Toggle mod status instantly when 'Z' is pressed
            while (toggleKey.wasPressed()) {
                isActive = !isActive;
                if (client.player != null) {
                    String status = isActive ? "§aENABLED (BLAZING FAST)" : "§cDISABLED";
                    client.player.sendMessage(Text.literal("§6[AutoSafeAnchor] §fMod is now " + status), true);
                }
            }

            // Execute instantly every tick without any delay if active
            if (isActive && client.player != null && client.world != null && client.interactionManager != null) {
                if (isEnemyNearby(client)) {
                    executeInstantAnchorSequence(client);
                }
            }
        });
    }

    private boolean isEnemyNearby(net.minecraft.client.MinecraftClient client) {
        for (PlayerEntity player : client.world.getPlayers()) {
            // Checks if any other player is within 9 blocks
            if (player != client.player && client.player.distanceTo(player) <= 9.0F) {
                return true;
            }
        }
        return false;
    }

    private void executeInstantAnchorSequence(net.minecraft.client.MinecraftClient client) {
        int originalSlot = client.player.getInventory().selectedSlot;

        // 1. Find Respawn Anchor and Glowstone in hotbar (slots 0-8)
        int anchorSlot = -1;
        int glowstoneSlot = -1;

        for (int i = 0; i < 9; i++) {
            if (client.player.getInventory().getStack(i).isOf(Items.RESPAWN_ANCHOR)) {
                anchorSlot = i;
            }
            if (client.player.getInventory().getStack(i).isOf(Items.GLOWSTONE)) {
                glowstoneSlot = i;
            }
        }

        // If missing either item, we can't execute the safe anchor combo
        if (anchorSlot == -1 || glowstoneSlot == -1) {
            return;
        }

        // Target block right at the player's feet
        BlockPos targetPos = client.player.getBlockPos().down();
        BlockHitResult hitResult = new BlockHitResult(
            new Vec3d(targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5),
            Direction.UP,
            targetPos,
            false
        );

        // --- STEP 1: Place Anchor Instantly ---
        client.player.getInventory().selectedSlot = anchorSlot;
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hitResult);

        // --- STEP 2: Charge with Glowstone Instantly ---
        client.player.getInventory().selectedSlot = glowstoneSlot;
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hitResult);

        // --- STEP 3: EXPLODE THE ANCHOR (USING SLOT 7) ---
        // Slot 7 in the game is index 6. We switch to it to click the anchor and detonate it!
        client.player.getInventory().selectedSlot = 6; 
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hitResult);

        // Restore original slot so you can keep fighting uninterrupted
        client.player.getInventory().selectedSlot = originalSlot;
        
        LOGGER.info("Instant Safe Anchor placed, charged, and exploded via Slot 7!");
    }
}
