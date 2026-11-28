package dev.zephyr.modules.utility;

import dev.zephyr.gui.ClickGuiScreen;
import dev.zephyr.gui.render.Ui;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything bought and sold. On the Bought tab, click a price to type a new one (1500,
 * 1.5k, 2m) and press Enter, then Sell. Newest entries are first.
 */
public final class AuctionLedgerScreen extends Screen {

    private static final float W = 400;
    private static final float ROW = 18;
    private static final float TEXT = 8.5f;

    private record Button(float x, float y, float w, float h, Runnable action) {
    }

    private final AuctionFlipper flipper;
    private final List<Button> buttons = new ArrayList<>();
    private boolean soldTab;
    private int scroll;
    private long editing = -1;
    private String typed = "";
    private long removing = -1;
    private String note = "";
    private float mouseGuiX;
    private float mouseGuiY;

    public AuctionLedgerScreen(AuctionFlipper flipper) {
        super(Text.literal("Bought and sold"));
        this.flipper = flipper;
    }

    private static float scale() {
        return Math.max(0.5f, ClickGuiScreen.currentScale());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        float scale = scale();
        mouseGuiX = mouseX / scale;
        mouseGuiY = mouseY / scale;
        float width = this.width / scale;
        float height = this.height / scale;
        float x = (width - W) / 2f;
        float y = 20;
        float h = height - 40;
        ctx.fill(0, 0, this.width, this.height, 0x8C06080C);
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().scale(scale, scale);
        Ui.beginFrame(scale);
        try {
            buttons.clear();
            Ui.panel(ctx, x, y, W, h, 9, Ui.BG, 1f);
            AuctionLedger ledger = flipper.ledger();
            Ui.text(ctx, "Spent $" + PriceText.shorten(ledger.spent()) + "   Earned $"
                    + PriceText.shorten(ledger.earned()) + "   Profit $" + PriceText.shorten(ledger.profit())
                    + "   Unconfirmed $" + PriceText.shorten(ledger.unconfirmed()), x + 10, y + 10, TEXT, Ui.TEXT, true);
            button(ctx, "Bought", x + 10, y + 26, 60, !soldTab, () -> tab(false));
            button(ctx, "Sold", x + 74, y + 26, 60, soldTab, () -> tab(true));
            button(ctx, "Close", x + W - 60, y + 26, 50, false, this::close);

            List<AuctionLedger.Entry> shown = new ArrayList<>();
            for (int i = ledger.entries().size() - 1; i >= 0; i--) {
                AuctionLedger.Entry e = ledger.entries().get(i);
                if ((e.status == AuctionLedger.Status.SOLD) == soldTab) {
                    shown.add(e);
                }
            }
            int rows = Math.max(1, (int) ((h - 70) / ROW));
            scroll = Math.max(0, Math.min(scroll, shown.size() - rows));
            float rowY = y + 48;
            if (shown.isEmpty()) {
                Ui.text(ctx, soldTab ? "Nothing sold yet." : "Nothing bought yet.", x + 10, rowY + 4, TEXT,
                        Ui.TEXT_DIM, false);
            }
            for (int i = scroll; i < shown.size() && i < scroll + rows; i++, rowY += ROW) {
                row(ctx, shown.get(i), x, rowY);
            }
            String hint = !note.isEmpty() ? note : soldTab && !flipper.soldMessageSet()
                    ? "No sold message set: use \"Sold\" on listed items, or set soldMessage in auction.json"
                    : "Click a price to change it, Enter keeps it. Wheel scrolls.";
            Ui.text(ctx, hint, x + 10, y + h - 14, TEXT, Ui.TEXT_DIM, false);
        } finally {
            ctx.getMatrices().popMatrix();
        }
    }

    private void row(DrawContext ctx, AuctionLedger.Entry e, float x, float y) {
        Ui.rect(ctx, x + 8, y + ROW - 1, W - 16, 1, 0x18FFFFFF);
        String name = e.count + "x " + e.name;
        if (name.length() > 28) {
            name = name.substring(0, 27) + "…";
        }
        Ui.text(ctx, name, x + 10, y + 5, TEXT, Ui.TEXT, false);
        String paid = e.paid > 0 ? "$" + PriceText.shorten(e.paid) : "-";
        if (soldTab) {
            Ui.text(ctx, "paid " + paid, x + 150, y + 5, TEXT, Ui.TEXT_DIM, false);
            Ui.text(ctx, "sold $" + PriceText.shorten(e.soldFor), x + 215, y + 5, TEXT, Ui.TEXT, false);
            if (e.paid > 0) {
                double gain = e.soldFor - e.paid;
                Ui.text(ctx, (gain >= 0 ? "+" : "") + PriceText.shorten(gain), x + 295, y + 5, TEXT,
                        gain >= 0 ? 0xFF30E070 : 0xFFFF6060, false);
            }
            removeButton(ctx, e, x, y);
            return;
        }
        Ui.text(ctx, paid, x + 150, y + 5, TEXT, Ui.TEXT_DIM, false);
        Ui.text(ctx, e.status.name().toLowerCase(java.util.Locale.ROOT), x + 192, y + 5, TEXT, Ui.TEXT_DIM, false);
        long id = e.id;
        if (e.status == AuctionLedger.Status.LISTED) {
            Ui.text(ctx, "asking $" + PriceText.shorten(e.asked), x + 252, y + 5, TEXT, Ui.TEXT, false);
            button(ctx, "Sold", x + 326, y + 2, 36, false, () -> flipper.markSold(id));
        } else if (e.status != AuctionLedger.Status.SELLING) {
            String price = editing == id ? typed + "_" : e.listAt > 0 ? "$" + PriceText.shorten(e.listAt) : "set price";
            button(ctx, price, x + 252, y + 2, 70, editing == id, () -> {
                editing = id;
                typed = "";
            });
            button(ctx, "Sell", x + 326, y + 2, 36, false, () -> sell(id));
        }
        removeButton(ctx, e, x, y);
    }

    private void removeButton(DrawContext ctx, AuctionLedger.Entry e, float x, float y) {
        long id = e.id;
        boolean sure = removing == id;
        button(ctx, sure ? "sure?" : "x", x + W - 34, y + 2, 26, sure, () -> {
            if (removing == id) {
                flipper.removeEntry(id);
                removing = -1;
            } else {
                removing = id;
            }
        });
    }

    private void button(DrawContext ctx, String label, float x, float y, float w, boolean lit, Runnable action) {
        boolean over = mouseGuiX >= x && mouseGuiX < x + w && mouseGuiY >= y && mouseGuiY < y + 14;
        Ui.panel(ctx, x, y, w, 14, 6, lit || over ? Ui.ACCENT : Ui.BG_2, 1f);
        Ui.textCentered(ctx, label, x + w / 2f, y + 3.5f, TEXT, lit || over ? 0xFF000000 : Ui.ACCENT, false);
        buttons.add(new Button(x, y, w, 14, action));
    }

    private void tab(boolean sold) {
        soldTab = sold;
        scroll = 0;
        editing = -1;
        note = "";
    }

    private void sell(long id) {
        keepTyped();
        String why = flipper.requestSale(id, true);
        if (why == null) {
            close(); // selling needs every window closed
        } else {
            note = why;
        }
    }

    private void keepTyped() {
        AuctionLedger.Entry e = flipper.ledger().byId(editing);
        double price = AuctionChat.amount(typed);
        if (e != null && price >= 1) {
            e.listAt = Math.floor(price);
        }
        editing = -1;
        typed = "";
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        if (click.button() == 0) {
            double mx = click.x() / scale();
            double my = click.y() / scale();
            for (Button b : List.copyOf(buttons)) {
                if (mx >= b.x() && mx < b.x() + b.w() && my >= b.y() && my < b.y() + b.h()) {
                    note = "";
                    b.action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(click, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        scroll = Math.max(0, scroll - (int) Math.signum(vertical));
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.getKeycode();
        if (editing >= 0) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                keepTyped();
            } else if (key == GLFW.GLFW_KEY_BACKSPACE && !typed.isEmpty()) {
                typed = typed.substring(0, typed.length() - 1);
            } else if (key == GLFW.GLFW_KEY_ESCAPE) {
                editing = -1;
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (editing < 0 || !input.isValidChar()) {
            return false;
        }
        String c = input.asString();
        if (typed.length() < 12 && c.matches("[0-9.,kKmMbB]")) {
            typed += c;
        }
        return true;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
