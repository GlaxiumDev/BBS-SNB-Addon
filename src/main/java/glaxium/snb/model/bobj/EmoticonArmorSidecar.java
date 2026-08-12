package glaxium.snb.model.bobj;

import glaxium.snb.BBSFbxAddon;

import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJLoader;
import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads the old Emoticons armor shells as an optional sidecar without ever
 * modifying/replacing the bundled player model BOBJ. The sidecar contributes
 * geometry and weights only: its actions and armatures are deliberately
 * ignored, and every armor mesh is rebound to the already-loaded model's
 * original armature.
 */
public final class EmoticonArmorSidecar
{
    public static final String HELMET = "armor_helmet";
    public static final String CHEST = "armor_chest";
    public static final String LEGGINGS = "armor_leggings";
    public static final String FEET = "armor_feet";

    private static final Set<String> ARMOR_MESHES = Set.of(HELMET, CHEST, LEGGINGS, FEET);

    private EmoticonArmorSidecar() {}

    public static boolean isArmorMesh(String name)
    {
        return ARMOR_MESHES.contains(name);
    }

    public static boolean supportsModel(String id)
    {
        return packagedSidecar(id) != null;
    }

    /**
     * BBS exposes the old Emoticons Simple+ models as Bend, while their
     * internal resource IDs remain {@code *_simple}. The addon sidecar
     * folders use the visible {@code *_bend} name. Extra aliases keep this
     * compatible with packs that expose either spelling.
     */
    public static Link packagedSidecar(String id)
    {
        if (id == null)
        {
            return null;
        }

        return switch (id)
        {
            case "emoticons/steve" -> Link.assets("models/emoticons/steve/armor.bobj");
            case "emoticons/alex" -> Link.assets("models/emoticons/alex/armor.bobj");
            case "emoticons/steve_simple", "emoticons/steve_bend", "emoticons/steve_bends" ->
                    Link.assets("models/emoticons/steve_bend/armor.bobj");
            case "emoticons/alex_simple", "emoticons/alex_bend", "emoticons/alex_bends" ->
                    Link.assets("models/emoticons/alex_bend/armor.bobj");
            default -> null;
        };
    }

    public static void tryMerge(String id, AssetProvider provider, Link modelFolder, BOBJLoader.BOBJData modelData)
    {
        Link packaged = packagedSidecar(id);

        if (packaged == null || modelData == null || modelData.armatures.isEmpty())
        {
            return;
        }

        /* Prefer an armor.bobj placed beside the live model. This lets a
         * resource pack replace just the armor sidecar. The packaged bend
         * alias is the fallback for BBS's *_simple model folders. */
        List<Link> candidates = new ArrayList<>();
        Link adjacent = modelFolder.combine("armor.bobj");

        candidates.add(adjacent);

        if (!packaged.equals(adjacent))
        {
            candidates.add(packaged);
        }

        for (Link candidate : candidates)
        {
            try (InputStream stream = provider.getAsset(candidate))
            {
                BOBJLoader.BOBJData armorData = BOBJLoader.readData(stream);

                if (mergeGeometry(modelData, armorData))
                {
                    BBSFbxAddon.LOGGER.info("Loaded Emoticons armor sidecar {} for {}", candidate, id);
                }

                return;
            }
            catch (Exception ignored)
            {
                /* Missing adjacent aliases are expected. Only warn after all
                 * candidates failed, below. */
            }
        }

        BBSFbxAddon.LOGGER.warn("Couldn't load the packaged Emoticons armor sidecar for {}", id);
    }

    /**
     * Similar to BOBJLoader.merge, except it never imports the sidecar's
     * armatures/actions. BOBJLoader.merge would putAll(sidecar.armatures),
     * which can replace the original armature object and make BBS reject
     * meshes during its identity-based armature filter.
     */
    private static boolean mergeGeometry(BOBJLoader.BOBJData modelData, BOBJLoader.BOBJData armorData)
    {
        Set<String> existing = new HashSet<>();

        for (BOBJLoader.BOBJMesh mesh : modelData.meshes)
        {
            existing.add(mesh.name);
        }

        List<BOBJLoader.BOBJMesh> selected = new ArrayList<>();

        for (BOBJLoader.BOBJMesh mesh : armorData.meshes)
        {
            if (isArmorMesh(mesh.name) && !existing.contains(mesh.name))
            {
                selected.add(mesh);
            }
        }

        if (selected.isEmpty())
        {
            return false;
        }

        int vertexOffset = modelData.vertices.size();
        int normalOffset = modelData.normals.size();
        int textureOffset = modelData.textures.size();
        BOBJArmature originalArmature = modelData.armatures.values().iterator().next();

        modelData.vertices.addAll(armorData.vertices);
        modelData.normals.addAll(armorData.normals);
        modelData.textures.addAll(armorData.textures);

        for (BOBJLoader.BOBJMesh mesh : selected)
        {
            BOBJLoader.BOBJMesh copy = mesh.add(vertexOffset, normalOffset, textureOffset);

            copy.armatureName = originalArmature.name;
            copy.armature = originalArmature;
            modelData.meshes.add(copy);
        }

        return true;
    }
}
