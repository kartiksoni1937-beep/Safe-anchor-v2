package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
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
    private static final double RANGE = 9.0D;

    private KeyBinding toggleKey;
    private boolean active;
    private int step = -1;
    private int originalSlot;
    private BlockPos targetAnchorPos;
    private String targetName = "None";

    @Override
    public void onInitializeClient() {
        LOGGER.info("Auto Safe Anchor initialized");

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.examplemod.toggle_anchor",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_Z,
                "category.examplemod.general"
        ));

        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) return;

            int x = 10;
            int y = 10;
            drawContext.drawText(client.textRenderer,
                    Text.literal("§6[AutoSafeAnchor] §f" + (active ? "§aENABLED" : "§cDISABLED")),
                    x, y, 0xFFFFFFFF, true);
            if (active) {
                drawContext.drawText(client.textRenderer,
                        Text.literal("§6Target: §f" + targetName), x, y + 12, 0xFFFFFFFF, true);
                drawContext.drawText(client.textRenderer,
                        Text.literal("§6Step: §e" + (step < 0 ? "Idle" : step)),
                        x, y + 24, 0xFFFFFFFF, true);
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void onTick(MinecraftClient client) {
        while (toggleKey.wasPressed()) {
            active = !active;
            if (client.player != null) {
                client.player.sendMessage(
                        Text.literal("§6[AutoSafeAnchor] §fMod is now " + (active ? "§aENABLED" : "§cDISABLED")),
                        true
                );
            }
            if (!active) reset(client);
        }

        if (!active || client.player == null || client.world == null || client.interactionManager == null) {
            targetName = "None";
            return;
        }

        if (step >= 0) {
            processSequence(client);
            return;
        }

        PlayerEntity enemy = getNearestEnemy(client);
        if (enemy == null) {
            targetName = "Searching...";
            return;
        }

        originalSlot = client.player.getInventory().selectedSlot;
        targetAnchorPos = enemy.getBlockPos();
        targetName = enemy.getName().getString();
        step = 0;
    }

    private PlayerEntity getNearestEnemy(MinecraftClient client) {
        PlayerEntity nearest = null;
        double nearestDistance = RANGE;

        for (PlayerEntity player : client.world.getPlayers()) {
            if (player == client.player || player.isSpectator() || !player.isAlive()) continue;

            double distance = client.player.squaredDistanceTo(player);
            if (distance <= nearestDistance * nearestDistance) {
                nearestDistance = Math.sqrt(distance);
                nearest = player;
            }
        }
        return nearest;
    }

    private void processSequence(MinecraftClient client) {
        if (targetAnchorPos == null || client.player == null) {
            reset(client);
            return;
        }

        switch (step) {
            case 0 -> {
                // Put the glowstone protection block in an open position above the player.
                int slot = findHotbarItem(client, Items.GLOWSTONE);
                BlockPos protectionPos = client.player.getBlockPos().up(2);
                if (slot < 0 || !canPlaceAt(client, protectionPos)) {
                    reset(client);
                    return;
                }

                client.player.getInventory().selectedSlot = slot;
                if (interactBlock(client, protectionPos.down(), Direction.UP)) {
                    step = 1;
                } else {
                    reset(client);
                }
            }
            case 1 -> {
                // Place the respawn anchor at the enemy's feet from the supporting block below.
                int slot = findHotbarItem(client, Items.RESPAWN_ANCHOR);
                if (slot < 0 || !canPlaceAt(client, targetAnchorPos)) {
                    reset(client);
                    return;
                }

                client.player.getInventory().selectedSlot = slot;
                if (interactBlock(client, targetAnchorPos.down(), Direction.UP)) {
                    step = 2;
                } else {
                    reset(client);
                }
            }
            case 2 -> {
                // Charge the placed anchor with glowstone.
                int slot = findHotbarItem(client, Items.GLOWSTONE);
                if (slot < 0) {
                    reset(client);
                    return;
                }

                client.player.getInventory().selectedSlot = slot;
                if (interactBlock(client, targetAnchorPos, Direction.UP)) {
                    step = 3;
                } else {
                    reset(client);
                }
            }
            case 3 -> {
                // Hold a totem while triggering the charged anchor, then restore the old slot.
                int totemSlot = findHotbarItem(client, Items.TOTEM_OF_UNDYING);
                if (totemSlot < 0) {
                    reset(client);
                    return;
                }

                client.player.getInventory().selectedSlot = totemSlot;
                interactBlock(client, targetAnchorPos, Direction.UP);
                reset(client);
            }
            default -> reset(client);
        }
    }

    private boolean interactBlock(MinecraftClient client, BlockPos blockPos, Direction side) {
        BlockHitResult hit = new BlockHitResult(
                Vec3d.ofCenter(blockPos),
                side,
                blockPos,
                false
        );
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
        return true;
    }

    private boolean canPlaceAt(MinecraftClient client, BlockPos pos) {
        BlockState state = client.world.getBlockState(pos);
        return state.isAir() || state.getCollisionShape(client.world, pos).isEmpty();
    }

    private int findHotbarItem(MinecraftClient client, Item item) {
        for (int i = 0; i < 9; i++) {
            if (client.player.getInventory().getStack(i).isOf(item)) return i;
        }
        return -1;
    }

    private void reset(MinecraftClient client) {
        if (client.player != null) {
            client.player.getInventory().selectedSlot = originalSlot;
        }
        step = -1;
        targetAnchorPos = null;
        targetName = active ? "Searching..." : "None";
    }
}
