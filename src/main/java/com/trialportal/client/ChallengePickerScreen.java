package com.trialportal.client;

import com.trialportal.network.TrialNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 副本选择界面：左边一列**可滚动**的副本列表（关卡多了也放得下），右边三档难度，下面确认。
 * <p>
 * 刻意只用原版件（{@link AbstractSelectionList} + {@link Button} + 自带字体 + 物品图标），
 * 面板底纹直接用平台那种黑石砖方块贴图——不新增资产，也不引任何 UI 库。
 */
public class ChallengePickerScreen extends Screen
{
    /** 面板底纹就是世界方块贴图：GUI 的 blit 按资源路径直接开纹理，不需要属于某个图集。 */
    private static final ResourceLocation PANEL_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/block/polished_blackstone_bricks.png");
    private static final int PANEL_WIDTH = 236;
    private static final int PANEL_HEIGHT = 168;
    private static final int LIST_WIDTH = 132;
    private static final int ROW_HEIGHT = 22;
    /**
     * 界面上的难度槽位数。键不再由客户端猜：服务端把 (key, label) 成对下发（{@link TrialNetwork.Option}），
     * 这里只负责"第几个槽显示哪个 label、点它就回传它的 key"。
     */
    private static final int DIFFICULTY_SLOTS = 3;

    private final List<TrialNetwork.Entry> entries;
    private int selected;
    private int difficulty;
    private ChallengeList list;
    private final Button[] difficultyButtons = new Button[DIFFICULTY_SLOTS];

    public ChallengePickerScreen(List<TrialNetwork.Entry> entries)
    {
        super(Component.translatable("screen.trial_portal.challenge_picker"));
        this.entries = new ArrayList<>(entries);
        if (this.entries.isEmpty())
        {
            this.entries.add(new TrialNetwork.Entry("", Component.translatable(
                    "screen.trial_portal.challenge_picker.empty").getString(), "minecraft:barrier", 0, List.of()));
        }
    }

    /** 收到 S2C 清单包后由网络层调用：必须在客户端线程上开界面。 */
    public static void show(List<TrialNetwork.Entry> entries)
    {
        Minecraft.getInstance().setScreen(new ChallengePickerScreen(entries));
    }

    @Override
    protected void init()
    {
        int left = (this.width - PANEL_WIDTH) / 2;
        int top = (this.height - PANEL_HEIGHT) / 2;
        int listTop = top + 26;
        this.list = new ChallengeList(this.minecraft, listTop, listTop + 104, row -> select(row.index()));
        this.list.setRenderBackground(false);
        // 列表默认贴着屏幕左缘自己居中，得显式挪进面板里那一列
        this.list.setLeftPos((this.width - PANEL_WIDTH) / 2 + 12);
        this.list.addRows(this.entries);
        this.list.refreshSelectedState(this.selected);
        this.addRenderableWidget(this.list);

        for (int i = 0; i < DIFFICULTY_SLOTS; i++)
        {
            final int index = i;
            Button button = Button.builder(Component.empty(), pressed -> this.difficulty = index)
                    .bounds(left + 152, listTop + 2 + i * 22, 72, 20).build();
            this.difficultyButtons[i] = button;
            this.addRenderableWidget(button);
        }
        refreshDifficulties();
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, pressed -> confirm())
                .bounds(left + PANEL_WIDTH - 84, top + PANEL_HEIGHT - 26, 72, 20).build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, pressed -> this.onClose())
                .bounds(left + 12, top + PANEL_HEIGHT - 26, 72, 20).build());
    }

    private void select(int index)
    {
        this.selected = index;
        this.list.refreshSelectedState(index);
        refreshDifficulties();
    }

    /**
     * 难度按钮的文案与可点状态跟着当前副本走（每份副本可以给自己起「浅层/中层/深层」这种名字）。
     * 只改按钮本身、不重建整屏——重建会把列表的滚动位置丢掉。
     */
    private void refreshDifficulties()
    {
        TrialNetwork.Entry entry = current();
        List<TrialNetwork.Option> options = entry.difficulties();
        this.difficulty = Math.max(0, Math.min(this.difficulty, options.size() - 1));
        for (int i = 0; i < this.difficultyButtons.length; i++)
        {
            boolean present = i < options.size();
            Button button = this.difficultyButtons[i];
            button.setMessage(Component.literal(present ? options.get(i).label() : "-"));
            button.active = present && !entry.id().isEmpty();
        }
    }

    /** 摘要行显示的难度名。 */
    private String difficultyLabel()
    {
        List<TrialNetwork.Option> options = current().difficulties();
        return this.difficulty < options.size() ? options.get(this.difficulty).label() : "-";
    }

    private void confirm()
    {
        TrialNetwork.Entry chosen = current();
        List<TrialNetwork.Option> options = chosen.difficulties();
        if (!chosen.id().isEmpty() && this.difficulty < options.size())
        {
            TrialNetwork.requestStart(chosen.id(), options.get(this.difficulty).key());
        }
        this.onClose();
    }

    private TrialNetwork.Entry current()
    {
        int index = Math.min(this.selected, this.entries.size() - 1);
        return this.entries.get(Math.max(0, index));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick)
    {
        this.renderBackground(graphics);
        int left = (this.width - PANEL_WIDTH) / 2;
        int top = (this.height - PANEL_HEIGHT) / 2;
        graphics.blitRepeating(PANEL_TEXTURE, left, top, PANEL_WIDTH, PANEL_HEIGHT,
                0, 0, 16, 16, 16, 16);
        graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xA0000000); // 压暗一层，字才读得清
        graphics.renderOutline(left, top, PANEL_WIDTH, PANEL_HEIGHT, 0xFF5A5A5A);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, top + 8, 0xFFFFFF);
        super.render(graphics, mouseX, mouseY, partialTick);
        TrialNetwork.Entry entry = current();
        if (entry.id().isEmpty())
        {
            graphics.drawCenteredString(this.font, entry.name(), left + PANEL_WIDTH / 2, top + 60, 0xFFB0B0);
            return;
        }
        graphics.drawString(this.font, entry.name() + " · " + difficultyLabel()
                        + " · 时限 " + entry.seconds() + " 秒", left + 12, top + PANEL_HEIGHT - 46, 0xE0E0E0, true);
        graphics.drawString(this.font, "滚轮翻页 · 左键选择", left + 12, top + PANEL_HEIGHT - 34, 0x909090);
    }

    private static Item itemOf(String id)
    {
        ResourceLocation key = ResourceLocation.tryParse(id);
        Item item = key == null ? null : ForgeRegistries.ITEMS.getValue(key);
        return item == null ? Items.AIR : item;
    }

    /** 左列：可滚动的副本列表。 */
    private static class ChallengeList extends AbstractSelectionList<ChallengeList.Row>
    {
        private final Consumer<Row> onPick;

        ChallengeList(Minecraft minecraft, int y0, int y1, Consumer<Row> onPick)
        {
            super(minecraft, LIST_WIDTH, y1 - y0, y0, y1, ROW_HEIGHT);
            this.onPick = onPick;
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button)
        {
            Row row = getEntryAtPosition(mouseX, mouseY);
            if (row != null && row.index() >= 0)
            {
                this.onPick.accept(row);
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public void updateNarration(net.minecraft.client.gui.narration.NarrationElementOutput output)
        {
            // 列表整体的朗读交给原版默认行为，这里不额外拼句子
        }

        void addRows(List<TrialNetwork.Entry> rows)
        {
            for (int i = 0; i < rows.size(); i++)
            {
                this.addEntry(new Row(rows.get(i), i));
            }
        }

        @Override
        public int getRowWidth()
        {
            return LIST_WIDTH - 18;
        }

        /** 选中态自己画（列表只给高亮背景，这里要连文字颜色一起变）。 */
        void refreshSelectedState(int index)
        {
            for (Row row : this.children())
            {
                row.markSelected(row.index() == index);
            }
        }

        static class Row extends AbstractSelectionList.Entry<Row>
        {
            private final TrialNetwork.Entry data;
            private final int index;
            private boolean selected;

            Row(TrialNetwork.Entry data, int index)
            {
                this.data = data;
                this.index = index;
            }

            TrialNetwork.Entry data()
            {
                return this.data;
            }

            int index()
            {
                return this.index;
            }

            void markSelected(boolean selected)
            {
                this.selected = selected;
            }

            @Override
            public void render(GuiGraphics graphics, int slot, int y, int left, int width, int mouseX, int mouseY,
                               int itemHeight, boolean isMouseOver, float partialTick)
            {
                if (this.selected)
                {
                    graphics.fill(left - 2, y, left + width + 2, y + itemHeight - 2, 0x70FFFFFF);
                }
                else if (isMouseOver)
                {
                    graphics.fill(left - 2, y, left + width + 2, y + itemHeight - 2, 0x30FFFFFF);
                }
                graphics.pose().pushPose();
                graphics.pose().translate(0.0F, 0.0F, 200.0F);
                graphics.renderItem(new ItemStack(itemOf(this.data.iconItem())), left + 2, y + 2);
                graphics.pose().popPose();
                graphics.drawString(Minecraft.getInstance().font, this.data.name(), left + 24, y + 7,
                        this.selected ? 0x404040 : 0xE0E0E0);
            }
        }
    }
}
