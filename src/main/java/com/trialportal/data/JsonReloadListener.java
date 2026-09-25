package com.trialportal.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;

import java.io.Reader;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * 通用 JSON 目录加载器：一个目录一个实例（trials / boss_affixes）。
 * 单份定义解析失败只跳过它自己并写日志，不影响同一目录里其它定义，也不会让整次 reload 失败。
 */
public class JsonReloadListener<T> extends SimpleJsonResourceReloadListener
{
    /** setLenient：允许注释式的宽松写法；未知字段被静默忽略（拼错字段名不会报错，这是已知的取舍）。 */
    public static final Gson GSON = (new GsonBuilder()).setLenient().disableHtmlEscaping().create();

    private final Class<T> type;
    private final String directory;
    private final String label;
    private final BiFunction<String, T, String> validator;
    private final Consumer<Map<String, T>> sink;

    /**
     * @param validator 收到 id 与模型，返回 null 表示通过，否则返回失败原因
     * @param sink      收到本次加载成功的完整表，由库整体替换旧内容
     */
    public JsonReloadListener(String directory, Class<T> type, String label,
                              BiFunction<String, T, String> validator, Consumer<Map<String, T>> sink)
    {
        super(GSON, directory);
        this.type = type;
        this.directory = directory;
        this.label = label;
        this.validator = validator;
        this.sink = sink;
    }

    /**
     * 不用父类的 scanDirectory：它在两个不同路径映射到同一个 ID 时抛 IllegalStateException，
     * 会让整次 /reload 一起崩。这里自己扫，冲突改成「后一份覆盖 + 一条 warn」。
     */
    @Override
    protected Map<ResourceLocation, JsonElement> prepare(ResourceManager manager, ProfilerFiller profiler)
    {
        Map<ResourceLocation, JsonElement> files = new LinkedHashMap<>();
        FileToIdConverter converter = FileToIdConverter.json(this.directory);
        for (Map.Entry<ResourceLocation, Resource> entry : converter.listMatchingResources(manager).entrySet())
        {
            ResourceLocation id = converter.fileToId(entry.getKey());
            try (Reader reader = entry.getValue().openAsReader())
            {
                JsonElement previous = files.put(id, GsonHelper.fromJson(GSON, reader, JsonElement.class));
                if (previous != null)
                {
                    LogUtils.getLogger().warn("[trial_portal] {} 定义 {} 由多个文件提供，取 {} 这一份",
                            this.label, id, entry.getKey());
                }
            }
            catch (Exception exception)
            {
                LogUtils.getLogger().error("[trial_portal] 无法读取{}文件 {}", this.label, entry.getKey(), exception);
            }
        }
        return files;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> jsons, ResourceManager manager, ProfilerFiller profiler)
    {
        Map<String, T> loaded = new LinkedHashMap<>();
        int failed = 0;
        for (Map.Entry<ResourceLocation, JsonElement> entry : jsons.entrySet())
        {
            String id = entry.getKey().toString();
            try
            {
                T model = GSON.fromJson(entry.getValue(), this.type);
                if (model == null)
                {
                    throw new IllegalStateException("内容为空");
                }
                String error = this.validator.apply(id, model);
                if (error != null)
                {
                    throw new IllegalStateException(error);
                }
                loaded.put(id, model);
            }
            catch (Exception exception)
            {
                failed++;
                LogUtils.getLogger().warn("[trial_portal] 跳过无效的{}定义 {}：{}",
                        this.label, id, exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
            }
        }
        this.sink.accept(loaded);
        LogUtils.getLogger().info("[trial_portal] 已加载 {} 份 {} 定义（{} 份被跳过）", loaded.size(), this.label, failed);
    }
}
