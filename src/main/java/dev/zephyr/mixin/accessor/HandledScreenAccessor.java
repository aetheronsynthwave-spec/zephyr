package dev.zephyr.mixin.accessor;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Where a container window's top left corner is on screen. */
@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {

    @Accessor("x")
    int zephyr$x();

    @Accessor("y")
    int zephyr$y();
}
