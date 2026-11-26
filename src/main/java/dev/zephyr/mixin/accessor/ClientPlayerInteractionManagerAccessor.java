package dev.zephyr.mixin.accessor;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Access to vanilla's hotbar sync state. {@code lastSelectedSlot} is the slot the server
 * was last told about; {@code syncSelectedSlot()} sends exactly one
 * {@code UpdateSelectedSlotC2SPacket} when the inventory's selected slot differs from it
 * and records the new value - the same call vanilla makes from {@code tick()},
 * {@code attackEntity}, {@code interactBlock}, {@code interactItem} and
 * {@code attackWithPiercingWeapon}.
 */
@Mixin(ClientPlayerInteractionManager.class)
public interface ClientPlayerInteractionManagerAccessor {

    @Accessor("lastSelectedSlot")
    int zephyr$getLastSelectedSlot();

    @Invoker("syncSelectedSlot")
    void zephyr$syncSelectedSlot();
}
