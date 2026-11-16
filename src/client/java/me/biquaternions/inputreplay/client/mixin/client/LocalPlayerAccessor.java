package me.biquaternions.inputreplay.client.mixin.client;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LocalPlayer.class)
public interface LocalPlayerAccessor {
    @Accessor("sprintTriggerTime")
    int inputreplay$getSprintTriggerTime();

    @Accessor("lastSentInput")
    Input inputreplay$getLastSentInput();
}
