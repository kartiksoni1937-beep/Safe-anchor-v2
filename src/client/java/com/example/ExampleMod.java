package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
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
    private static final double RANGE_SQ = RANGE * RANGE;
    private static final int CONFIRM_TIMEOUT = 5;
    private static final int RETRY_TICKS = 1;

    private KeyBinding toggleKey;
    private KeyBinding alternateToggleKey;
    private boolean active;
    private int step = -1;
    private int waitTicks;
    private int retries;
    private int originalSlot;
    private int pendingCharge = -1;
    private BlockPos targetAnchorPos;
    private BlockPos protectionPos;
    private String targetName = "None";

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.examplemod.toggle_anchor", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_Z, "category.examplemod.general"));
        alternateToggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.examplemod.toggle_anchor_alt", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_X, "category.examplemod.general"));

        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) return;
            drawContext.drawText(client.textRenderer,
                    Text.literal("§6[AutoSafeAnchor] " + (active ? "§aENABLED" : "§cDISABLED")),
                    10, 10, 0xFFFFFFFF, true);
            drawContext.drawText(client.textRenderer, Text.literal("§7Toggle: Z / X"),
                    10, 22, 0xFFFFFFFF, true);
            if (active) {
                drawContext.drawText(client.textRenderer,
                        Text.literal("§6Target: §f" + targetName), 10, 34, 0xFFFFFFFF, true);
                drawContext.drawText(client.textRenderer,
                        Text.literal("§6Step: §e" + (step < 0 ? "Idle" : step)),
                        10, 46, 0xFFFFFFFF, true);
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void onTick(MinecraftClient client) {
        if (toggleKey.wasPressed() || alternateToggleKey.wasPressed()) {
            active = !active;
            if (!active) reset(client);
            else {
                step = -1;
                waitTicks = 0;
                retries = 0;
                targetName = "Searching...";
            }
            if (client.player != null) {
                client.player.sendMessage(Text.literal("§6[AutoSafeAnchor] " +
                        (active ? "§aENABLED" : "§cDISABLED")), true);
            }
        }

        if (!active || client.player == null || client.world == null || client.interactionManager == null) return;

        if (step >= 0) {
            processSequence(client);
            return;
        }

        PlayerEntity enemy = getNearestEnemy(client);
        if (enemy == null) {
            targetName = "Searching...";
            return;
        }

        BlockPos candidate = findAnchorPosition(client, enemy);
        if (candidate == null) {
            targetName = enemy.getName().getString() + " (no valid spot)";
            return;
        }

        BlockPos cover = findProtectionPosition(client, candidate);
        if (cover == null) {
            targetName = enemy.getName().getString() + " (no safe cover)";
            return;
        }

        originalSlot = client.player.getInventory().selectedSlot;
        targetAnchorPos = candidate.toImmutable();
        protectionPos = cover.toImmutable();
        targetName = enemy.getName().getString();
        retries = 0;
        pendingCharge = -1;
        step = 0;
    }

    private PlayerEntity getNearestEnemy(MinecraftClient client) {
        PlayerEntity nearest = null;
        double best = RANGE_SQ;
        for (PlayerEntity player : client.world.getPlayers()) {
            if (player == client.player || player.isSpectator() || !player.isAlive()) continue;
            double d = client.player.squaredDistanceTo(player);
            if (d <= best) {
                best = d;
                nearest = player;
            }
        }
        return nearest;
    }

    private BlockPos findAnchorPosition(MinecraftClient client, PlayerEntity enemy) {
        BlockPos feet = enemy.getBlockPos();
        BlockPos[] candidates = {
                feet, feet.add(1, 0, 0), feet.add(-1, 0, 0),
                feet.add(0, 0, 1), feet.add(0, 0, -1)
        };
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : candidates) {
            if (!isWithinReach(client, pos) || !canPlaceAt(client, pos) || !hasSolidSupport(client, pos.down())) continue;
            double d = client.player.squaredDistanceTo(Vec3d.ofCenter(pos));
            if (d < bestDistance) {
                bestDistance = d;
                best = pos;
            }
        }
        return best;
    }

    private BlockPos findProtectionPosition(MinecraftClient client, BlockPos anchorPos) {
        Vec3d player = client.player.getPos();
        Vec3d anchor = Vec3d.ofCenter(anchorPos);
        double dx = player.x - anchor.x;
        double dz = player.z - anchor.z;
        Direction direction = Math.abs(dx) > Math.abs(dz)
                ? (dx >= 0 ? Direction.EAST : Direction.WEST)
                : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);

        BlockPos[] candidates = {
                anchorPos.offset(direction), anchorPos.offset(direction).up(),
                anchorPos.up(), anchorPos.up(2)
        };
        for (BlockPos pos : candidates) {
            if (isWithinReach(client, pos) && canPlaceAt(client, pos) && hasSolidSupport(client, pos.down())) {
                return pos;
            }
        }
        return null;
    }

    private void processSequence(MinecraftClient client) {
        if (targetAnchorPos == null || protectionPos == null) {
            reset(client);
            return;
        }

        // Positions are immutable for the whole sequence. Player rotation or movement
        // must never cause the module to pick another protection position mid-sequence.
        if (!isWithinReach(client, targetAnchorPos) || !isWithinReach(client, protectionPos)) {
            reset(client);
            return;
        }

        if (waitTicks > 0) {
            waitTicks--;
            return;
        }

        switch (step) {
            case 0 -> handleProtection(client);
            case 1 -> handleAnchor(client);
            case 2 -> handleCharge(client);
            case 3 -> handleTrigger(client);
            default -> reset(client);
        }
    }

    private void handleProtection(MinecraftClient client) {
        BlockState state = client.world.getBlockState(protectionPos);
        if (state.isOf(Blocks.GLOWSTONE)) {
            advance(1);
            return;
        }
        if (!state.isReplaceable() || !canPlaceAt(client, protectionPos)) {
            reset(client);
            return;
        }

        int slot = findHotbarItem(client, Items.GLOWSTONE);
        if (slot < 0) {
            reset(client);
            return;
        }

        client.player.getInventory().selectedSlot = slot;
        if (!tryPlace(client, protectionPos)) {
            reset(client);
            return;
        }

        // Do not issue another placement packet. Wait briefly for the server/world update.
        waitTicks = RETRY_TICKS;
        retries = 0;
        step = 10;
    }

    private void handleAnchor(MinecraftClient client) {
        BlockState state = client.world.getBlockState(targetAnchorPos);
        if (state.isOf(Blocks.RESPAWN_ANCHOR)) {
            advance(2);
            return;
        }
        if (!state.isReplaceable() || !canPlaceAt(client, targetAnchorPos) || !hasSolidSupport(client, targetAnchorPos.down())) {
            reset(client);
            return;
        }

        int slot = findHotbarItem(client, Items.RESPAWN_ANCHOR);
        if (slot < 0) {
            reset(client);
            return;
        }

        client.player.getInventory().selectedSlot = slot;
        if (!tryPlace(client, targetAnchorPos)) {
            reset(client);
            return;
        }

        waitTicks = RETRY_TICKS;
        retries = 0;
        step = 11;
    }

    private void handleCharge(MinecraftClient client) {
        BlockState state = client.world.getBlockState(targetAnchorPos);
        if (!state.isOf(Blocks.RESPAWN_ANCHOR)) {
            reset(client);
            return;
        }

        int charges = state.get(RespawnAnchorBlock.CHARGES);
        if (charges > 0) {
            advance(3);
            return;
        }

        int slot = findHotbarItem(client, Items.GLOWSTONE);
        if (slot < 0) {
            reset(client);
            return;
        }

        client.player.getInventory().selectedSlot = slot;
        pendingCharge = charges;
        if (!interactExistingBlock(client, targetAnchorPos)) {
            reset(client);
            return;
        }

        waitTicks = RETRY_TICKS;
        retries = 0;
        step = 12;
    }

    private void handleTrigger(MinecraftClient client) {
        BlockState state = client.world.getBlockState(targetAnchorPos);
        if (!state.isOf(Blocks.RESPAWN_ANCHOR) || state.get(RespawnAnchorBlock.CHARGES) <= 0) {
            reset(client);
            return;
        }

        int totemSlot = findHotbarItem(client, Items.TOTEM_OF_UNDYING);
        if (totemSlot < 0) {
            reset(client);
            return;
        }

        client.player.getInventory().selectedSlot = totemSlot;
        interactExistingBlock(client, targetAnchorPos);
        reset(client);
    }

    private void processPending(MinecraftClient client, int pendingStep, int nextStep) {
        if (pendingStep == 10 && client.world.getBlockState(protectionPos).isOf(Blocks.GLOWSTONE)) {
            advance(nextStep);
            return;
        }
        if (pendingStep == 11 && client.world.getBlockState(targetAnchorPos).isOf(Blocks.RESPAWN_ANCHOR)) {
            advance(nextStep);
            return;
        }
        if (pendingStep == 12) {
            BlockState state = client.world.getBlockState(targetAnchorPos);
            if (state.isOf(Blocks.RESPAWN_ANCHOR) && state.get(RespawnAnchorBlock.CHARGES) > pendingCharge) {
                advance(nextStep);
                return;
            }
        }

        if (waitTicks > 0) {
            waitTicks--;
            return;
        }

        if (++retries > 2) {
            reset(client);
            return;
        }

        // Re-enter only after the confirmation window expired; this prevents duplicate
        // packets on consecutive ticks while still recovering quickly from latency.
        step = pendingStep - 10;
        waitTicks = RETRY_TICKS;
    }

    private void advance(int nextStep) {
        step = nextStep;
        waitTicks = 0;
        retries = 0;
    }

    private boolean tryPlace(MinecraftClient client, BlockPos pos) {
        BlockPos support = pos.down();
        if (!hasSolidSupport(client, support)) return false;
        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(support), Direction.UP, support, false);
        ActionResult result = client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
        return result.isAccepted() || result == ActionResult.PASS;
    }

    private boolean interactExistingBlock(MinecraftClient client, BlockPos pos) {
        if (!client.world.getBlockState(pos).isOf(Blocks.RESPAWN_ANCHOR)) return false;
        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
        ActionResult result = client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
        return result.isAccepted() || result == ActionResult.PASS;
    }

    private boolean canPlaceAt(MinecraftClient client, BlockPos pos) {
        BlockState state = client.world.getBlockState(pos);
        return state.isReplaceable() && state.getCollisionShape(client.world, pos).isEmpty();
    }

    private boolean hasSolidSupport(MinecraftClient client, BlockPos pos) {
        BlockState state = client.world.getBlockState(pos);
        return !state.isAir() && !state.getCollisionShape(client.world, pos).isEmpty();
    }

    private boolean isWithinReach(MinecraftClient client, BlockPos pos) {
        return client.player.squaredDistanceTo(Vec3d.ofCenter(pos)) <= RANGE_SQ;
    }

    private int findHotbarItem(MinecraftClient client, Item item) {
        for (int i = 0; i < 9; i++) {
            if (client.player.getInventory().getStack(i).isOf(item)) return i;
        }
        return -1;
    }

    private void reset(MinecraftClient client) {
        if (client.player != null) client.player.getInventory().selectedSlot = originalSlot;
        step = -1;
        waitTicks = 0;
        retries = 0;
        pendingCharge = -1;
        targetAnchorPos = null;
        protectionPos = null;
        targetName = active ? "Searching..." : "None";
    }
}
