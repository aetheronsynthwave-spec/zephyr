package dev.zephyr.modules.combat;

import dev.zephyr.ZephyrClient;
import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.combat.CombatActions;
import dev.zephyr.core.inventory.InventoryManager;
import dev.zephyr.core.rotation.RotationManager;
import dev.zephyr.core.rotation.RotationRequest;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.target.TargetManager;
import dev.zephyr.core.target.TargetCriteria;
import dev.zephyr.core.timing.MsTimer;
import dev.zephyr.core.util.ItemUtil;
import dev.zephyr.core.util.MathUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Vec3d;

/**
 * LungeMacro / SpearMace / SpearSwap - full spear-to-mace engagement chain:
 *
 * <pre>IDLE -> ENGAGE (spear strike while cooldown-ready)
 *          -> SWAP (to breach mace via BreachSwap)
 *          -> STRIKE (mace slam window, delegates strike to AutoMace logic)
 *          -> RESTORE (back to spear, randomized ms) -> IDLE</pre>
 *
 * Stage transitions are ms-paced with gaussian jitter; every stage re-validates the
 * target (alive, in reach, hurt-time) before acting, so a dead or escaped target drops
 * the chain back to IDLE instead of firing at air.
 */
public final class LungeMacro extends Module {

    private final NumberSetting engageRange = num("engageRange", "Spear engage range", 1.5, 5.0, 0.1, 3.0);
    private final NumberSetting strikeRange = num("strikeRange", "Mace strike range", 1.5, 4.0, 0.1, 3.2);
    private final NumberSetting maxHurtTime = num("hurtTime", "Max target hurt time", 0, 10, 1, 5);
    private final NumberSetting swapMin = num("swapMin", "Swap delay min ms", 10, 200, 5, 30);
    private final NumberSetting swapMax = num("swapMax", "Swap delay max ms", 10, 300, 5, 90);
    private final NumberSetting restoreMin = num("restMin", "Restore delay min ms", 50, 500, 10, 100);
    private final NumberSetting restoreMax = num("restMax", "Restore delay max ms", 50, 800, 10, 280);
    private final BooleanSetting rotate = bool("rotate", "Rotate to target", true);
    private final BooleanSetting useBreachSwap = bool("breach", "Use BreachSwap module", true);

    private enum Stage { IDLE, ENGAGE, SWAP, STRIKE, RESTORE }

    private Stage stage = Stage.IDLE;
    private final MsTimer stageTimer = new MsTimer();
    private int spearSlot = -1;
    private int stageTicks = 0;

    public LungeMacro() {
        super("LungeMacro", ModuleCategory.COMBAT, 0);
    }

    @Override
    protected void onDisable() {
        stage = Stage.IDLE;
        stageTimer.invalidate();
        spearSlot = -1;
    }

    @Override
    public void onTick(MinecraftClient client) {
        ClientPlayerEntity self = client.player;
        if (self == null) {
            stage = Stage.IDLE;
            return;
        }
        TargetCriteria criteria = new TargetCriteria().range(Math.max(engageRange.get(), strikeRange.get()) + 1.0);
        LivingEntity target = TargetManager.acquire(client, criteria);
        stageTicks++;

        if (target == null || !target.isAlive()) {
            if (stage != Stage.IDLE && stage != Stage.RESTORE) {
                stage = Stage.IDLE;
                stageTimer.invalidate();
            }
            return;
        }

        if (rotate.get()) {
            Vec3d aim = MathUtil.aimPoint(target);
            RotationManager.request(RotationRequest.to(
                            MathUtil.yawTo(self.getEyePos(), aim), MathUtil.pitchTo(self.getEyePos(), aim))
                    .speed(30, 70).jitter(0.4).silent(false)
                    .validWhile(target::isAlive).expiresAfter(4).build());
        }

        switch (stage) {
            case IDLE -> {
                // Spear engagement: weapon present, not on cooldown, target hurt window open.
                spearSlot = InventoryManager.findHotbarSlot(self, ItemUtil::isSpear);
                boolean holdingSpear = spearSlot != -1 && self.getInventory().selectedSlot == spearSlot;
                boolean holdingMace = ItemUtil.isMace(self.getMainHandStack());
                if (holdingSpear && canEngage(self, target)) {
                    stage = Stage.ENGAGE;
                    stageTicks = 0;
                } else if (holdingMace && canStrike(self, target)) {
                    stage = Stage.STRIKE;
                    stageTicks = 0;
                }
            }
            case ENGAGE -> {
                if (!canEngage(self, target) || stageTicks > 8) {
                    stage = Stage.SWAP;
                    stageTimer.restart(swapMin.get(), swapMax.get());
                    stageTicks = 0;
                    return;
                }
                if (CombatActions.attackEntity(client, target)) {
                    stage = Stage.SWAP;
                    stageTimer.restart(swapMin.get(), swapMax.get());
                    stageTicks = 0;
                }
            }
            case SWAP -> {
                if (stageTimer.isDue()) {
                    BreachSwap breach = ZephyrClient.modules().get(BreachSwap.class);
                    if (useBreachSwap.get() && breach != null && breach.isEnabled()) {
                        breach.requestStrike();
                    } else {
                        selectMaceOrBest(client, self);
                    }
                    stage = Stage.STRIKE;
                    stageTicks = 0;
                }
            }
            case STRIKE -> {
                if (!holdingMace(self) && !InventoryManager.selectHotbar(client,
                        InventoryManager.findHotbarSlot(self, ItemUtil::isMace))) {
                    return;
                }
                if (canStrike(self, target)) {
                    CombatActions.attackEntity(client, target);
                    stageTimer.restart(restoreMin.get(), restoreMax.get());
                    stage = Stage.RESTORE;
                    stageTicks = 0;
                } else if (stageTicks > 20) {
                    stageTimer.restart(restoreMin.get(), restoreMax.get());
                    stage = Stage.RESTORE;
                    stageTicks = 0;
                }
            }
            case RESTORE -> {
                if (stageTimer.isDue()) {
                    if (spearSlot != -1) {
                        InventoryManager.selectHotbar(client, spearSlot);
                    }
                    stage = Stage.IDLE;
                    stageTimer.invalidate();
                    stageTicks = 0;
                }
            }
        }
    }

    private boolean canEngage(ClientPlayerEntity self, LivingEntity target) {
        if (target.hurtTime > maxHurtTime.get().intValue()) {
            return false;
        }
        double distSq = self.squaredDistanceTo(target);
        return distSq <= engageRange.get() * engageRange.get()
                && ItemUtil.attackCooldownProgress(self) >= 0.85f;
    }

    private boolean canStrike(ClientPlayerEntity self, LivingEntity target) {
        if (target.hurtTime > maxHurtTime.get().intValue()) {
            return false;
        }
        double distSq = self.squaredDistanceTo(target);
        return distSq <= strikeRange.get() * strikeRange.get()
                && ItemUtil.attackCooldownProgress(self) >= 0.9f;
    }

    private boolean holdingMace(ClientPlayerEntity self) {
        return ItemUtil.isMace(self.getMainHandStack());
    }

    private void selectMaceOrBest(MinecraftClient client, ClientPlayerEntity self) {
        int mace = InventoryManager.findHotbarSlot(self, ItemUtil::isMace);
        if (mace != -1) {
            InventoryManager.selectHotbar(client, mace);
        }
    }

    @Override
    public String hudLabel() {
        return name + " · " + stage.name().toLowerCase();
    }
}
