package me.biquaternions.inputreplay.client.mixin.client;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {
    @Accessor("xpos")
    void inputreplay$setXpos(double x);

    @Accessor("ypos")
    void inputreplay$setYpos(double y);

    @Accessor("accumulatedDX")
    void inputreplay$setAccumulatedDX(double v);

    @Accessor("accumulatedDY")
    void inputreplay$setAccumulatedDY(double v);
}
