package dev.zephyr.modules.utility;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opens an auction view with a command and turns its pages. Opening it again goes back to
 * page 1 with fresh listings. Free of game classes.
 */
public final class AuctionPager {

    private static final Pattern PAGE = Pattern.compile("(?i)page\\s*([0-9]+)");
    /** Ticks a window may be missing before it counts as closed (a server may swap windows). */
    private static final int GAP_TICKS = 20;

    private enum State { CLOSE, SEND, OPENING, SETTLING, READY, TURNING, FAILED }

    private final AuctionGame game;
    private final AuctionSetup setup;
    private final String command;
    private State state = State.CLOSE;
    private long since = -1;
    private long stableSince;
    private String signature = "";
    private int page = 1;
    private int clicks;
    private boolean ended;
    private boolean retried;
    private long goneSince = -1;
    private String problem;

    public AuctionPager(AuctionGame game, AuctionSetup setup, String command) {
        this.game = game;
        this.setup = setup;
        this.command = command;
    }

    public int page() {
        return page;
    }

    /** Why the pages cannot be trusted or read, or null. */
    public String problem() {
        return problem;
    }

    public boolean failed() {
        return state == State.FAILED;
    }

    /** The current page once it has stopped changing, else null. */
    public AuctionGame.Window tick(long now) {
        if (since < 0) {
            since = now;
        }
        AuctionGame.Window w = game.window();
        if (w != null) {
            goneSince = -1;
        }
        switch (state) {
            case CLOSE -> {
                if (!game.free()) {
                    game.close(); // a menu or chat of yours stays; the command waits for it
                    since = now;
                } else if (now - since >= setup.commandGapTicks) {
                    state = State.SEND;
                }
            }
            case SEND -> {
                if (!game.free()) {
                    state = State.CLOSE;
                    break;
                }
                game.command(command);
                enter(State.OPENING, now);
            }
            case OPENING -> {
                if (w != null) {
                    signature = signature(w);
                    stableSince = now;
                    enter(State.SETTLING, now);
                } else if (now - since > setup.windowTicks) {
                    reopenOrFail("no window opened for /" + command, now);
                }
            }
            case SETTLING -> {
                if (w == null) {
                    if (gone(now)) {
                        reopenOrFail("the auction window closed after /" + command, now);
                    }
                    break;
                }
                String seen = signature(w);
                if (!seen.equals(signature)) {
                    signature = seen;
                    stableSince = now;
                }
                if (now - stableSince >= setup.settleTicks) {
                    state = State.READY;
                    return checked(w);
                }
                if (now - since > setup.windowTicks * 3L) {
                    fail("the auction window never stopped changing");
                }
            }
            case READY -> {
                if (w == null) {
                    if (gone(now)) {
                        reopenOrFail("the auction window closed after /" + command, now);
                    }
                    break;
                }
                return w;
            }
            case TURNING -> {
                if (w != null && turned(w)) {
                    page++;
                    signature = signature(w);
                    stableSince = now;
                    enter(State.SETTLING, now);
                } else if (now - since > setup.windowTicks) {
                    // Nothing changed: try once more, then this was the last page.
                    if (w != null && clicks < 2) {
                        press(now);
                    } else if (w != null) {
                        ended = true;
                        state = State.READY;
                    } else {
                        fail("the auction window closed while turning the page");
                    }
                }
            }
            case FAILED -> {
            }
        }
        return null;
    }

    /**
     * Asks for the next page. False on the last page, and when the button cannot be
     * recognised ({@link #problem} then says so).
     */
    public boolean next(long now) {
        AuctionGame.Window w = game.window();
        if (state != State.READY || ended || w == null) {
            return false;
        }
        AuctionDeals.SlotView button = w.at(setup.nextSlot);
        if (button == null) {
            return false;
        }
        if (!button.itemId().equals(setup.nextItem)) {
            problem = "slot " + setup.nextSlot + " holds " + button.itemId() + ", not the next-page button";
            return false;
        }
        clicks = 0;
        press(now);
        return true;
    }

    private void press(long now) {
        clicks++;
        game.click(setup.nextSlot, 0, AuctionGame.Press.PICKUP);
        enter(State.TURNING, now);
    }

    /**
     * A window that names its page has turned when that number moves; listings changing
     * under an unchanged number are only the market moving. Otherwise any change counts.
     */
    private boolean turned(AuctionGame.Window w) {
        Matcher m = PAGE.matcher(w.title());
        return m.find() ? !m.group(1).equals(Integer.toString(page)) : !signature(w).equals(signature);
    }

    private AuctionGame.Window checked(AuctionGame.Window w) {
        Matcher m = PAGE.matcher(w.title());
        if (m.find() && !m.group(1).equals(Integer.toString(page))) {
            problem = "expected page " + page + " but the window says \"" + w.title() + "\"";
            fail(problem);
            return null;
        }
        return w;
    }

    private boolean gone(long now) {
        if (goneSince < 0) {
            goneSince = now;
        }
        return now - goneSince >= GAP_TICKS;
    }

    /** One more try from the command, only while still on page 1 so no page is skipped. */
    private void reopenOrFail(String why, long now) {
        if (retried || page != 1) {
            fail(why);
            return;
        }
        retried = true;
        goneSince = -1;
        // The extra gap is for a server that ignores commands sent in quick succession.
        enter(State.CLOSE, now + setup.commandGapTicks);
    }

    private void fail(String why) {
        problem = why;
        state = State.FAILED;
    }

    private void enter(State next, long now) {
        state = next;
        since = now;
    }

    private String signature(AuctionGame.Window w) {
        StringBuilder out = new StringBuilder().append(w.title());
        for (AuctionDeals.SlotView s : w.slots()) {
            out.append('\n').append(s.slot()).append(':').append(s.key()).append(':').append(s.count())
                    .append(':').append(PriceText.price(s.lore()));
        }
        return out.toString();
    }
}
