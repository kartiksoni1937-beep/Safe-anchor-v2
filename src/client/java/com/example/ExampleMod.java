package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrb;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class ExampleMod implements ClientModInitializer {
    public static final String MOD_ID = "example-mod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private boolean active_ = false;
    private static boolean safetySequenceActive_ = false;
    private boolean havePositions_ = false;
    private int clock_ = 0;
    private int step_ = 0;
    private int pendingAction_ = 0;
    private int pendingTicks_ = 0;
    private int anchorWait_ = 0;
    private int chargeWait_ = 0;
    private int explosionWait_ = 0;
    private int protectionWait_ = 0;
    private int protectionAttempts_ = 0;
    private boolean protectionSent_ = false;
    private boolean chargeAccepted_ = false;
    private boolean lastActionSucceeded_ = false;
    private int remoteFocus_ = 0;
    private int remoteBobTick_ = 0;
    private int remoteRotationHoldTicks_ = 0;
    private boolean silentPovStaged_ = false;
    private boolean silentRotationPrimed_ = false;
    private boolean smoothInitialized_ = false;
    private boolean smoothDone_ = false;

    private float targetYaw_ = 0.0f;
    private float targetPitch_ = 0.0f;
    private float currentYaw_ = 0.0f;
    private float currentPitch_ = 0.0f;

    private int anchorX_, anchorY_, anchorZ_;
    private int protectX_, protectY_, protectZ_;

    private int switchDelay_ = 0;
    private int explosionSlot_ = 1;
    private int range_ = 40; 
    private boolean silentRotations_ = true;
    private boolean smoothRotations_ = true;

    @Override
    public void onInitializeClient() {
        LOGGER.info("Initializing Example Mod with SafeAnchor logic!");

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            tick(client);
        });
    }

    public void resetState() {
        active_ = false;
        safetySequenceActive_ = false;
        havePositions_ = false;
        clock_ = 0;
        step_ = 0;
        pendingAction_ = 0;
        pendingTicks_ = 0;
        anchorWait_ = 0;
        chargeWait_ = 0;
        explosionWait_ = 0;
        protectionWait_ = 0;
        protectionAttempts_ = 0;
        protectionSent_ = false;
        chargeAccepted_ = false;
        lastActionSucceeded_ = false;
        remoteFocus_ = 0;
        remoteBobTick_ = 0;
        if (remoteRotationHoldTicks_ <= 0) {
            silentPovStaged_ = false;
        }
        silentRotationPrimed_ = false;
    }

    public void endTick(MinecraftClient client) {
        if (!silentRotations_ || client == null || client.player == null) {
            return;
        }
        ClientPlayerEntity player = client.player;
        if (pendingAction_ != 0) {
            stageSilentRotation(player, targetYaw_, targetPitch_);
            if (sendSyntheticLookPacket(client, player, targetYaw_, targetPitch_)) {
                silentRotationPrimed_ = false;
                runPendingAction(client);
            }
        } else if (active_ && havePositions_ && step_ >= 2) {
            holdAimOnAnchor(client);
        } else if (remoteRotationHoldTicks_ > 0) {
            stageSilentRotation(player, currentYaw_, currentPitch_);
            sendSyntheticLookPacket(client, player, currentYaw_, currentPitch_);
        }
    }

    public boolean hasRequiredItems(ClientPlayerEntity player) {
        return findHot(player, "respawn_anchor") >= 0 && findHot(player, "glowstone") >= 0;
    }

    private int findHot(ClientPlayerEntity player, String itemType) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < 9; i++) {
            var stack = inv.getStack(i);
            if (itemType.equals("respawn_anchor") && stack.isOf(Blocks.RESPAWN_ANCHOR.asItem())) return i;
            if (itemType.equals("glowstone") && stack.isOf(Items.GLOWSTONE)) return i;
        }
        return -1;
    }

    private BlockPos safeAnchorV3Pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    private boolean safeAnchorV3Replaceable(ClientWorld world, BlockPos pos) {
        if (world == null || pos == null) return false;
        BlockState state = world.getBlockState(pos);
        boolean replaceable = state.isAir() || state.isReplaceable();
        if (!replaceable) {
            String name = state.getBlock().getTranslationKey();
            String[] extraReplaceable = {
                "short_grass", "tall_grass", "fern", "large_fern", "dead_bush",
                "vine", "fire", "soul_fire", "water", "lava", "snow",
                "seagrass", "tall_seagrass", "kelp", "kelp_plant"
            };
            for (String id : extraReplaceable) {
                if (name.contains(id)) {
                    replaceable = true;
                    break;
                }
            }
        }
        return replaceable;
    }

    private boolean safeAnchorV3EntityBlocked(MinecraftClient client, ClientWorld world, int x, int y, int z) {
        if (client == null || world == null) return false;
        Box box = new Box(x + 0.01, y + 0.01, z + 0.01, x + 0.99, y + 0.99, z + 0.99);
        List<Entity> entities = world.getEntitiesByClass(Entity.class, box, entity -> true);
        
        boolean blocked = false;
        for (Entity entity : entities) {
            if (entity == null) continue;
            boolean nonBlockingDrop = (entity instanceof ItemEntity) || (entity instanceof ExperienceOrb);
            blocked = !nonBlockingDrop;
            if (blocked) break;
        }
        return blocked;
    }

    public boolean safeAnchorV3ProtectionReady(MinecraftClient client, int anchorX, int anchorY, int anchorZ, int protectX, int protectY, int protectZ) {
        if (client == null || client.world == null || client.player == null) return false;
        ClientWorld world = client.world;
        ClientPlayerEntity player = client.player;

        BlockPos anchorPos = safeAnchorV3Pos(anchorX, anchorY, anchorZ);
        BlockPos protectPos = safeAnchorV3Pos(protectX, protectY, protectZ);

        boolean ready = anchorPos != null && protectPos != null &&
                chargeAt(world, anchorPos) > 0 &&
                world.getBlockState(protectPos).getBlock().getTranslationKey().contains("glowstone");

        if (ready) {
            double playerX = player.getX();
            double playerY = player.getY();
            double playerZ = player.getZ();

            double anchorDx = anchorX + 0.5 - playerX;
            double anchorDz = anchorZ + 0.5 - playerZ;
            double shieldDx = protectX + 0.5 - playerX;
            double shieldDz = protectZ + 0.5 - playerZ;

            double anchorDistanceSq = anchorDx * anchorDx + anchorDz * anchorDz;
            double along = shieldDx * anchorDx + shieldDz * anchorDz;
            double perpendicularSq = shieldDx * shieldDx + shieldDz * shieldDz -
                    (along * along / Math.max(anchorDistanceSq, 0.001));

            int neighborX = Math.abs(protectX - anchorX);
            int neighborZ = Math.abs(protectZ - anchorZ);

            ready = anchorDistanceSq > 0.25 && along > 0.05 &&
                    along < anchorDistanceSq && perpendicularSq <= 0.80 &&
                    protectY == anchorY && neighborX <= 1 && neighborZ <= 1 &&
                    (neighborX != 0 || neighborZ != 0) &&
                    Math.abs(playerY - protectY) <= 1.5;
        }
        return ready;
    }

    private int chargeAt(ClientWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isOf(Blocks.RESPAWN_ANCHOR)) {
            return state.get(net.minecraft.block.RespawnAnchorBlock.CHARGES);
        }
        return -1;
    }

    public BlockHitResult safeAnchorV3PlacementHit(ClientWorld world, int x, int y, int z, double[] aimOut) {
        int[][] offsets = {
            {0, -1, 0}, {0, 1, 0}, {0, 0, -1},
            {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}
        };
        Direction[] faces = {
            Direction.UP, Direction.DOWN, Direction.SOUTH,
            Direction.NORTH, Direction.EAST, Direction.WEST
        };

        for (int i = 0; i < offsets.length; i++) {
            int[] offset = offsets[i];
            BlockPos support = new BlockPos(x + offset[0], y + offset[1], z + offset[2]);
            if (safeAnchorV3Replaceable(world, support)) {
                continue;
            }
            double px = x + 0.5 + offset[0] * 0.5;
            double py = y + 0.5 + offset[1] * 0.5;
            double pz = z + 0.5 + offset[2] * 0.5;

            if (aimOut != null && aimOut.length >= 3) {
                aimOut[0] = px;
                aimOut[1] = py;
                aimOut[2] = pz;
            }
            return new BlockHitResult(new Vec3d(px, py, pz), faces[i], support, false);
        }
        return null;
    }

    public BlockHitResult safeAnchorV3AnchorHit(BlockPos pos, double pointY) {
        if (pos == null) return null;
        Vec3d point = new Vec3d(pos.getX() + 0.5, pointY, pos.getZ() + 0.5);
        return new BlockHitResult(point, Direction.UP, pos, false);
    }

    private boolean sendSyntheticLookPacket(MinecraftClient client, ClientPlayerEntity player, float yaw, float pitch) {
        if (client == null || player == null || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            return false;
        }
        boolean onGround = player.isOnGround();
        PlayerMoveC2SPacket packet = new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, onGround);
        ClientPlayNetworkHandler network = client.getNetworkHandler();
        if (network != null) {
            network.sendPacket(packet);
            return true;
        }
        return false;
    }

    private boolean targetingBlock(MinecraftClient client) {
        return client.crosshairTarget != null && client.crosshairTarget.getType() == HitResult.Type.BLOCK;
    }

    private boolean useHit(MinecraftClient client, BlockHitResult hit) {
        if (client.interactionManager == null || client.player == null) return false;
        ActionResult result = client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
        return result.isAccepted();
    }

    public boolean acquireProtectionPosition(MinecraftClient client, ClientPlayerEntity player, ClientWorld world) {
        if (client == null || player == null || world == null) return false;
        double px = player.getX();
        double pz = player.getZ();
        int playerX = (int) Math.floor(px);
        int playerY = player.getBlockY();
        int playerZ = (int) Math.floor(pz);

        class Candidate {
            int x, z;
            double playerDistanceSq;
            Candidate(int x, int z) { this.x = x; this.z = z; }
        }

        List<Candidate> candidates = new ArrayList<>();
        candidates.add(new Candidate(anchorX_ + 1, anchorZ_));
        candidates.add(new Candidate(anchorX_ - 1, anchorZ_));
        candidates.add(new Candidate(anchorX_, anchorZ_ + 1));
        candidates.add(new Candidate(anchorX_, anchorZ_ - 1));
        candidates.add(new Candidate(anchorX_ + 1, anchorZ_ + 1));
        candidates.add(new Candidate(anchorX_ + 1, anchorZ_ - 1));
        candidates.add(new Candidate(anchorX_ - 1, anchorZ_ + 1));
        candidates.add(new Candidate(anchorX_ - 1, anchorZ_ - 1));

        for (Candidate c : candidates) {
            double dx = c.x + 0.5 - px;
            double dz = c.z + 0.5 - pz;
            c.playerDistanceSq = dx * dx + dz * dz;
        }
        candidates.sort(Comparator.comparingDouble(c -> c.playerDistanceSq));

        double anchorDx = anchorX_ + 0.5 - px;
        double anchorDz = anchorZ_ + 0.5 - pz;
        double anchorDistanceSq = anchorDx * anchorDx + anchorDz * anchorDz;
        BlockPos anchorPos = safeAnchorV3Pos(anchorX_, anchorY_, anchorZ_);
        boolean anchorReady = anchorPos != null && world.getBlockState(anchorPos).isOf(Blocks.RESPAWN_ANCHOR);

        for (Candidate candidate : candidates) {
            if (candidate.x == playerX && candidate.z == playerZ && (anchorY_ == playerY || anchorY_ == playerY + 1)) {
                continue;
            }
            double candidateDx = candidate.x + 0.5 - px;
            double candidateDz = candidate.z + 0.5 - pz;
            double along = candidateDx * anchorDx + candidateDz * anchorDz;
            double perpendicularSq = candidateDx * candidateDx + candidateDz * candidateDz -
                    (along * along / Math.max(anchorDistanceSq, 0.001));

            if (along <= 0.0 || along >= anchorDistanceSq || perpendicularSq > 0.80) {
                continue;
            }

            BlockPos targetPos = safeAnchorV3Pos(candidate.x, anchorY_, candidate.z);
            boolean alreadyGlowstone = targetPos != null && world.getBlockState(targetPos).isOf(Blocks.GLOWSTONE);
            boolean replaceable = targetPos != null && safeAnchorV3Replaceable(world, targetPos);

            if (!replaceable && !alreadyGlowstone) continue;
            if (replaceable && safeAnchorV3EntityBlocked(client, world, candidate.x, anchorY_, candidate.z)) continue;

            if (replaceable && anchorReady) {
                BlockHitResult placement = safeAnchorV3PlacementHit(world, candidate.x, anchorY_, candidate.z, null);
                if (placement == null) continue;
            }
            protectX_ = candidate.x;
            protectY_ = anchorY_;
            protectZ_ = candidate.z;
            return true;
        }
        return false;
    }

    public boolean acquirePositions(MinecraftClient client, ClientPlayerEntity player, ClientWorld world) {
        if (!targetingBlock(client)) return false;
        HitResult hit = client.crosshairTarget;
        if (!(hit instanceof BlockHitResult blockHit)) return false;

        BlockPos hitPos = blockHit.getBlockPos();
        Direction side = blockHit.getSide();
        if (hitPos == null || side == null) return false;

        anchorX_ = hitPos.getX();
        anchorY_ = hitPos.getY();
        anchorZ_ = hitPos.getZ();

        if (!safeAnchorV3Replaceable(world, hitPos)) {
            anchorX_ += side.getOffsetX();
            anchorY_ += side.getOffsetY();
            anchorZ_ += side.getOffsetZ();
        }

        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();

        double cx = anchorX_ + 0.5;
        double cy = anchorY_ + 0.5;
        double cz = anchorZ_ + 0.5;
        double distance = Math.sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy) + (pz - cz) * (pz - cz));

        if (distance > range_ / 10.0) {
            return false;
        }

        boolean foundProtection = acquireProtectionPosition(client, player, world);
        havePositions_ = foundProtection;
        return foundProtection;
    }

    public boolean placeAt(MinecraftClient client, ClientPlayerEntity player, ClientWorld world, boolean anchorItem) {
        int x = anchorItem ? anchorX_ : protectX_;
        int y = anchorItem ? anchorY_ : protectY_;
        int z = anchorItem ? anchorZ_ : protectZ_;
        BlockPos targetPos = safeAnchorV3Pos(x, y, z);
        if (targetPos == null || !safeAnchorV3Replaceable(world, targetPos)) {
            return false;
        }
        int slot = findHot(player, anchorItem ? "respawn_anchor" : "glowstone");
        if (slot < 0) return false;

        player.getInventory().selectedSlot = slot;

        double[] aim = new double[3];
        BlockHitResult placement = safeAnchorV3PlacementHit(world, x, y, z, aim);
        boolean accepted = false;
        if (placement != null) {
            int action = anchorItem ? 3 : 1;
            lastActionSucceeded_ = false;
            rotateTo(client, player, aim[0], aim[1], aim[2], action);
            accepted = lastActionSucceeded_ || pendingAction_ == action;
        }
        return accepted;
    }

    public boolean chargeAnchor(MinecraftClient client) {
        if (client.world == null || client.player == null) return false;
        ClientWorld world = client.world;
        ClientPlayerEntity player = client.player;

        BlockPos anchorPos = safeAnchorV3Pos(anchorX_, anchorY_, anchorZ_);
        if (anchorPos == null || safeAnchorV3Replaceable(world, anchorPos)) return false;

        int glowstone = findHot(player, "glowstone");
        if (glowstone < 0) return false;

        player.getInventory().selectedSlot = glowstone;
        lastActionSucceeded_ = false;
        rotateTo(client, player, anchorX_ + 0.5, anchorY_ + 0.5, anchorZ_ + 0.5, 4);
        return lastActionSucceeded_ || pendingAction_ == 4;
    }

    public boolean interactAnchor(MinecraftClient client) {
        if (client.player == null) return false;
        ClientPlayerEntity player = client.player;
        Vec3d eye = player.getEyePos();
        double aimY = anchorY_ + 1.0 > eye.getY() ? anchorY_ + 0.5 : anchorY_ + 1.0;

        lastActionSucceeded_ = false;
        rotateTo(client, player, anchorX_ + 0.5, aimY, anchorZ_ + 0.5, 2);
        return lastActionSucceeded_ || pendingAction_ == 2;
    }

    public void rotateTo(MinecraftClient client, ClientPlayerEntity player, double x, double y, double z, int action) {
        silentRotations_ = true;
        Vec3d eye = player.getEyePos();
        double dx = x - eye.getX();
        double dy = y - eye.getY();
        double dz = z - eye.getZ();

        targetYaw_ = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI - 90.0);
        targetPitch_ = (float) (-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180.0 / Math.PI);

        currentYaw_ = targetYaw_;
        currentPitch_ = targetPitch_;
        pendingTicks_ = 0;
        smoothInitialized_ = true;
        smoothDone_ = true;
        stageSilentRotation(player, targetYaw_, targetPitch_);
        pendingAction_ = action;
        silentRotationPrimed_ = sendSyntheticLookPacket(client, player, targetYaw_, targetPitch_);
    }

    public void stageSilentRotation(ClientPlayerEntity player, float yaw, float pitch) {
        if (player == null) return;
        currentYaw_ = yaw;
        currentPitch_ = pitch;
        silentPovStaged_ = Float.isFinite(yaw) && Float.isFinite(pitch);
    }

    public void holdAimOnAnchor(MinecraftClient client) {
        if (client.player == null || !havePositions_) return;
        ClientPlayerEntity player = client.player;
        Vec3d eye = player.getEyePos();

        boolean focusProtection = remoteFocus_ == 1 && step_ >= 4;
        double focusX = (focusProtection ? protectX_ : anchorX_) + 0.5;
        double focusY = (focusProtection ? protectY_ : anchorY_) + 0.5 +
                Math.sin((double) (++remoteBobTick_) * 0.65) * 0.035;
        double focusZ = (focusProtection ? protectZ_ : anchorZ_) + 0.5;

        double dx = focusX - eye.getX();
        double dy = focusY - eye.getY();
        double dz = focusZ - eye.getZ();
        doub
