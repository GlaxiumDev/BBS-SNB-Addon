package glaxium.snb.model.gltf;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import glaxium.snb.model.scene.Scene;
import glaxium.snb.model.scene.SceneAnimation;
import glaxium.snb.model.scene.SceneBone;
import glaxium.snb.model.scene.SceneMaterial;
import glaxium.snb.model.scene.SceneMesh;
import glaxium.snb.model.scene.SceneMorphTarget;
import glaxium.snb.model.scene.SceneNode;
import glaxium.snb.model.scene.SceneNodeAnim;
import glaxium.snb.model.scene.SceneTexture;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Converts a resolved glTF document into the shared scene IR. */
final class GltfSceneBuilder
{
    private final GltfDocument document;
    private final AccessorReader accessors;
    private final Scene result = new Scene();
    private final JsonArray nodeDefinitions;
    private final String[] nodeNames;
    private final SceneNode[] builtNodes;
    private final byte[] nodeStates;
    private final Map<MeshSkinKey, int[]> meshCache = new LinkedHashMap<>();

    private String[] imagePaths = new String[0];

    GltfSceneBuilder(GltfDocument document) throws IOException
    {
        this.document = document;
        this.accessors = new AccessorReader(document);
        this.nodeDefinitions = document.array("nodes");
        this.nodeNames = new String[nodeDefinitions.size()];
        this.builtNodes = new SceneNode[nodeDefinitions.size()];
        this.nodeStates = new byte[nodeDefinitions.size()];
        initializeNodeNames();
    }

    Scene build() throws IOException
    {
        readImages();
        readMaterials();
        readNodeHierarchy();
        readAnimations();
        return result;
    }

    private void initializeNodeNames() throws IOException
    {
        Set<String> usedNames = new HashSet<>();
        usedNames.add("RootNode");

        for (int index = 0; index < nodeDefinitions.size(); index++)
        {
            JsonObject node = GltfDocument.objectAt(nodeDefinitions, index, "node");
            String base = optionalString(node, "name", "node_" + index, "node " + index);
            String name = base;
            int suffix = 1;

            while (!usedNames.add(name))
            {
                name = base + "_" + suffix++;
            }
            nodeNames[index] = name;
        }
    }

    private void readImages() throws IOException
    {
        JsonArray images = document.array("images");
        imagePaths = new String[images.size()];
        for (int imageIndex = 0; imageIndex < images.size(); imageIndex++)
        {
            JsonObject image = GltfDocument.objectAt(images, imageIndex, "image");
            JsonElement viewElement = image.get("bufferView");
            JsonElement uriElement = image.get("uri");

            if (viewElement != null && !viewElement.isJsonNull())
            {
                int viewIndex = GltfDocument.requiredInt(image, "bufferView", "image " + imageIndex);
                byte[] data = document.bufferViewBytes(viewIndex);
                String mimeType = optionalString(image, "mimeType", "", "image " + imageIndex);
                imagePaths[imageIndex] = addEmbeddedTexture(image, imageIndex, data, mimeType);
            }
            else if (uriElement != null && !uriElement.isJsonNull())
            {
                if (!uriElement.isJsonPrimitive() || !uriElement.getAsJsonPrimitive().isString())
                {
                    throw new IOException("image " + imageIndex + " uri must be a string");
                }
                String uri = uriElement.getAsString();
                if (uri.startsWith("data:"))
                {
                    GltfDocument.DataUri dataUri = GltfDocument.decodeDataUri(uri);
                    String mimeType = optionalString(
                        image, "mimeType", dataUri.mediaType, "image " + imageIndex);
                    imagePaths[imageIndex] = addEmbeddedTexture(
                        image, imageIndex, dataUri.data, mimeType);
                }
                else
                {
                    imagePaths[imageIndex] = uri;
                }
            }
            else
            {
                throw new IOException("image " + imageIndex + " has neither uri nor bufferView");
            }
        }
    }

    private String addEmbeddedTexture(JsonObject image, int imageIndex, byte[] data, String mimeType)
        throws IOException
    {
        SceneTexture texture = new SceneTexture();
        texture.filename = optionalString(image, "name", "", "image " + imageIndex);
        if (texture.filename.isEmpty())
        {
            texture.filename = "image_" + imageIndex + extensionForMimeType(mimeType);
        }
        texture.width = data.length;
        texture.height = 0;
        texture.data = data;
        int textureIndex = result.textures.size();
        result.textures.add(texture);
        return "*" + textureIndex;
    }

    private void readMaterials() throws IOException
    {
        JsonArray materials = document.array("materials");
        for (int materialIndex = 0; materialIndex < materials.size(); materialIndex++)
        {
            JsonObject definition = GltfDocument.objectAt(materials, materialIndex, "material");
            SceneMaterial material = new SceneMaterial();
            material.name = optionalString(
                definition, "name", "material_" + materialIndex, "material " + materialIndex);

            float[] baseColor = {1.0f, 1.0f, 1.0f, 1.0f};
            JsonElement pbrElement = definition.get("pbrMetallicRoughness");
            if (pbrElement != null && !pbrElement.isJsonNull())
            {
                if (!pbrElement.isJsonObject())
                {
                    throw new IOException("material " + materialIndex
                        + " pbrMetallicRoughness must be an object");
                }
                JsonObject pbr = pbrElement.getAsJsonObject();
                JsonElement factorElement = pbr.get("baseColorFactor");
                if (factorElement != null && !factorElement.isJsonNull())
                {
                    baseColor = floatArray(
                        factorElement, 4, "material " + materialIndex + " baseColorFactor");
                }

                JsonElement textureElement = pbr.get("baseColorTexture");
                if (textureElement != null && !textureElement.isJsonNull())
                {
                    if (!textureElement.isJsonObject())
                    {
                        throw new IOException("material " + materialIndex
                            + " baseColorTexture must be an object");
                    }
                    int textureIndex = GltfDocument.requiredInt(
                        textureElement.getAsJsonObject(), "index",
                        "material " + materialIndex + " baseColorTexture");
                    material.diffuseTexturePath = texturePath(textureIndex);
                }
            }
            material.color = new float[]{baseColor[0], baseColor[1], baseColor[2]};
            result.materials.add(material);
        }
    }

    private String texturePath(int textureIndex) throws IOException
    {
        JsonObject texture = GltfDocument.objectAt(
            document.array("textures"), textureIndex, "texture");
        int imageIndex = GltfDocument.requiredInt(texture, "source", "texture " + textureIndex);
        if (imageIndex < 0 || imageIndex >= imagePaths.length)
        {
            throw new IOException("texture " + textureIndex + " references invalid image " + imageIndex);
        }
        return imagePaths[imageIndex];
    }

    private void readNodeHierarchy() throws IOException
    {
        SceneNode root = new SceneNode("RootNode");
        result.rootNode = root;

        JsonArray scenes = document.array("scenes");
        if (!scenes.isEmpty())
        {
            int defaultScene = GltfDocument.optionalInt(
                document.root, "scene", 0, "glTF root");
            JsonObject scene = GltfDocument.objectAt(scenes, defaultScene, "scene");
            JsonArray roots = optionalArray(scene, "nodes", "scene " + defaultScene);
            for (int index = 0; index < roots.size(); index++)
            {
                root.children.add(buildNode(arrayInt(
                    roots, index, "scene " + defaultScene + " root node")));
            }
            return;
        }

        // A scene is optional in glTF. In that case, expose all parentless nodes.
        boolean[] isChild = new boolean[nodeDefinitions.size()];
        for (int nodeIndex = 0; nodeIndex < nodeDefinitions.size(); nodeIndex++)
        {
            JsonObject node = GltfDocument.objectAt(nodeDefinitions, nodeIndex, "node");
            JsonArray children = optionalArray(node, "children", "node " + nodeIndex);
            for (int childIndex = 0; childIndex < children.size(); childIndex++)
            {
                int child = arrayInt(children, childIndex, "node " + nodeIndex + " child");
                validateNodeIndex(child, "node " + nodeIndex + " child");
                isChild[child] = true;
            }
        }
        for (int nodeIndex = 0; nodeIndex < nodeDefinitions.size(); nodeIndex++)
        {
            if (!isChild[nodeIndex])
            {
                root.children.add(buildNode(nodeIndex));
            }
        }
    }

    private SceneNode buildNode(int nodeIndex) throws IOException
    {
        validateNodeIndex(nodeIndex, "node");
        if (nodeStates[nodeIndex] == 1)
        {
            throw new IOException("Node hierarchy contains a cycle at node " + nodeIndex);
        }
        if (nodeStates[nodeIndex] == 2)
        {
            return builtNodes[nodeIndex];
        }

        nodeStates[nodeIndex] = 1;
        JsonObject definition = GltfDocument.objectAt(nodeDefinitions, nodeIndex, "node");
        SceneNode node = new SceneNode(nodeNames[nodeIndex]);
        builtNodes[nodeIndex] = node;
        readNodeTransform(definition, node, nodeIndex);

        JsonElement meshElement = definition.get("mesh");
        if (meshElement != null && !meshElement.isJsonNull())
        {
            int meshIndex = GltfDocument.requiredInt(definition, "mesh", "node " + nodeIndex);
            int skinIndex = GltfDocument.optionalInt(definition, "skin", -1, "node " + nodeIndex);
            int[] sceneMeshes = meshCache.get(new MeshSkinKey(meshIndex, skinIndex));
            if (sceneMeshes == null)
            {
                sceneMeshes = buildMesh(meshIndex, skinIndex);
                meshCache.put(new MeshSkinKey(meshIndex, skinIndex), sceneMeshes);
            }
            for (int sceneMesh : sceneMeshes)
            {
                node.meshIndices.add(sceneMesh);
            }
        }

        JsonArray children = optionalArray(definition, "children", "node " + nodeIndex);
        for (int childIndex = 0; childIndex < children.size(); childIndex++)
        {
            node.children.add(buildNode(arrayInt(children, childIndex, "node " + nodeIndex + " child")));
        }
        nodeStates[nodeIndex] = 2;
        return node;
    }

    private void readNodeTransform(JsonObject definition, SceneNode node, int nodeIndex)
        throws IOException
    {
        JsonElement matrixElement = definition.get("matrix");
        if (matrixElement != null && !matrixElement.isJsonNull())
        {
            float[] matrix = floatArray(matrixElement, 16, "node " + nodeIndex + " matrix");
            node.localTransform.set(matrix);
            return;
        }

        float[] translation = optionalFloatArray(
            definition, "translation", new float[]{0.0f, 0.0f, 0.0f}, "node " + nodeIndex);
        float[] rotation = optionalFloatArray(
            definition, "rotation", new float[]{0.0f, 0.0f, 0.0f, 1.0f}, "node " + nodeIndex);
        float[] scale = optionalFloatArray(
            definition, "scale", new float[]{1.0f, 1.0f, 1.0f}, "node " + nodeIndex);
        node.localTransform
            .translation(translation[0], translation[1], translation[2])
            .rotate(new Quaternionf(rotation[0], rotation[1], rotation[2], rotation[3]))
            .scale(scale[0], scale[1], scale[2]);
    }

    private int[] buildMesh(int meshIndex, int skinIndex) throws IOException
    {
        JsonObject meshDefinition = GltfDocument.objectAt(
            document.array("meshes"), meshIndex, "mesh");
        JsonArray primitives = requiredArray(meshDefinition, "primitives", "mesh " + meshIndex);
        if (primitives.isEmpty())
        {
            throw new IOException("mesh " + meshIndex + " has no primitives");
        }

        String meshName = optionalString(
            meshDefinition, "name", "mesh_" + meshIndex, "mesh " + meshIndex);
        String[] targetNames = morphTargetNames(meshDefinition);
        int[] sceneMeshIndices = new int[primitives.size()];
        for (int primitiveIndex = 0; primitiveIndex < primitives.size(); primitiveIndex++)
        {
            JsonObject primitive = GltfDocument.objectAt(primitives, primitiveIndex, "primitive");
            SceneMesh mesh = buildPrimitive(meshDefinition, primitive, meshIndex, primitiveIndex,
                primitives.size(), skinIndex, meshName, targetNames);
            sceneMeshIndices[primitiveIndex] = result.meshes.size();
            result.meshes.add(mesh);
        }
        return sceneMeshIndices;
    }

    private SceneMesh buildPrimitive(JsonObject meshDefinition, JsonObject primitive,
        int meshIndex, int primitiveIndex, int primitiveCount, int skinIndex,
        String meshName, String[] meshTargetNames) throws IOException
    {
        JsonElement attributesElement = primitive.get("attributes");
        if (attributesElement == null || !attributesElement.isJsonObject())
        {
            throw new IOException("mesh " + meshIndex + " primitive " + primitiveIndex
                + " is missing attributes");
        }
        JsonObject attributes = attributesElement.getAsJsonObject();

        int positionAccessor = requiredAttribute(
            attributes, "POSITION", meshIndex, primitiveIndex);
        AccessorReader.AccessorInfo positionInfo = accessors.info(positionAccessor);
        requireAccessorShape(positionInfo, "VEC3", 3, "POSITION");

        SceneMesh mesh = new SceneMesh();
        mesh.name = primitiveCount == 1 ? meshName : meshName + "_primitive_" + primitiveIndex;
        mesh.positions = accessors.readFloats(positionAccessor);
        int vertexCount = positionInfo.count;

        Integer normalAccessor = optionalAttribute(attributes, "NORMAL", meshIndex, primitiveIndex);
        if (normalAccessor != null)
        {
            AccessorReader.AccessorInfo info = accessors.info(normalAccessor);
            requireAttributeShape(info, "VEC3", 3, vertexCount, "NORMAL");
            mesh.normals = accessors.readFloats(normalAccessor);
        }

        Integer uvAccessor = optionalAttribute(attributes, "TEXCOORD_0", meshIndex, primitiveIndex);
        if (uvAccessor != null)
        {
            AccessorReader.AccessorInfo info = accessors.info(uvAccessor);
            requireAttributeShape(info, "VEC2", 2, vertexCount, "TEXCOORD_0");
            mesh.uvs = accessors.readFloats(uvAccessor);
        }

        int[] sourceIndices;
        JsonElement indexElement = primitive.get("indices");
        if (indexElement != null && !indexElement.isJsonNull())
        {
            int accessorIndex = GltfDocument.requiredInt(
                primitive, "indices", "mesh " + meshIndex + " primitive " + primitiveIndex);
            AccessorReader.AccessorInfo info = accessors.info(accessorIndex);
            requireAccessorShape(info, "SCALAR", 1, "indices");
            if (info.componentType != AccessorReader.UNSIGNED_BYTE
                && info.componentType != AccessorReader.UNSIGNED_SHORT
                && info.componentType != AccessorReader.UNSIGNED_INT)
            {
                throw new IOException("mesh " + meshIndex + " primitive " + primitiveIndex
                    + " indices use a non-unsigned component type");
            }
            sourceIndices = accessors.readInts(accessorIndex);
        }
        else
        {
            sourceIndices = new int[vertexCount];
            for (int index = 0; index < vertexCount; index++)
            {
                sourceIndices[index] = index;
            }
        }
        validateIndices(sourceIndices, vertexCount, meshIndex, primitiveIndex);
        int mode = GltfDocument.optionalInt(
            primitive, "mode", 4, "mesh " + meshIndex + " primitive " + primitiveIndex);
        mesh.indices = triangulate(sourceIndices, mode, meshIndex, primitiveIndex);
        mesh.triangulated = true;

        int materialIndex = GltfDocument.optionalInt(
            primitive, "material", -1, "mesh " + meshIndex + " primitive " + primitiveIndex);
        if (materialIndex < -1 || materialIndex >= result.materials.size())
        {
            throw new IOException("mesh " + meshIndex + " primitive " + primitiveIndex
                + " references invalid material " + materialIndex);
        }
        mesh.materialIndex = materialIndex;

        InfluenceSet influences0 = readInfluences(
            attributes, 0, vertexCount, meshIndex, primitiveIndex);
        InfluenceSet influences1 = readInfluences(
            attributes, 1, vertexCount, meshIndex, primitiveIndex);
        if (influences1 != null && influences0 == null)
        {
            throw new IOException("mesh " + meshIndex + " primitive " + primitiveIndex
                + " provides JOINTS_1/WEIGHTS_1 without set 0");
        }
        if (skinIndex < -1)
        {
            throw new IOException("mesh " + meshIndex + " primitive " + primitiveIndex
                + " references invalid skin " + skinIndex);
        }
        if (skinIndex >= 0)
        {
            GltfDocument.objectAt(document.array("skins"), skinIndex, "skin");
            if (influences0 != null)
            {
                buildBones(mesh, skinIndex, influences0, influences1, meshIndex, primitiveIndex);
            }
        }

        readMorphTargets(mesh, meshDefinition, primitive, meshIndex, primitiveIndex,
            vertexCount, meshTargetNames);
        return mesh;
    }

    private InfluenceSet readInfluences(JsonObject attributes, int set, int vertexCount,
        int meshIndex, int primitiveIndex) throws IOException
    {
        String jointsName = "JOINTS_" + set;
        String weightsName = "WEIGHTS_" + set;
        Integer jointsAccessor = optionalAttribute(attributes, jointsName, meshIndex, primitiveIndex);
        Integer weightsAccessor = optionalAttribute(attributes, weightsName, meshIndex, primitiveIndex);
        if (jointsAccessor == null && weightsAccessor == null)
        {
            return null;
        }
        if (jointsAccessor == null || weightsAccessor == null)
        {
            throw new IOException("mesh " + meshIndex + " primitive " + primitiveIndex
                + " must provide both " + jointsName + " and " + weightsName);
        }

        AccessorReader.AccessorInfo jointInfo = accessors.info(jointsAccessor);
        AccessorReader.AccessorInfo weightInfo = accessors.info(weightsAccessor);
        requireAttributeShape(jointInfo, "VEC4", 4, vertexCount, jointsName);
        requireAttributeShape(weightInfo, "VEC4", 4, vertexCount, weightsName);
        if (jointInfo.componentType == AccessorReader.FLOAT)
        {
            throw new IOException(jointsName + " must use an integer component type");
        }
        return new InfluenceSet(
            accessors.readInts(jointsAccessor), accessors.readFloats(weightsAccessor));
    }

    private void buildBones(SceneMesh mesh, int skinIndex,
        InfluenceSet influences0, InfluenceSet influences1,
        int meshIndex, int primitiveIndex) throws IOException
    {
        JsonObject skin = GltfDocument.objectAt(document.array("skins"), skinIndex, "skin");
        JsonArray joints = requiredArray(skin, "joints", "skin " + skinIndex);
        String[] names = new String[joints.size()];
        Matrix4f[] inverseBindMatrices = inverseBindMatrices(skin, skinIndex, joints.size());
        LinkedHashMap<String, BoneAccumulator> byName = new LinkedHashMap<>();
        BoneAccumulator[] byJoint = new BoneAccumulator[joints.size()];

        for (int jointIndex = 0; jointIndex < joints.size(); jointIndex++)
        {
            int nodeIndex = arrayInt(joints, jointIndex, "skin " + skinIndex + " joint");
            validateNodeIndex(nodeIndex, "skin " + skinIndex + " joint");
            names[jointIndex] = nodeNames[nodeIndex];
            BoneAccumulator accumulator = byName.get(names[jointIndex]);
            if (accumulator == null)
            {
                accumulator = new BoneAccumulator(names[jointIndex], inverseBindMatrices[jointIndex]);
                byName.put(names[jointIndex], accumulator);
            }
            byJoint[jointIndex] = accumulator;
        }

        for (int vertex = 0; vertex < mesh.vertexCount(); vertex++)
        {
            addInfluences(influences0, vertex, byJoint, skinIndex, meshIndex, primitiveIndex);
            addInfluences(influences1, vertex, byJoint, skinIndex, meshIndex, primitiveIndex);
        }
        for (BoneAccumulator accumulator : byName.values())
        {
            if (accumulator.size > 0)
            {
                mesh.bones.add(accumulator.finish());
            }
        }
    }

    private Matrix4f[] inverseBindMatrices(JsonObject skin, int skinIndex, int jointCount)
        throws IOException
    {
        Matrix4f[] matrices = new Matrix4f[jointCount];
        for (int index = 0; index < jointCount; index++)
        {
            matrices[index] = new Matrix4f();
        }

        JsonElement accessorElement = skin.get("inverseBindMatrices");
        if (accessorElement == null || accessorElement.isJsonNull())
        {
            return matrices;
        }
        int accessorIndex = GltfDocument.requiredInt(
            skin, "inverseBindMatrices", "skin " + skinIndex);
        AccessorReader.AccessorInfo info = accessors.info(accessorIndex);
        requireAccessorShape(info, "MAT4", 16, "skin inverseBindMatrices");
        if (info.count < jointCount)
        {
            throw new IOException("skin " + skinIndex + " has " + jointCount
                + " joints but only " + info.count + " inverse bind matrices");
        }
        float[] values = accessors.readFloats(accessorIndex);
        for (int index = 0; index < jointCount; index++)
        {
            int offset = index * 16;
            matrices[index].set(
                values[offset], values[offset + 1], values[offset + 2], values[offset + 3],
                values[offset + 4], values[offset + 5], values[offset + 6], values[offset + 7],
                values[offset + 8], values[offset + 9], values[offset + 10], values[offset + 11],
                values[offset + 12], values[offset + 13], values[offset + 14], values[offset + 15]);
        }
        return matrices;
    }

    private static void addInfluences(InfluenceSet set, int vertex, BoneAccumulator[] joints,
        int skinIndex, int meshIndex, int primitiveIndex) throws IOException
    {
        if (set == null)
        {
            return;
        }
        int offset = vertex * 4;
        for (int component = 0; component < 4; component++)
        {
            int jointIndex = set.joints[offset + component];
            float weight = set.weights[offset + component];
            if (jointIndex < 0 || jointIndex >= joints.length)
            {
                throw new IOException("mesh " + meshIndex + " primitive " + primitiveIndex
                    + " references joint " + jointIndex + " outside skin " + skinIndex);
            }
            if (!Float.isFinite(weight) || weight < 0.0f)
            {
                throw new IOException("mesh " + meshIndex + " primitive " + primitiveIndex
                    + " contains an invalid bone weight");
            }
            if (weight != 0.0f)
            {
                joints[jointIndex].add(vertex, weight);
            }
        }
    }

    private void readMorphTargets(SceneMesh mesh, JsonObject meshDefinition, JsonObject primitive,
        int meshIndex, int primitiveIndex, int vertexCount, String[] meshTargetNames)
        throws IOException
    {
        JsonElement targetsElement = primitive.get("targets");
        if (targetsElement == null || targetsElement.isJsonNull())
        {
            return;
        }
        if (!targetsElement.isJsonArray())
        {
            throw new IOException("mesh " + meshIndex + " primitive " + primitiveIndex
                + " targets must be an array");
        }

        JsonArray targets = targetsElement.getAsJsonArray();
        String[] primitiveNames = morphTargetNames(primitive);
        String[] names = meshTargetNames.length > 0 ? meshTargetNames : primitiveNames;
        for (int targetIndex = 0; targetIndex < targets.size(); targetIndex++)
        {
            JsonObject target = GltfDocument.objectAt(targets, targetIndex, "morph target");
            SceneMorphTarget morph = new SceneMorphTarget();
            morph.name = targetIndex < names.length && !names[targetIndex].isEmpty()
                ? names[targetIndex] : "target_" + targetIndex;

            Integer positionAccessor = optionalAttribute(
                target, "POSITION", meshIndex, primitiveIndex);
            if (positionAccessor != null)
            {
                AccessorReader.AccessorInfo info = accessors.info(positionAccessor);
                requireAttributeShape(info, "VEC3", 3, vertexCount, "morph POSITION");
                morph.positions = addDelta(mesh.positions, accessors.readFloats(positionAccessor));
            }

            Integer normalAccessor = optionalAttribute(
                target, "NORMAL", meshIndex, primitiveIndex);
            if (normalAccessor != null)
            {
                if (mesh.normals.length == 0)
                {
                    throw new IOException("mesh " + meshIndex + " primitive " + primitiveIndex
                        + " has a morph NORMAL target but no base normals");
                }
                AccessorReader.AccessorInfo info = accessors.info(normalAccessor);
                requireAttributeShape(info, "VEC3", 3, vertexCount, "morph NORMAL");
                morph.normals = addDelta(mesh.normals, accessors.readFloats(normalAccessor));
            }
            mesh.morphTargets.add(morph);
        }
    }

    private static float[] addDelta(float[] base, float[] delta)
    {
        float[] absolute = new float[base.length];
        for (int index = 0; index < base.length; index++)
        {
            absolute[index] = base[index] + delta[index];
        }
        return absolute;
    }

    private void readAnimations() throws IOException
    {
        JsonArray animations = document.array("animations");
        for (int animationIndex = 0; animationIndex < animations.size(); animationIndex++)
        {
            JsonObject definition = GltfDocument.objectAt(
                animations, animationIndex, "animation");
            JsonArray samplers = requiredArray(
                definition, "samplers", "animation " + animationIndex);
            JsonArray channels = requiredArray(
                definition, "channels", "animation " + animationIndex);

            SceneAnimation animation = new SceneAnimation();
            animation.name = optionalString(definition, "name",
                "animation_" + animationIndex, "animation " + animationIndex);
            animation.ticksPerSecond = 1.0;
            LinkedHashMap<Integer, SceneNodeAnim> nodeChannels = new LinkedHashMap<>();

            for (int channelIndex = 0; channelIndex < channels.size(); channelIndex++)
            {
                JsonObject channel = GltfDocument.objectAt(channels, channelIndex, "animation channel");
                JsonElement targetElement = channel.get("target");
                if (targetElement == null || !targetElement.isJsonObject())
                {
                    throw new IOException("animation " + animationIndex + " channel "
                        + channelIndex + " is missing target");
                }
                JsonObject target = targetElement.getAsJsonObject();
                String path = GltfDocument.requiredString(
                    target, "path", "animation " + animationIndex + " channel " + channelIndex);
                if ("weights".equals(path))
                {
                    continue; // SceneNodeAnim has no morph-weight track.
                }
                if (!"translation".equals(path) && !"rotation".equals(path) && !"scale".equals(path))
                {
                    throw new IOException("animation " + animationIndex
                        + " uses unsupported target path " + path);
                }

                int nodeIndex = GltfDocument.requiredInt(
                    target, "node", "animation " + animationIndex + " channel " + channelIndex);
                validateNodeIndex(nodeIndex, "animation target");
                int samplerIndex = GltfDocument.requiredInt(
                    channel, "sampler", "animation " + animationIndex + " channel " + channelIndex);
                JsonObject sampler = GltfDocument.objectAt(
                    samplers, samplerIndex, "animation sampler");
                AnimationValues values = readAnimationValues(
                    sampler, path, animationIndex, channelIndex);

                SceneNodeAnim nodeAnimation = nodeChannels.computeIfAbsent(nodeIndex, ignored ->
                {
                    SceneNodeAnim created = new SceneNodeAnim();
                    created.nodeName = nodeNames[nodeIndex];
                    return created;
                });
                assignAnimationValues(nodeAnimation, path, values);
            }

            animation.channels.addAll(nodeChannels.values());
            result.animations.add(animation);
        }
    }

    private AnimationValues readAnimationValues(JsonObject sampler, String path,
        int animationIndex, int channelIndex) throws IOException
    {
        String description = "animation " + animationIndex + " channel " + channelIndex;
        int inputAccessor = GltfDocument.requiredInt(sampler, "input", description + " sampler");
        AccessorReader.AccessorInfo inputInfo = accessors.info(inputAccessor);
        requireAccessorShape(inputInfo, "SCALAR", 1, "animation input");
        float[] input = accessors.readFloats(inputAccessor);
        double[] times = new double[input.length];
        for (int index = 0; index < input.length; index++)
        {
            if (!Float.isFinite(input[index]))
            {
                throw new IOException(description + " contains a non-finite key time");
            }
            if (index > 0 && input[index] < input[index - 1])
            {
                throw new IOException(description + " key times are not increasing");
            }
            times[index] = input[index];
        }

        String interpolation = optionalString(
            sampler, "interpolation", "LINEAR", description + " sampler");
        if (!"LINEAR".equals(interpolation)
            && !"STEP".equals(interpolation)
            && !"CUBICSPLINE".equals(interpolation))
        {
            throw new IOException(description + " uses unsupported interpolation " + interpolation);
        }

        int components = "rotation".equals(path) ? 4 : 3;
        String type = "rotation".equals(path) ? "VEC4" : "VEC3";
        int outputAccessor = GltfDocument.requiredInt(sampler, "output", description + " sampler");
        AccessorReader.AccessorInfo outputInfo = accessors.info(outputAccessor);
        requireAccessorShape(outputInfo, type, components, "animation " + path + " output");
        int expectedCount;
        try
        {
            expectedCount = Math.multiplyExact(
                inputInfo.count, "CUBICSPLINE".equals(interpolation) ? 3 : 1);
        }
        catch (ArithmeticException exception)
        {
            throw new IOException(description + " has too many animation keys", exception);
        }
        if (outputInfo.count != expectedCount)
        {
            throw new IOException(description + " has " + inputInfo.count + " input keys but "
                + outputInfo.count + " output values");
        }

        float[] output = accessors.readFloats(outputAccessor);
        float[] values = new float[inputInfo.count * components];
        boolean cubic = "CUBICSPLINE".equals(interpolation);
        float[] inTangents = cubic ? new float[values.length] : new float[0];
        float[] outTangents = cubic ? new float[values.length] : new float[0];
        for (int key = 0; key < inputInfo.count; key++)
        {
            int sourceElement = cubic ? key * 3 + 1 : key;
            System.arraycopy(output, sourceElement * components,
                values, key * components, components);
            if (cubic)
            {
                System.arraycopy(output, (sourceElement - 1) * components,
                    inTangents, key * components, components);
                System.arraycopy(output, (sourceElement + 1) * components,
                    outTangents, key * components, components);
            }
        }
        return new AnimationValues(times, values, inTangents, outTangents,
            components, interpolation);
    }

    private static void assignAnimationValues(
        SceneNodeAnim nodeAnimation, String path, AnimationValues values)
    {
        if ("translation".equals(path))
        {
            nodeAnimation.positionTimes = values.times;
            nodeAnimation.positionValues = vectors(values);
            nodeAnimation.positionInterpolation = values.interpolation;
            nodeAnimation.positionInTangents = vectors(values, values.inTangents);
            nodeAnimation.positionOutTangents = vectors(values, values.outTangents);
        }
        else if ("scale".equals(path))
        {
            nodeAnimation.scalingTimes = values.times;
            nodeAnimation.scalingValues = vectors(values);
            nodeAnimation.scalingInterpolation = values.interpolation;
            nodeAnimation.scalingInTangents = vectors(values, values.inTangents);
            nodeAnimation.scalingOutTangents = vectors(values, values.outTangents);
        }
        else
        {
            nodeAnimation.rotationTimes = values.times;
            nodeAnimation.rotationValues = quaternions(values);
            nodeAnimation.rotationInterpolation = values.interpolation;
            nodeAnimation.rotationInTangents = quaternions(values, values.inTangents);
            nodeAnimation.rotationOutTangents = quaternions(values, values.outTangents);
        }
    }

    private static Vector3f[] vectors(AnimationValues values)
    {
        return vectors(values, values.values);
    }

    private static Vector3f[] vectors(AnimationValues values, float[] data)
    {
        if (data.length == 0)
        {
            return new Vector3f[0];
        }
        Vector3f[] vectors = new Vector3f[values.times.length];
        for (int index = 0; index < vectors.length; index++)
        {
            int offset = index * values.components;
            vectors[index] = new Vector3f(
                data[offset], data[offset + 1], data[offset + 2]);
        }
        return vectors;
    }

    private static Quaternionf[] quaternions(AnimationValues values)
    {
        return quaternions(values, values.values);
    }

    private static Quaternionf[] quaternions(AnimationValues values, float[] data)
    {
        if (data.length == 0)
        {
            return new Quaternionf[0];
        }
        Quaternionf[] quaternions = new Quaternionf[values.times.length];
        for (int index = 0; index < quaternions.length; index++)
        {
            int offset = index * values.components;
            quaternions[index] = new Quaternionf(
                data[offset], data[offset + 1], data[offset + 2], data[offset + 3]);
        }
        return quaternions;
    }

    private static int[] triangulate(int[] source, int mode, int meshIndex, int primitiveIndex)
        throws IOException
    {
        if (mode == 4)
        {
            if (source.length % 3 != 0)
            {
                throw new IOException("mesh " + meshIndex + " primitive " + primitiveIndex
                    + " triangle index count is not divisible by 3");
            }
            return Arrays.copyOf(source, source.length);
        }
        if (mode != 5 && mode != 6)
        {
            throw new IOException("mesh " + meshIndex + " primitive " + primitiveIndex
                + " uses unsupported primitive mode " + mode);
        }
        if (source.length < 3)
        {
            return new int[0];
        }

        int[] triangles = new int[(source.length - 2) * 3];
        int output = 0;
        for (int index = 2; index < source.length; index++)
        {
            if (mode == 6)
            {
                triangles[output++] = source[0];
                triangles[output++] = source[index - 1];
                triangles[output++] = source[index];
            }
            else if ((index & 1) == 0)
            {
                triangles[output++] = source[index - 2];
                triangles[output++] = source[index - 1];
                triangles[output++] = source[index];
            }
            else
            {
                triangles[output++] = source[index - 1];
                triangles[output++] = source[index - 2];
                triangles[output++] = source[index];
            }
        }
        return triangles;
    }

    private static void validateIndices(
        int[] indices, int vertexCount, int meshIndex, int primitiveIndex) throws IOException
    {
        for (int index : indices)
        {
            if (index < 0 || index >= vertexCount)
            {
                throw new IOException("mesh " + meshIndex + " primitive " + primitiveIndex
                    + " contains out-of-range vertex index " + index);
            }
        }
    }

    private static void requireAttributeShape(AccessorReader.AccessorInfo info,
        String type, int components, int count, String semantic) throws IOException
    {
        requireAccessorShape(info, type, components, semantic);
        if (info.count != count)
        {
            throw new IOException(semantic + " accessor has " + info.count
                + " elements; expected " + count);
        }
    }

    private static void requireAccessorShape(AccessorReader.AccessorInfo info,
        String type, int components, String description) throws IOException
    {
        if (!type.equals(info.type) || info.components != components)
        {
            throw new IOException(description + " accessor must be " + type
                + ", not " + info.type);
        }
    }

    private static int requiredAttribute(
        JsonObject attributes, String name, int meshIndex, int primitiveIndex) throws IOException
    {
        Integer value = optionalAttribute(attributes, name, meshIndex, primitiveIndex);
        if (value == null)
        {
            throw new IOException("mesh " + meshIndex + " primitive " + primitiveIndex
                + " is missing required " + name + " attribute");
        }
        return value;
    }

    private static Integer optionalAttribute(
        JsonObject attributes, String name, int meshIndex, int primitiveIndex) throws IOException
    {
        JsonElement value = attributes.get(name);
        if (value == null || value.isJsonNull())
        {
            return null;
        }
        return GltfDocument.requiredInt(
            attributes, name, "mesh " + meshIndex + " primitive " + primitiveIndex + " attributes");
    }

    private static String[] morphTargetNames(JsonObject owner) throws IOException
    {
        JsonElement extrasElement = owner.get("extras");
        if (extrasElement == null || extrasElement.isJsonNull())
        {
            return new String[0];
        }
        if (!extrasElement.isJsonObject())
        {
            return new String[0];
        }
        JsonElement namesElement = extrasElement.getAsJsonObject().get("targetNames");
        if (namesElement == null || namesElement.isJsonNull())
        {
            return new String[0];
        }
        if (!namesElement.isJsonArray())
        {
            throw new IOException("extras.targetNames must be an array");
        }
        JsonArray names = namesElement.getAsJsonArray();
        String[] result = new String[names.size()];
        for (int index = 0; index < names.size(); index++)
        {
            JsonElement name = names.get(index);
            if (!name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString())
            {
                throw new IOException("extras.targetNames[" + index + "] must be a string");
            }
            result[index] = name.getAsString();
        }
        return result;
    }

    private void validateNodeIndex(int nodeIndex, String description) throws IOException
    {
        if (nodeIndex < 0 || nodeIndex >= nodeDefinitions.size())
        {
            throw new IOException(description + " references invalid node " + nodeIndex);
        }
    }

    private static JsonArray requiredArray(JsonObject object, String name, String description)
        throws IOException
    {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonArray())
        {
            throw new IOException(description + " is missing array " + name);
        }
        return value.getAsJsonArray();
    }

    private static JsonArray optionalArray(JsonObject object, String name, String description)
        throws IOException
    {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull())
        {
            return new JsonArray();
        }
        if (!value.isJsonArray())
        {
            throw new IOException(description + " " + name + " must be an array");
        }
        return value.getAsJsonArray();
    }

    private static int arrayInt(JsonArray array, int index, String description) throws IOException
    {
        JsonElement value = array.get(index);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
        {
            throw new IOException(description + " must be an integer");
        }
        try
        {
            return new BigDecimal(value.getAsString()).intValueExact();
        }
        catch (NumberFormatException | ArithmeticException exception)
        {
            throw new IOException(description + " is not a valid integer", exception);
        }
    }

    private static String optionalString(
        JsonObject object, String name, String fallback, String description) throws IOException
    {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull())
        {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
        {
            throw new IOException(description + " " + name + " must be a string");
        }
        return value.getAsString();
    }

    private static float[] optionalFloatArray(
        JsonObject object, String name, float[] fallback, String description) throws IOException
    {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull())
        {
            return fallback;
        }
        return floatArray(value, fallback.length, description + " " + name);
    }

    private static float[] floatArray(JsonElement element, int length, String description)
        throws IOException
    {
        if (!element.isJsonArray())
        {
            throw new IOException(description + " must be an array");
        }
        JsonArray array = element.getAsJsonArray();
        if (array.size() != length)
        {
            throw new IOException(description + " must contain " + length + " numbers");
        }
        float[] values = new float[length];
        for (int index = 0; index < length; index++)
        {
            JsonElement value = array.get(index);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            {
                throw new IOException(description + "[" + index + "] must be numeric");
            }
            try
            {
                values[index] = value.getAsFloat();
            }
            catch (NumberFormatException exception)
            {
                throw new IOException(description + "[" + index + "] is invalid", exception);
            }
            if (!Float.isFinite(values[index]))
            {
                throw new IOException(description + "[" + index + "] is not finite");
            }
        }
        return values;
    }

    private static String extensionForMimeType(String mimeType)
    {
        return switch (mimeType == null ? "" : mimeType.toLowerCase())
        {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            case "image/ktx2" -> ".ktx2";
            case "image/basis" -> ".basis";
            default -> ".bin";
        };
    }

    private record MeshSkinKey(int meshIndex, int skinIndex) {}

    private static final class InfluenceSet
    {
        final int[] joints;
        final float[] weights;

        InfluenceSet(int[] joints, float[] weights)
        {
            this.joints = joints;
            this.weights = weights;
        }
    }

    private static final class AnimationValues
    {
        final double[] times;
        final float[] values;
        final float[] inTangents;
        final float[] outTangents;
        final int components;
        final String interpolation;

        AnimationValues(double[] times, float[] values, float[] inTangents,
            float[] outTangents, int components, String interpolation)
        {
            this.times = times;
            this.values = values;
            this.inTangents = inTangents;
            this.outTangents = outTangents;
            this.components = components;
            this.interpolation = interpolation;
        }
    }

    private static final class BoneAccumulator
    {
        final SceneBone bone = new SceneBone();
        int[] vertexIds = new int[16];
        float[] weights = new float[16];
        int size;

        BoneAccumulator(String name, Matrix4f inverseBindMatrix)
        {
            bone.name = name;
            bone.offsetMatrix.set(inverseBindMatrix);
        }

        void add(int vertexId, float weight)
        {
            if (size > 0 && vertexIds[size - 1] == vertexId)
            {
                weights[size - 1] += weight;
                return;
            }
            if (size == vertexIds.length)
            {
                int newLength = vertexIds.length * 2;
                vertexIds = Arrays.copyOf(vertexIds, newLength);
                weights = Arrays.copyOf(weights, newLength);
            }
            vertexIds[size] = vertexId;
            weights[size] = weight;
            size++;
        }

        SceneBone finish()
        {
            bone.vertexIds = Arrays.copyOf(vertexIds, size);
            bone.weights = Arrays.copyOf(weights, size);
            return bone;
        }
    }
}
