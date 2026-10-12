package glaxium.snb.mixin.fs;

import glaxium.snb.model.bobj.EmoticonArmorSidecar;
import glaxium.snb.model.fbx.loaders.FBXCompiledData;
import glaxium.snb.model.fbx.loaders.IFbxModel;
import glaxium.snb.model.fbx.loaders.IModelMaterialTextures;
import glaxium.snb.model.fbx.loaders.IShapeKeyHolder;
import glaxium.snb.render.MaterialTextureDelegate;
import glaxium.snb.model.blockbuster.LegacyBBModel;
import glaxium.snb.model.blockbuster.LegacyBBRenderer;

import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.render.vao.BOBJModelVAO;
import mchorse.bbs_mod.obj.shapes.ShapeKeys;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import mchorse.bbs_mod.utils.colors.Color;

import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.util.math.MatrixStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * FS variant of the shape-key hook-up redirect targeting BBS FS 2.7+.
 * In BBS 2.7, BOBJModelVAO.updateMesh takes (StencilMap, long armatureKey).
 */
@Mixin(value = ModelInstance.class, remap = false)
public abstract class ModelInstanceMixinFS implements IModelMaterialTextures
{
    private static Method bbsFbx$updateMesh27Method;

    @Shadow public IModel model;
    @Shadow public String id;
    @Shadow public List<String> materials;
    @Shadow public Map<String, Link> materialTextures;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbsFbx$renderLegacyBB(
            MatrixStack stack, Supplier<ShaderProgram> program, Color color,
            int light, int overlay, StencilMap stencilMap, ShapeKeys keys,
            Function<String, Link> textureResolver, CallbackInfo ci)
    {
        if (this.model instanceof LegacyBBModel legacy)
        {
            LegacyBBRenderer.render(legacy, stack, program, color, light, overlay, stencilMap);
            ci.cancel();
        }
    }

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void bbsFbx$seedFbxMaterials(CallbackInfo info)
    {
        List<String> names = null;
        Link[] defaults = null;

        if (this.model instanceof IFbxModel fbxModel)
        {
            FBXCompiledData data = fbxModel.bbsFbx$getFbxData();

            if (data != null && data.materialNames != null && data.materialNames.length > 0)
            {
                names = List.of(data.materialNames);
                defaults = data.materialTextures;
            }
        }
        else if (this.model instanceof IModelMaterialTextures cubic)
        {
            names = cubic.bbsFbx$getMaterials();
        }

        if (names != null && !names.isEmpty())
        {
            for (String name : names)
            {
                if (EmoticonArmorSidecar.isArmorMesh(name))
                {
                    continue;
                }

                if (!this.materials.contains(name))
                {
                    this.materials.add(name);
                }
            }

            for (int i = 0; i < names.size(); i++)
            {
                if (EmoticonArmorSidecar.isArmorMesh(names.get(i)))
                {
                    continue;
                }

                Link texture = defaults != null && i < defaults.length ? defaults[i] : null;

                if (texture != null && !this.materialTextures.containsKey(names.get(i)))
                {
                    this.materialTextures.put(names.get(i), texture);
                }
            }
        }

        if (this.materialTextures != null)
        {
            this.materialTextures.keySet().removeIf(EmoticonArmorSidecar::isArmorMesh);
        }
    }

    @Redirect(
            method = "render",
            at = @At(value = "INVOKE", target = "Lmchorse/bbs_mod/cubic/render/vao/BOBJModelVAO;updateMesh(Lmchorse/bbs_mod/ui/framework/elements/utils/StencilMap;J)V"),
            remap = false
    )
    private void bbsFbx$redirectUpdateMesh(
            BOBJModelVAO vao, StencilMap stencilMap, long armatureKey,
            MatrixStack stack, Supplier<ShaderProgram> program, Color color,
            int light, int overlay, StencilMap stencilMap2, ShapeKeys keys,
            Function<String, Link> textureResolver)
    {
        if (vao instanceof IShapeKeyHolder holder)
        {
            holder.bbsFbx$setShapeKeys(keys);
        }

        if (bbsFbx$updateMesh27Method == null)
        {
            try
            {
                bbsFbx$updateMesh27Method = BOBJModelVAO.class.getMethod("updateMesh", StencilMap.class, long.class);
            }
            catch (Throwable ignored)
            {
            }
        }

        if (bbsFbx$updateMesh27Method != null)
        {
            try
            {
                bbsFbx$updateMesh27Method.invoke(vao, stencilMap, armatureKey);
                return;
            }
            catch (Throwable ignored)
            {
            }
        }

        vao.updateMesh(stencilMap);
    }

    @Override
    public List<String> bbsFbx$getMaterials()
    {
        return MaterialTextureDelegate.getMaterials(this.model);
    }

    @Override
    public Link bbsFbx$getDefaultMaterialTexture(String material)
    {
        return MaterialTextureDelegate.getDefaultMaterialTexture(this.model, material);
    }
}
