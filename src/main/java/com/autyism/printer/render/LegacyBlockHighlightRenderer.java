//? if <1.21 {
/*package com.autyism.printer.render;

import com.autyism.printer.Reference;
import com.autyism.printer.config.Configs;
import com.autyism.printer.enums.HighlightStyleType;
import com.autyism.printer.handler.Module;
import com.autyism.printer.handler.ModuleManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import fi.dy.masa.malilib.interfaces.IRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;

// 1.20.1：和 BlockHighlightRenderer 画一样的高亮框，用旧的 Tesselator。
// malilib 调用时视图矩阵里已经有相机朝向，所以顶点直接用相对相机的坐标
public class LegacyBlockHighlightRenderer implements IRenderer {

    @Override
    public void onRenderWorldLast(PoseStack poseStack, Matrix4f projMatrix) {
        renderInternal(Minecraft.getInstance().gameRenderer.getMainCamera().getPosition());
    }

    private void renderInternal(Vec3 cameraPos) {
        if (!Configs.Highlight.HIGHLIGHT_ENABLED.getBooleanValue()) return;

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;

        HighlightStyleType style = (HighlightStyleType) Configs.Highlight.HIGHLIGHT_STYLE.getOptionListValue();
        long fadeDurationMs = Configs.Highlight.HIGHLIGHT_FADE_DURATION.getIntegerValue() * 100L;
        boolean seeThrough = Configs.Highlight.HIGHLIGHT_THROUGH_WALLS.getBooleanValue();
        long now = System.currentTimeMillis();

        int[][] colors = new int[4][4];
        colors[0] = extractArgb(Configs.Highlight.HIGHLIGHT_COLOR_PLACE.getIntegerValue());
        colors[1] = extractArgb(Configs.Highlight.HIGHLIGHT_COLOR_ADJUST.getIntegerValue());
        colors[2] = extractArgb(Configs.Highlight.HIGHLIGHT_COLOR_BREAK.getIntegerValue());
        colors[3] = extractArgb(Configs.Highlight.HIGHLIGHT_COLOR_FAILED.getIntegerValue());

        List<HighlightEntry> entries = new ArrayList<>();

        for (Module module : ModuleManager.VALUES) {
            Queue<Module.PendingHighlight> pending = module.getPendingHighlights();
            if (pending.isEmpty()) continue;
            for (Module.PendingHighlight ph : pending) {
                long elapsed = now - ph.time();
                if (elapsed >= fadeDurationMs) continue;

                int[] c = colors[ph.type().ordinal()];
                float baseAlpha = c[0] / 255.0f;
                float fadeAlpha = elapsed <= 0 ? baseAlpha : baseAlpha * (1.0f - (float) elapsed / fadeDurationMs);
                if (fadeAlpha <= 0.001f) continue;

                float dx = (float) (ph.pos().getX() + 0.5 - cameraPos.x);
                float dy = (float) (ph.pos().getY() + 0.5 - cameraPos.y);
                float dz = (float) (ph.pos().getZ() + 0.5 - cameraPos.z);
                float distSq = dx * dx + dy * dy + dz * dz;

                entries.add(new HighlightEntry(ph.pos(), fadeAlpha, distSq, c[1], c[2], c[3], style));
            }
        }

        if (entries.isEmpty()) return;

        boolean hasOutline = false;
        boolean hasFilled = false;
        for (HighlightEntry e : entries) {
            if (e.style == HighlightStyleType.OUTLINE || e.style == HighlightStyleType.BOTH) hasOutline = true;
            if (e.style == HighlightStyleType.FILLED || e.style == HighlightStyleType.BOTH) hasFilled = true;
        }

        // 半透明的面要从远到近画；只画线时靠深度测试，不用排序
        if (hasFilled) {
            entries.sort(Comparator.comparingDouble(e -> -e.distSq));
        }

        draw(cameraPos, entries, seeThrough, hasOutline, hasFilled);
    }

    private void draw(Vec3 cameraPos, List<HighlightEntry> entries,
                      boolean seeThrough, boolean hasOutline, boolean hasFilled) {
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buf = tesselator.getBuilder();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        if (seeThrough) {
            RenderSystem.disableDepthTest();
        } else {
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(515); // GL_LEQUAL
        }
        try {
            if (hasOutline) {
                RenderSystem.lineWidth(1.0f);
                buf.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
                for (HighlightEntry e : entries) {
                    if (e.style == HighlightStyleType.FILLED) continue;
                    addOutlineBoxLines(buf, e.pos, e.r, e.g, e.b, (int) (e.alpha * 255), cameraPos);
                }
                tesselator.end();
            }

            if (hasFilled) {
                buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
                for (HighlightEntry e : entries) {
                    if (e.style == HighlightStyleType.OUTLINE) continue;
                    addFilledBox(buf, e.pos, e.r, e.g, e.b, (int) (e.alpha * 255), cameraPos);
                }
                tesselator.end();
            }
        } catch (Exception e) {
            if (buf.building()) buf.discard();
            Reference.LOGGER.error("BlockHighlight: draw exception: {}", e.getMessage());
        } finally {
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
        }
    }

    private void addFilledBox(BufferBuilder buf, BlockPos pos, int r, int g, int b, int a, Vec3 cameraPos) {
        float x1 = (float) (pos.getX() - cameraPos.x - 0.001);
        float y1 = (float) (pos.getY() - cameraPos.y - 0.001);
        float z1 = (float) (pos.getZ() - cameraPos.z - 0.001);
        float x2 = (float) (pos.getX() - cameraPos.x + 1 + 0.001);
        float y2 = (float) (pos.getY() - cameraPos.y + 1 + 0.001);
        float z2 = (float) (pos.getZ() - cameraPos.z + 1 + 0.001);

        quad(buf, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, r, g, b, a);
        quad(buf, x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1, r, g, b, a);
        quad(buf, x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1, r, g, b, a);
        quad(buf, x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2, r, g, b, a);
        quad(buf, x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1, r, g, b, a);
        quad(buf, x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2, r, g, b, a);
    }

    private void quad(BufferBuilder buf, float x1, float y1, float z1,
                      float x2, float y2, float z2, float x3, float y3, float z3,
                      float x4, float y4, float z4, int r, int g, int b, int a) {
        buf.vertex(x1, y1, z1).color(r, g, b, a).endVertex();
        buf.vertex(x2, y2, z2).color(r, g, b, a).endVertex();
        buf.vertex(x3, y3, z3).color(r, g, b, a).endVertex();
        buf.vertex(x4, y4, z4).color(r, g, b, a).endVertex();
    }

    private void addOutlineBoxLines(BufferBuilder buf, BlockPos pos, int r, int g, int b, int a, Vec3 cameraPos) {
        float x1 = (float) (pos.getX() - cameraPos.x);
        float y1 = (float) (pos.getY() - cameraPos.y);
        float z1 = (float) (pos.getZ() - cameraPos.z);
        float x2 = (float) (pos.getX() - cameraPos.x + 1);
        float y2 = (float) (pos.getY() - cameraPos.y + 1);
        float z2 = (float) (pos.getZ() - cameraPos.z + 1);

        line(buf, x1, y1, z1, x2, y1, z1, r, g, b, a);
        line(buf, x2, y1, z1, x2, y1, z2, r, g, b, a);
        line(buf, x2, y1, z2, x1, y1, z2, r, g, b, a);
        line(buf, x1, y1, z2, x1, y1, z1, r, g, b, a);
        line(buf, x1, y2, z1, x2, y2, z1, r, g, b, a);
        line(buf, x2, y2, z1, x2, y2, z2, r, g, b, a);
        line(buf, x2, y2, z2, x1, y2, z2, r, g, b, a);
        line(buf, x1, y2, z2, x1, y2, z1, r, g, b, a);
        line(buf, x1, y1, z1, x1, y2, z1, r, g, b, a);
        line(buf, x2, y1, z1, x2, y2, z1, r, g, b, a);
        line(buf, x2, y1, z2, x2, y2, z2, r, g, b, a);
        line(buf, x1, y1, z2, x1, y2, z2, r, g, b, a);
    }

    private void line(BufferBuilder buf, float x1, float y1, float z1,
                      float x2, float y2, float z2, int r, int g, int b, int a) {
        buf.vertex(x1, y1, z1).color(r, g, b, a).endVertex();
        buf.vertex(x2, y2, z2).color(r, g, b, a).endVertex();
    }

    private static int[] extractArgb(int argb) {
        return new int[]{
                (argb >> 24) & 0xFF,
                (argb >> 16) & 0xFF,
                (argb >> 8) & 0xFF,
                argb & 0xFF
        };
    }

    private record HighlightEntry(BlockPos pos, float alpha, float distSq,
                                  int r, int g, int b, HighlightStyleType style) {}
}
*///?}
