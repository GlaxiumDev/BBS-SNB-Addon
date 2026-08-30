package glaxium.snb.model.blockbuster;

import com.mojang.blaze3d.systems.RenderSystem;
import glaxium.snb.render.TextureBindRestore;
import glaxium.snb.render.MaterialTextureDelegate;
import glaxium.snb.render.CurrentModelTexture;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.obj.MeshOBJ;
import mchorse.bbs_mod.obj.MeshesOBJ;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import mchorse.bbs_mod.utils.colors.Color;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import mchorse.bbs_mod.cubic.render.vao.ModelVAORenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL30;

import java.util.List;
import java.util.function.Supplier;

/** Renderer which applies Blockbuster's native limb graph and coordinate math. */
public final class LegacyBBRenderer
{
    private static final float PIXEL = 1F / 16F;
    private LegacyBBRenderer() {}

    public static void render(
            LegacyBBModel model,
            MatrixStack matrices,
            Supplier<ShaderProgram> shader,
            Color color,
            int light,
            int overlay,
            StencilMap stencil)
    {
        ShaderProgram activeShader = shader.get();

        /* This CML build reserves picker ID 7 for Gizmo.STENCIL_FREE, but its
         * StencilMap also starts model picking at 7. That makes the first
         * legacy group activate the gizmo and move the previously selected
         * group instead of selecting itself. Keep legacy groups beyond all
         * seven reserved gizmo IDs and refresh Target because BBS configured
         * the picker shader before this adjustment. */
        if (stencil != null)
        {
            if (stencil.objectIndex == 7)
            {
                stencil.objectIndex = 8;
            }

            GlUniform target = activeShader.getUniform("Target");

            if (target != null)
            {
                target.set(stencil.objectIndex);
            }
        }

        RenderSystem.setShader(() -> activeShader);
        TextureBindRestore.Snapshot texture = TextureBindRestore.capture();

        try
        {
            /* The caller binds the Form's currently selected texture before
             * entering ModelInstance.render().  Keep that texture as the
             * model-wide skin for this draw.  Using model.defaultTexture()
             * here made standalone legacy JSON models permanently use the
             * `default` entry from their file (for example Teapot's promo
             * skin), regardless of the texture selected in BBS. */
            Link selectedTexture = CurrentModelTexture.current();
            DrawState drawState = new DrawState(stencil == null, texture.shaderTexture0(), selectedTexture);
            matrices.push();
            try
            {
                /* Vanilla RenderLivingBase.prepareScale, which the standalone BB
                 * renderer received outside ModelCustom.render. BBS has already
                 * supplied actor yaw/model scale, but not this legacy Y flip and
                 * 1.501-block model-space drop. */
                applyOuterTransform(matrices);

                for (String root : model.roots)
                {
                    renderNode(model, root, matrices, shader, color, light, overlay, stencil, true, drawState);
                }
            }
            finally
            {
                matrices.pop();
            }
        }
        finally
        {
            TextureBindRestore.restore(texture);
        }
    }

    private static void renderNode(
            LegacyBBModel model, String name, MatrixStack matrices,
            Supplier<ShaderProgram> shader, Color color, int light, int overlay,
            StencilMap stencil, boolean root, DrawState drawState)
    {
        ModelGroup group = model.group(name);
        BlockbusterModelLoader.LegacyLimb limb = model.data.limbs.get(name);
        if (group == null || limb == null) return;

        matrices.push();
        applyTransform(matrices, group, root);

        if (group.visible && limb.opacity > 0F)
        {
            /* Never inherit Sampler0 from the editor's preceding gizmo or
             * picking draw. Some legacy OBJ groups contain an unmaterialed
             * mesh, and inheriting that state made exactly one arbitrary
             * limb render with the black picker texture in edit mode. */
            bindModelTexture(drawState);
            int effectiveLight = limb.lighting
                    ? light
                    : (light & 0xffff0000) | LightmapTextureManager.MAX_BLOCK_LIGHT_COORDINATE;
            if (stencil != null)
            {
                effectiveLight = stencil.increment ? model.pickerIndex(name) : 0;
            }
            float alpha = color.a * limb.opacity;
            MeshesOBJ meshes = model.objMeshes.get(name);

            if (meshes != null)
            {
                renderObj(model, name, limb, meshes, matrices, shader, color.r, color.g, color.b, alpha,
                        effectiveLight, overlay, stencil != null, drawState);
            }
            else
            {
                if (stencil == null && drawState.warmFirstGeometry)
                {
                    drawState.warmFirstGeometry = false;
                    RenderSystem.colorMask(false, false, false, false);
                    RenderSystem.depthMask(false);

                    try
                    {
                        renderNonObj(model, name, limb, drawState.modelTexture, matrices, shader,
                                color.r, color.g, color.b, alpha, effectiveLight, overlay);
                    }
                    finally
                    {
                        RenderSystem.colorMask(true, true, true, true);
                        RenderSystem.depthMask(true);
                    }

                    bindModelTexture(drawState);
                }

                renderNonObj(model, name, limb, drawState.modelTexture, matrices, shader,
                        color.r, color.g, color.b, alpha, effectiveLight, overlay);
            }
        }

        for (String child : model.children.getOrDefault(name, List.of()))
        {
            renderNode(model, child, matrices, shader, color, light, overlay, stencil, false, drawState);
        }

        matrices.pop();
    }

    private static void renderNonObj(
            LegacyBBModel model, String name, BlockbusterModelLoader.LegacyLimb limb, Link texture,
            MatrixStack matrices, Supplier<ShaderProgram> shader,
            float red, float green, float blue, float alpha, int light, int overlay)
    {
        if (!limb.is3D || !LegacyBBExtruder.render(model, name, limb, texture, matrices, shader,
                red, green, blue, alpha, light, overlay))
        {
            renderCube(model, limb, matrices, shader, red, green, blue, alpha, light, overlay);
        }
    }

    /** BB 2.7 transform: translate, then rotate Z/Y/X, then scale. */
    static void applyTransform(MatrixStack matrices, ModelGroup group, boolean root)
    {
        Vector3f t = group.current.translate;
        Vector3f r = group.current.rotate;

        matrices.translate(t.x * PIXEL, (root ? -t.y + 24F : -t.y) * PIXEL, t.z * PIXEL);
        if (r.z != 0F) matrices.multiply(new Quaternionf().rotationZ((float) Math.toRadians(r.z)));
        if (r.y != 0F) matrices.multiply(new Quaternionf().rotationY((float) Math.toRadians(-r.y)));
        if (r.x != 0F) matrices.multiply(new Quaternionf().rotationX((float) Math.toRadians(-r.x)));
        matrices.scale(group.current.scale.x, group.current.scale.y, group.current.scale.z);
    }

    private static void renderCube(
            LegacyBBModel model, BlockbusterModelLoader.LegacyLimb limb,
            MatrixStack matrices, Supplier<ShaderProgram> shader,
            float red, float green, float blue, float alpha, int light, int overlay)
    {
        float w = limb.size.x;
        float h = limb.size.y;
        float d = limb.size.z;
        float x0 = -(1F - limb.anchor.x) * w - limb.sizeOffset;
        float y0 = -limb.anchor.y * h - limb.sizeOffset;
        float z0 = -limb.anchor.z * d - limb.sizeOffset;
        float x1 = x0 + w + limb.sizeOffset * 2F;
        float y1 = y0 + h + limb.sizeOffset * 2F;
        float z1 = z0 + d + limb.sizeOffset * 2F;

        if (limb.mirror)
        {
            float swap = x0;
            x0 = x1;
            x1 = swap;
        }

        float[][] p = {
                {x0,y0,z0},{x1,y0,z0},{x1,y1,z0},{x0,y1,z0},
                {x0,y0,z1},{x1,y0,z1},{x1,y1,z1},{x0,y1,z1}
        };
        int[][] faces = {{5,1,2,6},{0,4,7,3},{5,4,0,1},{2,3,7,6},{1,0,3,2},{4,5,6,7}};
        float u = limb.texture.x;
        float v = limb.texture.y;
        float[][] rect = {
                {u+d+w,v+d,u+d+w+d,v+d+h},{u,v+d,u+d,v+d+h},
                {u+d,v,u+d+w,v+d},{u+d+w,v+d,u+d+w+w,v},
                {u+d,v+d,u+d+w,v+d+h},{u+d+w+d,v+d,u+d+w+d+w,v+d+h}
        };

        BufferBuilder buffer = begin(VertexFormat.DrawMode.QUADS);

        for (int f = 0; f < 6; f++)
        {
            int[] indices = faces[f].clone();
            if (limb.mirror)
            {
                for (int i = 0; i < 2; i++)
                {
                    int swap = indices[i]; indices[i] = indices[3 - i]; indices[3 - i] = swap;
                }
            }

            float[] normal = normal(p[indices[0]], p[indices[1]], p[indices[2]]);
            float u1 = rect[f][0] / model.data.texture.x;
            float v1 = rect[f][1] / model.data.texture.y;
            float u2 = rect[f][2] / model.data.texture.x;
            float v2 = rect[f][3] / model.data.texture.y;
            float[][] uv = {{u2,v1},{u1,v1},{u1,v2},{u2,v2}};

            for (int i = 0; i < 4; i++)
            {
                float[] point = p[indices[i]];
                vertex(buffer, matrices, point[0] * PIXEL, point[1] * PIXEL, point[2] * PIXEL,
                        uv[i][0], uv[i][1], normal[0], normal[1], normal[2], red, green, blue, alpha, light, overlay);
            }
        }

        draw(buffer, matrices, shader);
    }

    private static void renderObj(
            LegacyBBModel model, String groupName, BlockbusterModelLoader.LegacyLimb limb, MeshesOBJ meshes,
            MatrixStack matrices, Supplier<ShaderProgram> shader,
            float red, float green, float blue, float alpha, int light, int overlay,
            boolean picking, DrawState drawState)
    {
        for (int meshIndex = 0; meshIndex < meshes.meshes.size(); meshIndex++)
        {
            MeshOBJ mesh = meshes.meshes.get(meshIndex);
            Link link = null;

            if (mesh.material != null)
            {
                Link materialTexture = MaterialTextureDelegate.resolveMaterialTexture(model, mesh.material.name);
                if (materialTexture != null) link = materialTexture;
            }

            /* Rebind for every mesh, including meshes without a material.
             * A previous mesh may have selected a generated solid-color link. */
            if (link != null) bindSampler0(link);
            else bindModelTexture(drawState);

            if (!picking && drawState.warmFirstGeometry)
            {
                drawState.warmFirstGeometry = false;
                RenderSystem.colorMask(false, false, false, false);
                RenderSystem.depthMask(false);

                try
                {
                    drawObjMesh(model, limb, mesh, matrices, shader,
                            red, green, blue, alpha, light, overlay);
                }
                finally
                {
                    RenderSystem.colorMask(true, true, true, true);
                    RenderSystem.depthMask(true);
                }

                if (link != null) bindSampler0(link);
                else bindModelTexture(drawState);
            }

            drawObjMesh(model, limb, mesh, matrices, shader,
                    red, green, blue, alpha, light, overlay);
        }
    }

    private static void drawObjMesh(
            LegacyBBModel model, BlockbusterModelLoader.LegacyLimb limb, MeshOBJ mesh,
            MatrixStack matrices, Supplier<ShaderProgram> shader,
            float red, float green, float blue, float alpha, int light, int overlay)
    {
        BufferBuilder buffer = begin(VertexFormat.DrawMode.TRIANGLES);
        int vertices = mesh.posData.length / 3;

        for (int i = 0; i < vertices; i++)
        {
            float px = mesh.posData[i * 3];
            float py = mesh.posData[i * 3 + 1];
            float pz = mesh.posData[i * 3 + 2];
            float nx = mesh.normData.length >= i * 3 + 3 ? mesh.normData[i * 3] : 0F;
            float ny = mesh.normData.length >= i * 3 + 3 ? mesh.normData[i * 3 + 1] : 1F;
            float nz = mesh.normData.length >= i * 3 + 3 ? mesh.normData[i * 3 + 2] : 0F;
            float x = px - limb.origin.x;
            float y = -py + limb.origin.y;
            float z = pz - limb.origin.z;

            if (!model.data.legacyObj)
            {
                x = -px + limb.origin.x;
                nx = -nx;
            }

            float tu = mesh.texData.length >= i * 2 + 2 ? mesh.texData[i * 2] : 0F;
            float tv = mesh.texData.length >= i * 2 + 2 ? mesh.texData[i * 2 + 1] : 0F;
            vertex(buffer, matrices, x, y, z, tu, tv,
                    nx, -ny, nz, red, green, blue, alpha, light, overlay);
        }

        draw(buffer, matrices, shader);
    }

    private static final class DrawState
    {
        private boolean warmFirstGeometry;
        private final int modelTextureId;
        private final Link modelTexture;

        private DrawState(boolean warmFirstGeometry, int modelTextureId, Link modelTexture)
        {
            this.warmFirstGeometry = warmFirstGeometry;
            this.modelTextureId = modelTextureId;
            this.modelTexture = modelTexture;
        }
    }

    static BufferBuilder begin(VertexFormat.DrawMode mode)
    {
        BufferBuilder buffer = Tessellator.getInstance().begin(mode, VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL);
        return buffer;
    }

    /**
     * Keep both Minecraft's tracked Sampler0 and the actual OpenGL unit 0 in
     * sync. The editor performs an off-screen picker render after its visible
     * render; that shader can leave another texture unit active and a black
     * picking texture bound. Updating only RenderSystem's tracked texture made
     * the first legacy limb of the next visible pass sample that stale texture.
     */
    private static void bindSampler0(Link texture)
    {
        BBSModClient.getTextures().bindTexture(texture);
        GL30.glActiveTexture(GL30.GL_TEXTURE0);
        BBSModClient.getTextures().bind(texture);
    }

    /** Rebind the skin BBS selected for this particular Form instance. */
    private static void bindSampler0(int textureId)
    {
        GL30.glActiveTexture(GL30.GL_TEXTURE0);
        RenderSystem.setShaderTexture(0, textureId);
        GL30.glBindTexture(GL30.GL_TEXTURE_2D, textureId);
    }

    private static void bindModelTexture(DrawState state)
    {
        if (state.modelTexture != null) bindSampler0(state.modelTexture);
        else bindSampler0(state.modelTextureId);
    }

    static void draw(BufferBuilder buffer, MatrixStack matrices, Supplier<ShaderProgram> shader)
    {
        BuiltBuffer built = buffer.endNullable();
        if (built == null) return;

        Vector3f savedLight0 = bbsFbx$currentLight(0);
        Vector3f savedLight1 = bbsFbx$currentLight(1);
        Vector3f[] sun = bbsFbx$worldSun();

        if (sun != null)
        {
            RenderSystem.setShaderLights(sun[0], sun[1]);
        }

        RenderSystem.setShader(shader);

        /* BufferBuilder.vertex(matrix, ...) already bakes the complete actor
         * + legacy limb transform into every position. Passing the same
         * MatrixStack to setupUniforms applies it a second time in the 1.21
         * shader, distorting OBJ-backed model.json limbs. Keep only the
         * global RenderSystem model-view for positions. Normals are submitted
         * untransformed, so they still need this limb's normal matrix. */
        MatrixStack identity = new MatrixStack();

        ModelVAORenderer.setupUniforms(
                shader.get(),
                ModelVAORenderer.captureModelView(identity),
                matrices.peek().getNormalMatrix());

        BufferRenderer.drawWithGlobalProgram(built);

        if (sun != null && savedLight0 != null && savedLight1 != null)
        {
            RenderSystem.setShaderLights(savedLight0, savedLight1);
        }
    }

    /** Reads the live shader light direction (reflective: the field is private). */
    private static Vector3f bbsFbx$currentLight(int index)
    {
        try
        {
            java.lang.reflect.Field field = RenderSystem.class.getDeclaredField("shaderLightDirections");

            field.setAccessible(true);

            Vector3f[] dirs = (Vector3f[]) field.get(null);

            return dirs == null || dirs.length <= index ? null : new Vector3f(dirs[index]);
        }
        catch (ReflectiveOperationException ignored)
        {
            return null;
        }
    }

    /**
     * World-space sun and moon directions derived from the level's sky angle,
     * rotated by the camera into view space. Null when there is no level (UI
     * previews keep the game's own lighting).
     */
    private static Vector3f[] bbsFbx$worldSun()
    {
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();

        if (client == null || client.world == null || client.gameRenderer == null
                || client.gameRenderer.getCamera() == null)
        {
            return null;
        }

        try
        {
            float skyAngle = client.world.getSkyAngle(client.getRenderTickCounter().getTickDelta(true));
            float angle = skyAngle * (float) (Math.PI * 2.0);

            Vector3f sunWorld = new Vector3f(
                    (float) Math.cos(angle),
                    0.75F,
                    (float) Math.sin(angle)).normalize();
            Vector3f moonWorld = new Vector3f(-sunWorld.x, -0.75F, -sunWorld.z).normalize();

            Quaternionf viewRotation = client.gameRenderer.getCamera().getRotation();

            return new Vector3f[] {
                    viewRotation.transform(sunWorld, new Vector3f()),
                    viewRotation.transform(moonWorld, new Vector3f())
            };
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    static void vertex(
            BufferBuilder buffer, MatrixStack matrices,
            float x, float y, float z, float u, float v, float nx, float ny, float nz,
            float red, float green, float blue, float alpha, int light, int overlay)
    {
        Matrix4f position = matrices.peek().getPositionMatrix();

        /* The BBS model shader multiplies the incoming normal by NormalMat
         * itself (vanilla entity-shader convention), so the normal must be
         * passed untransformed -- exactly like the game's own VAO models. */
        buffer.vertex(position, x, y, z)
                .color(red, green, blue, alpha)
                .texture(u, v)
                .overlay(overlay)
                .light(light)
                .normal(nx, ny, nz);
    }

    private static float[] normal(float[] a, float[] b, float[] c)
    {
        Vector3f one = new Vector3f(a[0] - b[0], a[1] - b[1], a[2] - b[2]);
        Vector3f two = new Vector3f(c[0] - b[0], c[1] - b[1], c[2] - b[2]);
        Vector3f normal = two.cross(one).normalize();
        return new float[] {normal.x, normal.y, normal.z};
    }

    public static void captureMatrices(LegacyBBModel model, MatrixCache cache)
    {
        MatrixStack matrices = new MatrixStack();
        applyOuterTransform(matrices);
        for (String root : model.roots) captureNode(model, root, matrices, cache, true);
    }

    private static void applyOuterTransform(MatrixStack matrices)
    {
        matrices.scale(-1F, -1F, 1F);
        matrices.translate(0F, -1.501F, 0F);
    }

    private static void captureNode(LegacyBBModel model, String name, MatrixStack matrices, MatrixCache cache, boolean root)
    {
        ModelGroup group = model.group(name);
        if (group == null) return;
        matrices.push();
        applyTransform(matrices, group, root);

        Matrix4f current = new Matrix4f(matrices.peek().getPositionMatrix());
        cache.put(name, current, new Matrix4f(current));

        for (String child : model.children.getOrDefault(name, List.of()))
        {
            captureNode(model, child, matrices, cache, false);
        }
        matrices.pop();
    }
}
