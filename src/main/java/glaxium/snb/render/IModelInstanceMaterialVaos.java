package glaxium.snb.render;

import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.cubic.render.vao.ModelVAO;

import java.util.Map;

/**
 * Per-material VAO storage access on a Base/CML {@code ModelInstance}. FS's
 * {@code ModelInstance} keeps its VAOs as
 * {@code Map<ModelGroup, Map<String, ModelVAO>>} (one VAO per material per
 * group); Base/CML keep {@code Map<ModelGroup, ModelVAO>} (one merged VAO
 * per group). This interface exposes the addon-held per-material map added
 * by {@code glaxium.snb.mixin.basecml.ModelInstanceVAOMixin} so the
 * VAO renderer mixins ({@code CubicVAORendererMixinBase} /
 * {@code CubicVAORendererMixinCML}) can draw OBJ models one material at a
 * time, binding each material's resolved texture before its draw.
 */
public interface IModelInstanceMaterialVaos
{
    Map<ModelGroup, Map<String, ModelVAO>> bbsFbx$getMaterialVaos();
}
