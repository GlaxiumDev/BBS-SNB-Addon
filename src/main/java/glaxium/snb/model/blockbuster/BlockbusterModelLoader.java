package glaxium.snb.model.blockbuster;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import glaxium.snb.model.bobj.MinecraftTextureSourcePack;
import glaxium.snb.compat.ModelInstanceCompat;
import glaxium.snb.model.fbx.loaders.FBXMaterialTextureConfig;
import glaxium.snb.model.fbx.loaders.FBXModelLoader;
import glaxium.snb.model.fbx.loaders.FBXTextureResolverCML;
import glaxium.snb.model.fbx.loaders.IModelMaterialTextures;
import glaxium.snb.model.fbx.loaders.IModelMeshMaterial;

import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.data.animation.Animations;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelCube;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.cubic.data.model.ModelMesh;
import mchorse.bbs_mod.cubic.model.ArmorSlot;
import mchorse.bbs_mod.cubic.model.ArmorType;
import mchorse.bbs_mod.cubic.model.ModelManager;
import mchorse.bbs_mod.cubic.model.View;
import mchorse.bbs_mod.cubic.model.loaders.IModelLoader;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.obj.MeshOBJ;
import mchorse.bbs_mod.obj.MeshesOBJ;
import mchorse.bbs_mod.obj.OBJMaterial;
import mchorse.bbs_mod.obj.OBJParser;
import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_mod.utils.pose.Pose;
import mchorse.bbs_mod.utils.pose.PoseManager;
import mchorse.bbs_mod.utils.pose.PoseTransform;

import net.minecraft.util.Identifier;

import org.joml.Vector2f;
import org.joml.Vector3f;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runtime compatibility loader for Blockbuster/Metamorph's legacy model JSON
 * format. It supports both old folder packages ({@code model.json} plus
 * {@code model.obj}) and the standalone cube entity files from Blockbuster's
 * {@code assets/blockbuster/models/entity/} folder.
 */
public final class BlockbusterModelLoader implements IModelLoader
{
    private static final Gson GSON = new Gson();
    private static final Set<String> AUTOMATIC_POSES = Set.of("standing", "sneaking", "flying", "sleeping", "riding");
    private static final String MODELS_PATH = ModelManager.MODELS_PREFIX.endsWith("/")
            ? ModelManager.MODELS_PREFIX
            : ModelManager.MODELS_PREFIX + "/";

    @Override
    public ModelInstance load(String id, ModelManager models, Link model, Collection<Link> links, MapType config)
    {
        LoadTarget target = findTarget(models.provider, model, links);

        if (target == null)
        {
            return null;
        }

        try
        {
            LegacyModel legacy = readLegacyModel(models.provider, target.metadata);

            if (legacy == null)
            {
                return null;
            }

            Link obj = target.folder ? findMainObj(links, model) : null;
            boolean objBacked = target.folder && obj != null && (legacy.providesObj || hasObjMeshes(links, model));
            ObjResult objResult = objBacked
                    ? readObjMeshes(models.provider, model, links, obj)
                    : ObjResult.EMPTY;
            LegacyBBModel runtime = new LegacyBBModel(models.parser, id, legacy, objResult.meshes, objResult.materialNames, objResult.materialTextures);

            Link texture = objBacked
                    ? resolveObjDefaultTexture(legacy, model, links, objResult.materialTextures)
                    : resolveDefaultTexture(legacy, model, links);

            runtime.setDefaultTexture(texture);

            ModelInstance instance = new ModelInstance(id, runtime, new Animations(models.parser), texture);

            /* Pre-extrude is3D limbs on this (background) loader thread, so
             * the render thread never stalls on PNG decoding and voxel
             * extrusion when the model first renders after a reload. */
            try
            {
                LegacyBBExtruder.warm(runtime);
            }
            catch (Exception e)
            {
                System.err.println("[BBS FBX] Legacy extrusion warm-up failed for " + id + ": " + e.getMessage());
            }

            configureLegacyInstance(instance, legacy);
            instance.applyConfig(config);

            return instance;
        }
        catch (Exception e)
        {
            System.err.println("[BBS FBX] Failed to load Blockbuster model for " + id + ": "
                    + e.getClass().getSimpleName() + " - " + e.getMessage());
        }

        return null;
    }

    public static List<String> discoverStandalone(AssetProvider provider)
    {
        List<String> ids = new ArrayList<>();

        try
        {
            List<Link> links = new ArrayList<>(provider.getLinksFromPath(Link.assets(ModelManager.MODELS_PREFIX), true));
            links.sort(Comparator.comparing((Link link) -> link.path));

            for (Link link : links)
            {
                if (!isStandaloneCandidatePath(link.path) || !isStandaloneLegacyAsset(provider, link))
                {
                    continue;
                }

                String id = standaloneId(link.path);

                if (!id.isEmpty() && !ids.contains(id))
                {
                    ids.add(id);
                }

            }
        }
        catch (Exception ignored)
        {
        }

        return ids;
    }

    public static boolean isFolderModelJson(Link link)
    {
        return link != null
                && link.path != null
                && link.path.startsWith(MODELS_PATH)
                && link.path.endsWith("/model.json");
    }

    public static boolean isStandaloneLegacyAsset(AssetProvider provider, Link link)
    {
        if (link == null || !isStandaloneCandidatePath(link.path))
        {
            return false;
        }

        try (InputStream stream = provider.getAsset(link))
        {
            if (stream == null)
            {
                return false;
            }

            JsonObject object = parseObject(stream);

            return isLegacyJson(object) && !hasFolderPackageName(link);
        }
        catch (Exception e)
        {
            return false;
        }
    }

    public static boolean isFolderStandaloneLegacyAsset(AssetProvider provider, Link link)
    {
        return isStandaloneLegacyAsset(provider, link) && hasSameFolderAndFileName(link.path);
    }

    public static String standaloneId(String path)
    {
        String prefix = MODELS_PATH;

        if (path == null || !path.startsWith(prefix) || !path.endsWith(".json"))
        {
            return "";
        }

        String id = path.substring(prefix.length(), path.length() - ".json".length());

        if (hasSameFolderAndFileName(path))
        {
            int slash = id.lastIndexOf('/');
            String parent = id.substring(0, slash);

            return parent;
        }

        return id;
    }

    private static boolean hasSameFolderAndFileName(String path)
    {
        if (path == null || !path.endsWith(".json"))
        {
            return false;
        }

        String id = path.substring(0, path.length() - ".json".length());
        int slash = id.lastIndexOf('/');

        if (slash <= 0)
        {
            return false;
        }

        String parent = id.substring(0, slash);
        String file = id.substring(slash + 1);
        int parentSlash = parent.lastIndexOf('/');
        String folder = parentSlash >= 0 ? parent.substring(parentSlash + 1) : parent;

        return file.equals(folder);
    }

    private static LoadTarget findTarget(AssetProvider provider, Link model, Collection<Link> links)
    {
        if (model == null)
        {
            return null;
        }

        Link folderJson = model.combine("model.json");

        if (links != null && links.contains(folderJson))
        {
            return new LoadTarget(folderJson, true);
        }

        Link standalone = new Link(model.source, model.path + ".json");

        if (isStandaloneLegacyAsset(provider, standalone))
        {
            return new LoadTarget(standalone, false);
        }

        int slash = model.path.lastIndexOf('/');

        if (slash >= 0 && slash + 1 < model.path.length())
        {
            Link namedStandalone = model.combine(model.path.substring(slash + 1) + ".json");

            if (isStandaloneLegacyAsset(provider, namedStandalone))
            {
                return new LoadTarget(namedStandalone, false);
            }
        }

        return null;
    }

    private static LegacyModel readLegacyModel(AssetProvider provider, Link link) throws Exception
    {
        try (InputStream stream = provider.getAsset(link))
        {
            if (stream == null)
            {
                return null;
            }

            JsonObject object = parseObject(stream);

            if (!isLegacyJson(object))
            {
                return null;
            }

            return LegacyModel.fromJson(object);
        }
    }

    private static JsonObject parseObject(InputStream stream)
    {
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8))
        {
            JsonElement element = JsonParser.parseReader(reader);

            return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        }
        catch (Exception e)
        {
            return new JsonObject();
        }
    }

    private static boolean isLegacyJson(JsonObject object)
    {
        return object != null
                && object.has("scheme")
                && object.has("limbs")
                && object.get("limbs").isJsonObject()
                && object.has("poses")
                && object.get("poses").isJsonObject();
    }

    public static boolean isStandaloneCandidatePath(String path)
    {
        if (path == null
                || !path.startsWith(MODELS_PATH)
                || !path.endsWith(".json")
                || path.contains("/animations/")
                || path.contains("/shapes/"))
        {
            return false;
        }

        String lower = path.toLowerCase();

        return !lower.endsWith("/model.json")
                && !lower.endsWith("/config.json")
                && !lower.endsWith(".bbs.json")
                && !lower.endsWith(".geo.json")
                && !lower.endsWith(".animation.json");
    }

    private static boolean hasFolderPackageName(Link link)
    {
        return link.path != null && link.path.endsWith("/model.json");
    }

    private static Map<String, ModelGroup> createGroups(Model model, LegacyModel legacy)
    {
        Map<String, ModelGroup> groups = new LinkedHashMap<>();

        for (String id : legacy.limbs.keySet())
        {
            ModelGroup group = new ModelGroup(id);
            LegacyLimb limb = legacy.limbs.get(id);
            LegacyTransform standing = legacy.standingTransform(id);
            Vector3f world = legacy.worldTranslate(id, "standing");

            group.initial.translate.set(world);
            group.initial.rotate.set(convertRotation(standing.rotate));
            group.initial.scale.set(standing.scale);
            group.current.copy(group.initial);

            if (limb.opacity <= 0F)
            {
                group.visible = false;
            }

            groups.put(id, group);
        }

        return groups;
    }

    private static void attachHierarchy(Model model, LegacyModel legacy, Map<String, ModelGroup> groups)
    {
        Set<String> attached = new HashSet<>();

        for (Map.Entry<String, ModelGroup> entry : groups.entrySet())
        {
            String id = entry.getKey();
            ModelGroup group = entry.getValue();
            String parentId = legacy.limbs.get(id).parent;
            ModelGroup parent = parentId == null || parentId.isEmpty() ? null : groups.get(parentId);

            if (parent != null && parent != group)
            {
                parent.children.add(group);
                attached.add(id);
            }
        }

        for (Map.Entry<String, ModelGroup> entry : groups.entrySet())
        {
            if (!attached.contains(entry.getKey()))
            {
                model.topGroups.add(entry.getValue());
            }
        }
    }

    private static void addLegacyCubes(Model model, LegacyModel legacy, Map<String, ModelGroup> groups)
    {
        for (Map.Entry<String, LegacyLimb> entry : legacy.limbs.entrySet())
        {
            LegacyLimb limb = entry.getValue();
            ModelGroup group = groups.get(entry.getKey());

            if (group == null || limb.opacity <= 0F)
            {
                continue;
            }

            Vector3f pivot = group.initial.translate;
            float w = limb.size.x;
            float h = limb.size.y;
            float d = limb.size.z;
            float x = pivot.x - limb.anchor.x * w;
            float y = pivot.y - (1F - limb.anchor.y) * h;
            float z = pivot.z - (1F - limb.anchor.z) * d;

            ModelCube cube = new ModelCube();
            cube.origin.set(x, y, z);
            cube.size.set(w, h, d);
            cube.pivot.set(x + w / 2F, y + h / 2F, z + d / 2F);
            cube.inflate = limb.sizeOffset;
            cube.setupBoxUV(limb.texture, limb.mirror);
            cube.generateQuads(model.textureWidth, model.textureHeight);

            group.cubes.add(cube);
        }
    }

    private static ObjResult loadObjMeshes(
            AssetProvider provider,
            Link model,
            Collection<Link> links,
            Link obj,
            Map<String, ModelGroup> groups,
            Model converted,
            LegacyModel legacy) throws Exception
    {
        Map<String, MeshesOBJ> compile;
        Link mtl = new Link(obj.source, StringUtils.removeExtension(obj.path) + ".mtl");

        try (InputStream stream = provider.getAsset(obj))
        {
            if (stream == null)
            {
                return ObjResult.EMPTY;
            }

            InputStream mtlStream = null;

            try
            {
                mtlStream = provider.getAsset(mtl);
            }
            catch (Exception ignored)
            {
            }

            try (InputStream safeMtl = mtlStream)
            {
                OBJParser parser = new OBJParser(stream, safeMtl);
                parser.read();
                compile = parser.compile();
            }
        }

        Map<String, String> mtlTextures = readMtlTexturePaths(provider, mtl);
        List<String> materialNames = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map.Entry<String, MeshesOBJ> entry : compile.entrySet())
        {
            if (entry.getValue().meshes.isEmpty())
            {
                continue;
            }

            String groupId = entry.getKey();

            /* BBS's procedural animator calls the legacy "body" limb
             * "torso". Keep OBJ object-to-limb binding in sync with the JSON
             * rename. Blockbuster only creates renderers for OBJ objects whose
             * names match model limbs, so do not manufacture extra groups for
             * genuinely unmatched OBJ objects. */
            if (!groups.containsKey(groupId) && "body".equals(groupId) && groups.containsKey("torso"))
            {
                groupId = "torso";
            }

            ModelGroup group = groups.get(groupId);

            if (group == null)
            {
                continue;
            }

            for (MeshOBJ mesh : entry.getValue().meshes)
            {
                ModelMesh modelMesh = new ModelMesh();
                modelMesh.baseData.fill(mesh, converted.textureWidth, converted.textureHeight);

                String material = mesh.material != null && mesh.material.name != null ? mesh.material.name : "";
                setMeshMaterial(modelMesh, material);

                if (!material.isEmpty() && seen.add(material))
                {
                    materialNames.add(material);
                }

                group.meshes.add(modelMesh);
            }
        }

        Map<String, Link> materialTextures = resolveObjMaterialTextures(materialNames, compile, mtlTextures, model, links, provider);

        return new ObjResult(materialNames, materialTextures, compile);
    }

    /** Read BB's OBJ payload without converting it into BBS cubic meshes. */
    private static ObjResult readObjMeshes(
            AssetProvider provider, Link model, Collection<Link> links, Link obj) throws Exception
    {
        Map<String, MeshesOBJ> compile;
        Link mtl = new Link(obj.source, StringUtils.removeExtension(obj.path) + ".mtl");

        try (InputStream stream = provider.getAsset(obj))
        {
            if (stream == null)
            {
                return ObjResult.EMPTY;
            }

            InputStream mtlStream = null;

            try
            {
                mtlStream = provider.getAsset(mtl);
            }
            catch (Exception ignored)
            {
            }

            try (InputStream safeMtl = mtlStream)
            {
                OBJParser parser = new OBJParser(stream, safeMtl);
                parser.read();
                compile = parser.compile();
            }
        }

        List<String> materialNames = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (MeshesOBJ meshes : compile.values())
        {
            for (MeshOBJ mesh : meshes.meshes)
            {
                if (mesh.material != null && mesh.material.name != null && !mesh.material.name.isEmpty()
                        && seen.add(mesh.material.name))
                {
                    materialNames.add(mesh.material.name);
                }
            }
        }

        Map<String, String> mtlTextures = readMtlTexturePaths(provider, mtl);
        Map<String, Link> materialTextures = resolveObjMaterialTextures(
                materialNames, compile, mtlTextures, model, links, provider);

        return new ObjResult(materialNames, materialTextures, compile);
    }

    private static void setMeshMaterial(ModelMesh mesh, String material)
    {
        if (mesh instanceof IModelMeshMaterial holder)
        {
            holder.bbsFbx$setMaterial(material);
            return;
        }

        try
        {
            Field field = mesh.getClass().getField("material");
            field.set(mesh, material == null ? "" : material);
        }
        catch (Exception ignored)
        {
        }
    }

    private static Map<String, Link> resolveObjMaterialTextures(
            List<String> materials,
            Map<String, MeshesOBJ> compile,
            Map<String, String> mtlTextures,
            Link model,
            Collection<Link> links,
            AssetProvider provider)
    {
        Map<String, Link> saved = FBXMaterialTextureConfig.load(provider, model);
        Map<String, OBJMaterial> materialByName = new HashMap<>();

        for (MeshesOBJ value : compile.values())
        {
            for (MeshOBJ mesh : value.meshes)
            {
                if (mesh.material != null && mesh.material.name != null && !mesh.material.name.isEmpty())
                {
                    materialByName.putIfAbsent(mesh.material.name, mesh.material);
                }
            }
        }

        Map<String, Link> result = new LinkedHashMap<>();

        for (String name : materials)
        {
            Link chosen = saved.get(name);

            if (chosen != null)
            {
                result.put(name, chosen);
                continue;
            }

            OBJMaterial material = materialByName.get(name);
            Link resolved = FBXTextureResolverCML.resolveMaterialTexture(name, model, links);

            if (resolved == null)
            {
                String mtlTexture = mtlTextures.get(name);

                if (mtlTexture != null && !mtlTexture.isEmpty())
                {
                    resolved = resolveMtlTexture(mtlTexture, model, links);
                }
                else if (material != null && material.useTexture)
                {
                    resolved = resolveMtlTexture(material.texture, model, links);
                }
            }

            if (resolved == null && material != null && !material.useTexture)
            {
                resolved = FBXTextureResolverCML.colorLink(new float[] { material.r, material.g, material.b });
            }

            if (resolved == null)
            {
                FBXModelLoader.ensureMaterialFolder(provider, model, name);
                float r = material != null ? material.r : 1F;
                float g = material != null ? material.g : 1F;
                float b = material != null ? material.b : 1F;

                resolved = FBXTextureResolverCML.colorLink(new float[] { r, g, b });
            }

            result.put(name, resolved);
        }

        return result;
    }

    private static Map<String, String> readMtlTexturePaths(AssetProvider provider, Link mtl)
    {
        Map<String, String> textures = new HashMap<>();

        try (InputStream stream = provider.getAsset(mtl))
        {
            if (stream == null)
            {
                return textures;
            }

            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8))
            {
                String current = null;
                StringBuilder line = new StringBuilder();
                int ch;

                while ((ch = reader.read()) >= 0)
                {
                    if (ch == '\n')
                    {
                        current = readMtlLine(line.toString(), current, textures);
                        line.setLength(0);
                    }
                    else if (ch != '\r')
                    {
                        line.append((char) ch);
                    }
                }

                if (line.length() > 0)
                {
                    readMtlLine(line.toString(), current, textures);
                }
            }
        }
        catch (Exception ignored)
        {
        }

        return textures;
    }

    private static String readMtlLine(String raw, String current, Map<String, String> textures)
    {
        String line = raw;
        int comment = line.indexOf('#');

        if (comment >= 0)
        {
            line = line.substring(0, comment);
        }

        line = line.trim();

        if (line.isEmpty())
        {
            return current;
        }

        if (line.startsWith("newmtl "))
        {
            return OBJParser.processMaterialName(line.substring("newmtl ".length()).trim());
        }

        if (current != null && (line.startsWith("map_Kd ") || line.startsWith("map_Kd_path ")))
        {
            int space = line.indexOf(' ');
            String path = space >= 0 ? line.substring(space + 1).trim() : "";

            if (!path.isEmpty())
            {
                textures.put(current, path);
            }
        }

        return current;
    }

    private static Link resolveMtlTexture(Link mtlTexture, Link model, Collection<Link> links)
    {
        if (mtlTexture == null || mtlTexture.path == null || mtlTexture.path.isEmpty())
        {
            return null;
        }

        return resolveMtlTexture(mtlTexture.path, model, links);
    }

    private static Link resolveMtlTexture(String rawPath, Link model, Collection<Link> links)
    {
        if (rawPath == null || rawPath.isEmpty())
        {
            return null;
        }

        String path = rawPath.replace('\\', '/');
        Link combined = model.combine(path);

        if (links.contains(combined))
        {
            return combined;
        }

        int slash = path.lastIndexOf('/');
        String fileName = slash >= 0 ? path.substring(slash + 1) : path;

        if (fileName.startsWith("\"") && fileName.endsWith("\"") && fileName.length() > 1)
        {
            fileName = fileName.substring(1, fileName.length() - 1);
        }

        String lowerPath = path.toLowerCase();
        String lowerFileName = fileName.toLowerCase();

        for (Link link : links)
        {
            String linkPath = link.path.replace('\\', '/').toLowerCase();

            if (linkPath.endsWith(lowerPath) || (!lowerFileName.isEmpty() && linkPath.endsWith(lowerFileName)))
            {
                return link;
            }
        }

        return null;
    }

    private static void configureLegacyInstance(ModelInstance instance, LegacyModel legacy)
    {
        ModelInstanceCompat.setProcedural(instance, true);
        ModelInstanceCompat.setCulling(instance, false);
        ModelInstanceCompat.setScale(instance, legacy.scale);
        ModelInstanceCompat.setUiScale(instance, legacy.scaleGui);

        for (String group : legacy.limbs.keySet())
        {
            if ("anchor".equalsIgnoreCase(group))
            {
                ModelInstanceCompat.setAnchor(instance, group);
                break;
            }
        }

        Map<String, Float> swipeFactors = new LinkedHashMap<>();
        Map<String, Float> idleFactors = new LinkedHashMap<>();

        for (Map.Entry<String, LegacyLimb> entry : legacy.limbs.entrySet())
        {
            String group = entry.getKey();
            LegacyLimb limb = entry.getValue();

            if (limb.swiping)
            {
                swipeFactors.put(group, limb.mirror ^ limb.invert ? -1F : 1F);
            }

            if ("right_arm".equals(group) || "left_arm".equals(group))
            {
                idleFactors.put(group, limb.idle ? (limb.mirror ^ limb.invert ? -1F : 1F) : 0F);
            }

            if (limb.lookX || limb.lookY)
            {
                ModelInstanceCompat.setView(instance, group, limb.lookX);
            }

            if ("right".equals(limb.holding))
            {
                ModelInstanceCompat.getItemsMain(instance).add(itemSlot(group, group + "_item", limb, groupsContain(instance, group + "_item")));
            }
            else if ("left".equals(limb.holding))
            {
                ModelInstanceCompat.getItemsOff(instance).add(itemSlot(group, group + "_item", limb, groupsContain(instance, group + "_item")));
            }

            ArmorType armorType = armorType(limb.slot);

            if (armorType != null)
            {
                ModelInstanceCompat.getArmorSlots(instance).put(armorType, armorSlot(group, limb, armorType));
            }
        }

        Pose sneaking = legacy.poseDelta("sneaking");

        if (sneaking != null)
        {
            ModelInstanceCompat.setSneakingPose(instance, sneaking);
        }

        Map<String, Pose> allPoses = new LinkedHashMap<>();

        for (String name : legacy.poses.keySet())
        {
            Pose pose = legacy.poseDelta(name);

            /* Standing is intentionally an empty delta: choosing it in BBS
             * resets the Form pose back to the model's BB standing pose. */
            allPoses.put(name, pose == null ? new Pose() : pose);
        }

        if (instance instanceof LegacyPoseHolder holder)
        {
            holder.bbsFbx$setLegacyModel(true);
            holder.bbsFbx$setLegacyPoses(allPoses);
            holder.bbsFbx$setLegacySwipeFactors(swipeFactors);
            holder.bbsFbx$setLegacyIdleFactors(idleFactors);
        }

        /* BBS's pose popup reads PoseManager by ModelInstance.poseGroup. Add
         * the bundled BB poses to that in-memory group so they appear beside
         * user-created presets. Do not save or overwrite a user's preset with
         * the same name. */
        MapType posePresets = PoseManager.INSTANCE.getData(ModelInstanceCompat.getPoseGroup(instance));

        for (Map.Entry<String, Pose> entry : allPoses.entrySet())
        {
            if (!posePresets.has(entry.getKey()))
            {
                posePresets.put(entry.getKey(), entry.getValue().toData());
            }
        }
    }

    private static boolean groupsContain(ModelInstance instance, String group)
    {
        return instance.model != null && instance.model.getAllGroupKeys().contains(group);
    }

    private static ArmorSlot itemSlot(String limbGroup, String itemGroup, LegacyLimb limb, boolean hasItemGroup)
    {
        ArmorSlot slot = ModelInstanceCompat.newArmorSlot("item", hasItemGroup ? itemGroup : limbGroup);

        if (!hasItemGroup)
        {
            /*
             * BBS already applies the same fixed item-space rotation as old
             * Blockbuster plus a Y translation of 0.125. Supply only the
             * remaining legacy LayerHeldItem transform here.
             */
            float x = limb.size.x * (0.5F - limb.anchor.x) / 16F;
            float y = -limb.size.z * limb.anchor.z / 16F - 0.125F;
            float z = limb.size.y * (1F - limb.anchor.y) / 16F;

            if (limb.size.x > limb.size.y)
            {
                x = limb.size.x * (10F / 12F) / 16F;
                slot.transform.rotate2.y = (float) Math.toRadians(-90F);
            }

            slot.transform.translate.set(x, y, z);
        }

        /* BBS starts held items at +90 X while Blockbuster starts them at
         * -90 X. This local half-turn converts between the two conventions. */
        slot.transform.rotate.x = (float) Math.PI;
        slot.transform.scale.set(limb.itemScale, limb.itemScale, limb.itemScale);

        return slot;
    }

    /**
     * Convert Blockbuster's LayerActorArmor#setModelSlotVisible transform to
     * BBS's captured-bone ArmorSlot transform. BBS renders the vanilla armor
     * part after a fixed 180 degree Y rotation, so X/Z pivot compensation is
     * expressed in that final armor space.
     */
    private static ArmorSlot armorSlot(String group, LegacyLimb limb, ArmorType type)
    {
        ArmorSlot slot = ModelInstanceCompat.newArmorSlot(type.name().toLowerCase(), group);

        float sx;
        float sy;
        float sz;
        float pivotX = 0F;
        float pivotY;

        if (type == ArmorType.HELMET)
        {
            sx = limb.size.x / 8F;
            sy = limb.size.y / 8F;
            sz = limb.size.z / 8F;
            pivotY = 4F;
        }
        else if (type == ArmorType.CHEST || type == ArmorType.LEGGINGS)
        {
            sx = limb.size.x / 8F;
            sy = limb.size.y / 12F;
            sz = limb.size.z / 4F;
            pivotY = -6F;
        }
        else
        {
            sx = limb.size.x / 4F;
            sy = limb.size.y / 12F;
            sz = limb.size.z / 4F;
            pivotY = type == ArmorType.LEFT_ARM || type == ArmorType.RIGHT_ARM ? -4F : -6F;

            /* Blockbuster's vanilla biped pivot table. The later BBS armor
             * X rotation does not exchange the left and right axes. */
            if (type == ArmorType.RIGHT_ARM)
            {
                pivotX = 1F;
            }
            else if (type == ArmorType.LEFT_ARM)
            {
                pivotX = -1F;
            }
        }

        float x = limb.size.x * (limb.anchor.x - 0.5F) / 16F + sx * pivotX / 16F;
        float y = limb.size.y * (0.5F - limb.anchor.y) / 16F + sy * pivotY / 16F;
        float z = limb.size.z * (0.5F - limb.anchor.z) / 16F;

        slot.transform.translate.set(x, y, z);
        /* Cancel BBS's fixed armor-space 180 X rotation. Legacy BB renders
         * the vanilla armor part directly after its limb attachment matrix. */
        slot.transform.rotate.x = (float) Math.PI;
        slot.transform.scale.set(sx, sy, sz);

        return slot;
    }

    private static ArmorType armorType(String slot)
    {
        if (slot == null)
        {
            return null;
        }

        return switch (slot)
        {
            case "head" -> ArmorType.HELMET;
            case "chest" -> ArmorType.CHEST;
            case "leggings" -> ArmorType.LEGGINGS;
            case "left_shoulder" -> ArmorType.LEFT_ARM;
            case "right_shoulder" -> ArmorType.RIGHT_ARM;
            case "left_leg" -> ArmorType.LEFT_LEG;
            case "right_leg" -> ArmorType.RIGHT_LEG;
            case "left_foot" -> ArmorType.LEFT_BOOT;
            case "right_foot" -> ArmorType.RIGHT_BOOT;
            default -> null;
        };
    }

    private static Link resolveDefaultTexture(LegacyModel legacy, Link model, Collection<Link> links)
    {
        return resolveDefaultTexture(legacy, model, links, true);
    }

    private static Link resolveDefaultTexture(LegacyModel legacy, Link model, Collection<Link> links, boolean allowAnyImage)
    {
        if (legacy.defaultTexture != null && !legacy.defaultTexture.isEmpty())
        {
            String rawPath = legacy.defaultTexture;
            int colon = rawPath.indexOf(':');
            if (colon >= 0) rawPath = rawPath.substring(colon + 1);
            rawPath = rawPath.replace('\\', '/');
            int firstSlash = rawPath.indexOf('/');
            String withoutModelFolder = firstSlash >= 0 ? rawPath.substring(firstSlash + 1) : rawPath;

            for (Link link : links)
            {
                String path = link.path.replace('\\', '/');
                if (path.endsWith(rawPath) || path.endsWith(withoutModelFolder))
                {
                    return link;
                }
            }

            Link remapped = remapLegacyTexture(legacy.defaultTexture);

            if (remapped != null)
            {
                return remapped;
            }
        }

        Link modelPng = model.combine("model.png");

        if (links.contains(modelPng))
        {
            return modelPng;
        }

        return allowAnyImage ? firstImage(links) : null;
    }

    private static Link resolveObjDefaultTexture(LegacyModel legacy, Link model, Collection<Link> links, Map<String, Link> materialTextures)
    {
        Link texture = resolveDefaultTexture(legacy, model, links, false);

        if (texture != null)
        {
            return texture;
        }

        for (Link link : materialTextures.values())
        {
            if (link != null)
            {
                return link;
            }
        }

        return null;
    }

    private static Link remapLegacyTexture(String raw)
    {
        Identifier identifier = Identifier.tryParse(raw);

        if (identifier != null && "blockbuster".equals(identifier.getNamespace()))
        {
            return Link.assets("models/default.png");
        }

        if (identifier != null && "minecraft".equals(identifier.getNamespace()))
        {
            return MinecraftTextureSourcePack.link(identifier);
        }

        return Link.create(raw);
    }

    private static Link firstImage(Collection<Link> links)
    {
        for (Link link : links)
        {
            String path = link.path.toLowerCase();

            if (path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg"))
            {
                return link;
            }
        }

        return null;
    }

    private static Link findMainObj(Collection<Link> links, Link model)
    {
        Link modelObj = model.combine("model.obj");

        if (links.contains(modelObj))
        {
            return modelObj;
        }

        for (Link link : links)
        {
            if (link.path.endsWith(".obj") && link.path.startsWith(model.path + "/"))
            {
                String path = link.path.substring(model.path.length() + 1);

                if (!path.contains("/"))
                {
                    return link;
                }
            }
        }

        return null;
    }

    private static boolean hasObjMeshes(Collection<Link> links, Link model)
    {
        return findMainObj(links, model) != null;
    }

    private static Vector3f convertTranslate(Vector3f old)
    {
        return new Vector3f(old.x, old.y, -old.z);
    }

    private static Vector3f convertRotation(Vector3f old)
    {
        return new Vector3f(-old.x, old.y, -old.z);
    }

    private static Vector3f radians(Vector3f degrees)
    {
        return new Vector3f((float) Math.toRadians(degrees.x), (float) Math.toRadians(degrees.y), (float) Math.toRadians(degrees.z));
    }

    private record LoadTarget(Link metadata, boolean folder) {}

    private record ObjResult(List<String> materialNames, Map<String, Link> materialTextures, Map<String, MeshesOBJ> meshes)
    {
        private static final ObjResult EMPTY = new ObjResult(List.of(), Map.of(), Map.of());
    }

    static final class LegacyModel
    {
        final Vector2f texture;
        final boolean providesObj;
        final boolean legacyObj;
        final int extrudeMaxFactor;
        final int extrudeInwards;
        final String defaultTexture;
        final Vector3f scale;
        final float scaleGui;
        final Map<String, LegacyLimb> limbs;
        final Map<String, LegacyPose> poses;

        private LegacyModel(
                Vector2f texture,
                boolean providesObj,
                boolean legacyObj,
                int extrudeMaxFactor,
                int extrudeInwards,
                String defaultTexture,
                Vector3f scale,
                float scaleGui,
                Map<String, LegacyLimb> limbs,
                Map<String, LegacyPose> poses)
        {
            this.texture = texture;
            this.providesObj = providesObj;
            this.legacyObj = legacyObj;
            this.extrudeMaxFactor = extrudeMaxFactor;
            this.extrudeInwards = extrudeInwards;
            this.defaultTexture = defaultTexture;
            this.scale = scale;
            this.scaleGui = scaleGui;
            this.limbs = limbs;
            this.poses = poses;
        }

        private static LegacyModel fromJson(JsonObject object)
        {
            Vector2f texture = vector2(object.get("texture"), 64F, 32F);
            Vector3f scale = vector3(object.get("scale"), 1F, 1F, 1F);
            boolean providesObj = bool(object, "providesObj", false);
            boolean legacyObj = bool(object, "legacyObj", true);
            int extrudeMaxFactor = Math.max(1, Math.round(number(object, "extrudeMaxFactor", 1F)));
            int extrudeInwards = Math.max(1, Math.round(number(object, "extrudeInwards", 1F)));
            float scaleGui = number(object, "scaleGui", 1F);
            String defaultTexture = string(object, "default", null);
            Map<String, LegacyLimb> limbs = new LinkedHashMap<>();
            Map<String, LegacyPose> poses = new LinkedHashMap<>();

            JsonObject limbObject = object.getAsJsonObject("limbs");
            boolean bodyToTorso = false;

            for (Map.Entry<String, JsonElement> entry : limbObject.entrySet())
            {
                if (entry.getValue().isJsonObject())
                {
                    limbs.put(legacyLimbKey(entry.getKey(), bodyToTorso), LegacyLimb.fromJson(entry.getValue().getAsJsonObject(), bodyToTorso));
                }
            }

            JsonObject poseObject = object.getAsJsonObject("poses");

            for (Map.Entry<String, JsonElement> entry : poseObject.entrySet())
            {
                if (entry.getValue().isJsonObject())
                {
                    poses.put(entry.getKey(), LegacyPose.fromJson(entry.getValue().getAsJsonObject(), bodyToTorso));
                }
            }

            poses.putIfAbsent("standing", new LegacyPose(Map.of()));

            return new LegacyModel(texture, providesObj, legacyObj, extrudeMaxFactor, extrudeInwards, defaultTexture, scale, scaleGui, limbs, poses);
        }

        private static String legacyLimbKey(String key, boolean bodyToTorso)
        {
            return bodyToTorso && "body".equals(key) ? "torso" : key;
        }

        LegacyTransform standingTransform(String limb)
        {
            return transform("standing", limb);
        }

        LegacyTransform transform(String pose, String limb)
        {
            LegacyPose legacyPose = this.poses.get(pose);
            LegacyTransform fallback = new LegacyTransform();

            if (!"standing".equals(pose))
            {
                fallback = this.standingTransform(limb);
            }

            if (legacyPose == null)
            {
                return fallback.copy();
            }

            LegacyTransform transform = legacyPose.limbs.get(limb);

            return transform == null ? fallback.copy() : transform.withFallback(fallback);
        }

        private Vector3f worldTranslate(String limb, String pose)
        {
            LegacyTransform transform = this.transform(pose, limb);
            Vector3f result = convertTranslate(transform.translate);
            LegacyLimb data = this.limbs.get(limb);

            if (data != null && data.parent != null && this.limbs.containsKey(data.parent) && !data.parent.equals(limb))
            {
                result.add(this.worldTranslate(data.parent, pose));
            }

            return result;
        }

        private Pose poseDelta(String poseName)
        {
            if (!this.poses.containsKey(poseName))
            {
                return null;
            }

            Pose pose = new Pose();

            for (String limb : this.limbs.keySet())
            {
                LegacyTransform standing = this.standingTransform(limb);
                LegacyTransform target = this.transform(poseName, limb);
                PoseTransform transform = pose.get(limb);
                Vector3f standingTranslate = convertTranslate(standing.translate);
                Vector3f targetTranslate = convertTranslate(target.translate);
                Vector3f standingRotate = convertRotation(standing.rotate);
                Vector3f targetRotate = convertRotation(target.rotate);

                transform.translate.set(targetTranslate).sub(standingTranslate);
                transform.rotate.set(radians(targetRotate.sub(standingRotate, new Vector3f())));
                transform.scale.set(
                        target.scale.x - standing.scale.x + 1F,
                        target.scale.y - standing.scale.y + 1F,
                        target.scale.z - standing.scale.z + 1F);
            }

            return pose.isEmpty() ? null : pose;
        }
    }

    static final class LegacyLimb
    {
        final Vector3f size;
        final Vector2f texture;
        final Vector3f anchor;
        final Vector3f origin;
        final String parent;
        final float sizeOffset;
        final float opacity;
        final boolean mirror;
        final boolean invert;
        final boolean idle;
        final boolean swinging;
        final boolean swiping;
        final boolean lookX;
        final boolean lookY;
        final boolean hold;
        final boolean wheel;
        final boolean wing;
        final boolean roll;
        final boolean lighting;
        final boolean is3D;
        final String holding;
        final String slot;
        final float itemScale;

        private LegacyLimb(
                Vector3f size,
                Vector2f texture,
                Vector3f anchor,
                Vector3f origin,
                String parent,
                float sizeOffset,
                float opacity,
                boolean mirror,
                boolean invert,
                boolean idle,
                boolean swinging,
                boolean swiping,
                boolean lookX,
                boolean lookY,
                boolean hold,
                boolean wheel,
                boolean wing,
                boolean roll,
                boolean lighting,
                boolean is3D,
                String holding,
                String slot,
                float itemScale)
        {
            this.size = size;
            this.texture = texture;
            this.anchor = anchor;
            this.origin = origin;
            this.parent = parent;
            this.sizeOffset = sizeOffset;
            this.opacity = opacity;
            this.mirror = mirror;
            this.invert = invert;
            this.idle = idle;
            this.swinging = swinging;
            this.swiping = swiping;
            this.lookX = lookX;
            this.lookY = lookY;
            this.hold = hold;
            this.wheel = wheel;
            this.wing = wing;
            this.roll = roll;
            this.lighting = lighting;
            this.is3D = is3D;
            this.holding = holding;
            this.slot = slot;
            this.itemScale = itemScale;
        }

        private static LegacyLimb fromJson(JsonObject object, boolean bodyToTorso)
        {
            boolean looking = bool(object, "looking", false);
            String parent = string(object, "parent", null);

            if (bodyToTorso && "body".equals(parent))
            {
                parent = "torso";
            }

            return new LegacyLimb(
                    vector3(object.get("size"), 0F, 0F, 0F),
                    vector2(object.get("texture"), 0F, 0F),
                    vector3(object.get("anchor"), 0.5F, 0.5F, 0.5F),
                    vector3(object.get("origin"), 0F, 0F, 0F),
                    parent,
                    number(object, "sizeOffset", 0F),
                    number(object, "opacity", 1F),
                    bool(object, "mirror", false),
                    bool(object, "invert", false),
                    bool(object, "idle", false),
                    bool(object, "swinging", false),
                    bool(object, "swiping", false),
                    bool(object, "lookX", looking),
                    bool(object, "lookY", looking),
                    bool(object, "hold", true),
                    bool(object, "wheel", false),
                    bool(object, "wing", false),
                    bool(object, "roll", false),
                    bool(object, "lighting", true),
                    bool(object, "is3D", false),
                    string(object, "holding", null),
                    string(object, "slot", null),
                    number(object, "itemScale", 1F)
            );
        }
    }

    static final class LegacyPose
    {
        final Map<String, LegacyTransform> limbs;

        private LegacyPose(Map<String, LegacyTransform> limbs)
        {
            this.limbs = limbs;
        }

        private static LegacyPose fromJson(JsonObject object, boolean bodyToTorso)
        {
            JsonObject limbsObject = object.has("limbs") && object.get("limbs").isJsonObject()
                    ? object.getAsJsonObject("limbs")
                    : new JsonObject();
            Map<String, LegacyTransform> limbs = new LinkedHashMap<>();

            for (Map.Entry<String, JsonElement> entry : limbsObject.entrySet())
            {
                if (entry.getValue().isJsonObject())
                {
                    limbs.put(LegacyModel.legacyLimbKey(entry.getKey(), bodyToTorso), LegacyTransform.fromJson(entry.getValue().getAsJsonObject()));
                }
            }

            return new LegacyPose(limbs);
        }
    }

    static final class LegacyTransform
    {
        final Vector3f translate = new Vector3f();
        final Vector3f rotate = new Vector3f();
        final Vector3f scale = new Vector3f(1F, 1F, 1F);
        private boolean hasTranslate;
        private boolean hasRotate;
        private boolean hasScale;

        private static LegacyTransform fromJson(JsonObject object)
        {
            LegacyTransform transform = new LegacyTransform();

            if (object.has("translate"))
            {
                transform.translate.set(vector3(object.get("translate"), 0F, 0F, 0F));
                transform.hasTranslate = true;
            }

            if (object.has("rotate"))
            {
                transform.rotate.set(vector3(object.get("rotate"), 0F, 0F, 0F));
                transform.hasRotate = true;
            }

            if (object.has("scale"))
            {
                transform.scale.set(vector3(object.get("scale"), 1F, 1F, 1F));
                transform.hasScale = true;
            }

            return transform;
        }

        private LegacyTransform withFallback(LegacyTransform fallback)
        {
            LegacyTransform transform = this.copy();

            if (!transform.hasTranslate)
            {
                transform.translate.set(fallback.translate);
            }

            if (!transform.hasRotate)
            {
                transform.rotate.set(fallback.rotate);
            }

            if (!transform.hasScale)
            {
                transform.scale.set(fallback.scale);
            }

            return transform;
        }

        private LegacyTransform copy()
        {
            LegacyTransform transform = new LegacyTransform();

            transform.translate.set(this.translate);
            transform.rotate.set(this.rotate);
            transform.scale.set(this.scale);
            transform.hasTranslate = this.hasTranslate;
            transform.hasRotate = this.hasRotate;
            transform.hasScale = this.hasScale;

            return transform;
        }
    }

    private static Vector2f vector2(JsonElement element, float x, float y)
    {
        float[] values = vector(element, new float[] { x, y });

        return new Vector2f(values[0], values[1]);
    }

    private static Vector3f vector3(JsonElement element, float x, float y, float z)
    {
        float[] values = vector(element, new float[] { x, y, z });

        return new Vector3f(values[0], values[1], values[2]);
    }

    private static float[] vector(JsonElement element, float[] defaults)
    {
        float[] values = defaults.clone();

        if (element != null && element.isJsonArray())
        {
            int i = 0;

            for (JsonElement value : element.getAsJsonArray())
            {
                if (i >= values.length)
                {
                    break;
                }

                try
                {
                    values[i] = value.getAsFloat();
                }
                catch (Exception ignored)
                {
                }

                i++;
            }
        }

        return values;
    }

    private static boolean bool(JsonObject object, String key, boolean fallback)
    {
        try
        {
            return object.has(key) ? object.get(key).getAsBoolean() : fallback;
        }
        catch (Exception e)
        {
            return fallback;
        }
    }

    private static float number(JsonObject object, String key, float fallback)
    {
        try
        {
            return object.has(key) ? object.get(key).getAsFloat() : fallback;
        }
        catch (Exception e)
        {
            return fallback;
        }
    }

    private static String string(JsonObject object, String key, String fallback)
    {
        try
        {
            return object.has(key) ? object.get(key).getAsString() : fallback;
        }
        catch (Exception e)
        {
            return fallback;
        }
    }
}
