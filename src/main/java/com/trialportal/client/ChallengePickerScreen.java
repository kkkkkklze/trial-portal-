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

/**
 * 玩法选单：左边一列<b>可滚动</b>的清单（关卡多了也放得下），右边那一列是该玩法的可选项——
 * 闯关是难度档（浅层/中层/深层），守塔没有可选项，槽位数按清单里最大的那份算，
 * 两种玩法走同一份代码、同一套编解码。
 * <p>
 * 刻意只用原版件（{@link AbstractSelectionList} + {@link Button} + 自带字体 + 物品图标），
 * 面板底纹直接用平台那种黑石砖方块贴图——不新增资产，也不引任何 UI 库。
 * <p>
 * 类名还留着 Challenge：它现在也服务守塔，改名要删一份被 git 跟踪的文件（记在开发文档 §10 的待办里）。
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
    /** 右侧那一列可选项按钮的位置（清单窄了才放得下）。 */
    private static final int OPTION_BUTTON_X = 152;
    private static final int OPTION_BUTTON_WIDTH = 72;
    /** 可选项槽位上限：数据里写了更多档也只画这么多，多余的读不到（ presently 三档）。 */
    private static final int MAX_OPTION_SLOTS = 8;

    private final TrialNetwork.OpenPicker picker;
    private final List<TrialNetwork.Entry> entries;
    private final int optionSlots;
    private int selected;
    private int optionIndex;
    private ChallengeList list;
    private final Button[] optionButtons = new Button[MAX_OPTION_SLOTS];

    public ChallengePickerScreen(TrialNetwork.OpenPicker picker)
    {
        super(Component.translatable(titleKey(picker.kind())));
        this.picker = picker;
        this.entries = new ArrayList<>(picker.entries());
        int widest = 0;
        for (TrialNetwork.Entry entry : this.entries)
        {
            widest = Math.max(widest, entry.options().size());
        }
        this.optionSlots = Math.min(widest, MAX_OPTION_SLOTS);
        if (this.entries.isEmpty())
        {
            this.entries.add(new TrialNetwork.Entry("", Component.translatable(emptyKey(picker.kind())).getString(),
                    "minecraft:barrier", "", List.of()));
        }
    }

    /**
     * 标题键按玩法分：守塔那张写「选择资源副本」就是把话说错。
     * 键写成完整字面量而不是拼出来——语言键门（TrialDataCheck#checkLangKeys）只认得字面量，
     * 拼出来的键名会同时骗过「定义了没人用」和「用了没定义」两侧。
     */
    static String titleKey(TrialNetwork.Kind kind)
    {
        return kind == TrialNetwork.Kind.DEFENSE
                ? "screen.trial_portal.defense_picker"
                : "screen.trial_portal.challenge_picker";
    }

    static String emptyKey(TrialNetwork.Kind kind)
    {
        return kind == TrialNetwork.Kind.DEFENSE
                ? "screen.trial_portal.defense_picker_empty"
                : "screen.trial_portal.challenge_picker_empty";
    }

    /** 收到 S2C 清单包后由网络层调用：必须在客户端线程上开界面。 */
    public static void show(TrialNetwork.OpenPicker picker)
    {
        Minecraft.getInstance().setScreen(new ChallengePickerScreen(picker));
    }

    @Override
    protected void init()
    {
        int left = (this.width - PANEL_WIDTH) / 2;
        int top = (this.height - PANEL_HEIGHT) / 2;
        int listTop = top + 26;
        // 视口下沿必须停在摘要行（`top + PANEL_HEIGHT - 46` = top+122）**之上**：
        // 原先写的是 listTop + 104（= top+130），只有 3 行时列表填不满下面空着，看不出问题；
        // 关卡一多（装了按整合包普查生成的叠加层之后是 7 行）第 5 行就滚到摘要行底下，两行字叠在一起。
        // 92 = 4 行整（行高 22），留 6 像素气口给摘要行。
        this.list = new ChallengeList(this.minecraft, listTop, listTop + 92, row -> select(row.index()));
        this.list.setRenderBackground(false);
        // 列表默认会在自己上下沿各铺一条原版泥土底纹（AbstractSelectionList#renderTopAndBottom），
        // 贴在黑石砖面板上就是"UI 上面多了泥土的贴图"；淡出遮罩留着，它只是渐变不是贴图。
        this.list.setRenderTopAndBottom(false);
        // 列表默认贴着屏幕左缘自己居中，得显式挪进面板里那一列
        this.list.setLeftPos((this.width - PANEL_WIDTH) / 2 + 12);
        this.list.addRows(this.entries);
        this.list.refreshSelectedState(this.selected);
        this.addRenderableWidget(this.list);

        for (int i = 0; i < this.optionSlots; i++)
        {
            final int index = i;
            Button button = Button.builder(Component.empty(), pressed -> this.optionIndex = index)
                    .bounds(left + OPTION_BUTTON_X, listTop + 2 + i * 22, OPTION_BUTTON_WIDTH, 20).build();
            this.optionButtons[i] = button;
            this.addRenderableWidget(button);
        }
        refreshOptions();
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, pressed -> confirm())
                .bounds(left + PANEL_WIDTH - 84, top + PANEL_HEIGHT - 26, 72, 20).build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, pressed -> this.onClose())
                .bounds(left + 12, top + PANEL_HEIGHT - 26, 72, 20).build());
    }

    private void select(int index)
    {
        this.selected = index;
        this.list.refreshSelectedState(index);
        refreshOptions();
    }

    /**
     * 可选项按钮的文案与可点状态跟着当前行走（每份副本可以给自己起「浅层/中层/深层」这种名字）。
     * 只改按钮本身、不重建整屏——重建会把列表的滚动位置丢掉。
     */
    private void refreshOptions()
    {
        List<TrialNetwork.Option> options = current().options();
        this.optionIndex = Math.max(0, Math.min(this.optionIndex, options.size() - 1));
        for (int i = 0; i < this.optionSlots; i++)
        {
            Button button = this.optionButtons[i];
            boolean present = i < options.size();
            button.setMessage(Component.literal(present ? options.get(i).label() : "-"));
            button.active = present && !current().id().isEmpty();
        }
    }

    /** 摘要行显示的可选项名；没有可选项的玩法返回空串，摘要行自己决定要不要加分隔点。 */
    private String optionLabel()
    {
        List<TrialNetwork.Option> options = current().options();
        return this.optionIndex < options.size() ? options.get(this.optionIndex).label() : "";
    }

    private void confirm()
    {
        TrialNetwork.Entry chosen = current();
        if (!TrialNetwork.confirmable(this.picker.kind(), chosen, this.optionIndex))
        {
            return; // 空 ID（清单为空那行的占位）或还没选到一档：不发包，也不假装开局
        }
        if (this.picker.kind() == TrialNetwork.Kind.DEFENSE)
        {
            TrialNetwork.requestStartDefense(chosen.id());
        }
        else
        {
            TrialNetwork.requestStart(chosen.id(), chosen.options().get(this.optionIndex).key());
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
        String option = optionLabel();
        graphics.drawString(this.font, entry.name() + (option.isEmpty() ? "" : " · " + option)
                        + " · " + entry.detail(), left + 12, top + PANEL_HEIGHT - 46, 0xE0E0E0, true);
        graphics.drawString(this.font, "滚轮翻页 · 左键选择", left + 12, top + PANEL_HEIGHT - 34, 0x909090);
    }

    private static Item itemOf(String id)
    {
        ResourceLocation key = ResourceLocation.tryParse(id);
        Item item = key == null ? null : ForgeRegistries.ITEMS.getValue(key);
        return item == null ? Items.AIR : item;
    }

    /** 左列：可滚动的清单。 */
    private static class ChallengeList extends AbstractSelectionList<ChallengeList.Row>
    {
        private final java.util.function.Consumer<Row> onPick;

        ChallengeList(Minecraft minecraft, int y0, int y1, java.util.function.Consumer<Row> onPick)
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
