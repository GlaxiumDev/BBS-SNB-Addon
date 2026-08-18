package glaxium.snb.model.fbx.loaders.java;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import glaxium.snb.model.fbx.scene.JavaScene;
import glaxium.snb.model.fbx.scene.JavaScene.Animation;
import glaxium.snb.model.fbx.scene.JavaScene.Bone;
import glaxium.snb.model.fbx.scene.JavaScene.Material;
import glaxium.snb.model.fbx.scene.JavaScene.Mesh;
import glaxium.snb.model.fbx.scene.JavaScene.Node;
import glaxium.snb.model.fbx.scene.JavaScene.NodeAnimation;
import glaxium.snb.model.fbx.scene.JavaScene.QuaternionKey;
import glaxium.snb.model.fbx.scene.JavaScene.ShapeKey;
import glaxium.snb.model.fbx.scene.JavaScene.Texture;
import glaxium.snb.model.fbx.scene.JavaScene.VectorKey;
import glaxium.snb.model.fbx.scene.JavaScene.VertexWeight;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure-Java glTF 2.0 / GLB reader for the scene features consumed by BOBJ. */
final class JavaGltfImporter
{
    private static final int GLB_MAGIC = 0x46546C67;
    private static final int JSON_CHUNK = 0x4E4F534A;
    private static final int BIN_CHUNK = 0x004E4942;

    private final JsonObject json;
    private final File baseDirectory;
    private final byte[] glbBuffer;
    private final JavaScene scene = new JavaScene();
    private final List<byte[]> buffers = new ArrayList<>();
    private final List<Texture> images = new ArrayList<>();
    private final List<GltfNode> nodes = new ArrayList<>();

    private JavaGltfImporter(JsonObject json, byte[] glbBuffer, File baseDirectory)
    {
        this.json = json;
        this.glbBuffer = glbBuffer;
        this.baseDirectory = baseDirectory;
    }

    static JavaScene read(byte[] bytes, boolean binary, File sourceFile) throws IOException
    {
        JsonObject json;
        byte[] binaryBuffer = null;

        if (binary)
        {
            ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if (input.remaining() < 12 || input.getInt() != GLB_MAGIC || input.getInt() != 2)
            {
                throw new IOException("Invalid or unsupported GLB header");
            }
            int declaredLength = input.getInt();
            if (declaredLength < 12 || declaredLength > bytes.length) throw new IOException("Invalid GLB file length");
            String jsonText = null;
            while (input.position() + 8 <= declaredLength)
            {
                int length = input.getInt();
                int type = input.getInt();
                if (length < 0 || (long) input.position() + length > declaredLength || length > input.remaining())
                {
                    throw new IOException("Invalid GLB chunk length");
                }
                byte[] chunk = new byte[length];
                input.get(chunk);
                if (type == JSON_CHUNK) jsonText = new String(chunk, StandardCharsets.UTF_8).trim();
                else if (type == BIN_CHUNK && binaryBuffer == null) binaryBuffer = chunk;
            }
            if (jsonText == null) throw new IOException("GLB has no JSON chunk");
            json = JsonParser.parseString(jsonText).getAsJsonObject();
        }
        else
        {
            json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        }

        File base = sourceFile == null ? null : sourceFile.getParentFile();
        return new JavaGltfImporter(json, binaryBuffer, base).build();
    }

    private JavaScene build() throws IOException
    {
        loadBuffers();
        loadImages();
        loadMaterials();
        loadNodes();
        buildNodeTree();
        loadAnimations();
        return this.scene;
    }

    private void loadBuffers() throws IOException
    {
        JsonArray source = array(this.json, "buffers");
        for (int i = 0; i < source.size(); i++)
        {
            JsonObject buffer = source.get(i).getAsJsonObject();
            String uri = string(buffer, "uri", null);
            if (uri == null && i == 0 && this.glbBuffer != null) this.buffers.add(this.glbBuffer);
            else this.buffers.add(readUri(uri));
        }
    }

    private void loadImages() throws IOException
    {
        JsonArray source = array(this.json, "images");
        for (int i = 0; i < source.size(); i++)
        {
            JsonObject image = source.get(i).getAsJsonObject();
            Texture texture = new Texture();
            texture.name = string(image, "name", "Image_" + i);
            String uri = string(image, "uri", null);
            if (uri != null)
            {
                texture.fileName = baseName(uri);
                try
                {
                    texture.data = readUri(uri);
                }
                catch (IOException e)
                {
                    /* A missing image should leave the material untextured,
                     * not prevent otherwise valid geometry/animation from
                     * loading. Missing geometry buffers remain fatal. */
                    System.err.println("[BBS FBX] Missing glTF image, continuing without it: " + uri);
                }
            }
            else if (image.has("bufferView"))
            {
                texture.data = bufferViewBytes(image.get("bufferView").getAsInt());
                texture.fileName = texture.name;
            }
            this.images.add(texture);
            this.scene.textures.add(texture);
        }
    }

    private void loadMaterials()
    {
        JsonArray source = array(this.json, "materials");
        JsonArray gltfTextures = array(this.json, "textures");
        for (int i = 0; i < source.size(); i++)
        {
            JsonObject input = source.get(i).getAsJsonObject();
            Material material = new Material();
            material.name = string(input, "name", "Material_" + i);
            JsonObject pbr = object(input, "pbrMetallicRoughness");
            JsonArray color = array(pbr, "baseColorFactor");
            if (color.size() >= 3)
            {
                material.color = new float[] {color.get(0).getAsFloat(), color.get(1).getAsFloat(), color.get(2).getAsFloat()};
            }
            JsonObject baseTexture = object(pbr, "baseColorTexture");
            if (baseTexture.has("index"))
            {
                int textureIndex = baseTexture.get("index").getAsInt();
                if (textureIndex >= 0 && textureIndex < gltfTextures.size())
                {
                    JsonObject texture = gltfTextures.get(textureIndex).getAsJsonObject();
                    int imageIndex = integer(texture, "source", -1);
                    if (imageIndex >= 0 && imageIndex < this.images.size())
                    {
                        material.texture = this.images.get(imageIndex);
                        material.texturePath = material.texture.fileName;
                    }
                }
            }
            this.scene.materials.add(material);
        }
    }

    private void loadNodes()
    {
        JsonArray source = array(this.json, "nodes");
        Set<String> names = new HashSet<>();
        for (int i = 0; i < source.size(); i++)
        {
            JsonObject input = source.get(i).getAsJsonObject();
            String baseName = string(input, "name", "Node_" + i);
            String name = uniqueName(baseName, names);
            GltfNode gltfNode = new GltfNode(input, new Node(name));
            JsonArray matrix = array(input, "matrix");
            if (matrix.size() == 16)
            {
                gltfNode.node.transform.set(
                        matrix.get(0).getAsFloat(), matrix.get(1).getAsFloat(), matrix.get(2).getAsFloat(), matrix.get(3).getAsFloat(),
                        matrix.get(4).getAsFloat(), matrix.get(5).getAsFloat(), matrix.get(6).getAsFloat(), matrix.get(7).getAsFloat(),
                        matrix.get(8).getAsFloat(), matrix.get(9).getAsFloat(), matrix.get(10).getAsFloat(), matrix.get(11).getAsFloat(),
                        matrix.get(12).getAsFloat(), matrix.get(13).getAsFloat(), matrix.get(14).getAsFloat(), matrix.get(15).getAsFloat());
            }
            else
            {
                Vector3f translation = vector3(input, "translation", new Vector3f());
                Vector3f scale = vector3(input, "scale", new Vector3f(1));
                Quaternionf rotation = quaternion(input, "rotation", new Quaternionf());
                gltfNode.node.transform.translationRotateScale(translation, rotation, scale);
            }
            this.nodes.add(gltfNode);
        }

        for (int i = 0; i < this.nodes.size(); i++)
        {
            GltfNode node = this.nodes.get(i);
            JsonArray children = array(node.json, "children");
            for (JsonElement child : children)
            {
                int index = child.getAsInt();
                if (index >= 0 && index < this.nodes.size()) node.children.add(index);
            }
            int mesh = integer(node.json, "mesh", -1);
            if (mesh >= 0) attachMesh(node, mesh, integer(node.json, "skin", -1));
        }
    }

    private void buildNodeTree()
    {
        JsonArray scenes = array(this.json, "scenes");
        int active = integer(this.json, "scene", 0);
        JsonArray roots = active >= 0 && active < scenes.size() ? array(scenes.get(active).getAsJsonObject(), "nodes") : new JsonArray();
        Set<Integer> attached = new HashSet<>();

        for (int i = 0; i < this.nodes.size(); i++)
        {
            for (int child : this.nodes.get(i).children)
            {
                this.nodes.get(i).node.children.add(this.nodes.get(child).node);
                attached.add(child);
            }
        }

        if (!roots.isEmpty())
        {
            for (JsonElement root : roots)
            {
                int index = root.getAsInt();
                if (index >= 0 && index < this.nodes.size()) this.scene.root.children.add(this.nodes.get(index).node);
            }
        }
        else
        {
            for (int i = 0; i < this.nodes.size(); i++) if (!attached.contains(i)) this.scene.root.children.add(this.nodes.get(i).node);
        }
    }

    private void attachMesh(GltfNode targetNode, int meshIndex, int skinIndex)
    {
        JsonArray meshes = array(this.json, "meshes");
        if (meshIndex < 0 || meshIndex >= meshes.size()) return;
        JsonObject meshObject = meshes.get(meshIndex).getAsJsonObject();
        JsonArray primitives = array(meshObject, "primitives");
        JsonArray targetNames = array(object(meshObject, "extras"), "targetNames");

        for (int primitiveIndex = 0; primitiveIndex < primitives.size(); primitiveIndex++)
        {
            JsonObject primitive = primitives.get(primitiveIndex).getAsJsonObject();
            JsonObject attributes = object(primitive, "attributes");
            if (!attributes.has("POSITION")) continue;
            Mesh mesh = new Mesh();
            mesh.name = string(meshObject, "name", targetNode.node.name) + (primitives.size() > 1 ? "_" + primitiveIndex : "");

            float[][] positionData = accessor(attributes.get("POSITION").getAsInt());
            float[][] normalData = attributes.has("NORMAL") ? accessor(attributes.get("NORMAL").getAsInt()) : new float[0][];
            float[][] uvData = attributes.has("TEXCOORD_0") ? accessor(attributes.get("TEXCOORD_0").getAsInt()) : new float[0][];
            mesh.vertices = new Vector3f[positionData.length];
            mesh.normals = new Vector3f[positionData.length];
            mesh.texCoords = new Vector2f[positionData.length];
            for (int i = 0; i < positionData.length; i++)
            {
                mesh.vertices[i] = vec3(positionData[i], new Vector3f());
                mesh.normals[i] = i < normalData.length ? vec3(normalData[i], new Vector3f()) : new Vector3f();
                /* glTF accessors already use the UV orientation expected by
                 * BBS's texture upload path. Assimp's glTF reader internally
                 * converted that orientation before aiProcess_FlipUVs changed
                 * it back; applying 1-v directly here therefore flips twice
                 * and can sample a transparent half of an atlas. */
                Vector2f uv = i < uvData.length && uvData[i].length >= 2 ? new Vector2f(uvData[i][0], uvData[i][1]) : new Vector2f();
                mesh.texCoords[i] = uv;
            }

            int[] indices;
            if (primitive.has("indices")) indices = accessorInts(primitive.get("indices").getAsInt());
            else
            {
                indices = new int[positionData.length];
                for (int i = 0; i < indices.length; i++) indices[i] = i;
            }
            mesh.faces = triangulate(indices, integer(primitive, "mode", 4));
            if (normalData.length == 0) generateNormals(mesh);
            mesh.materialIndex = integer(primitive, "material", -1);

            attachSkin(mesh, attributes, skinIndex);
            JsonArray targets = array(primitive, "targets");
            for (int targetIndex = 0; targetIndex < targets.size(); targetIndex++)
            {
                JsonObject target = targets.get(targetIndex).getAsJsonObject();
                ShapeKey shape = new ShapeKey();
                shape.name = targetIndex < targetNames.size() ? targetNames.get(targetIndex).getAsString() : "ShapeKey_" + targetIndex;
                float[][] deltas = target.has("POSITION") ? accessor(target.get("POSITION").getAsInt()) : new float[0][];
                float[][] normalDeltas = target.has("NORMAL") ? accessor(target.get("NORMAL").getAsInt()) : new float[0][];
                shape.vertices = new Vector3f[mesh.vertices.length];
                shape.normals = new Vector3f[mesh.normals.length];
                for (int i = 0; i < mesh.vertices.length; i++)
                {
                    shape.vertices[i] = new Vector3f(mesh.vertices[i]);
                    if (i < deltas.length) shape.vertices[i].add(vec3(deltas[i], new Vector3f()));
                    shape.normals[i] = new Vector3f(mesh.normals[i]);
                    if (i < normalDeltas.length) shape.normals[i].add(vec3(normalDeltas[i], new Vector3f())).normalize();
                }
                mesh.shapeKeys.add(shape);
            }

            List<float[][]> sourceAttributes = new ArrayList<>();
            List<String> attributeNames = new ArrayList<>(attributes.keySet());
            attributeNames.sort(String::compareTo);
            for (String attributeName : attributeNames)
            {
                if (!attributeName.startsWith("JOINTS_") && !attributeName.startsWith("WEIGHTS_"))
                {
                    sourceAttributes.add(accessor(attributes.get(attributeName).getAsInt()));
                }
            }
            deduplicateMesh(mesh, sourceAttributes);

            targetNode.node.meshes.add(this.scene.meshes.size());
            this.scene.meshes.add(mesh);
        }
    }

    private void attachSkin(Mesh mesh, JsonObject attributes, int skinIndex)
    {
        JsonArray skins = array(this.json, "skins");
        if (skinIndex < 0 || skinIndex >= skins.size() || !attributes.has("JOINTS_0") || !attributes.has("WEIGHTS_0")) return;
        JsonObject skin = skins.get(skinIndex).getAsJsonObject();
        JsonArray joints = array(skin, "joints");
        float[][] inverseBinds = skin.has("inverseBindMatrices") ? accessor(skin.get("inverseBindMatrices").getAsInt()) : new float[0][];
        float[][] joints0 = accessor(attributes.get("JOINTS_0").getAsInt());
        float[][] weights0 = accessor(attributes.get("WEIGHTS_0").getAsInt());
        float[][] joints1 = attributes.has("JOINTS_1") ? accessor(attributes.get("JOINTS_1").getAsInt()) : null;
        float[][] weights1 = attributes.has("WEIGHTS_1") ? accessor(attributes.get("WEIGHTS_1").getAsInt()) : null;

        List<Bone> bones = new ArrayList<>();
        for (int i = 0; i < joints.size(); i++)
        {
            Bone bone = new Bone();
            int nodeIndex = joints.get(i).getAsInt();
            bone.name = nodeIndex >= 0 && nodeIndex < this.nodes.size() ? this.nodes.get(nodeIndex).node.name : "Joint_" + i;
            if (i < inverseBinds.length && inverseBinds[i].length >= 16) bone.offsetMatrix.set(inverseBinds[i]);
            bones.add(bone);
        }

        for (int vertex = 0; vertex < mesh.vertices.length; vertex++)
        {
            addWeights(bones, vertex, vertex < joints0.length ? joints0[vertex] : null, vertex < weights0.length ? weights0[vertex] : null);
            if (joints1 != null && weights1 != null) addWeights(bones, vertex, vertex < joints1.length ? joints1[vertex] : null, vertex < weights1.length ? weights1[vertex] : null);
        }
        for (Bone bone : bones)
        {
            if (!bone.weights.isEmpty()) mesh.bones.add(bone);
        }
    }

    private static void addWeights(List<Bone> bones, int vertex, float[] joints, float[] weights)
    {
        if (joints == null || weights == null) return;
        for (int i = 0; i < Math.min(joints.length, weights.length); i++)
        {
            int joint = Math.round(joints[i]);
            if (joint >= 0 && joint < bones.size() && weights[i] > 0) bones.get(joint).weights.add(new VertexWeight(vertex, weights[i]));
        }
    }

    /** Applies the legacy 1e-5 approximate vertex join to indexed glTF data. */
    private static void deduplicateMesh(Mesh mesh, List<float[][]> sourceAttributes)
    {
        int count = mesh.vertices.length;
        if (count == 0) return;

        Map<PositionBits, List<Integer>> buckets = new LinkedHashMap<>();
        int[] remap = new int[count];
        List<Integer> firstVertices = new ArrayList<>();

        for (int vertex = 0; vertex < count; vertex++)
        {
            Vector3f position = mesh.vertices[vertex];
            PositionBits key = new PositionBits(bits(position.x), bits(position.y), bits(position.z));
            List<Integer> candidates = buckets.computeIfAbsent(key, ignored -> new ArrayList<>());
            int target = -1;
            for (int candidate : candidates)
            {
                if (sameVertex(mesh, sourceAttributes, vertex, firstVertices.get(candidate)))
                {
                    target = candidate;
                    break;
                }
            }
            if (target < 0)
            {
                target = firstVertices.size();
                firstVertices.add(vertex);
                candidates.add(target);
            }
            remap[vertex] = target;
        }

        if (firstVertices.size() == count) return;

        Vector3f[] vertices = new Vector3f[firstVertices.size()];
        Vector3f[] normals = new Vector3f[firstVertices.size()];
        Vector2f[] texCoords = new Vector2f[firstVertices.size()];
        for (int i = 0; i < firstVertices.size(); i++)
        {
            int source = firstVertices.get(i);
            vertices[i] = mesh.vertices[source];
            normals[i] = mesh.normals[source];
            texCoords[i] = mesh.texCoords[source];
        }
        mesh.vertices = vertices;
        mesh.normals = normals;
        mesh.texCoords = texCoords;
        for (int[] face : mesh.faces) for (int i = 0; i < face.length; i++) face[i] = remap[face[i]];

        for (Bone bone : mesh.bones)
        {
            Map<Integer, Float> weights = new LinkedHashMap<>();
            for (VertexWeight weight : bone.weights) weights.putIfAbsent(remap[weight.vertexId()], weight.weight());
            bone.weights.clear();
            for (Map.Entry<Integer, Float> weight : weights.entrySet()) bone.weights.add(new VertexWeight(weight.getKey(), weight.getValue()));
        }

        for (ShapeKey shape : mesh.shapeKeys)
        {
            Vector3f[] shapeVertices = new Vector3f[firstVertices.size()];
            Vector3f[] shapeNormals = new Vector3f[firstVertices.size()];
            for (int i = 0; i < firstVertices.size(); i++)
            {
                int source = firstVertices.get(i);
                shapeVertices[i] = shape.vertices[source];
                shapeNormals[i] = shape.normals[source];
            }
            shape.vertices = shapeVertices;
            shape.normals = shapeNormals;
        }
    }

    private static boolean sameVertex(Mesh mesh, List<float[][]> sourceAttributes, int left, int right)
    {
        final float epsilonSquared = 1e-10f;
        if (mesh.vertices[left].distanceSquared(mesh.vertices[right]) > epsilonSquared) return false;
        if (mesh.normals[left].distanceSquared(mesh.normals[right]) > epsilonSquared) return false;
        if (mesh.texCoords[left].distanceSquared(mesh.texCoords[right]) > epsilonSquared) return false;

        for (float[][] attribute : sourceAttributes)
        {
            if (left >= attribute.length || right >= attribute.length) return false;
            float[] a = attribute[left];
            float[] b = attribute[right];
            if (a.length != b.length) return false;
            float difference = 0;
            for (int i = 0; i < a.length; i++)
            {
                float delta = a[i] - b[i];
                difference += delta * delta;
            }
            if (difference > epsilonSquared) return false;
        }

        for (ShapeKey shape : mesh.shapeKeys)
        {
            if (shape.vertices[left].distanceSquared(shape.vertices[right]) > epsilonSquared) return false;
            if (shape.normals[left].distanceSquared(shape.normals[right]) > epsilonSquared) return false;
        }
        return true;
    }

    private void loadAnimations()
    {
        JsonArray animations = array(this.json, "animations");
        for (int animationIndex = 0; animationIndex < animations.size(); animationIndex++)
        {
            JsonObject source = animations.get(animationIndex).getAsJsonObject();
            JsonArray samplers = array(source, "samplers");
            Map<Integer, NodeAnimation> channelsByNode = new LinkedHashMap<>();
            Animation animation = new Animation();
            animation.name = string(source, "name", "animation_" + animationIndex);
            animation.ticksPerSecond = 1.0;

            for (JsonElement channelElement : array(source, "channels"))
            {
                JsonObject channel = channelElement.getAsJsonObject();
                int samplerIndex = integer(channel, "sampler", -1);
                JsonObject target = object(channel, "target");
                int nodeIndex = integer(target, "node", -1);
                String path = string(target, "path", "");
                if (samplerIndex < 0 || samplerIndex >= samplers.size() || nodeIndex < 0 || nodeIndex >= this.nodes.size()) continue;
                if (!path.equals("translation") && !path.equals("rotation") && !path.equals("scale")) continue;

                JsonObject sampler = samplers.get(samplerIndex).getAsJsonObject();
                float[][] times = accessor(integer(sampler, "input", -1));
                float[][] values = accessor(integer(sampler, "output", -1));
                boolean cubic = "CUBICSPLINE".equals(string(sampler, "interpolation", "LINEAR"));
                NodeAnimation output = channelsByNode.computeIfAbsent(nodeIndex, ignored -> {
                    NodeAnimation created = new NodeAnimation();
                    created.nodeName = this.nodes.get(nodeIndex).node.name;
                    return created;
                });

                for (int i = 0; i < times.length; i++)
                {
                    int valueIndex = cubic ? i * 3 + 1 : i;
                    if (times[i].length == 0 || valueIndex >= values.length) continue;
                    double time = times[i][0];
                    animation.duration = Math.max(animation.duration, time);
                    if (path.equals("rotation"))
                    {
                        float[] value = values[valueIndex];
                        if (value.length >= 4) output.rotations.add(new QuaternionKey(time, new Quaternionf(value[0], value[1], value[2], value[3]).normalize()));
                    }
                    else
                    {
                        VectorKey key = new VectorKey(time, vec3(values[valueIndex], path.equals("scale") ? new Vector3f(1) : new Vector3f()));
                        if (path.equals("translation")) output.positions.add(key); else output.scales.add(key);
                    }
                }
            }

            animation.channels.addAll(channelsByNode.values());
            if (!animation.channels.isEmpty()) this.scene.animations.add(animation);
        }
    }

    private float[][] accessor(int accessorIndex)
    {
        JsonArray accessors = array(this.json, "accessors");
        if (accessorIndex < 0 || accessorIndex >= accessors.size()) return new float[0][];
        JsonObject accessor = accessors.get(accessorIndex).getAsJsonObject();
        int count = integer(accessor, "count", 0);
        int components = components(string(accessor, "type", "SCALAR"));
        int componentType = integer(accessor, "componentType", 5126);
        boolean normalized = bool(accessor, "normalized", false);
        float[][] result = new float[count][components];

        if (accessor.has("bufferView"))
        {
            readAccessorData(result, accessor.get("bufferView").getAsInt(), integer(accessor, "byteOffset", 0), componentType, normalized);
        }
        JsonObject sparse = object(accessor, "sparse");
        if (sparse.has("count"))
        {
            int sparseCount = integer(sparse, "count", 0);
            JsonObject indices = object(sparse, "indices");
            JsonObject values = object(sparse, "values");
            int[] destinations = readScalarInts(integer(indices, "bufferView", -1), integer(indices, "byteOffset", 0), integer(indices, "componentType", 5123), sparseCount);
            float[][] replacements = new float[sparseCount][components];
            readAccessorData(replacements, integer(values, "bufferView", -1), integer(values, "byteOffset", 0), componentType, normalized);
            for (int i = 0; i < sparseCount; i++) if (destinations[i] >= 0 && destinations[i] < result.length) result[destinations[i]] = replacements[i];
        }
        return result;
    }

    private int[] accessorInts(int accessorIndex)
    {
        float[][] values = accessor(accessorIndex);
        int[] result = new int[values.length];
        for (int i = 0; i < result.length; i++) result[i] = values[i].length == 0 ? 0 : Math.round(values[i][0]);
        return result;
    }

    private void readAccessorData(float[][] output, int viewIndex, int accessorOffset, int componentType, boolean normalized)
    {
        JsonArray views = array(this.json, "bufferViews");
        if (viewIndex < 0 || viewIndex >= views.size()) return;
        JsonObject view = views.get(viewIndex).getAsJsonObject();
        int bufferIndex = integer(view, "buffer", -1);
        if (bufferIndex < 0 || bufferIndex >= this.buffers.size()) return;
        ByteBuffer input = ByteBuffer.wrap(this.buffers.get(bufferIndex)).order(ByteOrder.LITTLE_ENDIAN);
        int componentSize = componentSize(componentType);
        int elementSize = output.length == 0 ? componentSize : output[0].length * componentSize;
        int stride = integer(view, "byteStride", elementSize);
        int start = integer(view, "byteOffset", 0) + accessorOffset;
        for (int i = 0; i < output.length; i++)
        {
            int offset = start + i * stride;
            for (int c = 0; c < output[i].length; c++)
            {
                int position = offset + c * componentSize;
                if (position < 0 || position + componentSize > input.capacity()) return;
                output[i][c] = component(input, position, componentType, normalized);
            }
        }
    }

    private int[] readScalarInts(int viewIndex, int offset, int componentType, int count)
    {
        float[][] values = new float[count][1];
        readAccessorData(values, viewIndex, offset, componentType, false);
        int[] result = new int[count];
        for (int i = 0; i < count; i++) result[i] = Math.round(values[i][0]);
        return result;
    }

    private byte[] bufferViewBytes(int viewIndex)
    {
        JsonArray views = array(this.json, "bufferViews");
        if (viewIndex < 0 || viewIndex >= views.size()) return null;
        JsonObject view = views.get(viewIndex).getAsJsonObject();
        int bufferIndex = integer(view, "buffer", -1);
        if (bufferIndex < 0 || bufferIndex >= this.buffers.size()) return null;
        int offset = integer(view, "byteOffset", 0);
        int length = integer(view, "byteLength", 0);
        byte[] buffer = this.buffers.get(bufferIndex);
        if (offset < 0 || length < 0 || offset + length > buffer.length) return null;
        byte[] result = new byte[length];
        System.arraycopy(buffer, offset, result, 0, length);
        return result;
    }

    private byte[] readUri(String uri) throws IOException
    {
        if (uri == null) throw new IOException("glTF buffer has no URI and no GLB BIN chunk");
        if (uri.startsWith("data:"))
        {
            int comma = uri.indexOf(',');
            if (comma < 0) throw new IOException("Invalid glTF data URI");
            String metadata = uri.substring(0, comma);
            String payload = uri.substring(comma + 1);
            return metadata.endsWith(";base64") ? Base64.getDecoder().decode(payload) : URLDecoder.decode(payload, StandardCharsets.UTF_8).getBytes(StandardCharsets.ISO_8859_1);
        }
        if (this.baseDirectory == null) throw new IOException("External glTF resource cannot be resolved from an in-memory asset: " + uri);
        String decoded = URLDecoder.decode(uri, StandardCharsets.UTF_8).replace('\\', '/');
        File candidate = new File(this.baseDirectory, decoded).getCanonicalFile();
        File base = this.baseDirectory.getCanonicalFile();
        if (!candidate.toPath().startsWith(base.toPath())) throw new IOException("glTF URI escapes its model folder: " + uri);
        return Files.readAllBytes(candidate.toPath());
    }

    private static int[][] triangulate(int[] indices, int mode)
    {
        List<int[]> faces = new ArrayList<>();
        if (mode == 4)
        {
            for (int i = 0; i + 2 < indices.length; i += 3) faces.add(new int[] {indices[i], indices[i + 1], indices[i + 2]});
        }
        else if (mode == 5)
        {
            for (int i = 0; i + 2 < indices.length; i++) faces.add(i % 2 == 0 ? new int[] {indices[i], indices[i + 1], indices[i + 2]} : new int[] {indices[i + 1], indices[i], indices[i + 2]});
        }
        else if (mode == 6)
        {
            for (int i = 1; i + 1 < indices.length; i++) faces.add(new int[] {indices[0], indices[i], indices[i + 1]});
        }
        return faces.toArray(int[][]::new);
    }

    private static void generateNormals(Mesh mesh)
    {
        for (int[] face : mesh.faces)
        {
            Vector3f normal = new Vector3f(mesh.vertices[face[1]]).sub(mesh.vertices[face[0]])
                    .cross(new Vector3f(mesh.vertices[face[2]]).sub(mesh.vertices[face[0]]));
            for (int index : face) mesh.normals[index].add(normal);
        }
        for (Vector3f normal : mesh.normals) if (normal.lengthSquared() > 1e-12f) normal.normalize(); else normal.set(0, 1, 0);
    }

    private static float component(ByteBuffer input, int position, int type, boolean normalized)
    {
        return switch (type)
        {
            case 5120 -> normalized ? Math.max(-1.0f, input.get(position) / 127.0f) : input.get(position);
            case 5121 -> normalized ? Byte.toUnsignedInt(input.get(position)) / 255.0f : Byte.toUnsignedInt(input.get(position));
            case 5122 -> normalized ? Math.max(-1.0f, input.getShort(position) / 32767.0f) : input.getShort(position);
            case 5123 -> normalized ? Short.toUnsignedInt(input.getShort(position)) / 65535.0f : Short.toUnsignedInt(input.getShort(position));
            case 5125 -> Integer.toUnsignedLong(input.getInt(position));
            default -> input.getFloat(position);
        };
    }

    private static int componentSize(int type)
    {
        return switch (type) { case 5120, 5121 -> 1; case 5122, 5123 -> 2; default -> 4; };
    }

    private static int components(String type)
    {
        return switch (type) { case "VEC2" -> 2; case "VEC3" -> 3; case "VEC4", "MAT2" -> 4; case "MAT3" -> 9; case "MAT4" -> 16; default -> 1; };
    }

    private static JsonArray array(JsonObject object, String name)
    {
        return object != null && object.has(name) && object.get(name).isJsonArray() ? object.getAsJsonArray(name) : new JsonArray();
    }

    private static JsonObject object(JsonObject object, String name)
    {
        return object != null && object.has(name) && object.get(name).isJsonObject() ? object.getAsJsonObject(name) : new JsonObject();
    }

    private static String string(JsonObject object, String name, String fallback)
    {
        return object != null && object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : fallback;
    }

    private static int integer(JsonObject object, String name, int fallback)
    {
        return object != null && object.has(name) ? object.get(name).getAsInt() : fallback;
    }

    private static boolean bool(JsonObject object, String name, boolean fallback)
    {
        return object != null && object.has(name) ? object.get(name).getAsBoolean() : fallback;
    }

    private static Vector3f vector3(JsonObject object, String name, Vector3f fallback)
    {
        JsonArray values = array(object, name);
        return values.size() >= 3 ? new Vector3f(values.get(0).getAsFloat(), values.get(1).getAsFloat(), values.get(2).getAsFloat()) : new Vector3f(fallback);
    }

    private static Quaternionf quaternion(JsonObject object, String name, Quaternionf fallback)
    {
        JsonArray values = array(object, name);
        return values.size() >= 4 ? new Quaternionf(values.get(0).getAsFloat(), values.get(1).getAsFloat(), values.get(2).getAsFloat(), values.get(3).getAsFloat()) : new Quaternionf(fallback);
    }

    private static Vector3f vec3(float[] values, Vector3f fallback)
    {
        return values != null && values.length >= 3 ? new Vector3f(values[0], values[1], values[2]) : new Vector3f(fallback);
    }

    private static String uniqueName(String base, Set<String> used)
    {
        String candidate = base == null || base.isBlank() ? "Node" : base;
        String root = candidate;
        int suffix = 1;
        while (!used.add(candidate)) candidate = root + "_" + suffix++;
        return candidate;
    }

    private static int bits(float value)
    {
        return Float.floatToIntBits(value == 0.0f ? 0.0f : value);
    }

    private record PositionBits(int x, int y, int z) {}

    private static String baseName(String uri)
    {
        String normalized = URLDecoder.decode(uri, StandardCharsets.UTF_8).replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? normalized : normalized.substring(slash + 1);
    }

    private static final class GltfNode
    {
        final JsonObject json;
        final Node node;
        final List<Integer> children = new ArrayList<>();

        GltfNode(JsonObject json, Node node)
        {
            this.json = json;
            this.node = node;
        }
    }
}
