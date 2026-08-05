package glaxium.snb.render;

/**
 * Per-material PBR intensity override for the CML fork's film editor. The
 * whole-model PBR values live on {@code ModelForm.pbrNormalIntensity} /
 * {@code pbrSpecularIntensity} (CML-only properties); the per-material film
 * tracks write here instead. A null field means "no override - fall back to
 * the whole-model value for that channel" (see
 * {@code BOBJModelVAOMixinCML}'s per-material draw loop), and an entry whose
 * both fields are null is removed entirely.
 */
public final class MaterialPbrIntensity
{
    public Float normal;
    public Float specular;

    public static MaterialPbrIntensity neutral()
    {
        MaterialPbrIntensity intensity = new MaterialPbrIntensity();

        intensity.normal = 1.0F;
        intensity.specular = 1.0F;

        return intensity;
    }
}
