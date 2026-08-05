package glaxium.snb.model.fbx.loaders;

/**
 * Material name access on a cubic {@code ModelMesh}. Base/CML's
 * {@code ModelMesh} has no material field (BBS FS declares
 * {@code public String material = ""} natively), so {@code ModelMeshMixin}
 * adds one and this interface is how the addon reads it back:
 * {@code CubicModelLoaderMixinBaseCML} sets it when it builds one
 * {@code ModelMesh} per OBJ {@code usemtl} group (inside one
 * {@code ModelGroup} per OBJ object), and {@code CubicVAOBucketingBuilder}
 * buckets each mesh's baked geometry by it so the VAO renderer can draw one
 * material at a time. Empty string means the model's default texture,
 * matching BBS FS.
 */
public interface IModelMeshMaterial
{
    String bbsFbx$getMaterial();

    void bbsFbx$setMaterial(String material);
}
