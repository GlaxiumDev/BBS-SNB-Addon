package glaxium.snb.mixin.cml;

import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.cubic.render.vao.BOBJModelVAO;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;

import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.util.math.MatrixStack;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Set;

@Mixin(value = BOBJModelVAO.class, remap = false)
public abstract class FlipDebugMixinCML
{
    @Shadow public BOBJArmature armature;
    @Shadow private float[] tmpVertices;
    @Shadow private int count;

    private static final Set<String> LOGGED = new HashSet<>();

    private boolean bbsFbx$interesting()
    {
        if (this.armature == null)
        {
            return false;
        }
        for (BOBJBone bone : this.armature.orderedBones)
        {
            if (bone.name.equals("Anchor_1") || bone.name.equals("Armature_0") || bone.name.equals("anchor"))
            {
                return true;
            }
        }
        return false;
    }

    private String bbsFbx$tag()
    {
        return this.armature.orderedBones.stream().anyMatch(b -> b.name.equals("Anchor_1")) ? "SHREK" : "EMOTICON";
    }

    @Inject(method = "updateMesh", at = @At("RETURN"))
    private void bbsFbx$dumpSkin(StencilMap stencilMap, CallbackInfo info)
    {
        if (!bbsFbx$interesting() || this.tmpVertices == null)
        {
            return;
        }
        String tag = bbsFbx$tag();
        if (!LOGGED.add("skin:" + tag))
        {
            return;
        }
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < this.count * 3; i += 3)
        {
            float x = this.tmpVertices[i], y = this.tmpVertices[i + 1], z = this.tmpVertices[i + 2];
            minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
        }
        System.err.println("[FLIPDBG] " + tag + " skinnedAABB=(" + String.format("%.4f", minX) + ", " + String.format("%.4f", minY) + ", " + String.format("%.4f", minZ)
                + ")..(" + String.format("%.4f", maxX) + ", " + String.format("%.4f", maxY) + ", " + String.format("%.4f", maxZ) + ")");
        for (int i = 0; i < this.armature.matrices.length; i++)
        {
            Matrix4f m = this.armature.matrices[i];
            if (m == null)
            {
                continue;
            }
            System.err.println("[FLIPDBG] " + tag + " matrix[" + i + "] t=(" + String.format("%.4f", m.m30()) + ", " + String.format("%.4f", m.m31()) + ", " + String.format("%.4f", m.m32())
                    + ") r=(" + String.format("%.3f", m.m00()) + ", " + String.format("%.3f", m.m01()) + ", " + String.format("%.3f", m.m02())
                    + " | " + String.format("%.3f", m.m10()) + ", " + String.format("%.3f", m.m11()) + ", " + String.format("%.3f", m.m12())
                    + " | " + String.format("%.3f", m.m20()) + ", " + String.format("%.3f", m.m21()) + ", " + String.format("%.3f", m.m22()) + ")");
        }
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void bbsFbx$dumpStack(ShaderProgram shader, MatrixStack stack, float r, float g, float b, float a,
            StencilMap stencilMap, int light, int overlay, Link defaultTexture, CallbackInfo info)
    {
        if (!bbsFbx$interesting())
        {
            return;
        }
        String tag = bbsFbx$tag();
        if (!LOGGED.add("stack:" + tag))
        {
            return;
        }
        Matrix4f m = stack.peek().getPositionMatrix();
        System.err.println("[FLIPDBG] " + tag + " modelMatrix t=(" + String.format("%.4f", m.m30()) + ", " + String.format("%.4f", m.m31()) + ", " + String.format("%.4f", m.m32())
                + ") r=(" + String.format("%.3f", m.m00()) + ", " + String.format("%.3f", m.m01()) + ", " + String.format("%.3f", m.m02())
                + " | " + String.format("%.3f", m.m10()) + ", " + String.format("%.3f", m.m11()) + ", " + String.format("%.3f", m.m12())
                + " | " + String.format("%.3f", m.m20()) + ", " + String.format("%.3f", m.m21()) + ", " + String.format("%.3f", m.m22()) + ")");
    }
}