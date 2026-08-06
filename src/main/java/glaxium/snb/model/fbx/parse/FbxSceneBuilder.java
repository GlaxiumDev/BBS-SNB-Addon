package glaxium.snb.model.fbx.parse;

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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Converts the format-neutral FBX node tree into the addon's Java scene model. */
final class FbxSceneBuilder
{
    private static final double FBX_TIME_UNITS_PER_SECOND = 46_186_158_000.0;

    private final FbxDocument document;
    private final Scene scene = new Scene();
    private final Map<Long, FbxObject> objects = new LinkedHashMap<>();
    private final List<Connection> connections = new ArrayList<>();
    private final Map<Long, SceneNode> modelNodes = new LinkedHashMap<>();
    private final Map<Long, PropertyBag> objectProperties = new HashMap<>();
    private final Map<Long, Integer> materialIndices = new HashMap<>();
    private final Map<Long, MeshData> meshes = new LinkedHashMap<>();

    FbxSceneBuilder(FbxDocument document)
    {
        this.document = document;
    }

    Scene build() throws IOException
    {
        this.scene.rootNode = new SceneNode("RootNode");
        readObjects();
        readConnections();
        readGlobalSettings();
        buildMaterialsAndTextures();
        buildModelNodes();
        buildMeshes();
        attachSkins();
        attachMorphTargets();
        buildHierarchy();
        buildAnimations();
        return this.scene;
    }

    private void readObjects() throws IOException
    {
        FbxNode objectRoot = this.document.root("Objects");

        if (objectRoot == null)
        {
            return;
        }

        for (FbxNode node : objectRoot.children)
        {
            if (node.properties.isEmpty())
            {
                continue;
            }

            Long id = asLong(node.properties.get(0));

            if (id == null)
            {
                continue;
            }

            String rawName = node.properties.size() > 1
                    ? asString(node.properties.get(1), "")
                    : "";
            String subtype = node.properties.size() > 2
                    ? asString(node.properties.get(2), "")
                    : "";
            FbxObject object = new FbxObject(id, node, cleanObjectName(rawName, node.name + "_" + id),
                    subtype);

            if (this.objects.putIfAbsent(id, object) != null)
            {
                throw new IOException("FBX contains duplicate object ID " + id);
            }

            this.objectProperties.put(id, new PropertyBag(node));
        }
    }

    private void readConnections()
    {
        FbxNode connectionRoot = this.document.root("Connections");

        if (connectionRoot == null)
        {
            return;
        }

        for (FbxNode node : connectionRoot.children("C"))
        {
            if (node.properties.size() < 3)
            {
                continue;
            }

            Long child = asLong(node.properties.get(1));
            Long parent = asLong(node.properties.get(2));

            if (child == null || parent == null)
            {
                continue;
            }

            String type = asString(node.properties.get(0), "OO");
            String property = node.properties.size() > 3
                    ? asString(node.properties.get(3), "")
                    : "";
            this.connections.add(new Connection(type, child, parent, property));
        }
    }

    private void readGlobalSettings()
    {
        FbxNode settings = this.document.root("GlobalSettings");

        if (settings == null)
        {
            return;
        }

        PropertyBag properties = new PropertyBag(settings);
        this.scene.metadata.upAxis = properties.integer("UpAxis", this.scene.metadata.upAxis);
        this.scene.metadata.originalUpAxis = properties.integer("OriginalUpAxis",
                this.scene.metadata.upAxis);
        this.scene.metadata.frontAxis = properties.integer("FrontAxis",
                this.scene.metadata.frontAxis);
        this.scene.metadata.coordAxis = properties.integer("CoordAxis",
                this.scene.metadata.coordAxis);
        this.scene.metadata.unitScaleFactor = properties.number("UnitScaleFactor",
                this.scene.metadata.unitScaleFactor);
    }

    private void buildModelNodes()
    {
        for (FbxObject object : this.objects.values())
        {
            if (!object.is("Model"))
            {
                continue;
            }

            SceneNode node = new SceneNode(object.name);
            node.localTransform.set(buildLocalTransform(properties(object.id)));
            this.modelNodes.put(object.id, node);
        }
    }

    private void buildMeshes() throws IOException
    {
        for (FbxObject geometry : this.objects.values())
        {
            if (!geometry.is("Geometry") || !geometry.subtype.equalsIgnoreCase("Mesh"))
            {
                continue;
            }

            FbxObject model = firstConnectedObject(geometry.id, "Model", null);
            Matrix4f geometricTransform = model == null
                    ? new Matrix4f()
                    : buildGeometricTransform(properties(model.id));
            MeshData data = buildMesh(geometry, geometricTransform);

            if (data == null)
            {
                continue;
            }

            int meshIndex = this.scene.meshes.size();
            Integer materialIndex = findMaterialIndex(geometry.id, model == null ? null : model.id);

            if (materialIndex != null)
            {
                data.mesh.materialIndex = materialIndex;
            }

            this.scene.meshes.add(data.mesh);
            data.sceneIndex = meshIndex;
            this.meshes.put(geometry.id, data);

            List<FbxObject> models = connectedObjects(geometry.id, "Model", null);

            for (FbxObject attachedModel : models)
            {
                SceneNode modelNode = this.modelNodes.get(attachedModel.id);

                if (modelNode != null && !modelNode.meshIndices.contains(meshIndex))
                {
                    modelNode.meshIndices.add(meshIndex);
                    data.attached = true;
                }
            }
        }
    }

    private MeshData buildMesh(FbxObject geometry, Matrix4f geometricTransform) throws IOException
    {
        double[] vertices = arrayAsDoubles(geometry.node.child("Vertices"));
        int[] polygonVertexIndices = arrayAsInts(geometry.node.child("PolygonVertexIndex"));

        if (vertices.length < 3 || polygonVertexIndices.length == 0)
        {
            return null;
        }
        if (vertices.length % 3 != 0)
        {
            throw new IOException("Geometry " + geometry.name + " has a malformed Vertices array");
        }

        int controlCount = vertices.length / 3;
        float[] transformedControls = new float[vertices.length];
        Vector3f vector = new Vector3f();

        for (int i = 0; i < controlCount; i++)
        {
            vector.set((float) vertices[i * 3], (float) vertices[i * 3 + 1],
                    (float) vertices[i * 3 + 2]);
            geometricTransform.transformPosition(vector);
            transformedControls[i * 3] = vector.x;
            transformedControls[i * 3 + 1] = vector.y;
            transformedControls[i * 3 + 2] = vector.z;
        }

        Layer normals = Layer.normal(firstLayer(geometry.node, "LayerElementNormal"));
        Layer uvs = Layer.uv(firstLayer(geometry.node, "LayerElementUV"));
        Matrix4f normalTransform = new Matrix4f(geometricTransform);

        if (Math.abs(normalTransform.determinant()) > 1.0e-12f)
        {
            normalTransform.invert().transpose();
        }
        else
        {
            normalTransform.identity();
        }

        FloatArray positionsOut = new FloatArray();
        FloatArray normalsOut = new FloatArray();
        FloatArray uvsOut = new FloatArray();
        IntArray indicesOut = new IntArray();
        IntArray expandedControls = new IntArray();
        List<Corner> polygon = new ArrayList<>();
        int polygonVertex = 0;
        int polygonIndex = 0;

        for (int rawIndex : polygonVertexIndices)
        {
            boolean polygonEnd = rawIndex < 0;
            int controlIndex = polygonEnd ? ~rawIndex : rawIndex;

            if (controlIndex < 0 || controlIndex >= controlCount)
            {
                throw new IOException("Geometry " + geometry.name
                        + " references control point " + controlIndex + " of " + controlCount);
            }

            polygon.add(new Corner(controlIndex, polygonVertex++, polygonIndex));

            if (polygonEnd)
            {
                emitPolygon(polygon, transformedControls, normals, uvs, normalTransform,
                        positionsOut, normalsOut, uvsOut, indicesOut, expandedControls);
                polygon.clear();
                polygonIndex++;
            }
        }

        if (!polygon.isEmpty())
        {
            emitPolygon(polygon, transformedControls, normals, uvs, normalTransform,
                    positionsOut, normalsOut, uvsOut, indicesOut, expandedControls);
        }

        SceneMesh mesh = new SceneMesh();
        mesh.name = geometry.name;
        mesh.positions = positionsOut.toArray();
        mesh.normals = normalsOut.toArray();
        mesh.uvs = uvsOut.toArray();
        mesh.indices = indicesOut.toArray();
        mesh.triangulated = true;

        return new MeshData(geometry, mesh, transformedControls, expandedControls.toArray(),
                new Matrix4f(geometricTransform));
    }

    private void emitPolygon(List<Corner> polygon, float[] controls, Layer normals, Layer uvs,
            Matrix4f normalTransform, FloatArray positionsOut, FloatArray normalsOut,
            FloatArray uvsOut, IntArray indicesOut, IntArray expandedControls)
    {
        if (polygon.size() < 3)
        {
            return;
        }

        int base = positionsOut.size() / 3;
        Vector3f normal = new Vector3f();

        for (Corner corner : polygon)
        {
            int positionOffset = corner.controlIndex * 3;
            positionsOut.add(controls[positionOffset]);
            positionsOut.add(controls[positionOffset + 1]);
            positionsOut.add(controls[positionOffset + 2]);
            expandedControls.add(corner.controlIndex);

            normals.read(corner, normal, 0f, 1f, 0f);
            normalTransform.transformDirection(normal);
            if (normal.lengthSquared() > 1.0e-12f) normal.normalize();
            else normal.set(0f, 1f, 0f);
            normalsOut.add(normal.x);
            normalsOut.add(normal.y);
            normalsOut.add(normal.z);

            uvsOut.add(uvs.read(corner, 0, 0f));
            uvsOut.add(uvs.read(corner, 1, 0f));
        }

        for (int i = 1; i + 1 < polygon.size(); i++)
        {
            indicesOut.add(base);
            indicesOut.add(base + i);
            indicesOut.add(base + i + 1);
        }
    }

    private void attachSkins() throws IOException
    {
        for (MeshData mesh : this.meshes.values())
        {
            Set<Long> skinIds = new LinkedHashSet<>();

            for (FbxObject object : connectedObjects(mesh.geometry.id, "Deformer", "Skin"))
            {
                skinIds.add(object.id);
            }

            for (long skinId : skinIds)
            {
                for (FbxObject cluster : connectedObjects(skinId, "Deformer", "Cluster"))
                {
                    attachCluster(mesh, cluster);
                }
            }
        }
    }

    private void attachCluster(MeshData mesh, FbxObject cluster) throws IOException
    {
        int[] controlIndices = arrayAsInts(cluster.node.child("Indexes"));
        double[] weights = arrayAsDoubles(cluster.node.child("Weights"));

        if (controlIndices.length == 0 || weights.length == 0)
        {
            return;
        }

        FbxObject boneModel = firstConnectedObject(cluster.id, "Model", null);
        SceneBone bone = new SceneBone();
        bone.name = boneModel == null ? cluster.name : boneModel.name;
        double[] transformLink = arrayAsDoubles(cluster.node.child("TransformLink"));

        if (transformLink.length >= 16)
        {
            Matrix4f link = matrixFromFbx(transformLink);

            if (Math.abs(link.determinant()) > 1.0e-12f)
            {
                bone.offsetMatrix.set(link.invert());
            }
        }

        Map<Integer, IntArray> expandedByControl = mesh.expandedByControl();
        IntArray vertexIds = new IntArray();
        FloatArray expandedWeights = new FloatArray();
        int count = Math.min(controlIndices.length, weights.length);

        for (int i = 0; i < count; i++)
        {
            if (!Double.isFinite(weights[i]) || weights[i] <= 0.0)
            {
                continue;
            }

            IntArray expanded = expandedByControl.get(controlIndices[i]);

            if (expanded == null)
            {
                continue;
            }

            for (int j = 0; j < expanded.size(); j++)
            {
                vertexIds.add(expanded.get(j));
                expandedWeights.add((float) weights[i]);
            }
        }

        bone.vertexIds = vertexIds.toArray();
        bone.weights = expandedWeights.toArray();

        if (bone.vertexIds.length > 0)
        {
            mesh.mesh.bones.add(bone);
        }
    }

    private void attachMorphTargets()
    {
        for (MeshData mesh : this.meshes.values())
        {
            for (FbxObject blendShape : connectedObjects(mesh.geometry.id, "Deformer", "BlendShape"))
            {
                for (FbxObject channel : connectedObjects(blendShape.id, "Deformer",
                        "BlendShapeChannel"))
                {
                    FbxObject shape = firstConnectedObject(channel.id, "Geometry", "Shape");

                    if (shape != null)
                    {
                        SceneMorphTarget target = buildMorphTarget(mesh, channel, shape);

                        if (target != null)
                        {
                            mesh.mesh.morphTargets.add(target);
                        }
                    }
                }
            }
        }
    }

    private SceneMorphTarget buildMorphTarget(MeshData base, FbxObject channel, FbxObject shape)
    {
        double[] shapeVertices = arrayAsDoubles(shape.node.child("Vertices"));
        int[] shapeIndices = arrayAsInts(shape.node.child("Indexes"));
        int controlCount = base.controlPositions.length / 3;

        if (shapeVertices.length == 0 || shapeVertices.length % 3 != 0)
        {
            return null;
        }

        float[] targetControls;

        if (shapeIndices.length == 0 && shapeVertices.length == base.controlPositions.length)
        {
            targetControls = transformAbsolutePositions(shapeVertices, base.geometricTransform);
        }
        else if (shapeIndices.length > 0 && shapeVertices.length >= shapeIndices.length * 3)
        {
            targetControls = base.controlPositions.clone();
            Vector3f delta = new Vector3f();

            for (int i = 0; i < shapeIndices.length; i++)
            {
                int control = shapeIndices[i];

                if (control < 0 || control >= controlCount)
                {
                    continue;
                }

                delta.set((float) shapeVertices[i * 3], (float) shapeVertices[i * 3 + 1],
                        (float) shapeVertices[i * 3 + 2]);
                base.geometricTransform.transformDirection(delta);
                targetControls[control * 3] += delta.x;
                targetControls[control * 3 + 1] += delta.y;
                targetControls[control * 3 + 2] += delta.z;
            }
        }
        else
        {
            return null;
        }

        SceneMorphTarget target = new SceneMorphTarget();
        target.name = channel.name;
        target.positions = new float[base.expandedControls.length * 3];

        for (int i = 0; i < base.expandedControls.length; i++)
        {
            int source = base.expandedControls[i] * 3;
            target.positions[i * 3] = targetControls[source];
            target.positions[i * 3 + 1] = targetControls[source + 1];
            target.positions[i * 3 + 2] = targetControls[source + 2];
        }

        target.normals = buildMorphNormals(base, shape);
        return target;
    }

    private float[] buildMorphNormals(MeshData base, FbxObject shape)
    {
        double[] shapeNormals = arrayAsDoubles(shape.node.child("Normals"));

        if (shapeNormals.length != base.controlPositions.length)
        {
            return base.mesh.normals.clone();
        }

        Matrix4f transform = new Matrix4f(base.geometricTransform);
        if (Math.abs(transform.determinant()) > 1.0e-12f) transform.invert().transpose();
        else transform.identity();

        float[] controls = new float[shapeNormals.length];
        Vector3f normal = new Vector3f();

        for (int i = 0; i < shapeNormals.length / 3; i++)
        {
            normal.set((float) shapeNormals[i * 3], (float) shapeNormals[i * 3 + 1],
                    (float) shapeNormals[i * 3 + 2]);
            transform.transformDirection(normal);
            if (normal.lengthSquared() > 1.0e-12f) normal.normalize();
            controls[i * 3] = normal.x;
            controls[i * 3 + 1] = normal.y;
            controls[i * 3 + 2] = normal.z;
        }

        float[] expanded = new float[base.expandedControls.length * 3];

        for (int i = 0; i < base.expandedControls.length; i++)
        {
            int source = base.expandedControls[i] * 3;
            expanded[i * 3] = controls[source];
            expanded[i * 3 + 1] = controls[source + 1];
            expanded[i * 3 + 2] = controls[source + 2];
        }

        return expanded;
    }

    private void buildHierarchy()
    {
        Map<Long, Long> parentByChild = new HashMap<>();

        for (Connection connection : this.connections)
        {
            if (!connection.isObjectConnection())
            {
                continue;
            }

            FbxObject child = this.objects.get(connection.child);
            FbxObject parent = this.objects.get(connection.parent);

            if (child != null && parent != null && child.is("Model") && parent.is("Model")
                    && child.id != parent.id)
            {
                parentByChild.putIfAbsent(child.id, parent.id);
            }
        }

        /* Remove cycles instead of constructing a scene graph that can never be walked. */
        for (Long child : new ArrayList<>(parentByChild.keySet()))
        {
            if (hasParentCycle(child, parentByChild))
            {
                parentByChild.remove(child);
            }
        }

        for (Map.Entry<Long, SceneNode> entry : this.modelNodes.entrySet())
        {
            Long parentId = parentByChild.get(entry.getKey());
            SceneNode parent = parentId == null ? null : this.modelNodes.get(parentId);

            if (parent == null)
            {
                this.scene.rootNode.children.add(entry.getValue());
            }
            else
            {
                parent.children.add(entry.getValue());
            }
        }

        for (MeshData mesh : this.meshes.values())
        {
            if (!mesh.attached)
            {
                this.scene.rootNode.meshIndices.add(mesh.sceneIndex);
            }
        }
    }

    private boolean hasParentCycle(long start, Map<Long, Long> parents)
    {
        Set<Long> seen = new HashSet<>();
        long current = start;

        while (parents.containsKey(current))
        {
            if (!seen.add(current))
            {
                return true;
            }

            current = parents.get(current);
        }

        return false;
    }

    private void buildMaterialsAndTextures()
    {
        Map<Long, String> videoPaths = new HashMap<>();

        for (FbxObject video : this.objects.values())
        {
            if (!video.is("Video"))
            {
                continue;
            }

            String filename = objectFilename(video);
            byte[] content = embeddedContent(video.node.child("Content"));

            if (content.length > 0)
            {
                SceneTexture texture = new SceneTexture();
                texture.filename = baseFilename(filename);
                texture.width = content.length;
                texture.height = 0;
                texture.data = content;
                int index = this.scene.textures.size();
                this.scene.textures.add(texture);
                videoPaths.put(video.id, "*" + index);
            }
            else if (!filename.isEmpty())
            {
                videoPaths.put(video.id, baseFilename(filename));
            }
        }

        Map<Long, String> texturePaths = new HashMap<>();

        for (FbxObject texture : this.objects.values())
        {
            if (!texture.is("Texture"))
            {
                continue;
            }

            String path = "";

            for (FbxObject video : connectedObjects(texture.id, "Video", null))
            {
                String candidate = videoPaths.get(video.id);

                if (candidate != null && (path.isEmpty() || candidate.startsWith("*")))
                {
                    path = candidate;
                }
            }

            if (path.isEmpty())
            {
                path = baseFilename(objectFilename(texture));
            }

            if (!path.isEmpty())
            {
                texturePaths.put(texture.id, path);
            }
        }

        for (FbxObject materialObject : this.objects.values())
        {
            if (!materialObject.is("Material"))
            {
                continue;
            }

            SceneMaterial material = new SceneMaterial();
            material.name = materialObject.name;
            PropertyBag properties = properties(materialObject.id);
            Vector3f color = properties.vector("DiffuseColor",
                    properties.vector("BaseColor", null));

            if (color != null)
            {
                material.color = new float[] { color.x, color.y, color.z };
            }

            int index = this.scene.materials.size();
            this.scene.materials.add(material);
            this.materialIndices.put(materialObject.id, index);
        }

        for (Connection connection : this.connections)
        {
            FbxObject texture = this.objects.get(connection.child);
            FbxObject material = this.objects.get(connection.parent);

            if (texture == null || material == null
                    || !texture.is("Texture") || !material.is("Material"))
            {
                continue;
            }

            Integer materialIndex = this.materialIndices.get(material.id);
            String path = texturePaths.get(texture.id);

            if (materialIndex == null || path == null)
            {
                continue;
            }

            SceneMaterial sceneMaterial = this.scene.materials.get(materialIndex);
            String property = connection.property.toLowerCase(Locale.ROOT);

            if (sceneMaterial.diffuseTexturePath == null
                    || property.contains("diffuse") || property.contains("basecolor")
                    || property.contains("base_color"))
            {
                sceneMaterial.diffuseTexturePath = path;
            }
        }
    }

    private Integer findMaterialIndex(long geometryId, Long modelId)
    {
        Integer direct = connectedMaterialIndex(geometryId);
        return direct != null || modelId == null ? direct : connectedMaterialIndex(modelId);
    }

    private Integer connectedMaterialIndex(long objectId)
    {
        for (Connection connection : this.connections)
        {
            long candidate = connection.child == objectId ? connection.parent
                    : connection.parent == objectId ? connection.child : Long.MIN_VALUE;
            Integer index = this.materialIndices.get(candidate);

            if (index != null)
            {
                return index;
            }
        }

        return null;
    }

    private void buildAnimations()
    {
        Map<Long, Curve> curves = new HashMap<>();

        for (FbxObject object : this.objects.values())
        {
            if (!object.is("AnimationCurve"))
            {
                continue;
            }

            long[] times = arrayAsLongs(object.node.child("KeyTime"));
            double[] values = arrayAsDoubles(firstNonNull(object.node.child("KeyValueFloat"),
                    object.node.child("KeyValueDouble")));
            int count = Math.min(times.length, values.length);

            if (count > 0)
            {
                curves.put(object.id, new Curve(Arrays.copyOf(times, count),
                        Arrays.copyOf(values, count)));
            }
        }

        if (curves.isEmpty())
        {
            return;
        }

        List<FbxObject> stacks = objectsOf("AnimationStack", null);

        if (stacks.isEmpty())
        {
            SceneAnimation animation = buildAnimation("Animation", null, curves);
            if (!animation.channels.isEmpty()) this.scene.animations.add(animation);
            return;
        }

        for (FbxObject stack : stacks)
        {
            Set<Long> layers = new HashSet<>();

            for (FbxObject layer : connectedObjects(stack.id, "AnimationLayer", null))
            {
                layers.add(layer.id);
            }

            SceneAnimation animation = buildAnimation(stack.name, layers, curves);

            if (!animation.channels.isEmpty())
            {
                this.scene.animations.add(animation);
            }
        }
    }

    private SceneAnimation buildAnimation(String name, Set<Long> allowedLayers,
            Map<Long, Curve> curves)
    {
        Map<Long, AnimatedModel> animatedModels = new LinkedHashMap<>();

        for (FbxObject curveNode : this.objects.values())
        {
            if (!curveNode.is("AnimationCurveNode")
                    || !curveNodeBelongsToLayers(curveNode.id, allowedLayers))
            {
                continue;
            }

            ModelBinding binding = findModelBinding(curveNode);

            if (binding == null)
            {
                continue;
            }

            AxisCurves axisCurves = new AxisCurves();

            for (Connection connection : this.connections)
            {
                if (connection.parent != curveNode.id)
                {
                    continue;
                }

                Curve curve = curves.get(connection.child);

                if (curve != null)
                {
                    axisCurves.set(axisFrom(connection.property,
                            this.objects.get(connection.child)), curve);
                }
            }

            if (axisCurves.isEmpty())
            {
                continue;
            }

            AnimatedModel model = animatedModels.computeIfAbsent(binding.model.id,
                    ignored -> new AnimatedModel(binding.model,
                            properties(binding.model.id)));
            model.set(binding.transform, axisCurves, new PropertyBag(curveNode.node));
        }

        SceneAnimation animation = new SceneAnimation();
        animation.name = name == null || name.isBlank() ? "Animation" : name;
        animation.ticksPerSecond = 1.0;

        for (AnimatedModel animated : animatedModels.values())
        {
            SceneNodeAnim channel = animated.toChannel();

            if (channel.positionTimes.length > 0 || channel.rotationTimes.length > 0
                    || channel.scalingTimes.length > 0)
            {
                animation.channels.add(channel);
            }
        }

        return animation;
    }

    private boolean curveNodeBelongsToLayers(long curveNodeId, Set<Long> allowedLayers)
    {
        if (allowedLayers == null || allowedLayers.isEmpty())
        {
            return true;
        }

        boolean hasLayer = false;

        for (Connection connection : this.connections)
        {
            if (connection.child != curveNodeId)
            {
                continue;
            }

            FbxObject parent = this.objects.get(connection.parent);

            if (parent != null && parent.is("AnimationLayer"))
            {
                hasLayer = true;

                if (allowedLayers.contains(parent.id))
                {
                    return true;
                }
            }
        }

        return !hasLayer;
    }

    private ModelBinding findModelBinding(FbxObject curveNode)
    {
        for (Connection connection : this.connections)
        {
            if (connection.child != curveNode.id)
            {
                continue;
            }

            FbxObject model = this.objects.get(connection.parent);

            if (model != null && model.is("Model"))
            {
                TransformKind transform = TransformKind.from(connection.property,
                        curveNode.name);

                if (transform != null)
                {
                    return new ModelBinding(model, transform);
                }
            }
        }

        return null;
    }

    private int axisFrom(String property, FbxObject curve)
    {
        String value = property == null ? "" : property.toUpperCase(Locale.ROOT);

        if (value.endsWith("X")) return 0;
        if (value.endsWith("Y")) return 1;
        if (value.endsWith("Z")) return 2;

        value = curve == null ? "" : curve.name.toUpperCase(Locale.ROOT);
        if (value.endsWith("X")) return 0;
        if (value.endsWith("Y")) return 1;
        if (value.endsWith("Z")) return 2;
        return -1;
    }

    private Matrix4f buildLocalTransform(PropertyBag properties)
    {
        Vector3f translation = properties.vector("Lcl Translation", new Vector3f());
        Vector3f rotation = properties.vector("Lcl Rotation", new Vector3f());
        Vector3f scaling = properties.vector("Lcl Scaling", new Vector3f(1f));
        Vector3f preRotation = properties.vector("PreRotation", new Vector3f());
        Vector3f postRotation = properties.vector("PostRotation", new Vector3f());
        Matrix4f transform = new Matrix4f().translation(translation);

        rotateDegreesXYZ(transform, preRotation);
        rotateDegreesXYZ(transform, rotation);

        if (postRotation.lengthSquared() > 0f)
        {
            Matrix4f post = new Matrix4f();
            rotateDegreesXYZ(post, postRotation);
            transform.mul(post.invert());
        }

        return transform.scale(scaling);
    }

    private Matrix4f buildGeometricTransform(PropertyBag properties)
    {
        Vector3f translation = properties.vector("GeometricTranslation", new Vector3f());
        Vector3f rotation = properties.vector("GeometricRotation", new Vector3f());
        Vector3f scaling = properties.vector("GeometricScaling", new Vector3f(1f));
        Matrix4f transform = new Matrix4f().translation(translation);
        rotateDegreesXYZ(transform, rotation);
        return transform.scale(scaling);
    }

    private static void rotateDegreesXYZ(Matrix4f matrix, Vector3f degrees)
    {
        matrix.rotateXYZ((float) Math.toRadians(degrees.x),
                (float) Math.toRadians(degrees.y),
                (float) Math.toRadians(degrees.z));
    }

    private Matrix4f matrixFromFbx(double[] values) throws IOException
    {
        if (values.length < 16)
        {
            throw new IOException("FBX matrix has fewer than 16 values");
        }

        /*
         * FBX serializes rows. JOML's m(column)(row) naming means each source
         * row must be written across m00,m10,m20,m30, i.e. transposed into
         * JOML's column-vector representation.
         */
        Matrix4f matrix = new Matrix4f();
        matrix.m00((float) values[0]).m10((float) values[1])
                .m20((float) values[2]).m30((float) values[3]);
        matrix.m01((float) values[4]).m11((float) values[5])
                .m21((float) values[6]).m31((float) values[7]);
        matrix.m02((float) values[8]).m12((float) values[9])
                .m22((float) values[10]).m32((float) values[11]);
        matrix.m03((float) values[12]).m13((float) values[13])
                .m23((float) values[14]).m33((float) values[15]);
        return matrix;
    }

    private float[] transformAbsolutePositions(double[] positions, Matrix4f transform)
    {
        float[] result = new float[positions.length];
        Vector3f value = new Vector3f();

        for (int i = 0; i < positions.length / 3; i++)
        {
            value.set((float) positions[i * 3], (float) positions[i * 3 + 1],
                    (float) positions[i * 3 + 2]);
            transform.transformPosition(value);
            result[i * 3] = value.x;
            result[i * 3 + 1] = value.y;
            result[i * 3 + 2] = value.z;
        }

        return result;
    }

    private PropertyBag properties(long id)
    {
        return this.objectProperties.getOrDefault(id, PropertyBag.EMPTY);
    }

    private FbxObject firstConnectedObject(long id, String kind, String subtype)
    {
        List<FbxObject> objects = connectedObjects(id, kind, subtype);
        return objects.isEmpty() ? null : objects.get(0);
    }

    private List<FbxObject> connectedObjects(long id, String kind, String subtype)
    {
        List<FbxObject> result = new ArrayList<>();
        Set<Long> seen = new HashSet<>();

        for (Connection connection : this.connections)
        {
            long other;

            if (connection.child == id)
            {
                other = connection.parent;
            }
            else if (connection.parent == id)
            {
                other = connection.child;
            }
            else
            {
                continue;
            }

            FbxObject object = this.objects.get(other);

            if (object != null && object.is(kind)
                    && (subtype == null || object.subtype.equalsIgnoreCase(subtype))
                    && seen.add(object.id))
            {
                result.add(object);
            }
        }

        return result;
    }

    private List<FbxObject> objectsOf(String kind, String subtype)
    {
        List<FbxObject> result = new ArrayList<>();

        for (FbxObject object : this.objects.values())
        {
            if (object.is(kind) && (subtype == null || object.subtype.equalsIgnoreCase(subtype)))
            {
                result.add(object);
            }
        }

        return result;
    }

    private FbxNode firstLayer(FbxNode node, String name)
    {
        List<FbxNode> layers = node.children(name);
        return layers.isEmpty() ? null : layers.get(0);
    }

    private FbxNode firstNonNull(FbxNode first, FbxNode second)
    {
        return first == null ? second : first;
    }

    private String objectFilename(FbxObject object)
    {
        String relative = childString(object.node, "RelativeFilename");
        return relative.isEmpty() ? childString(object.node, "FileName") : relative;
    }

    private String childString(FbxNode parent, String name)
    {
        FbxNode child = parent.child(name);
        return child == null || child.properties.isEmpty()
                ? ""
                : asString(child.properties.get(0), "");
    }

    private byte[] embeddedContent(FbxNode content)
    {
        if (content == null || content.properties.isEmpty())
        {
            return new byte[0];
        }

        Object value = content.properties.get(content.properties.size() - 1);

        if (value instanceof byte[] bytes)
        {
            return bytes;
        }
        if (value instanceof String string && !string.isBlank())
        {
            try
            {
                return Base64.getMimeDecoder().decode(string);
            }
            catch (IllegalArgumentException ignored)
            {
                return string.getBytes(StandardCharsets.ISO_8859_1);
            }
        }

        return new byte[0];
    }

    private static String baseFilename(String path)
    {
        if (path == null)
        {
            return "";
        }

        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? normalized : normalized.substring(slash + 1);
    }

    private static String cleanObjectName(String raw, String fallback)
    {
        if (raw == null || raw.isBlank())
        {
            return fallback;
        }

        String cleaned = raw.replace("\0\1", "::");
        int namespace = cleaned.lastIndexOf("::");

        if (namespace >= 0 && namespace + 2 < cleaned.length())
        {
            cleaned = cleaned.substring(namespace + 2);
        }

        int nul = cleaned.indexOf('\0');
        if (nul >= 0) cleaned = cleaned.substring(0, nul);
        return cleaned.isBlank() ? fallback : cleaned;
    }

    private static String asString(Object value, String fallback)
    {
        return value == null ? fallback : String.valueOf(value);
    }

    private static Long asLong(Object value)
    {
        if (value instanceof Number number)
        {
            return number.longValue();
        }

        try
        {
            return value == null ? null : Long.parseLong(value.toString());
        }
        catch (NumberFormatException ignored)
        {
            return null;
        }
    }

    private static double[] arrayAsDoubles(FbxNode node)
    {
        if (node == null || node.properties.isEmpty())
        {
            return new double[0];
        }

        Object value = node.properties.get(0);

        if (value instanceof double[] values) return values;
        if (value instanceof float[] values)
        {
            double[] result = new double[values.length];
            for (int i = 0; i < values.length; i++) result[i] = values[i];
            return result;
        }
        if (value instanceof int[] values)
        {
            double[] result = new double[values.length];
            for (int i = 0; i < values.length; i++) result[i] = values[i];
            return result;
        }
        if (value instanceof long[] values)
        {
            double[] result = new double[values.length];
            for (int i = 0; i < values.length; i++) result[i] = values[i];
            return result;
        }
        if (value instanceof Number number)
        {
            return new double[] { number.doubleValue() };
        }

        return new double[0];
    }

    private static int[] arrayAsInts(FbxNode node)
    {
        if (node == null || node.properties.isEmpty())
        {
            return new int[0];
        }

        Object value = node.properties.get(0);

        if (value instanceof int[] values) return values;
        if (value instanceof long[] values)
        {
            int[] result = new int[values.length];
            for (int i = 0; i < values.length; i++) result[i] = (int) values[i];
            return result;
        }
        if (value instanceof double[] values)
        {
            int[] result = new int[values.length];
            for (int i = 0; i < values.length; i++) result[i] = (int) values[i];
            return result;
        }
        if (value instanceof float[] values)
        {
            int[] result = new int[values.length];
            for (int i = 0; i < values.length; i++) result[i] = (int) values[i];
            return result;
        }
        if (value instanceof Number number)
        {
            return new int[] { number.intValue() };
        }

        return new int[0];
    }

    private static long[] arrayAsLongs(FbxNode node)
    {
        if (node == null || node.properties.isEmpty())
        {
            return new long[0];
        }

        Object value = node.properties.get(0);

        if (value instanceof long[] values) return values;
        if (value instanceof int[] values)
        {
            long[] result = new long[values.length];
            for (int i = 0; i < values.length; i++) result[i] = values[i];
            return result;
        }
        if (value instanceof double[] values)
        {
            long[] result = new long[values.length];
            for (int i = 0; i < values.length; i++) result[i] = (long) values[i];
            return result;
        }
        if (value instanceof Number number)
        {
            return new long[] { number.longValue() };
        }

        return new long[0];
    }

    private static final class FbxObject
    {
        private final long id;
        private final FbxNode node;
        private final String name;
        private final String subtype;

        private FbxObject(long id, FbxNode node, String name, String subtype)
        {
            this.id = id;
            this.node = node;
            this.name = name;
            this.subtype = subtype == null ? "" : subtype;
        }

        private boolean is(String kind)
        {
            return this.node.name.equalsIgnoreCase(kind);
        }
    }

    private static final class Connection
    {
        private final String type;
        private final long child;
        private final long parent;
        private final String property;

        private Connection(String type, long child, long parent, String property)
        {
            this.type = type == null ? "" : type;
            this.child = child;
            this.parent = parent;
            this.property = property == null ? "" : property;
        }

        private boolean isObjectConnection()
        {
            return this.type.equalsIgnoreCase("OO");
        }
    }

    private static final class PropertyBag
    {
        private static final PropertyBag EMPTY = new PropertyBag();
        private final Map<String, List<Object>> values = new HashMap<>();

        private PropertyBag() {}

        private PropertyBag(FbxNode object)
        {
            FbxNode properties70 = object.child("Properties70");

            if (properties70 == null)
            {
                properties70 = object.child("Properties60");
            }
            if (properties70 == null)
            {
                return;
            }

            for (FbxNode property : properties70.children("P"))
            {
                if (property.properties.isEmpty())
                {
                    continue;
                }

                String name = asString(property.properties.get(0), "");
                int valueStart = property.properties.size() > 4 ? 4 : 1;
                this.values.put(name.toLowerCase(Locale.ROOT),
                        new ArrayList<>(property.properties.subList(valueStart,
                                property.properties.size())));
            }
        }

        private double number(String name, double fallback)
        {
            List<Object> property = this.values.get(name.toLowerCase(Locale.ROOT));

            if (property != null)
            {
                for (int i = property.size() - 1; i >= 0; i--)
                {
                    if (property.get(i) instanceof Number number)
                    {
                        return number.doubleValue();
                    }
                }
            }

            return fallback;
        }

        private int integer(String name, int fallback)
        {
            return (int) number(name, fallback);
        }

        private Vector3f vector(String name, Vector3f fallback)
        {
            List<Object> property = this.values.get(name.toLowerCase(Locale.ROOT));

            if (property == null)
            {
                return fallback == null ? null : new Vector3f(fallback);
            }

            float[] result = new float[3];
            int count = 0;

            for (Object value : property)
            {
                if (value instanceof Number number && count < 3)
                {
                    result[count++] = number.floatValue();
                }
            }

            return count == 3
                    ? new Vector3f(result[0], result[1], result[2])
                    : fallback == null ? null : new Vector3f(fallback);
        }
    }

    private static final class MeshData
    {
        private final FbxObject geometry;
        private final SceneMesh mesh;
        private final float[] controlPositions;
        private final int[] expandedControls;
        private final Matrix4f geometricTransform;
        private Map<Integer, IntArray> expandedByControl;
        private int sceneIndex;
        private boolean attached;

        private MeshData(FbxObject geometry, SceneMesh mesh, float[] controlPositions,
                int[] expandedControls, Matrix4f geometricTransform)
        {
            this.geometry = geometry;
            this.mesh = mesh;
            this.controlPositions = controlPositions;
            this.expandedControls = expandedControls;
            this.geometricTransform = geometricTransform;
        }

        private Map<Integer, IntArray> expandedByControl()
        {
            if (this.expandedByControl == null)
            {
                this.expandedByControl = new HashMap<>();

                for (int i = 0; i < this.expandedControls.length; i++)
                {
                    this.expandedByControl.computeIfAbsent(this.expandedControls[i],
                            ignored -> new IntArray()).add(i);
                }
            }

            return this.expandedByControl;
        }
    }

    private static final class Corner
    {
        private final int controlIndex;
        private final int polygonVertexIndex;
        private final int polygonIndex;

        private Corner(int controlIndex, int polygonVertexIndex, int polygonIndex)
        {
            this.controlIndex = controlIndex;
            this.polygonVertexIndex = polygonVertexIndex;
            this.polygonIndex = polygonIndex;
        }
    }

    private static final class Layer
    {
        private static final Layer EMPTY = new Layer("", "", new double[0], new int[0], 0);

        private final String mapping;
        private final String reference;
        private final double[] direct;
        private final int[] indices;
        private final int components;

        private Layer(String mapping, String reference, double[] direct, int[] indices,
                int components)
        {
            this.mapping = mapping.toLowerCase(Locale.ROOT);
            this.reference = reference.toLowerCase(Locale.ROOT);
            this.direct = direct;
            this.indices = indices;
            this.components = components;
        }

        private static Layer normal(FbxNode node)
        {
            if (node == null) return EMPTY;
            return new Layer(childStringStatic(node, "MappingInformationType"),
                    childStringStatic(node, "ReferenceInformationType"),
                    arrayAsDoubles(node.child("Normals")),
                    arrayAsInts(firstNonNullStatic(node.child("NormalsIndex"),
                            node.child("NormalIndex"))), 3);
        }

        private static Layer uv(FbxNode node)
        {
            if (node == null) return EMPTY;
            return new Layer(childStringStatic(node, "MappingInformationType"),
                    childStringStatic(node, "ReferenceInformationType"),
                    arrayAsDoubles(node.child("UV")),
                    arrayAsInts(node.child("UVIndex")), 2);
        }

        private void read(Corner corner, Vector3f destination, float x, float y, float z)
        {
            int index = directIndex(corner);

            if (index < 0 || (long) index * this.components + 2 >= this.direct.length)
            {
                destination.set(x, y, z);
            }
            else
            {
                int offset = index * this.components;
                destination.set((float) this.direct[offset], (float) this.direct[offset + 1],
                        (float) this.direct[offset + 2]);
            }
        }

        private float read(Corner corner, int component, float fallback)
        {
            int index = directIndex(corner);
            long offset = (long) index * this.components + component;
            return index < 0 || offset < 0 || offset >= this.direct.length
                    ? fallback
                    : (float) this.direct[(int) offset];
        }

        private int directIndex(Corner corner)
        {
            int mapped;

            if (this.mapping.equals("bypolygonvertex"))
            {
                mapped = corner.polygonVertexIndex;
            }
            else if (this.mapping.equals("byvertex") || this.mapping.equals("byvertice"))
            {
                mapped = corner.controlIndex;
            }
            else if (this.mapping.equals("bypolygon"))
            {
                mapped = corner.polygonIndex;
            }
            else if (this.mapping.equals("allsame"))
            {
                mapped = 0;
            }
            else
            {
                return -1;
            }

            if (this.reference.equals("indextodirect") || this.reference.equals("index"))
            {
                return mapped >= 0 && mapped < this.indices.length ? this.indices[mapped] : -1;
            }

            return mapped;
        }

        private static String childStringStatic(FbxNode parent, String name)
        {
            FbxNode child = parent.child(name);
            return child == null || child.properties.isEmpty()
                    ? ""
                    : asString(child.properties.get(0), "");
        }

        private static FbxNode firstNonNullStatic(FbxNode first, FbxNode second)
        {
            return first == null ? second : first;
        }
    }

    private static final class Curve
    {
        private final long[] times;
        private final double[] values;

        private Curve(long[] times, double[] values)
        {
            this.times = times;
            this.values = values;
        }

        private double sample(long time)
        {
            if (time <= this.times[0]) return this.values[0];
            int last = this.times.length - 1;
            if (time >= this.times[last]) return this.values[last];
            int index = Arrays.binarySearch(this.times, time);
            if (index >= 0) return this.values[index];
            int right = -index - 1;
            int left = right - 1;
            long span = this.times[right] - this.times[left];
            double factor = span == 0 ? 0.0
                    : (double) (time - this.times[left]) / (double) span;
            return this.values[left] + (this.values[right] - this.values[left]) * factor;
        }
    }

    private static final class AxisCurves
    {
        private final Curve[] axes = new Curve[3];

        private void set(int axis, Curve curve)
        {
            if (axis >= 0 && axis < 3) this.axes[axis] = curve;
        }

        private boolean isEmpty()
        {
            return this.axes[0] == null && this.axes[1] == null && this.axes[2] == null;
        }

        private TreeSet<Long> times()
        {
            TreeSet<Long> times = new TreeSet<>();
            for (Curve curve : this.axes)
            {
                if (curve != null)
                {
                    for (long time : curve.times) times.add(time);
                }
            }
            return times;
        }
    }

    private enum TransformKind
    {
        TRANSLATION,
        ROTATION,
        SCALING;

        private static TransformKind from(String property, String curveNodeName)
        {
            String value = ((property == null ? "" : property) + " "
                    + (curveNodeName == null ? "" : curveNodeName)).toLowerCase(Locale.ROOT);
            if (value.contains("translation") || value.endsWith(" t")) return TRANSLATION;
            if (value.contains("rotation") || value.endsWith(" r")) return ROTATION;
            if (value.contains("scaling") || value.contains("scale") || value.endsWith(" s"))
            {
                return SCALING;
            }
            return null;
        }
    }

    private static final class ModelBinding
    {
        private final FbxObject model;
        private final TransformKind transform;

        private ModelBinding(FbxObject model, TransformKind transform)
        {
            this.model = model;
            this.transform = transform;
        }
    }

    private static final class AnimatedModel
    {
        private final FbxObject model;
        private final PropertyBag modelProperties;
        private AxisCurves translation;
        private AxisCurves rotation;
        private AxisCurves scaling;
        private Vector3f translationDefault;
        private Vector3f rotationDefault;
        private Vector3f scalingDefault;

        private AnimatedModel(FbxObject model, PropertyBag modelProperties)
        {
            this.model = model;
            this.modelProperties = modelProperties;
            this.translationDefault = modelProperties.vector("Lcl Translation", new Vector3f());
            this.rotationDefault = modelProperties.vector("Lcl Rotation", new Vector3f());
            this.scalingDefault = modelProperties.vector("Lcl Scaling", new Vector3f(1f));
        }

        private void set(TransformKind kind, AxisCurves curves, PropertyBag curveDefaults)
        {
            Vector3f defaults = new Vector3f(
                    (float) curveDefaults.number("d|X", defaultFor(kind).x),
                    (float) curveDefaults.number("d|Y", defaultFor(kind).y),
                    (float) curveDefaults.number("d|Z", defaultFor(kind).z));

            switch (kind)
            {
                case TRANSLATION ->
                {
                    this.translation = curves;
                    this.translationDefault = defaults;
                }
                case ROTATION ->
                {
                    this.rotation = curves;
                    this.rotationDefault = defaults;
                }
                case SCALING ->
                {
                    this.scaling = curves;
                    this.scalingDefault = defaults;
                }
            }
        }

        private Vector3f defaultFor(TransformKind kind)
        {
            return switch (kind)
            {
                case TRANSLATION -> this.translationDefault;
                case ROTATION -> this.rotationDefault;
                case SCALING -> this.scalingDefault;
            };
        }

        private SceneNodeAnim toChannel()
        {
            SceneNodeAnim channel = new SceneNodeAnim();
            channel.nodeName = this.model.name;

            VectorSamples translationSamples = sample(this.translation, this.translationDefault);
            channel.positionTimes = translationSamples.times;
            channel.positionValues = translationSamples.values;

            VectorSamples scalingSamples = sample(this.scaling, this.scalingDefault);
            channel.scalingTimes = scalingSamples.times;
            channel.scalingValues = scalingSamples.values;

            if (this.rotation != null)
            {
                TreeSet<Long> rawTimes = this.rotation.times();
                channel.rotationTimes = seconds(rawTimes);
                channel.rotationValues = new Quaternionf[rawTimes.size()];
                int index = 0;

                for (long time : rawTimes)
                {
                    Vector3f degrees = sampleValue(this.rotation, this.rotationDefault, time);
                    channel.rotationValues[index++] = new Quaternionf().rotationXYZ(
                            (float) Math.toRadians(degrees.x),
                            (float) Math.toRadians(degrees.y),
                            (float) Math.toRadians(degrees.z));
                }
            }

            return channel;
        }

        private static VectorSamples sample(AxisCurves curves, Vector3f defaults)
        {
            if (curves == null)
            {
                return VectorSamples.EMPTY;
            }

            TreeSet<Long> rawTimes = curves.times();
            Vector3f[] values = new Vector3f[rawTimes.size()];
            int index = 0;

            for (long time : rawTimes)
            {
                values[index++] = sampleValue(curves, defaults, time);
            }

            return new VectorSamples(seconds(rawTimes), values);
        }

        private static Vector3f sampleValue(AxisCurves curves, Vector3f defaults, long time)
        {
            return new Vector3f(
                    curves.axes[0] == null ? defaults.x : (float) curves.axes[0].sample(time),
                    curves.axes[1] == null ? defaults.y : (float) curves.axes[1].sample(time),
                    curves.axes[2] == null ? defaults.z : (float) curves.axes[2].sample(time));
        }

        private static double[] seconds(TreeSet<Long> times)
        {
            double[] result = new double[times.size()];
            int index = 0;
            for (long time : times) result[index++] = time / FBX_TIME_UNITS_PER_SECOND;
            return result;
        }
    }

    private static final class VectorSamples
    {
        private static final VectorSamples EMPTY =
                new VectorSamples(new double[0], new Vector3f[0]);
        private final double[] times;
        private final Vector3f[] values;

        private VectorSamples(double[] times, Vector3f[] values)
        {
            this.times = times;
            this.values = values;
        }
    }

    private static final class FloatArray
    {
        private float[] values = new float[32];
        private int size;

        private void add(float value)
        {
            if (this.size == this.values.length)
            {
                this.values = Arrays.copyOf(this.values, this.values.length * 2);
            }
            this.values[this.size++] = value;
        }

        private int size()
        {
            return this.size;
        }

        private float[] toArray()
        {
            return Arrays.copyOf(this.values, this.size);
        }
    }

    private static final class IntArray
    {
        private int[] values = new int[32];
        private int size;

        private void add(int value)
        {
            if (this.size == this.values.length)
            {
                this.values = Arrays.copyOf(this.values, this.values.length * 2);
            }
            this.values[this.size++] = value;
        }

        private int get(int index)
        {
            return this.values[index];
        }

        private int size()
        {
            return this.size;
        }

        private int[] toArray()
        {
            return Arrays.copyOf(this.values, this.size);
        }
    }
}
