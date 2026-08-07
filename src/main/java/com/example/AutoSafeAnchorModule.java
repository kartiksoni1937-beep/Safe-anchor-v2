package com.example;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public class AutoSafeAnchorModule {

    private final MinecraftClient client = MinecraftClient.getInstance();

    private static final double ENEMY_REACH_BLOCKS = 8.0;
    private static final long SUB_TICK_COOLDOWN_MS = 10;
    
    private long lastExecutionMs = 0;
    private boolean enabled = true;

    public void register() {
        HudRenderCallback.EVENT.register(this::onRenderFrame);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private void onRenderFrame(DrawContext drawContext, RenderTickCounter tickCounter) {
        if (!enabled || client.player == null || client.world == null) return;

        long now = System.currentTimeMillis();
        if (now - lastExecutionMs < SUB_TICK_COOLDOWN_MS) return;

        PlayerEntity targetEnemy = findNearestEnemy(ENEMY_REACH_BLOCKS);
        if (targetEnemy == null) return;

        BlockPos anchorPos = findAnchorPosNearEnemy(targetEnemy);
        if (anchorPos == null) return;

        executeSubTickAnchorSequence(anchorPos);
        lastExecutionMs = now;
    }

    private PlayerEntity findNearestEnemy(double maxRange) {
        PlayerEntity nearest = null;
        double closestDistSq = maxRange * maxRange;

        for (PlayerEntity other : client.world.getPlayers()) {
            if (other == client.player || !other.isAlive() || other.isSpectator()) continue;

            double distSq = client.player.squaredDistanceTo(other);
            if (distSq <= closestDistSq) {
                closestDistSq = distSq;
                nearest = other;
            }
        }
        return nearest;
    }

    private BlockPos findAnchorPosNearEnemy(PlayerEntity enemy) {
        BlockPos enemyFeetPos = enemy.getBlockPos();
        BlockPos[] candidates = new BlockPos[]{
            enemyFeetPos,
            enemyFeetPos.north(), enemyFeetPos.south(), 
            enemyFeetPos.east(), enemyFeetPos.west()
        };

        for (BlockPos pos : candidates) {
            var state = client.world.getBlockState(pos);
            if (state.isOf(Blocks.RESPAWN_ANCHOR)) {
                return pos;
            }
            if (state.isAir() && client.world.getBlockState(pos.down()).isSolidBlock(client.world, pos.down())) {
                return pos;
            }
        }
        return null;
    }

    private void executeSubTickAnchorSequence(BlockPos anchorPos) {
        ClientPlayerEntity player = client.player;
        ClientPlayNetworkHandler networkHandler = client.getNetworkHandler();
        if (player == null || networkHandler == null) return;

        int anchorSlot = findHotbarSlot(Items.RESPAWN_ANCHOR);
        int glowstoneSlot = findHotbarSlot(Items.GLOWSTONE);
        int totemSlot = findHotbarSlot(Items.TOTEM_OF_UNDYING);

        if (anchorSlot == -1 || glowstoneSlot == -1 || totemSlot == -1) return;

        BlockPos shieldPos = calculateShieldBlockPos(player.getPos(), anchorPos);
        int originalSlot = player.getInventory().selectedSlot;

        // Step 1: Place Anchor
        if (client.world.getBlockState(anchorPos).isAir()) {
            sendSilentRotation(networkHandler, player.getEyePos(), anchorPos);
            networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(anchorSlot));
            sendBlockInteractPacket(networkHandler, anchorPos, Direction.UP);
        }

        // Step 2: Charge Anchor with Glowstone
        sendSilentRotation(networkHandler, player.getEyePos(), anchorPos);
        networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(glowstoneSlot));
        sendBlockInteractPacket(networkHandler, anchorPos, Direction.UP);

        // Step 3: Place Shield Block with Glowstone
        if (client.world.getBlockState(shieldPos).isAir() && !shieldPos.equals(anchorPos)) {
            sendSilentRotation(networkHandler, player.getEyePos(), shieldPos);
            networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(glowstoneSlot));
            sendBlockInteractPacket(networkHandler, shieldPos, Direction.UP);
        }

        // Step 4: Detonate Anchor using Totem of Undying
        sendSilentRotation(networkHandler, player.getEyePos(), anchorPos);
        networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(totemSlot));
        sendBlockInteractPacket(networkHandler, anchorPos, Direction.UP);

        networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(originalSlot));
    }

    private BlockPos calculateShieldBlockPos(Vec3d playerPos, BlockPos anchorPos) {
        Vec3d anchorVec = Vec3d.ofCenter(anchorPos);
        Vec3d dir = playerPos.subtract(anchorVec).normalize();
        Vec3d shieldVec = anchorVec.add(dir.x, 0, dir.z);
        return BlockPos.ofFloored(shieldVec);
    }

    private void sendSilentRotation(ClientPlayNetworkHandler handler, Vec3d eyePos, BlockPos targetPos) {
        Vec3d targetVec = Vec3d.ofCenter(targetPos);
        double dx = targetVec.x - eyePos.x;
        double dy = targetVec.y - eyePos.y;
        double dz = targetVec.z - eyePos.z;
        double distance = Math.sqrt(dx * dx + dz * dz);

        float yaw = MathHelper.wrapDegrees((float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F);
        float pitch = MathHelper.wrapDegrees((float) -Math.toDegrees(Math.atan2(dy, distance)));

        handler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, client.player.isOnGround()));
    }

    private void sendBlockInteractPacket(ClientPlayNetworkHandler handler, BlockPos pos, Direction dir) {
        BlockHitResult hitResult = new BlockHitResult(Vec3d.ofCenter(pos), dir, pos, false);
        handler.sendPacket(new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, hitResult, 0));
    }

    private int findHotbarSlot(Item item) {
        for (int i = 0; i < 9; i++) {
            if (client.player.getInventory().getStack(i).isOf(item)) {
                return i;
            }
        }
        return -1;
    }
            }
                                         
