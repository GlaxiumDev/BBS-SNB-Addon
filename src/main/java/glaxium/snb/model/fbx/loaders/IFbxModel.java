package glaxium.snb.model.fbx.loaders;

import java.util.Set;

/**
 * Implemented by {@code BOBJModelMixin}, which is applied to
 * {@code mchorse.bbs_mod.cubic.model.bobj.BOBJModel} on every fork. Gives
 * the FBX loader a place to hang the two things that fork apart otherwise:
 * the {@link FBXCompiledData} (read back by the material-name/texture UI and
 * the per-material VAO split) and the shape-key name list (read back by the
 * {@code getShapeKeys()} override that actually switches shape keys on, since
 * every fork's {@code BOBJModel.getShapeKeys()} is hardcoded to empty).
 *
 * <p>This interface exists because the FBX model is now a plain
 * {@code BOBJModel} rather than a per-fork subclass: Base/CML construct it
 * with a single {@code CompiledData} and FS with a {@code List<CompiledData>}
 * -- structurally different supertype constructors with no common form, so a
 * shared subclass can't call either (and per-fork subclasses can't coexist in
 * one source tree that must compile against one jar at a time). Carrying the
 * FBX data on the shared {@code BOBJModel} via a mixin interface avoids the
 * divergent constructor entirely, and the reflective constructor pick in
 * {@link FBXModelLoader} handles building the model.</p>
 */
public interface IFbxModel
{
    /** The merged per-material CompiledData this FBX model was built from, or null for non-FBX models. */
    FBXCompiledData bbsFbx$getFbxData();

    void bbsFbx$setFbxData(FBXCompiledData data);

    /** Shape key names for this FBX model, or null when none were collected. */
    Set<String> bbsFbx$getShapeKeyNames();

    void bbsFbx$setShapeKeyNames(Set<String> names);
}
