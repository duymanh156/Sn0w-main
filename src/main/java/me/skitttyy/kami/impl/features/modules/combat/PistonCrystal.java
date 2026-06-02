package me.skitttyy.kami.impl.features.modules.combat;

import lombok.Getter;
import lombok.Setter;
import me.skitttyy.kami.api.event.eventbus.SubscribeEvent;
import me.skitttyy.kami.api.event.events.TickEvent;
import me.skitttyy.kami.api.feature.module.Module;
import me.skitttyy.kami.api.management.FriendManager;
import me.skitttyy.kami.api.utils.NullUtils;
import me.skitttyy.kami.api.utils.Timer;
import me.skitttyy.kami.api.utils.chat.ChatUtils;
import me.skitttyy.kami.api.utils.players.InventoryUtils;
import me.skitttyy.kami.api.utils.players.PlayerUtils;
import me.skitttyy.kami.api.utils.players.rotation.RotationUtils;
import me.skitttyy.kami.api.utils.world.BlockUtils;
import me.skitttyy.kami.api.utils.world.CrystalUtil;
import me.skitttyy.kami.api.value.Value;
import me.skitttyy.kami.api.value.builder.ValueBuilder;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class PistonCrystal extends Module {
    
    // Timers for delay management
    private final Timer timer = new Timer();
    private final Timer attackTimer = new Timer();
    
    // Minimum damage required to place piston-crystal combo
    Value<Number> minDamage = new ValueBuilder<Number>()
            .withDescriptor("MinDamage")
            .withValue(7.0)
            .withRange(0d, 20d)
            .withPlaces(1)
            .register(this);
    
    // Maximum self damage allowed (safety)
    Value<Number> maxLocalDamage = new ValueBuilder<Number>()
            .withDescriptor("MaxLocalDamage")
            .withValue(13.0)
            .withRange(0d, 36d)
            .withPlaces(1)
            .register(this);
    
    // Delay between each placement action (in ticks)
    Value<Number> delay = new ValueBuilder<Number>()
            .withDescriptor("Delay")
            .withValue(4)
            .withRange(0, 20)
            .withAction(set -> timer.setDelay(set.getValue().intValue()))
            .register(this);
    
    // Wait time before attacking crystal after placement (in ticks)
    Value<Number> attackWait = new ValueBuilder<Number>()
            .withDescriptor("AttackWait")
            .withValue(2)
            .withRange(0, 10)
            .withAction(set -> attackTimer.setDelay(set.getValue().intValue()))
            .register(this);
    
    // 1.12 crystal placement mode (different crystal hitbox)
    Value<Boolean> v1_12 = new ValueBuilder<Boolean>()
            .withDescriptor("1.12")
            .withValue(false)
            .register(this);
    
    // Range to search for targets
    Value<Number> targetRange = new ValueBuilder<Number>()
            .withDescriptor("TargetRange")
            .withValue(6.0)
            .withRange(1d, 12d)
            .register(this);
    
    // Sorting mode for position selection
    Value<SortMode> sort = new ValueBuilder<SortMode>()
            .withDescriptor("Sort")
            .withValue(SortMode.LOWEST_DISTANCE)
            .withModes(SortMode.values())
            .register(this);
    
    // Whether to target players who are in air (not on ground)
    Value<Boolean> inAirTarget = new ValueBuilder<Boolean>()
            .withDescriptor("InAirTarget")
            .withValue(true)
            .register(this);
    
    // Whether to rotate to face placement position (uses PistonKick rotation)
    Value<Boolean> rotate = new ValueBuilder<Boolean>()
            .withDescriptor("Rotate")
            .withValue(true)
            .register(this);
    
    // Grim anti-cheat mode (stricter placement checks)
    Value<Boolean> grim = new ValueBuilder<Boolean>()
            .withDescriptor("Grim")
            .withValue(true)
            .register(this);
    
    // Whether to break the crystal after placing
    Value<Boolean> breakCrystal = new ValueBuilder<Boolean>()
            .withDescriptor("BreakCrystal")
            .withValue(true)
            .register(this);
    
    // Maximum distance to place blocks and crystals
    Value<Number> placeRange = new ValueBuilder<Number>()
            .withDescriptor("PlaceRange")
            .withValue(5.5)
            .withRange(1d, 8d)
            .register(this);
    
    // Maximum distance to break crystals
    Value<Number> breakRange = new ValueBuilder<Number>()
            .withDescriptor("BreakRange")
            .withValue(3.0)
            .withRange(1d, 6d)
            .register(this);
    
    // Item swap action mode
    Value<SwapAction> swapAction = new ValueBuilder<SwapAction>()
            .withDescriptor("SwapAction")
            .withValue(SwapAction.SCREEN)
            .withModes(SwapAction.values())
            .register(this);
    
    // Safety check for self damage
    Value<Boolean> safety = new ValueBuilder<Boolean>()
            .withDescriptor("Safety")
            .withValue(true)
            .register(this);
    
    // Predict target movement based on velocity
    Value<Boolean> selfExtrapolate = new ValueBuilder<Boolean>()
            .withDescriptor("SelfExtrapolate")
            .withValue(true)
            .register(this);
    
    // Number of ticks to extrapolate target position
    Value<Number> extrapolationTicks = new ValueBuilder<Number>()
            .withDescriptor("ExtrapolationTicks")
            .withValue(3)
            .withRange(0, 10)
            .register(this);
    
    // Assume target has best armor for damage calculation
    Value<Boolean> assumeBestArmor = new ValueBuilder<Boolean>()
            .withDescriptor("AssumeBestArmor")
            .withValue(true)
            .register(this);
    
    // Sort mode enumeration
    public enum SortMode {
        CLOSEST_ANGLE,   // Sort by closest to current look angle
        LOWEST_DISTANCE, // Sort by closest distance to player
        LOWEST_HEALTH    // Sort by target's lowest health
    }
    
    // Swap action enumeration
    public enum SwapAction {
        SCREEN,   // Find item in entire inventory (slots 0-35), use ghost swap (packet)
        HOTBAR,   // Find item only in hotbar (slots 0-8), change client slot normally
        NONE      // Don't search, only place if already holding correct item
    }
    
    // State variables
    private PlayerEntity target;
    private PistonData currentData;
    private boolean didPiston;
    private boolean didRedstone;
    private boolean didCrystal;
    private int extrapolationOffset;
    
    // Constructor
    public PistonCrystal() {
        super("PistonCrystal", Category.Combat);
    }
    
    // Main update method called every tick
    @SubscribeEvent
    public void onUpdate(final TickEvent.PlayerTickEvent.Pre event) {
        // Null check for player and world
        if (NullUtils.nullCheck()) return;
        
        // Update extrapolation offset if enabled
        if (selfExtrapolate.getValue() && extrapolationOffset < extrapolationTicks.getValue().intValue()) {
            extrapolationOffset++;
        }
        
        // Find target player
        target = getTarget();
        if (target == null) {
            resetState();
            return;
        }
        
        // Check delay timer
        if (!timer.hasPassed(delay.getValue().intValue() * 50L)) return;
        
        // Get best piston position if not already calculated
        if (currentData == null) {
            currentData = getBestPosition(target);
            if (currentData == null) return;
            
            // Check damage requirements
            if (currentData.damage < minDamage.getValue().doubleValue()) return;
            if (safety.getValue() && currentData.selfDamage > maxLocalDamage.getValue().doubleValue()) return;
        }
        
        // Sequential placement logic
        if (!didPiston) {
            if (!tryPlace(currentData.pistonPos, getPistonItem(), true)) return;
            didPiston = true;
        } else if (!didRedstone) {
            if (!tryPlace(currentData.redstonePos, getRedstoneItem(), true)) return;
            didRedstone = true;
            attackTimer.reset();
        } else if (!didCrystal) {
            if (!attackTimer.hasPassed(attackWait.getValue().intValue() * 50L)) return;
            if (!tryPlace(currentData.crystalPos, Items.END_CRYSTAL, false)) return;
            didCrystal = true;
            attackTimer.reset();
        } else {
            // All items placed, attack crystal if enabled
            if (breakCrystal.getValue() && attackTimer.hasPassed(attackWait.getValue().intValue() * 50L)) {
                attackCrystal();
            }
            resetState();
            toggle();
            return;
        }
        
        timer.reset();
    }
    
    // Attempt to place a block at the specified position
    private boolean tryPlace(BlockPos pos, Item item, boolean needDirection) {
        // For NONE mode: check if already holding correct item
        if (swapAction.getValue() == SwapAction.NONE) {
            if (!mc.player.getMainHandStack().getItem().equals(item)) {
                return false;
            }
        }
        
        // Find item slot based on swap action
        int slot = -1;
        switch (swapAction.getValue()) {
            case SCREEN:
                slot = getInventoryItemSlot(item);
                if (slot != -1) InventoryUtils.switchToSlotGhost(slot);
                break;
            case HOTBAR:
                slot = InventoryUtils.getHotbarItemSlot(item);
                if (slot != -1) InventoryUtils.switchToSlot(slot);
                break;
            case NONE:
                slot = mc.player.getInventory().selectedSlot;
                break;
        }
        
        // Check if item was found
        if (slot == -1) return false;
        
        // Verify we are holding correct item after swap
        if (!mc.player.getMainHandStack().getItem().equals(item)) return false;
        
        // Get placement direction
        Direction side = null;
        if (needDirection) {
            side = BlockUtils.getPlaceableSide(pos, grim.getValue());
            if (side == null) return false;
        } else {
            side = Direction.UP;
        }
        
        // Rotate to face placement position if enabled (using PistonKick rotation if available)
        if (rotate.getValue()) {
            if (PistonKick.INSTANCE != null && PistonKick.INSTANCE.isEnabled()) {
                // Use PistonKick's rotation logic
                if (needDirection) {
                    RotationUtils.doRotate(pos, side);
                } else {
                    RotationUtils.doRotate(pos);
                }
            } else {
                // Fallback to normal rotation
                if (needDirection) {
                    RotationUtils.doRotate(pos, side);
                } else {
                    RotationUtils.doRotate(pos);
                }
            }
        }
        
        // Place the block
        int oldSlot = mc.player.getInventory().selectedSlot;
        boolean success = BlockUtils.placeBlock(pos, side, false, true);
        
        // Restore old slot for HOTBAR mode
        if (swapAction.getValue() == SwapAction.HOTBAR && oldSlot != mc.player.getInventory().selectedSlot) {
            InventoryUtils.switchToSlot(oldSlot);
        }
        
        return success;
    }
    
    // Search entire inventory (slots 0-35) for an item
    private int getInventoryItemSlot(Item item) {
        for (int i = 0; i <= 35; i++) {
            if (mc.player.getInventory().getStack(i).getItem().equals(item)) {
                return i;
            }
        }
        return -1;
    }
    
    // Get piston item (prefer normal piston, fallback to sticky piston)
    private Item getPistonItem() {
        if (InventoryUtils.getHotbarItemSlot(Items.PISTON) != -1) return Items.PISTON;
        if (InventoryUtils.getHotbarItemSlot(Items.STICKY_PISTON) != -1) return Items.STICKY_PISTON;
        return Items.PISTON;
    }
    
    // Get redstone item (prefer torch, fallback to block)
    private Item getRedstoneItem() {
        if (InventoryUtils.getHotbarItemSlot(Items.REDSTONE_TORCH) != -1) return Items.REDSTONE_TORCH;
        if (InventoryUtils.getHotbarItemSlot(Items.REDSTONE_BLOCK) != -1) return Items.REDSTONE_BLOCK;
        return Items.REDSTONE_TORCH;
    }
    
    // Attack nearest crystal within break range
    private void attackCrystal() {
        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof EndCrystalEntity crystal && crystal.isAlive() 
                && mc.player.distanceTo(crystal) <= breakRange.getValue().doubleValue()) {
                if (rotate.getValue()) {
                    float[] rots = RotationUtils.getRotationsTo(mc.player.getEyePos(), crystal.getPos());
                    RotationUtils.setRotation(rots);
                }
                PlayerUtils.attackTarget(crystal);
                break;
            }
        }
    }
    
    // Find best piston-crystal position for the target
    private PistonData getBestPosition(PlayerEntity target) {
        List<PistonData> positions = new ArrayList<>();
        BlockPos targetPos = getTargetPosition(target);
        
        // Check three levels: feet, body, and head
        BlockPos[] levels = {targetPos, targetPos.up(), targetPos.up(2)};
        
        for (BlockPos level : levels) {
            for (Direction dir : Direction.values()) {
                // Only horizontal directions
                if (!dir.getAxis().isHorizontal()) continue;
                
                BlockPos pistonPos = level.offset(dir);
                if (!canPlacePiston(pistonPos, dir)) continue;
                
                for (Direction redstoneDir : Direction.values()) {
                    // Redstone cannot be opposite to piston direction or vertical
                    if (redstoneDir == dir.getOpposite() || redstoneDir.getAxis().isVertical()) continue;
                    
                    BlockPos redstonePos = pistonPos.offset(redstoneDir);
                    if (!BlockUtils.canPlaceBlock(redstonePos, grim.getValue(), pistonPos)) continue;
                    if (!BlockUtils.isInRange(redstonePos, placeRange.getValue().floatValue())) continue;
                    
                    // Calculate position after piston push
                    BlockPos pushedPos = level.offset(dir, 2);
                    BlockPos crystalPos = pushedPos.up();
                    
                    // Calculate damage to target and self
                    float damage = CrystalUtil.calculateDamage(target, crystalPos.toCenterPos(), false, false);
                    float selfDamage = CrystalUtil.calculateDamage(mc.player, crystalPos.toCenterPos(), false, false);
                    
                    // Apply best armor reduction if enabled
                    if (assumeBestArmor.getValue()) {
                        damage *= 0.36f;
                        selfDamage *= 0.36f;
                    }
                    
                    // Calculate angle to position for sorting
                    float angle = getAngleTo(pistonPos);
                    
                    positions.add(new PistonData(pistonPos, redstonePos, crystalPos, damage, selfDamage, angle, target.getHealth()));
                }
            }
        }
        
        if (positions.isEmpty()) return null;
        
        // Sort positions based on selected mode
        switch (sort.getValue()) {
            case CLOSEST_ANGLE:
                positions.sort(Comparator.comparingDouble(p -> p.angle));
                break;
            case LOWEST_HEALTH:
                positions.sort(Comparator.comparingDouble(p -> p.targetHealth));
                break;
            case LOWEST_DISTANCE:
            default:
                positions.sort(Comparator.comparingDouble(p -> mc.player.getPos().distanceTo(p.pistonPos.toCenterPos())));
                break;
        }
        
        // Filter by damage requirements
        positions.removeIf(p -> p.damage < minDamage.getValue().doubleValue());
        if (safety.getValue()) {
            positions.removeIf(p -> p.selfDamage > maxLocalDamage.getValue().doubleValue());
        }
        
        return positions.isEmpty() ? null : positions.get(0);
    }
    
    // Get target position with optional extrapolation
    private BlockPos getTargetPosition(PlayerEntity target) {
        BlockPos pos = target.getBlockPos();
        
        if (selfExtrapolate.getValue() && extrapolationOffset > 0) {
            Vec3d velocity = target.getVelocity();
            if (velocity.length() > 0.1) {
                int ticks = Math.min(extrapolationOffset, extrapolationTicks.getValue().intValue());
                double offsetX = velocity.x * ticks;
                double offsetZ = velocity.z * ticks;
                pos = new BlockPos(
                    (int) Math.round(target.getX() + offsetX),
                    (int) target.getY(),
                    (int) Math.round(target.getZ() + offsetZ)
                );
            }
        }
        
        return pos;
    }
    
    // Calculate angle difference between player look direction and target position
    private float getAngleTo(BlockPos pos) {
        float[] rots = RotationUtils.getRotationsTo(mc.player.getEyePos(), pos.toCenterPos());
        float deltaYaw = Math.abs(MathHelper.wrapDegrees(rots[0] - mc.player.getYaw()));
        float deltaPitch = Math.abs(rots[1] - mc.player.getPitch());
        return deltaYaw + deltaPitch;
    }
    
    // Check if piston can be placed at position
    private boolean canPlacePiston(BlockPos pos, Direction dir) {
        // Check if block can be placed
        if (!BlockUtils.canPlaceBlock(pos, grim.getValue())) return false;
        
        // Check range
        if (!BlockUtils.isInRange(pos, placeRange.getValue().floatValue())) return false;
        
        // Check space behind piston for extension
        BlockPos behind = pos.offset(dir.getOpposite(), 2);
        if (!mc.world.getBlockState(behind).isAir()) return false;
        
        return true;
    }
    
    // Find nearest player target within range
    private PlayerEntity getTarget() {
        PlayerEntity closest = null;
        double closestDist = targetRange.getValue().doubleValue();
        
        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof PlayerEntity player && player != mc.player 
                && !FriendManager.INSTANCE.isFriend(player) && player.isAlive()
                && mc.player.distanceTo(player) <= closestDist) {
                
                // Check if target is on ground or in air (based on setting)
                if (inAirTarget.getValue() || player.isOnGround()) {
                    double dist = mc.player.distanceTo(player);
                    if (dist < closestDist) {
                        closestDist = dist;
                        closest = player;
                    }
                }
            }
        }
        return closest;
    }
    
    // Check if player has required items based on swap action mode
    private boolean hasRequiredItems() {
        switch (swapAction.getValue()) {
            case NONE:
                return true;
            case HOTBAR:
                return InventoryUtils.getHotbarItemSlot(Items.PISTON) != -1 
                    || InventoryUtils.getHotbarItemSlot(Items.STICKY_PISTON) != -1;
            case SCREEN:
            default:
                return getInventoryItemSlot(Items.PISTON) != -1 
                    || getInventoryItemSlot(Items.STICKY_PISTON) != -1;
        }
    }
    
    // Reset all state variables
    private void resetState() {
        currentData = null;
        didPiston = false;
        didRedstone = false;
        didCrystal = false;
        extrapolationOffset = 0;
    }
    
    // Called when module is enabled
    @Override
    public void onEnable() {
        super.onEnable();
        if (NullUtils.nullCheck()) return;
        resetState();
        timer.reset();
        attackTimer.reset();
        
        // Check if PistonKick is available for rotation
        if (rotate.getValue() && PistonKick.INSTANCE == null) {
            ChatUtils.sendMessage("[PistonCrystal] PistonKick is not loaded! Rotation may not work properly.");
        }
        
        // Check for required items
        if (!hasRequiredItems()) {
            ChatUtils.sendMessage("[PistonCrystal] Missing required items!");
            toggle();
        }
    }
    
    // Module description
    @Override
    public String getDescription() {
        return "PistonCrystal - Push target into crystal using pistons at feet, body, and head levels";
    }
    
    // Data class for piston-crystal position information
    @Getter
    @Setter
    public static class PistonData {
        private final BlockPos pistonPos;
        private final BlockPos redstonePos;
        private final BlockPos crystalPos;
        private final float damage;
        private final float selfDamage;
        private final float angle;
        private final float targetHealth;
        
        public PistonData(BlockPos pistonPos, BlockPos redstonePos, BlockPos crystalPos,
                         float damage, float selfDamage, float angle, float targetHealth) {
            this.pistonPos = pistonPos;
            this.redstonePos = redstonePos;
            this.crystalPos = crystalPos;
            this.damage = damage;
            this.selfDamage = selfDamage;
            this.angle = angle;
            this.targetHealth = targetHealth;
        }
    }
}