package dev.zephyr.modules.render;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;

/** Implemented by render modules that participate in world-space rendering. */
public interface WorldRenderParticipant {

    void onWorldRender(WorldRenderContext ctx);
}
