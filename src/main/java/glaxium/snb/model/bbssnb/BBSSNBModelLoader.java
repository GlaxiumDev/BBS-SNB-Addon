package glaxium.snb.model.bbssnb;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import glaxium.snb.model.bobj.EmoticonDefaultAnimations;
import glaxium.snb.model.fbx.FBXConverter;
import glaxium.snb.model.fbx.FBXShapeKeyNames;
import glaxium.snb.model.fbx.loaders.FBXAnimationConverter;
import glaxium.snb.model.fbx.loaders.FBXCompiledData;
import glaxium.snb.model.fbx.loaders.FBXMeshCompiler;
import glaxium.snb.model.fbx.loaders.FBXModelLoadCache;
import glaxium.snb.model.fbx.loaders.FBXModelLoader;
import glaxium.snb.model.fbx.loaders.FBXTextureResolverCML;
import glaxium.snb.model.fbx.loaders.IFbxModel;
import glaxium.snb.model.fbx.loaders.SceneFormat;
import glaxium.snb.model.fbx.loaders.java.JavaSceneImporter;
import glaxium.snb.model.fbx.scene.JavaScene;
import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJLoader.BOBJData;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.data.animation.Animation;
import mchorse.bbs_mod.cubic.data.animation.Animations;
import mchorse.bbs_mod.cubic.model.ModelManager;
import mchorse.bbs_mod.cubic.model.bobj.BOBJModel;
import mchorse.bbs_mod.cubic.model.loaders.IModelLoader;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.resources.Link;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Loads the armature-capable {@code model.bbs.json} written by BBS S&amp;B.js. */
public final class BBSSNBModelLoader implements IModelLoader
{
    private static final String FORMAT = "bbs_snb";

    @Override
    public ModelInstance load(String id, ModelManager models, Link model, Collection<Link> links, MapType config)
    {
        Link source = findModel(model, links);

        if (source == null)
        {
            return null;
        }

        try
        {
            byte[] bytes;

            try (InputStream stream = models.provider.getAsset(source))
            {
                if (stream == null)
                {
                    return null;
                }

                bytes = stream.readAllBytes();
            }

            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));

            if (!parsed.isJsonObject())
            {
                return null;
            }

            JsonObject packageJson = parsed.getAsJsonObject();

            if (!FORMAT.equals(string(packageJson, "format")))
            {
                /* A legacy cubic .bbs.json belongs to BBS's stock loader. */
                return null;
            }

            JsonObject sceneJson = object(packageJson, "scene");

            if (sceneJson == null)
            {
                throw new IllegalArgumentException("BBS S&B package has no embedded scene");
            }

            JsonObject settings = object(packageJson, "settings");
            boolean smooth = settings != null && settings.has("smooth_shading")
                    && settings.get("smooth_shading").getAsBoolean();

            long contentHash = FBXModelLoadCache.hash(bytes);
            String cacheKey = "bbs_snb:" + source.path;
            FBXModelLoadCache.Cached cached = FBXModelLoadCache.get(cacheKey, contentHash);

            BOBJData data;
            Set<String> shapeKeyNames;
            Set<String> texturedMaterials;

            if (cached != null)
            {
                data = cached.data;
                shapeKeyNames = cached.shapeKeyNames;
                texturedMaterials = cached.texturedMaterials;
            }
            else
            {
                File sourceFile = models.provider.getFile(source);
                byte[] gltf = sceneJson.toString().getBytes(StandardCharsets.UTF_8);
                JavaScene scene = JavaSceneImporter.importScene(gltf, SceneFormat.GLTF,
                        sourceFile != null && sourceFile.isFile() ? sourceFile : null);

                BBSSNBShading.apply(scene, smooth);
                shapeKeyNames = FBXShapeKeyNames.collectShapeKeyNames(scene);
                data = FBXConverter.convert(scene, 1.0F);
                texturedMaterials = FBXConverter.extractEmbeddedTextures(scene, models.provider, model);

                FBXModelLoadCache.put(cacheKey, contentHash, data, shapeKeyNames, texturedMaterials, sourceFile);
            }

            data.initiateArmatures();

            System.err.println("[BBS S&B][DEBUG] model=" + source.path + " verts=" + data.vertices.size());
            {
                float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
                float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
                for (mchorse.bbs_mod.bobj.BOBJLoader.Vertex v : data.vertices)
                {
                    minX = Math.min(minX, v.x); minY = Math.min(minY, v.y); minZ = Math.min(minZ, v.z);
                    maxX = Math.max(maxX, v.x); maxY = Math.max(maxY, v.y); maxZ = Math.max(maxZ, v.z);
                }
                System.err.println("[BBS S&B][DEBUG] vertsAABB=(" + String.format("%.4f", minX) + ", " + String.format("%.4f", minY) + ", " + String.format("%.4f", minZ)
                        + ")..(" + String.format("%.4f", maxX) + ", " + String.format("%.4f", maxY) + ", " + String.format("%.4f", maxZ) + ")");
            }
            for (mchorse.bbs_mod.bobj.BOBJArmature arm : data.armatures.values())
            {
                System.err.println("[BBS S&B][DEBUG] armature=" + arm.name + " bones=" + arm.bones.size());
                for (mchorse.bbs_mod.bobj.BOBJBone bone : arm.orderedBones)
                {
                    org.joml.Matrix4f m = bone.mat;
                    System.err.println("[BBS S&B][DEBUG]   bone '" + bone.name + "' parent='" + bone.parent + "' t=("
                            + String.format("%.4f", m.m30()) + ", " + String.format("%.4f", m.m31()) + ", "
                            + String.format("%.4f", m.m32()) + ") r=("
                            + String.format("%.3f", m.m00()) + ", " + String.format("%.3f", m.m01()) + ", " + String.format("%.3f", m.m02())
                            + " | " + String.format("%.3f", m.m10()) + ", " + String.format("%.3f", m.m11()) + ", " + String.format("%.3f", m.m12())
                            + " | " + String.format("%.3f", m.m20()) + ", " + String.format("%.3f", m.m21()) + ", " + String.format("%.3f", m.m22()) + ")");
                }
            }
            FBXCompiledData merged = FBXMeshCompiler.compileMergedWithMaterials(data);
            Collection<Link> effectiveLinks = withExtractedTextures(links, model, texturedMaterials);

            if (merged.materialNames != null && merged.materialNames.length > 0)
            {
                FBXModelLoader.resolveMaterialTextures(merged, data, model, effectiveLinks, models.provider);
            }

            BOBJArmature armature = data.armatures.isEmpty()
                    ? new BOBJArmature("Armature") : data.armatures.values().iterator().next();

            if (data.armatures.isEmpty())
            {
                armature.initArmature();
            }

            BOBJModel bobjModel = FBXModelLoader.createModel(armature, merged);
            IFbxModel importedModel = (IFbxModel) bobjModel;
            importedModel.bbsFbx$setFbxData(merged);
            importedModel.bbsFbx$setShapeKeyNames(shapeKeyNames);

            Animations animations = FBXAnimationConverter.convert(data.actions, models.parser);

            if (id.startsWith("emoticons/"))
            {
                Animations defaultEmoticonAnimations = EmoticonDefaultAnimations.load(models.provider, models.parser, armature);

                for (Animation animation : defaultEmoticonAnimations.animations.values())
                {
                    animations.add(animation);
                }
            }

            Link texture = FBXTextureResolverCML.resolveTexture(data, model, effectiveLinks, models.provider);

            if (texture == null)
            {
                float[] color = FBXTextureResolverCML.detectSolidColor(data);

                if (color != null)
                {
                    texture = FBXTextureResolverCML.colorLink(color);
                }
            }

            ModelInstance instance = new ModelInstance(id, bobjModel, animations, texture);
            instance.applyConfig(config);

            return instance;
        }
        catch (Throwable e)
        {
            System.err.println("[BBS S&B] Failed to load " + source.path + ": "
                    + e.getClass().getSimpleName() + " - " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private static Link findModel(Link folder, Collection<Link> links)
    {
        String prefix = folder.path.endsWith("/") ? folder.path : folder.path + "/";
        String preferred = prefix + "model.bbs.json";
        Link fallback = null;

        for (Link link : links)
        {
            String path = link.path;

            if (preferred.equals(path))
            {
                return link;
            }

            if (path != null && path.startsWith(prefix) && path.endsWith(".bbs.json"))
            {
                String relative = path.substring(prefix.length());

                if (!relative.contains("/") && fallback == null)
                {
                    fallback = link;
                }
            }
        }

        return fallback;
    }

    private static Collection<Link> withExtractedTextures(Collection<Link> links, Link model, Set<String> materials)
    {
        List<Link> effective = new ArrayList<>(links);
        Set<String> paths = new LinkedHashSet<>();

        for (Link link : effective)
        {
            paths.add(link.path);
        }

        if (materials != null)
        {
            for (String material : materials)
            {
                if (material == null || material.isBlank())
                {
                    continue;
                }

                Link texture = model.combine("textures/" + material + "/default.png");

                if (paths.add(texture.path))
                {
                    effective.add(texture);
                }
            }
        }

        return effective;
    }

    private static JsonObject object(JsonObject object, String key)
    {
        return object.has(key) && object.get(key).isJsonObject() ? object.getAsJsonObject(key) : null;
    }

    private static String string(JsonObject object, String key)
    {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : null;
    }
}
