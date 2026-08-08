package com.example.addon.modules;

import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class ConsistentSafeAnchorModule {

private final MinecraftClient mc = MinecraftClient.getInstance();  

// Configuration  
private final double maxRange = 5.0;  
private final double minSafetyDistance = 4.0; // Prevents self-damage  
private final float minHealth = 10.0f;  
private final int timeoutTicks = 6;           // Watchdog timer to prevent getting stuck  

private enum State { IDLE, PLACING, CHARGING, DETONATING }  
private State currentState = State.IDLE;  
private int stageTimer = 0;  
private BlockPos currentAnchorPos = null;  

public void onTick() {  
    if (mc.player == null || mc.world == null) return;  

    // 1. Global Safety & Environment Checks  
    if (mc.world.getDimension().respawnAnchorWorks() ||   
        (mc.player.getHealth() + mc.player.getAbsorptionAmount() < minHealth)) {  
        resetState();  
        return;  
    }  

    // 2. Target Acquisition  
    PlayerEntity target = getBestTarget();  
    if (target == null) {  
        resetState();  
        return;  
    }  

    BlockPos targetPos = target.getBlockPos().add(0, 2, 0);  

    // 3. Self-Harm Safety Distance Check  
    if (mc.player.getPos().distanceTo(Vec3d.ofCenter(targetPos)) < minSafetyDistance) {  
        return;   
    }  

    currentAnchorPos = targetPos;  

    // 4. Watchdog Timer (Consistency Protector)  
    if (currentState != State.IDLE) {  
        stageTimer++;  
        if (stageTimer > timeoutTicks) {  
            // If server takes too long to respond, reset state to bypass desync/rubberbanding  
            resetState();  
            return;  
        }  
    }  

    // 5. Execute Sequence with Verification  
    executeConsistentSequence(currentAnchorPos);  
}  

private void executeConsistentSequence(BlockPos pos) {  
    var blockState = mc.world.getBlockState(pos);  

    if (blockState.isAir()) {  
        currentState = State.PLACING;  
        if (switchToItem(Items.RESPAWN_ANCHOR)) {  
            sendInteractPacket(pos);  
        }  
    }   
    else if (blockState.isOf(Blocks.RESPAWN_ANCHOR)) {  
        int charges = blockState.get(RespawnAnchorBlock.CHARGES);  

        if (charges == 0) {  
            currentState = State.CHARGING;  
            if (switchToItem(Items.GLOWSTONE)) {  
                sendInteractPacket(pos);  
            }  
        } else {  
            currentState = State.DETONATING;  
            sendInteractPacket(pos);  
            resetState(); // Reset immediately after detonation trigger for continuous loops  
        }  
    }   
    else {  
        // Block is occupied by something else; reset to avoid breaking loops  
        resetState();  
    }  
}  

private void sendInteractPacket(BlockPos pos) {  
    BlockHitResult hitResult = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);  
    mc.getNetworkHandler().sendPacket(new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, hitResult, 0));  
}  

private boolean switchToItem(net.minecraft.item.Item item) {  
    for (int i = 0; i < 9; i++) {  
        if (mc.player.getInventory().getStack(i).getItem() == item) {  
            if (mc.player.getInventory().selectedSlot != i) {  
                mc.player.getInventory().selectedSlot = i;  
                mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(i));  
            }  
            return true;  
        }  
    }  
    return false;  
}  

private PlayerEntity getBestTarget() {  
    return mc.world.getPlayers().stream()  
            .filter(p -> p != mc.player && !p.isDead() && !p.isSpectator())  
            .filter(p -> mc.player.distanceTo(p) <= maxRange)  
            .min((p1, p2) -> Double.compare(mc.player.distanceTo(p1), mc.player.distanceTo(p2)))  
            .orElse(null);  
}  

private void resetState() {  
    currentState = State.IDLE;  
    stageTimer = 0;  
    currentAnchorPos = null;  
}

}
