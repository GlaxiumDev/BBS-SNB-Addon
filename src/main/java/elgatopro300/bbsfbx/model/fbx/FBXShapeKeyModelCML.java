package elgatopro300.bbsfbx.model.fbx;

import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJLoader;
import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.model.bobj.BOBJModel;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * CML twin of the FS addon's {@code FBXShapeKeyModel}, also used unmodified
 * on BBS Base since {@code BOBJModel}'s constructor is byte-for-byte
 * identical there (single merged {@code CompiledData} - confirmed directly
 * against both real jars, see MIGRATION.md). BBS CML EDITION's own
 * {@code BOBJModel.getShapeKeys()} is hardcoded to return an empty set (see
 * its source, likewise on Base), which is what tells the rest of the engine
 * "this model has no shape keys, don't bother asking for weights" -
 * overriding it here is what actually turns shape keys on for an FBX model
 * on both targets, feeding {@code ModelInstance.hasShapeKeys()} / the
 * shape-key UI real key names.
 *
 * <p>CML's {@code BOBJModel.copy()} is hardcoded to
 * {@code new BOBJModel(...)} rather than using the runtime type, so it's
 * overridden here too - otherwise a copy (used for e.g. pose-editor preview)
 * would silently downgrade back to the base class and lose its shape keys.
 * {@code IModel} doesn't declare {@code copy()} at all on Base (confirmed
 * directly against {@code mchorse/bbs-mod}), so nothing there can ever call
 * this override polymorphically - it's inert dead code on that target,
 * which is why it's fine to keep unconditionally rather than fork-gating
 * the method itself. What Base's compiler WOULD reject is a direct
 * {@code BOBJArmature.copy()} call, since that method doesn't exist there
 * either - {@link #copyArmature} routes around that via reflection so this
 * one class compiles and runs correctly on both targets from a single
 * build. (BBS FS cannot share this class at all: its {@code BOBJModel}
 * constructor takes a {@code List<CompiledData>}, one per mesh, instead of
 * a single merged one - a structurally different supertype that would need
 * its own model/loader pair, not a reflection workaround.)</p>
 */
public class FBXShapeKeyModelCML extends BOBJModel
{
    private final Set<String> shapeKeys;

    public FBXShapeKeyModelCML(BOBJArmature armature, BOBJLoader.CompiledData meshData, boolean simple, Set<String> shapeKeys)
    {
        super(armature, meshData, simple);

        LinkedHashSet<String> copy = new LinkedHashSet<>();

        if (shapeKeys != null)
        {
            copy.addAll(shapeKeys);
        }

        this.shapeKeys = Collections.unmodifiableSet(copy);
    }

    @Override
    public Set<String> getShapeKeys()
    {
        return this.shapeKeys;
    }

    /**
     * Not {@code @Override}: {@code IModel} only declares {@code copy()} on
     * CML, so annotating it would break compilation against Base's jar.
     * Still correctly implements/overrides {@code IModel.copy()} at the
     * bytecode level whenever it IS declared (CML) - the annotation is a
     * compile-time-only safety net, not something the JVM checks.
     */
    public IModel copy()
    {
        FBXShapeKeyModelCML model = new FBXShapeKeyModelCML(copyArmature(this.getArmature()), this.getMeshData(), false, this.shapeKeys);

        model.setup();

        return model;
    }

    /**
     * Reflection wrapper around {@code BOBJArmature.copy()}, which only
     * exists on CML. Falls back to reusing the same armature instance when
     * it's absent (Base) - safe because nothing on Base can reach this
     * method in the first place (see class doc), this is purely a
     * belt-and-suspenders guard against a {@code NoSuchMethodError} if that
     * ever changes.
     */
    private static BOBJArmature copyArmature(BOBJArmature armature)
    {
        try
        {
            Method copyMethod = armature.getClass().getMethod("copy");
            Object result = copyMethod.invoke(armature);

            if (result instanceof BOBJArmature copied)
            {
                return copied;
            }
        }
        catch (ReflectiveOperationException ignored)
        {
            // BOBJArmature.copy() doesn't exist on this fork - reuse the same instance.
        }

        return armature;
    }
}
