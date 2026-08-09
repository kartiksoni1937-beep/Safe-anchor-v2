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
import net.minecraft.entity.ExperienceOrbEntity;
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
    private boolean disabled_ = false;
    private int clock_ = 0;
    private int step_ = 0;
    private boolean previousZPressed_ = false;
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

    @Override
    public void onInitializeClient() {
        LOGGER.info("Initializing Example Mod with SafeAnchor logic!");

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            tick(client);
            endTick(client);
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
        smoothInitialized_ = false;
        smoothDone_ = false;
        targetYaw_ = 0.0f;
        targetPitch_ = 0.0f;
        currentYaw_ = 0.0f;
        currentPitch_ = 0.0f;
        anchorX_ = 0;
        anchorY_ = 0;
        anchorZ_ = 0;
        protectX_ = 0;
        protectY_ = 0;
        protectZ_ = 0;
    }

    public void startSequence(ClientPlayerEntity player) {
        disabled_ = false;
        resetState();
        active_ = true;
        safetySequenceActive_ = true;
        if (player != null) {
            currentYaw_ = player.getYaw();
            currentPitch_ = player.getPitch();
            targetYaw_ = currentYaw_;
            targetPitch_ = currentPitch_;
        }
        smoothInitialized_ = true;
        smoothDone_ = true;
    }

    public void endTick(MinecraftClient client) {
        if (client == null || client.player == null) {
            return;
        }
        if (pendingAction_ != 0) {
            runPendingAction(client);
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
            boolean nonBlockingDrop = (entity instanceof ItemEntity) || (entity instanceof ExperienceOrbEntity);
    
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

    private float wrapDegrees(float angle) {
        angle %= 360.0f;
        if (angle >= 180.0f) angle -= 360.0f;
        if (angle < -180.0f) angle += 360.0f;
        return angle;
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
        int action = anchorItem ? 3 : 1;
        if (pendingAction_ != 0) {
            return pendingAction_ == action;
        }

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
        if (placement == null) {
            return false;
        }

        lastActionSucceeded_ = false;
        pendingTicks_ = 0;
        rotateTo(client, player, aim[0], aim[1], aim[2], action);
        if (anchorItem) {
            anchorWait_ = 1;
        }
        return pendingAction_ == action;
    }

    public boolean chargeAnchor(MinecraftClient client) {
        if (pendingAction_ != 0) {
            return pendingAction_ == 4;
        }
        if (client.world == null || client.player == null) return false;
        ClientWorld world = client.world;
        ClientPlayerEntity player = client.player;

        BlockPos anchorPos = safeAnchorV3Pos(anchorX_, anchorY_, anchorZ_);
        if (anchorPos == null || safeAnchorV3Replaceable(world, anchorPos)) return false;

        int glowstone = findHot(player, "glowstone");
        if (glowstone < 0) return false;

        player.getInventory().selectedSlot = glowstone;
        lastActionSucceeded_ = false;
        pendingTicks_ = 0;
        rotateTo(client, player, anchorX_ + 0.5, anchorY_ + 0.5, anchorZ_ + 0.5, 4);
        return pendingAction_ == 4;
    }

    public boolean interactAnchor(MinecraftClient client) {
        if (pendingAction_ != 0) {
            return pendingAction_ == 2;
        }
        if (client.player == null) return false;
        ClientPlayerEntity player = client.player;
        Vec3d eye = player.getEyePos();
        double aimY = anchorY_ + 1.0 > eye.getY() ? anchorY_ + 0.5 : anchorY_ + 1.0;

        lastActionSucceeded_ = false;
        pendingTicks_ = 0;
        rotateTo(client, player, anchorX_ + 0.5, aimY, anchorZ_ + 0.5, 2);
        return pendingAction_ == 2;
    }

    public void rotateTo(MinecraftClient client, ClientPlayerEntity player, double x, double y, double z, int action) {
        Vec3d eye = player.getEyePos();
        double dx = x - eye.getX();
        double dy = y - eye.getY();
        double dz = z - eye.getZ();

        targetYaw_ = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI - 90.0);
        targetPitch_ = (float) (-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180.0 / Math.PI);

        currentYaw_ = player.getYaw();
        currentPitch_ = player.getPitch();
        pendingTicks_ = 0;
        smoothInitialized_ = true;
        smoothDone_ = false;
        pendingAction_ = action;

        // Send a single temporary look packet so the server receives the correct
        // aim for the upcoming interaction, but do not keep the camera locked.
        sendSyntheticLookPacket(client, player, targetYaw_, targetPitch_);
    }

    public void runPendingAction(MinecraftClient client) {
        int action = pendingAction_;
        pendingAction_ = 0;
        pendingTicks_ = 0;
        lastActionSucceeded_ = false;

        if (action == 0 || !havePositions_) return;

        if (action == 1 || action == 3) {
            ClientWorld world = client.world;
            if (world != null) {
                int targetX = (action == 3) ? anchorX_ : protectX_;
                int targetY = (action == 3) ? anchorY_ : protectY_;
                int targetZ = (action == 3) ? anchorZ_ : protectZ_;
                BlockHitResult hit = safeAnchorV3PlacementHit(world, targetX, targetY, targetZ, null);
                if (hit != null) {
                    lastActionSucceeded_ = useHit(client, hit);
                }
            }
        } else if (action == 2 || action == 4) {
            BlockPos anchorPos = safeAnchorV3Pos(anchorX_, anchorY_, anchorZ_);
            BlockHitResult hit = anchorPos != null ? safeAnchorV3AnchorHit(anchorPos, anchorY_ + 0.5) : null;
            if (hit != null) {
                boolean safeToExplode = action != 2 || safeAnchorV3ProtectionReady(client, anchorX_, anchorY_, anchorZ_, protectX_, protectY_, protectZ_);
                if (safeToExplode) {
                    lastActionSucceeded_ = useHit(client, hit);
                }
                if (action == 4 && lastActionSucceeded_) {
                    chargeAccepted_ = true;
                }
            }
        }

        if (lastActionSucceeded_) {
            // Action succeeded; continue waiting for world confirmation.
        }
    }

    public void tick(MinecraftClient client) {
        if (client.player == null || client.world == null || client.player.isDead()) {
            if (active_) resetState();
            return;
        }

        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;

        boolean zPressed = GLFW.glfwGetKey(client.getWindow().getHandle(), GLFW.GLFW_KEY_Z) == GLFW.GLFW_PRESS;
        boolean zJustPressed = zPressed && !previousZPressed_;
        previousZPressed_ = zPressed;

        if (zJustPressed) {
            if (active_) {
                resetState();
                disabled_ = true;
                return;
            }
            startSequence(player);
        }

        if (!active_) {
            if (!disabled_ && hasRequiredItems(player) && targetingBlock(client)) {
                startSequence(player);
            } else {
                smoothInitialized_ = false;
                smoothDone_ = false;
                return;
            }
        }

        if (!hasRequiredItems(player)) {
            if (active_) resetState();
            return;
        }

        if (pendingAction_ != 0) {
            pendingTicks_++;
            if (pendingTicks_ > 6) {
                pendingAction_ = 0;
                pendingTicks_ = 0;
                lastActionSucceeded_ = false;
                anchorWait_ = 0;
                chargeWait_ = 0;
                protectionWait_ = 0;
                protectionSent_ = false;
            }
        } else {
            pendingTicks_ = 0;
        }

        if (clock_ < switchDelay_) {
            clock_++;
            return;
        }
        clock_ = 0;

        switch (step_) {
            case 0:
                if (!acquirePositions(client, player, world)) {
                    resetState();
                } else {
                    step_++;
                }
                break;
            case 1:
                if (pendingAction_ == 0 && anchorWait_ == 0) {
                    if (!placeAt(client, player, world, true)) {
                        resetState();
                        break;
                    }
                }
                if (pendingAction_ != 0) {
                    break;
                }
                BlockPos anchor = safeAnchorV3Pos(anchorX_, anchorY_, anchorZ_);
                boolean anchorVisible = anchor != null && world.getBlockState(anchor).isOf(Blocks.RESPAWN_ANCHOR);
                boolean anchorSpaceOpen = anchor != null && safeAnchorV3Replaceable(world, anchor);
                if (!anchorVisible) {
                    if (!anchorSpaceOpen) {
                        resetState();
                        break;
                    }
                    if (anchorWait_ == 0) {
                        anchorWait_ = 1;
                        break;
                    }
                    if (++anchorWait_ > 8) {
                        resetState();
                        break;
                    }
                    break;
                }
                anchorWait_ = 0;
                step_++;
                break;
            case 2:
                anchor = safeAnchorV3Pos(anchorX_, anchorY_, anchorZ_);
                anchorVisible = anchor != null && world.getBlockState(anchor).isOf(Blocks.RESPAWN_ANCHOR);
                int existingCharge = anchorVisible ? chargeAt(world, anchor) : -1;
                if (!anchorVisible) {
                    step_ = 1;
                    break;
                }
                if (existingCharge > 0) {
                    chargeAccepted_ = true;
                    step_++;
                    break;
                }
                if (chargeAccepted_) {
                    step_++;
                    break;
                }
                if (chargeAnchor(client)) {
                    break;
                }
                break;
            case 3:
                BlockPos chargedAnchor = safeAnchorV3Pos(anchorX_, anchorY_, anchorZ_);
                int charge = chargedAnchor != null ? chargeAt(world, chargedAnchor) : -1;
                if (charge < 0) {
                    resetState();
                    break;
                }
                if (charge == 0) {
                    if (pendingAction_ == 4) break;
                    if (!chargeAccepted_) {
                        step_ = 2;
                        break;
                    }
                }
                chargeWait_ = 0;
                if (!protectionSent_ && !acquireProtectionPosition(client, player, world)) {
                    protectionWait_ = 0;
                    break;
                }
                BlockPos protection = safeAnchorV3Pos(protectX_, protectY_, protectZ_);
                boolean replaceable = protection != null && safeAnchorV3Replaceable(world, protection);
                boolean glowstonePlaced = protection != null && world.getBlockState(protection).isOf(Blocks.GLOWSTONE);

                if (glowstonePlaced) {
                    protectionSent_ = false;
                    protectionWait_ = 0;
                    protectionAttempts_ = 0;
                    step_++;
                } else if (!replaceable) {
                    protectionSent_ = false;
                    protectionWait_ = 0;
                    protectionAttempts_ = 0;
                    acquireProtectionPosition(client, player, world);
                } else if (!protectionSent_) {
                    protectionSent_ = placeAt(client, player, world, false);
                    protectionWait_ = 0;
                    protectionAttempts_++;
                } else if (pendingAction_ != 0) {
                    protectionWait_ = 0;
                } else if (!glowstonePlaced && ++protectionWait_ > Math.min(4, protectionAttempts_)) {
                    protectionSent_ = false;
                    protectionWait_ = 0;
                }
                break;
            case 4:
                player.getInventory().selectedSlot = Math.clamp(explosionSlot_, 1, 9) - 1;
                step_++;
                break;
            case 5:
                if (!safeAnchorV3ProtectionReady(client, anchorX_, anchorY_, anchorZ_, protectX_, protectY_, protectZ_)) {
                    BlockPos anchorBlock = safeAnchorV3Pos(anchorX_, anchorY_, anchorZ_);
                    int currentCharge = anchorBlock != null ? chargeAt(world, anchorBlock) : -1;
                    if (currentCharge <= 0) {
                        if (++chargeWait_ > 1) resetState();
                        break;
                    }
                    protectionSent_ = false;
                    protectionWait_ = 0;
                    acquireProtectionPosition(client, player, world);
                    step_ = 3;
                    break;
                }
                chargeWait_ = 0;
                if (!interactAnchor(client)) {
                    resetState();
                    break;
                }
                if (pendingAction_ == 0) {
                    step_++;
                }
                break;
            case 6:
                BlockPos finalAnchor = safeAnchorV3Pos(anchorX_, anchorY_, anchorZ_);
                int finalCharge = finalAnchor != null ? chargeAt(world, finalAnchor) : -1;
                if (finalCharge < 0) {
                    resetState();
                    break;
                }
                if (!lastActionSucceeded_ || ++explosionWait_ > 0) {
                    explosionWait_ = 0;
                    resetState();
                }
                break;
            default:
                resetState();
                break;
        }
    } 
} //
