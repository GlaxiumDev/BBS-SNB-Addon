package glaxium.snb.model.blockbuster;

import glaxium.snb.model.fbx.loaders.IModelMaterialTextures;
import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.cubic.CubicModelAnimator;
import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.MolangHelper;
import mchorse.bbs_mod.cubic.data.animation.Animation;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.obj.MeshesOBJ;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.pose.Pose;
import mchorse.bbs_mod.utils.pose.PoseTransform;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Isolated runtime representation of Blockbuster's legacy model format.
 *
 * <p>The {@link ModelGroup}s are only BBS's animation/editor value carriers;
 * geometry, hierarchy and matrices are interpreted by {@link LegacyBBRenderer}
 * with Blockbuster's coordinate rules. This deliberately avoids turning a BB
 * model into a BBS cubic model.</p>
 */
public final class LegacyBBModel implements IModel, IModelMaterialTextures
{
    final String debugId;
    final BlockbusterModelLoader.LegacyModel data;
    final Map<String, MeshesOBJ> objMeshes;
    final Map<String, ModelGroup> groups = new LinkedHashMap<>();
    final Map<String, List<String>> children = new LinkedHashMap<>();
    final List<String> roots = new ArrayList<>();

    private final Model animationCarrier;
    private final MolangParser parser;
    private List<String> materials;
    private Map<String, Link> materialTextures;
    private Link defaultTexture;

    LegacyBBModel(
            MolangParser parser,
            String debugId,
            BlockbusterModelLoader.LegacyModel data,
            Map<String, MeshesOBJ> objMeshes,
            List<String> materials,
            Map<String, Link> materialTextures)
    {
        this.debugId = debugId;
        this.parser = parser;
        this.data = data;
        this.objMeshes = objMeshes == null ? Map.of() : Map.copyOf(objMeshes);
        this.materials = materials == null ? List.of() : List.copyOf(materials);
        this.materialTextures = materialTextures == null ? Map.of() : Map.copyOf(materialTextures);
        this.animationCarrier = new Model(parser);

        for (Map.Entry<String, BlockbusterModelLoader.LegacyLimb> entry : data.limbs.entrySet())
        {
            String name = entry.getKey();
            BlockbusterModelLoader.LegacyTransform standing = data.standingTransform(name);
            ModelGroup group = new ModelGroup(name);

            /* BBS animation channels use the add-on's established converted
             * coordinate convention. LegacyBBRenderer converts it back at the
             * render boundary before applying BB's exact transform order. */
            group.initial.translate.set(standing.translate.x, standing.translate.y, -standing.translate.z);
            group.initial.rotate.set(-standing.rotate.x, standing.rotate.y, -standing.rotate.z);
            group.initial.scale.set(standing.scale);
            group.current.copy(group.initial);
            group.visible = entry.getValue().opacity > 0F;
            group.owner = this.animationCarrier;

            this.groups.put(name, group);
            this.children.put(name, new ArrayList<>());
        }

        for (Map.Entry<String, BlockbusterModelLoader.LegacyLimb> entry : data.limbs.entrySet())
        {
            String name = entry.getKey();
            String parent = entry.getValue().parent;

            if (parent != null && !parent.isEmpty() && this.groups.containsKey(parent) && !parent.equals(name))
            {
                this.children.get(parent).add(name);
                this.groups.get(parent).children.add(this.groups.get(name));
                this.groups.get(name).parent = this.groups.get(parent);
            }
            else
            {
                this.roots.add(name);
                this.animationCarrier.topGroups.add(this.groups.get(name));
            }
        }

        this.animationCarrier.initialize();
    }

    @Override
    public Pose createPose()
    {
        Pose pose = new Pose();

        for (ModelGroup group : this.groups.values())
        {
            PoseTransform transform = pose.get(group.id);
            transform.copy(group.current);
            transform.translate.sub(group.initial.translate);
            transform.rotate.sub(group.initial.rotate).mul((float) Math.PI / 180F);
        }

        return pose;
    }

    @Override
    public void resetPose()
    {
        for (ModelGroup group : this.groups.values())
        {
            group.reset();
        }
    }

    @Override
    public void applyPose(Pose pose)
    {
        if (pose == null || pose.isEmpty()) return;

        for (Map.Entry<String, PoseTransform> entry : pose.transforms.entrySet())
        {
            ModelGroup group = this.groups.get(entry.getKey());
            if (group == null) continue;
            PoseTransform transform = entry.getValue();

            if (transform.fix > 0F)
            {
                group.current.lerp(group.initial, transform.fix);
            }

            group.current.translate.add(transform.translate);
            group.current.scale.add(transform.scale).sub(1F, 1F, 1F);
            group.current.rotate.add(
                    (float) Math.toDegrees(transform.rotate.x),
                    (float) Math.toDegrees(transform.rotate.y),
                    (float) Math.toDegrees(transform.rotate.z));
        }
    }

    @Override public Set<String> getShapeKeys() { return Collections.emptySet(); }
    @Override
    public String getAnchor()
    {
        for (String key : this.groups.keySet())
        {
            if ("anchor".equalsIgnoreCase(key)) return key;
        }

        return "";
    }
    @Override public Collection<String> getAllGroupKeys() { return Collections.unmodifiableSet(this.groups.keySet()); }
    @Override public Collection<ModelGroup> getAllGroups() { return Collections.unmodifiableCollection(this.groups.values()); }
    @Override public Collection<BOBJBone> getAllBOBJBones() { return Collections.emptyList(); }

    @Override
    public Collection<String> getAllChildrenKeys(String key)
    {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        collectChildren(key, result);
        return result;
    }

    private void collectChildren(String key, Set<String> result)
    {
        for (String child : this.children.getOrDefault(key, List.of()))
        {
            if (result.add(child)) collectChildren(child, result);
        }
    }

    @Override
    public Collection<String> getAdjacentGroups(String key)
    {
        return List.copyOf(this.children.getOrDefault(key, List.of()));
    }

    @Override
    public Collection<String> getHierarchyGroups(String key)
    {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        String current = key;

        while (current != null && result.add(current))
        {
            BlockbusterModelLoader.LegacyLimb limb = this.data.limbs.get(current);
            current = limb == null ? null : limb.parent;
        }

        return result;
    }

    /* FS adds explicit hierarchy accessors to IModel. Keeping these methods
     * on every build is binary-safe for Base/CML and lets one source support
     * all three APIs. */
    public Collection<String> getRootGroupKeys() { return List.copyOf(this.roots); }
    public Collection<String> getDirectChildrenKeys(String key) { return List.copyOf(this.children.getOrDefault(key, List.of())); }
    public String getParentGroupKey(String key)
    {
        BlockbusterModelLoader.LegacyLimb limb = this.data.limbs.get(key);
        return limb == null ? null : limb.parent;
    }

    /* CML requires independently mutable model copies per instance. */
    public IModel copy()
    {
        LegacyBBModel copy = new LegacyBBModel(this.parser, this.debugId, this.data, this.objMeshes, this.materials, this.materialTextures);
        copy.defaultTexture = this.defaultTexture;
        return copy;
    }

    @Override
    public void apply(IEntity entity, Animation animation, float tick, float blend, float tickDelta, boolean looping)
    {
        MolangHelper.setMolangVariables(this.animationCarrier.parser, entity, tick, tickDelta);
        CubicModelAnimator.animate(this.animationCarrier, animation, tick, blend, looping);
    }

    @Override public void postApply(IEntity entity, Animation animation, float tick, float tickDelta) {}

    ModelGroup group(String name) { return this.groups.get(name); }

    /** Offset encoded by the picker shader for this model.json limb. */
    int pickerIndex(String name)
    {
        int index = 0;

        for (String key : this.groups.keySet())
        {
            if (key.equals(name)) return index;
            index++;
        }

        return 0;
    }

    Link defaultTexture() { return this.defaultTexture; }
    void setDefaultTexture(Link texture) { this.defaultTexture = texture; }

    @Override
    public void bbsFbx$setMaterialTextures(List<String> materials, Map<String, Link> textures)
    {
        this.materials = materials == null ? List.of() : List.copyOf(materials);
        this.materialTextures = textures == null ? Map.of() : Map.copyOf(textures);
    }

    @Override public List<String> bbsFbx$getMaterials() { return this.materials; }
    @Override public Link bbsFbx$getDefaultMaterialTexture(String material) { return this.materialTextures.get(material); }
}
