package me.biquaternions.inputreplay.client.session;

import me.biquaternions.inputreplay.client.io.Bin;
import me.biquaternions.inputreplay.client.mixin.client.LocalPlayerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/** Per-tick snapshot of the local player's physics state (SNAPSHOT record body, see irr_format.md). */
public final class PlayerSnapshotter {
    private PlayerSnapshotter() {}

    public static final int F_ON_GROUND = 1;
    public static final int F_HORIZONTAL_COLLISION = 1 << 1;
    public static final int F_VERTICAL_COLLISION = 1 << 2;
    public static final int F_SPRINTING = 1 << 3;
    public static final int F_SWIMMING = 1 << 4;
    public static final int F_FALL_FLYING = 1 << 5;
    public static final int F_SHIFT_KEY_DOWN = 1 << 6;
    public static final int F_CROUCHING = 1 << 7;
    public static final int F_USING_ITEM = 1 << 8;
    public static final int F_MINOR_HORIZONTAL_COLLISION = 1 << 9;

    public static int inputBits(Input in) {
        if (in == null) return 0;
        int b = 0;
        if (in.forward()) b |= 1;
        if (in.backward()) b |= 2;
        if (in.left()) b |= 4;
        if (in.right()) b |= 8;
        if (in.jump()) b |= 16;
        if (in.shift()) b |= 32;
        if (in.sprint()) b |= 64;
        return b;
    }

    /** SNAPSHOT record body for the local player at the end of a tick. */
    public static Bin snapshot(Minecraft mc, LocalPlayer p) {
        Bin b = new Bin(160);
        Vec3 v = p.getDeltaMovement();
        b.f64(p.getX()).f64(p.getY()).f64(p.getZ());
        b.f32(p.getYRot()).f32(p.getXRot()).f32(p.getYHeadRot());
        b.f64(v.x).f64(v.y).f64(v.z);
        int flags = 0;
        if (p.onGround()) flags |= F_ON_GROUND;
        if (p.horizontalCollision) flags |= F_HORIZONTAL_COLLISION;
        if (p.verticalCollision) flags |= F_VERTICAL_COLLISION;
        if (p.isSprinting()) flags |= F_SPRINTING;
        if (p.isSwimming()) flags |= F_SWIMMING;
        if (p.isFallFlying()) flags |= F_FALL_FLYING;
        if (p.isShiftKeyDown()) flags |= F_SHIFT_KEY_DOWN;
        if (p.isCrouching()) flags |= F_CROUCHING;
        if (p.isUsingItem()) flags |= F_USING_ITEM;
        if (p.minorHorizontalCollision) flags |= F_MINOR_HORIZONTAL_COLLISION;
        b.uvarint(flags);
        b.u8(p.getPose().ordinal());
        b.f64(p.fallDistance);
        b.f32(p.getJumpRidingScale());
        LocalPlayerAccessor acc = (LocalPlayerAccessor) p;
        b.svarint(acc.inputreplay$getSprintTriggerTime());
        Entity vehicle = p.getVehicle();
        b.svarint(vehicle == null ? -1 : vehicle.getId());
        b.u8(p.input == null ? 0 : inputBits(p.input.keyPresses));
        Vec2 mv = p.input == null ? Vec2.ZERO : p.input.getMoveVector();
        b.f32(mv.x).f32(mv.y);
        b.u8(inputBits(acc.inputreplay$getLastSentInput()));
        HitResult hit = mc.hitResult;
        if (hit == null) {
            b.u8(0).f64(0).f64(0).f64(0).svarint(-1);
        } else {
            Vec3 loc = hit.getLocation();
            b.u8(hit.getType().ordinal() + 1).f64(loc.x).f64(loc.y).f64(loc.z);
            b.svarint(hit instanceof EntityHitResult eh ? eh.getEntity().getId() : -1);
        }
        b.svarint(p.getInventory().getSelectedSlot());
        ItemStack held = p.getMainHandItem();
        b.str(held.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(held.getItem()).toString());
        return b;
    }
}
