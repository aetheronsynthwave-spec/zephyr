package dev.zephyr.modules.utility;

import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * The craft loop: sell finished items one at a time as the cheapest at a profit, craft what
 * has been bought, and buy more ingredients only when the current market for the result
 * pays for the current cheapest ingredient plus the profit. Free of game classes.
 */
public final class CraftRoutine extends AuctionRoutine {

    public static final String OWNER = "craft";

    private final Supplier<List<CraftRecipe>> recipes;
    private int turn;

    public CraftRoutine(AuctionGame game, AuctionHost host, Supplier<AuctionSetup> setup,
                        Supplier<List<CraftRecipe>> recipes) {
        super(game, host, setup);
        this.recipes = recipes;
    }

    @Override
    protected void cycle(long now) {
        AuctionSetup s = setup.get();
        List<CraftRecipe> all = recipes.get().stream().filter(r -> r.enabled && r.valid()
                && game.item(r.buy()) != null && game.item(r.sell()) != null).toList();
        if (all.isEmpty()) {
            rest(now, s.restTicks, "no usable recipe in craft.json");
            return;
        }
        boolean waiting = false;
        for (CraftRecipe r : all) {
            AuctionLedger.Entry finished = stock(game.item(r.sell()).key());
            if (finished != null) {
                if (due(finished, now)) {
                    sellOne(finished, now);
                    return;
                }
                waiting = true;
                continue;
            }
            AuctionLedger.Entry raw = stock(game.item(r.buy()).key());
            if (raw != null && due(raw, now)) {
                craft(r, raw);
                return;
            }
            waiting |= raw != null;
        }
        if (waiting) {
            rest(now, s.restTicks, "waiting to sell or craft what was bought");
            return;
        }
        if (readMoney(now)) {
            return;
        }
        String blocked = host.limit(s, 0);
        if (blocked != null) {
            rest(now, s.restTicks, blocked);
            return;
        }
        market(all.get(Math.floorMod(turn++, all.size())), now);
    }

    /** The oldest unsold entry of this item that this module bought; single items first. */
    private AuctionLedger.Entry stock(String key) {
        AuctionLedger.Entry found = null;
        for (AuctionLedger.Entry e : host.ledger().entries()) {
            if (e.owner.equals(OWNER) && e.status == AuctionLedger.Status.BOUGHT && e.key.equals(key)) {
                if (e.count == 1) {
                    return e;
                }
                found = found == null ? e : found;
            }
        }
        return found;
    }

    /** Takes one item off the lot in the books; the sale splits the stack itself. */
    private void sellOne(AuctionLedger.Entry lot, long now) {
        AuctionLedger.Entry one = host.ledger().split(lot, 1, host.millis());
        sell(one, this::pauseOnFailure);
    }

    private void pauseOnFailure(SellTask.Result result, long now) {
        if (!result.listed()) {
            // No profit at today's prices, or no free listing: do not split off more yet.
            rest(now, setup.get().recheckTicks, result.message());
        }
    }

    private void craft(CraftRecipe recipe, AuctionLedger.Entry raw) {
        status = "crafting " + raw.count + "x " + raw.name;
        await(new CraftTask(game, recipe), (result, now) -> {
            AuctionGame.Item out = game.item(recipe.sell());
            if (result.made() > 0) {
                host.ledger().transform(raw, out.name(), out.key(), result.made());
                host.say("crafted " + result.made() + "x " + out.name()
                        + (result.problem() == null ? "" : " (" + result.problem() + ")"));
            } else {
                raw.note = result.problem() == null ? "nothing to craft from" : result.problem();
                host.say(raw.note);
                rest(now, setup.get().recheckTicks, raw.note);
            }
            host.save();
        });
    }

    private void market(CraftRecipe recipe, long start) {
        AuctionSetup s = setup.get();
        AuctionGame.Item in = game.item(recipe.buy());
        AuctionGame.Item out = game.item(recipe.sell());
        // Only what this module buys is crafted, so none of the ingredients may be around yet.
        for (CraftRecipe.Step step : recipe.steps) {
            AuctionGame.Item ingredient = game.item(step.input);
            if (ingredient != null && game.inventory().stacks().stream()
                    .anyMatch(i -> i.key().equals(ingredient.key()))) {
                rest(start, s.restTicks, "move your own " + ingredient.name() + " out of your inventory");
                return;
            }
        }
        status = "checking the price of " + out.name();
        await(new MarketScan(game, s, out), (results, now) -> {
            status = "checking the price of " + in.name();
            await(new MarketScan(game, s, in), (supply, at) -> {
                String no = "no usable " + in.name() + " listing";
                // Cheapest per item first: the first one that is affordable and pays off.
                for (MarketScan.Offer offer : supply.offers().stream()
                        .sorted(Comparator.comparingDouble(MarketScan.Offer::lowPerItem)).toList()) {
                    int made = recipe.yield(offer.item().count());
                    if (made < 1) {
                        continue;
                    }
                    MarketPrice.Quote quote = MarketPrice.quote(results, 1, offer.price().high() / made,
                            host.ledger().askedPerItem(out.key(), host.millis() - 30 * 60_000L), s);
                    no = quote.price() < 0 ? quote.why() : host.limit(s, offer.price().high());
                    if (no == null) {
                        buy(supply, offer, OWNER, (result, later) -> {
                            if (!result.bought()) {
                                rest(later, s.restTicks, result.message());
                            }
                        });
                        return;
                    }
                    if (quote.price() < 0) {
                        break; // dearer ingredients can only be worse
                    }
                }
                rest(at, s.restTicks, no);
                game.close();
            });
        });
    }
}
