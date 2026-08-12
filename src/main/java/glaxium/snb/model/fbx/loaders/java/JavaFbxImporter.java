package glaxium.snb.model.fbx.loaders.java;

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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static glaxium.snb.model.fbx.loaders.java.FbxBinaryParser.Element;

/** Converts a binary FBX 7.x object/connection graph into {@link JavaScene}. */
final class JavaFbxImporter
{
    private static final double FBX_TIME_UNITS_PER_SECOND = 46_186_158_000.0;
    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

    private JavaFbxImporter() {}

    static JavaScene read(byte[] bytes) throws IOException
    {
        FbxBinaryParser.Document document = FbxBinaryParser.parse(bytes);
        Context context = new Context(document);
        context.readGraph();
        return context.build();
    }

    private record Connection(String type, long child, long parent, String property) {}
    private record Corner(int controlPoint, int polygonVertex) {}
    private record PositionBits(int x, int y, int z) {}

    private static final class VertexKey
    {
        final int[] values;
        final int hash;

        VertexKey(int[] values)
        {
            this.values = values;
            this.hash = Arrays.hashCode(values);
        }

        @Override
        public int hashCode() { return this.hash; }

        @Override
        public boolean equals(Object object)
        {
            return object instanceof VertexKey other && Arrays.equals(this.values, other.values);
        }
    }

    private static final class ObjectData
    {
        final long id;
        final Element element;
        final String kind;
        final String subtype;
        final String name;

        ObjectData(Element element)
        {
            this.element = element;
            this.id = element.longValue(0);
            this.kind = element.name;
            this.name = cleanName(element.string(1));
            this.subtype = element.string(2);
        }
    }

    private static final class Context
    {
        final FbxBinaryParser.Document document;
        final JavaScene scene = new JavaScene();
        final Map<Long, ObjectData> objects = new LinkedHashMap<>();
        final List<Connection> connections = new ArrayList<>();
        final Map<Long, List<Connection>> byParent = new HashMap<>();
        final Map<Long, List<Connection>> byChild = new HashMap<>();
        final Map<Long, ModelData> models = new LinkedHashMap<>();
        final Map<Long, Integer> materialIndices = new LinkedHashMap<>();

        Context(FbxBinaryParser.Document document)
        {
            this.document = document;
        }

        void readGraph() throws IOException
        {
            Element objectsElement = root("Objects");
            if (objectsElement == null)
            {
                throw new IOException("FBX file has no Objects section");
            }

            for (Element element : objectsElement.children)
            {
                if (element.properties.length > 0 && element.property(0) instanceof Number)
                {
                    ObjectData object = new ObjectData(element);
                    this.objects.put(object.id, object);
                }
            }

            Element connectionElement = root("Connections");
            if (connectionElement != null)
            {
                for (Element element : connectionElement.children("C"))
                {
                    if (element.properties.length < 3)
                    {
                        continue;
                    }
                    Connection connection = new Connection(element.string(0), element.longValue(1), element.longValue(2), element.string(3));
                    this.connections.add(connection);
                    this.byParent.computeIfAbsent(connection.parent, ignored -> new ArrayList<>()).add(connection);
                    this.byChild.computeIfAbsent(connection.child, ignored -> new ArrayList<>()).add(connection);
                }
            }

            for (ObjectData object : this.objects.values())
            {
                if (object.kind.equals("Model"))
                {
                    this.models.put(object.id, new ModelData(object));
                }
            }
        }

        JavaScene build()
        {
            readMetadata();
            readMaterials();
            buildMeshes();
            buildNodes();
            buildAnimations();
            return this.scene;
        }

        private Element root(String name)
        {
            for (Element root : this.document.roots())
            {
                if (root.name.equals(name)) return root;
            }
            return null;
        }

        private void readMetadata()
        {
            Element settings = root("GlobalSettings");
            Map<String, Object[]> properties = properties(settings);
            this.scene.metadata.upAxis = scalarInt(properties, "UpAxis", 1);
            this.scene.metadata.originalUpAxis = scalarInt(properties, "OriginalUpAxis", this.scene.metadata.upAxis);
            this.scene.metadata.frontAxis = scalarInt(properties, "FrontAxis", 2);
            this.scene.metadata.coordAxis = scalarInt(properties, "CoordAxis", 0);
            this.scene.metadata.unitScaleFactor = scalarDouble(properties, "UnitScaleFactor", 1.0);
        }

        private void readMaterials()
        {
            for (ObjectData object : this.objects.values())
            {
                if (!object.kind.equals("Material"))
                {
                    continue;
                }

                Material material = new Material();
                material.name = object.name.isBlank() ? "Material_" + this.scene.materials.size() : object.name;
                Map<String, Object[]> props = properties(object.element);
                Vector3f color = vector(props, "DiffuseColor", null);
                if (color == null) color = vector(props, "BaseColor", null);
                if (color == null) color = vector(props, "Maya|baseColor", null);
                if (color != null) material.color = new float[] {color.x, color.y, color.z};

                ObjectData textureObject = firstConnectedChild(object.id, "Texture", "DiffuseColor", "BaseColor", "Maya|baseColor");
                if (textureObject == null)
                {
                    textureObject = firstConnectedChild(object.id, "Texture");
                }
                if (textureObject != null)
                {
                    ObjectData video = firstConnectedChild(textureObject.id, "Video");
                    String relative = childString(textureObject.element, "RelativeFilename");
                    String absolute = childString(textureObject.element, "FileName");
                    if (video != null)
                    {
                        if (relative.isBlank()) relative = childString(video.element, "RelativeFilename");
                        if (absolute.isBlank()) absolute = childString(video.element, "FileName");
                    }
                    material.texturePath = !relative.isBlank() ? relative : absolute;

                    byte[] content = video == null ? null : childBytes(video.element, "Content");
                    if (content == null) content = childBytes(textureObject.element, "Content");
                    if (content != null && content.length > 0)
                    {
                        Texture texture = new Texture();
                        texture.name = video == null ? textureObject.name : video.name;
                        texture.fileName = baseName(material.texturePath == null ? texture.name : material.texturePath);
                        texture.data = content;
                        this.scene.textures.add(texture);
                        material.texture = texture;
                    }
                }

                this.materialIndices.put(object.id, this.scene.materials.size());
                this.scene.materials.add(material);
            }
        }

        private ObjectData firstConnectedChild(long parent, String kind, String... preferredProperties)
        {
            List<Connection> candidates = this.byParent.getOrDefault(parent, List.of());
            for (String preferred : preferredProperties)
            {
                for (Connection connection : candidates)
                {
                    ObjectData child = this.objects.get(connection.child);
                    if (child != null && child.kind.equals(kind) && preferred.equalsIgnoreCase(connection.property))
                    {
                        return child;
                    }
                }
            }
            for (Connection connection : candidates)
            {
                ObjectData child = this.objects.get(connection.child);
                if (child != null && child.kind.equals(kind)) return child;
            }
            return null;
        }

        private void buildMeshes()
        {
            for (ModelData model : this.models.values())
            {
                List<Long> materialSlots = connectedChildren(model.object.id, "Material");

                for (long geometryId : connectedChildren(model.object.id, "Geometry"))
                {
                    ObjectData geometry = this.objects.get(geometryId);
                    if (geometry == null || !geometry.subtype.equalsIgnoreCase("Mesh"))
                    {
                        continue;
                    }

                    List<Mesh> meshes = convertGeometry(model, geometry, materialSlots);
                    for (Mesh mesh : meshes)
                    {
                        model.meshes.add(this.scene.meshes.size());
                        this.scene.meshes.add(mesh);
                    }
                }
            }
        }

        private List<Mesh> convertGeometry(ModelData model, ObjectData geometry, List<Long> materialSlots)
        {
            double[] rawVertices = childDoubles(geometry.element, "Vertices");
            int[] polygonIndices = childInts(geometry.element, "PolygonVertexIndex");
            if (rawVertices == null || polygonIndices == null)
            {
                return List.of();
            }

            Vector3f[] controlPoints = new Vector3f[rawVertices.length / 3];
            for (int i = 0; i < controlPoints.length; i++)
            {
                controlPoints[i] = new Vector3f((float) rawVertices[i * 3], (float) rawVertices[i * 3 + 1], (float) rawVertices[i * 3 + 2]);
                model.geometricTransform.transformPosition(controlPoints[i]);
            }

            List<List<Corner>> polygons = decodePolygons(polygonIndices, controlPoints.length);
            Layer normalLayer = Layer.normal(geometry.element);
            List<Layer> uvLayers = Layer.uvs(geometry.element);
            Layer uvLayer = uvLayers.isEmpty() ? null : uvLayers.get(0);
            Layer materialLayer = Layer.material(geometry.element);
            Map<Integer, List<Integer>> polygonsByMaterial = new LinkedHashMap<>();

            for (int polygon = 0; polygon < polygons.size(); polygon++)
            {
                int slot = materialLayer == null ? 0 : materialLayer.integerValue(0, polygon, polygons.get(polygon).get(0).polygonVertex, polygon);
                polygonsByMaterial.computeIfAbsent(slot, ignored -> new ArrayList<>()).add(polygon);
            }

            List<ClusterData> clusters = clustersForGeometry(geometry.id);
            List<ShapeData> shapes = shapesForGeometry(geometry.id, controlPoints);
            List<Mesh> result = new ArrayList<>();

            for (Map.Entry<Integer, List<Integer>> group : polygonsByMaterial.entrySet())
            {
                Mesh mesh = buildMaterialMesh(geometry, controlPoints, polygons, group.getValue(), normalLayer, uvLayer, uvLayers, clusters, shapes);
                int slot = group.getKey();
                if (slot >= 0 && slot < materialSlots.size())
                {
                    mesh.materialIndex = this.materialIndices.getOrDefault(materialSlots.get(slot), -1);
                }
                else if (materialSlots.size() == 1)
                {
                    mesh.materialIndex = this.materialIndices.getOrDefault(materialSlots.get(0), -1);
                }
                result.add(mesh);
            }
            return result;
        }

        private Mesh buildMaterialMesh(ObjectData geometry, Vector3f[] controlPoints, List<List<Corner>> polygons,
                List<Integer> selectedPolygons, Layer normals, Layer uvs, List<Layer> uvLayers,
                List<ClusterData> clusters, List<ShapeData> shapes)
        {
            Mesh mesh = new Mesh();
            mesh.name = geometry.name;
            Map<VertexKey, Integer> vertices = new LinkedHashMap<>();
            List<Vector3f> positions = new ArrayList<>();
            List<Vector3f> normalValues = new ArrayList<>();
            List<Vector2f> uvValues = new ArrayList<>();
            List<List<Vector2f>> extraUvValues = new ArrayList<>();
            for (int i = 1; i < uvLayers.size(); i++) extraUvValues.add(new ArrayList<>());
            List<Integer> finalControlPoints = new ArrayList<>();
            List<int[]> faces = new ArrayList<>();

            for (int polygonIndex : selectedPolygons)
            {
                List<Corner> polygon = polygons.get(polygonIndex);
                if (polygon.size() < 3) continue;

                int[] polygonVertices = new int[polygon.size()];
                for (int cornerIndex = 0; cornerIndex < polygon.size(); cornerIndex++)
                {
                    Corner corner = polygon.get(cornerIndex);
                    int normalIndex = normals == null ? -1 : normals.directIndex(corner.controlPoint, polygonIndex, corner.polygonVertex, cornerIndex);
                    int uvIndex = uvs == null ? -1 : uvs.directIndex(corner.controlPoint, polygonIndex, corner.polygonVertex, cornerIndex);
                    Vector3f cornerNormal = normals == null ? new Vector3f() : normals.vector3(normalIndex);
                    Vector2f cornerUv = uvs == null ? new Vector2f() : uvs.vector2(uvIndex);
                    Vector2f[] cornerExtraUvs = new Vector2f[extraUvValues.size()];
                    int[] keyValues = new int[6 + extraUvValues.size() * 2];
                    keyValues[0] = corner.controlPoint;
                    keyValues[1] = bits(cornerNormal.x);
                    keyValues[2] = bits(cornerNormal.y);
                    keyValues[3] = bits(cornerNormal.z);
                    keyValues[4] = bits(cornerUv.x);
                    keyValues[5] = bits(cornerUv.y);
                    for (int layerIndex = 1; layerIndex < uvLayers.size(); layerIndex++)
                    {
                        Layer layer = uvLayers.get(layerIndex);
                        int index = layer.directIndex(corner.controlPoint, polygonIndex, corner.polygonVertex, cornerIndex);
                        Vector2f value = layer.vector2(index);
                        cornerExtraUvs[layerIndex - 1] = value;
                        keyValues[4 + layerIndex * 2] = bits(value.x);
                        keyValues[5 + layerIndex * 2] = bits(value.y);
                    }
                    /* Vertex joining compares actual attributes, not the FBX
                     * direct-array indices. Exporters
                     * often repeat an identical normal once per polygon
                     * corner, so using those indices here would turn every
                     * triangle corner into a separate BOBJ vertex. */
                    VertexKey key = new VertexKey(keyValues);
                    Integer finalIndex = vertices.get(key);
                    if (finalIndex == null)
                    {
                        finalIndex = positions.size();
                        vertices.put(key, finalIndex);
                        positions.add(new Vector3f(controlPoints[corner.controlPoint]));
                        normalValues.add(cornerNormal);
                        uvValues.add(new Vector2f(cornerUv.x, 1.0f - cornerUv.y));
                        for (int i = 0; i < cornerExtraUvs.length; i++) extraUvValues.get(i).add(cornerExtraUvs[i]);
                        finalControlPoints.add(corner.controlPoint);
                    }
                    polygonVertices[cornerIndex] = finalIndex;
                }

                for (int i = 1; i + 1 < polygonVertices.length; i++)
                {
                    faces.add(new int[] {polygonVertices[0], polygonVertices[i], polygonVertices[i + 1]});
                }
            }

            mesh.vertices = positions.toArray(Vector3f[]::new);
            mesh.normals = normalValues.toArray(Vector3f[]::new);
            mesh.texCoords = uvValues.toArray(Vector2f[]::new);
            mesh.faces = faces.toArray(int[][]::new);
            if (normals == null) generateNormals(mesh);

            Map<Integer, List<Integer>> finalByControlPoint = new HashMap<>();
            for (int i = 0; i < finalControlPoints.size(); i++)
            {
                finalByControlPoint.computeIfAbsent(finalControlPoints.get(i), ignored -> new ArrayList<>()).add(i);
            }

            for (ClusterData cluster : clusters)
            {
                Bone bone = new Bone();
                bone.name = cluster.boneName;
                bone.offsetMatrix.set(cluster.offsetMatrix);
                for (int i = 0; i < Math.min(cluster.indices.length, cluster.weights.length); i++)
                {
                    for (int finalIndex : finalByControlPoint.getOrDefault(cluster.indices[i], List.of()))
                    {
                        bone.weights.add(new VertexWeight(finalIndex, cluster.weights[i]));
                    }
                }
                /* Empty clusters are still skeleton joints. Blender exports
                 * them for non-deforming facial/control bones and the old
                 * PopulateArmatureData path kept them in the hierarchy. */
                mesh.bones.add(bone);
            }

            for (ShapeData shape : shapes)
            {
                ShapeKey key = new ShapeKey();
                key.name = shape.name;
                key.vertices = new Vector3f[mesh.vertices.length];
                key.normals = new Vector3f[mesh.normals.length];
                for (int i = 0; i < finalControlPoints.size(); i++)
                {
                    int cp = finalControlPoints.get(i);
                    key.vertices[i] = new Vector3f(shape.vertices[cp]);
                    key.normals[i] = new Vector3f(mesh.normals[i]);
                }
                mesh.shapeKeys.add(key);
            }
            List<Vector2f[]> extraUvs = new ArrayList<>();
            for (List<Vector2f> values : extraUvValues) extraUvs.add(values.toArray(Vector2f[]::new));
            joinIdenticalVertices(mesh, extraUvs);
            return mesh;
        }

        /** Reproduces the legacy final vertex-join behavior: positions
         * share an exact hash while normals and UVs compare within 1e-5.
         * Skin weights are remapped after the graphical vertex merge. */
        private static void joinIdenticalVertices(Mesh mesh, List<Vector2f[]> extraUvs)
        {
            int count = mesh.vertices.length;
            if (count == 0) return;

            Map<PositionBits, List<Integer>> buckets = new LinkedHashMap<>();
            List<Integer> firstVertices = new ArrayList<>();
            int[] remap = new int[count];
            final float epsilonSquared = 1e-10f;

            for (int vertex = 0; vertex < count; vertex++)
            {
                Vector3f position = mesh.vertices[vertex];
                PositionBits key = new PositionBits(bits(position.x), bits(position.y), bits(position.z));
                List<Integer> candidates = buckets.computeIfAbsent(key, ignored -> new ArrayList<>());
                int target = -1;
                for (int candidate : candidates)
                {
                    int source = firstVertices.get(candidate);
                    if (mesh.vertices[vertex].distanceSquared(mesh.vertices[source]) <= epsilonSquared &&
                            mesh.normals[vertex].distanceSquared(mesh.normals[source]) <= epsilonSquared &&
                            mesh.texCoords[vertex].distanceSquared(mesh.texCoords[source]) <= epsilonSquared &&
                            sameExtraUvs(extraUvs, vertex, source, epsilonSquared))
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
            for (int[] face : mesh.faces)
            {
                for (int i = 0; i < face.length; i++) face[i] = remap[face[i]];
            }

            for (Bone bone : mesh.bones)
            {
                Map<Integer, Float> weights = new LinkedHashMap<>();
                for (VertexWeight weight : bone.weights)
                {
                    weights.putIfAbsent(remap[weight.vertexId()], weight.weight());
                }
                bone.weights.clear();
                for (Map.Entry<Integer, Float> weight : weights.entrySet())
                {
                    bone.weights.add(new VertexWeight(weight.getKey(), weight.getValue()));
                }
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

        private static boolean sameExtraUvs(List<Vector2f[]> extraUvs, int left, int right, float epsilonSquared)
        {
            for (Vector2f[] layer : extraUvs)
            {
                if (layer[left].distanceSquared(layer[right]) > epsilonSquared) return false;
            }
            return true;
        }

        private List<ClusterData> clustersForGeometry(long geometryId)
        {
            List<ClusterData> result = new ArrayList<>();
            for (long skinId : connectedChildren(geometryId, "Deformer", "Skin"))
            {
                for (long clusterId : connectedChildren(skinId, "Deformer", "Cluster"))
                {
                    ObjectData cluster = this.objects.get(clusterId);
                    if (cluster == null) continue;
                    int[] indices = childInts(cluster.element, "Indexes");
                    double[] rawWeights = childDoubles(cluster.element, "Weights");
                    double[] transform = childDoubles(cluster.element, "Transform");
                    double[] transformLink = childDoubles(cluster.element, "TransformLink");
                    if (transform == null || transformLink == null) continue;
                    if (indices == null) indices = new int[0];
                    if (rawWeights == null) rawWeights = new double[0];

                    ObjectData boneModel = null;
                    for (Connection connection : this.byParent.getOrDefault(clusterId, List.of()))
                    {
                        ObjectData candidate = this.objects.get(connection.child);
                        if (candidate != null && candidate.kind.equals("Model"))
                        {
                            boneModel = candidate;
                            break;
                        }
                    }
                    if (boneModel == null) continue;

                    float[] weights = new float[rawWeights.length];
                    for (int i = 0; i < weights.length; i++) weights[i] = (float) rawWeights[i];
                    /* Cluster.Transform is already the final
                     * mesh-to-bone offset matrix. TransformLink is required
                     * by FBX, but composing it again doubles the bind offset
                     * and makes every animated facial bone jump away. */
                    Matrix4f offset = matrix(transform);
                    result.add(new ClusterData(boneModel.name, indices, weights, offset));
                }
            }
            return result;
        }

        private List<ShapeData> shapesForGeometry(long geometryId, Vector3f[] base)
        {
            List<ShapeData> result = new ArrayList<>();
            for (long blendShapeId : connectedChildren(geometryId, "Deformer", "BlendShape"))
            {
                for (long channelId : connectedChildren(blendShapeId, "Deformer", "BlendShapeChannel"))
                {
                    ObjectData channel = this.objects.get(channelId);
                    if (channel == null) continue;
                    int shapeNumber = 0;
                    for (long shapeId : connectedChildren(channelId, "Geometry", "Shape"))
                    {
                        ObjectData shape = this.objects.get(shapeId);
                        if (shape == null) continue;
                        int[] indices = childInts(shape.element, "Indexes");
                        double[] deltas = childDoubles(shape.element, "Vertices");
                        if (deltas == null) continue;
                        Vector3f[] positions = new Vector3f[base.length];
                        for (int i = 0; i < base.length; i++) positions[i] = new Vector3f(base[i]);

                        if (indices == null || indices.length == 0)
                        {
                            int count = Math.min(base.length, deltas.length / 3);
                            for (int i = 0; i < count; i++) positions[i].add((float) deltas[i * 3], (float) deltas[i * 3 + 1], (float) deltas[i * 3 + 2]);
                        }
                        else
                        {
                            for (int i = 0; i < indices.length && i * 3 + 2 < deltas.length; i++)
                            {
                                int cp = indices[i];
                                if (cp >= 0 && cp < positions.length) positions[cp].add((float) deltas[i * 3], (float) deltas[i * 3 + 1], (float) deltas[i * 3 + 2]);
                            }
                        }
                        String name = channel.name.isBlank() ? shape.name : channel.name;
                        if (shapeNumber++ > 0) name += "_" + shapeNumber;
                        result.add(new ShapeData(name, positions));
                    }
                }
            }
            return result;
        }

        private void buildNodes()
        {
            Map<Long, Node> nodes = new LinkedHashMap<>();
            for (ModelData model : this.models.values())
            {
                Node node = new Node(model.object.name);
                node.transform.set(model.localTransform);
                node.meshes.addAll(model.meshes);
                nodes.put(model.object.id, node);
            }

            Set<Long> hasModelParent = new HashSet<>();
            for (Connection connection : this.connections)
            {
                if (!connection.type.equals("OO")) continue;
                Node child = nodes.get(connection.child);
                Node parent = nodes.get(connection.parent);
                if (child != null && parent != null && child != parent)
                {
                    parent.children.add(child);
                    hasModelParent.add(connection.child);
                }
            }

            for (Map.Entry<Long, Node> entry : nodes.entrySet())
            {
                if (!hasModelParent.contains(entry.getKey())) this.scene.root.children.add(entry.getValue());
            }
        }

        private void buildAnimations()
        {
            Map<Long, CurveData> curves = new HashMap<>();
            for (ObjectData object : this.objects.values())
            {
                if (object.kind.equals("AnimationCurve")) curves.put(object.id, new CurveData(object.element));
            }

            for (ObjectData stack : this.objects.values())
            {
                if (!stack.kind.equals("AnimationStack")) continue;
                Set<Long> layers = new LinkedHashSet<>(connectedChildren(stack.id, "AnimationLayer"));
                if (layers.isEmpty()) continue;

                Map<Long, ModelCurves> animatedModels = new LinkedHashMap<>();
                for (long layerId : layers)
                {
                    for (long curveNodeId : connectedChildren(layerId, "AnimationCurveNode"))
                    {
                        Connection targetConnection = null;
                        for (Connection connection : this.byChild.getOrDefault(curveNodeId, List.of()))
                        {
                            if (this.models.containsKey(connection.parent))
                            {
                                targetConnection = connection;
                                break;
                            }
                        }
                        if (targetConnection == null) continue;

                        String property = normalizeAnimatedProperty(targetConnection.property);
                        if (property == null) continue;
                        ModelCurves modelCurves = animatedModels.computeIfAbsent(targetConnection.parent, ignored -> new ModelCurves());
                        AxisCurves axes = modelCurves.forProperty(property);

                        for (Connection connection : this.byParent.getOrDefault(curveNodeId, List.of()))
                        {
                            CurveData curve = curves.get(connection.child);
                            if (curve == null) continue;
                            int axis = axis(connection.property);
                            if (axis >= 0) axes.curves[axis] = curve;
                        }
                    }
                }

                Animation animation = new Animation();
                animation.name = stack.name.isBlank() ? "animation_" + this.scene.animations.size() : stack.name;
                animation.ticksPerSecond = 1.0;
                long stackStart = scalarLong(properties(stack.element), "LocalStart", Long.MIN_VALUE);
                long stackStop = scalarLong(properties(stack.element), "LocalStop", Long.MIN_VALUE);
                long inferredStart = Long.MAX_VALUE;
                long inferredStop = Long.MIN_VALUE;
                for (ModelCurves value : animatedModels.values())
                {
                    for (CurveData curve : value.allCurves())
                    {
                        if (curve.times.length > 0)
                        {
                            inferredStart = Math.min(inferredStart, curve.times[0]);
                            inferredStop = Math.max(inferredStop, curve.times[curve.times.length - 1]);
                        }
                    }
                }
                if (inferredStart == Long.MAX_VALUE) continue;
                long start = stackStart != Long.MIN_VALUE && stackStart <= inferredStart ? stackStart : inferredStart;
                long stop = stackStop != Long.MIN_VALUE && stackStop >= inferredStop ? stackStop : inferredStop;

                for (Map.Entry<Long, ModelCurves> entry : animatedModels.entrySet())
                {
                    ModelData model = this.models.get(entry.getKey());
                    if (model == null) continue;
                    TreeSet<Long> times = entry.getValue().times();
                    if (times.isEmpty()) continue;
                    if (start < times.first()) times.add(start);
                    if (stop > times.last()) times.add(stop);

                    NodeAnimation channel = new NodeAnimation();
                    channel.nodeName = model.object.name;
                    Quaternionf previous = null;
                    for (long time : times)
                    {
                        Vector3f translation = entry.getValue().translation.valueAt(time, model.translation);
                        Vector3f rotation = entry.getValue().rotation.valueAt(time, model.rotation);
                        Vector3f scale = entry.getValue().scale.valueAt(time, model.scale);
                        Matrix4f local = model.transform(translation, rotation, scale, false);
                        Vector3f outT = local.getTranslation(new Vector3f());
                        Vector3f outS = local.getScale(new Vector3f());
                        Quaternionf outR = local.getUnnormalizedRotation(new Quaternionf()).normalize();
                        if (previous != null && previous.dot(outR) < 0) outR.set(-outR.x, -outR.y, -outR.z, -outR.w);
                        previous = new Quaternionf(outR);
                        double seconds = (time - start) / FBX_TIME_UNITS_PER_SECOND;
                        channel.positions.add(new VectorKey(seconds, outT));
                        channel.rotations.add(new QuaternionKey(seconds, outR));
                        channel.scales.add(new VectorKey(seconds, outS));
                    }
                    animation.channels.add(channel);
                }

                animation.duration = Math.max(0.0, (stop - start) / FBX_TIME_UNITS_PER_SECOND);
                if (!animation.channels.isEmpty()) this.scene.animations.add(animation);
            }
        }

        private List<Long> connectedChildren(long parent, String kind, String... subtypes)
        {
            List<Long> result = new ArrayList<>();
            for (Connection connection : this.byParent.getOrDefault(parent, List.of()))
            {
                ObjectData child = this.objects.get(connection.child);
                if (child == null || !child.kind.equals(kind)) continue;
                if (subtypes.length == 0 || Arrays.stream(subtypes).anyMatch(value -> value.equalsIgnoreCase(child.subtype)))
                {
                    result.add(child.id);
                }
            }
            return result;
        }
    }

    private static final class ModelData
    {
        final ObjectData object;
        final Vector3f translation;
        final Vector3f rotation;
        final Vector3f scale;
        final Vector3f rotationOffset;
        final Vector3f rotationPivot;
        final Vector3f preRotation;
        final Vector3f postRotation;
        final Vector3f scalingOffset;
        final Vector3f scalingPivot;
        final int rotationOrder;
        final Matrix4f geometricTransform;
        final Matrix4f localTransform;
        final List<Integer> meshes = new ArrayList<>();

        ModelData(ObjectData object)
        {
            this.object = object;
            Map<String, Object[]> props = properties(object.element);
            this.translation = vector(props, "Lcl Translation", new Vector3f());
            this.rotation = vector(props, "Lcl Rotation", new Vector3f());
            this.scale = vector(props, "Lcl Scaling", new Vector3f(1));
            this.rotationOffset = vector(props, "RotationOffset", new Vector3f());
            this.rotationPivot = vector(props, "RotationPivot", new Vector3f());
            this.preRotation = vector(props, "PreRotation", new Vector3f());
            this.postRotation = vector(props, "PostRotation", new Vector3f());
            this.scalingOffset = vector(props, "ScalingOffset", new Vector3f());
            this.scalingPivot = vector(props, "ScalingPivot", new Vector3f());
            this.rotationOrder = scalarInt(props, "RotationOrder", 0);
            Vector3f geometricTranslation = vector(props, "GeometricTranslation", new Vector3f());
            Vector3f geometricRotation = vector(props, "GeometricRotation", new Vector3f());
            Vector3f geometricScale = vector(props, "GeometricScaling", new Vector3f(1));
            this.geometricTransform = new Matrix4f().translation(geometricTranslation)
                    .mul(rotation(geometricRotation, 0)).scale(geometricScale);
            this.localTransform = transform(this.translation, this.rotation, this.scale, false);
        }

        Matrix4f transform(Vector3f translation, Vector3f rotation, Vector3f scale, boolean includeGeometry)
        {
            Matrix4f result = new Matrix4f()
                    .translate(translation)
                    .translate(this.rotationOffset)
                    .translate(this.rotationPivot)
                    .mul(rotation(this.preRotation, 0))
                    .mul(rotation(rotation, this.rotationOrder))
                    .mul(new Matrix4f(rotation(this.postRotation, 0)).invert())
                    .translate(new Vector3f(this.rotationPivot).negate())
                    .translate(this.scalingOffset)
                    .translate(this.scalingPivot)
                    .scale(scale)
                    .translate(new Vector3f(this.scalingPivot).negate());
            if (includeGeometry) result.mul(this.geometricTransform);
            return result;
        }
    }

    private record ClusterData(String boneName, int[] indices, float[] weights, Matrix4f offsetMatrix) {}
    private record ShapeData(String name, Vector3f[] vertices) {}

    private static final class CurveData
    {
        final long[] times;
        final float[] values;
        final float defaultValue;

        CurveData(Element element)
        {
            long[] rawTimes = childLongs(element, "KeyTime");
            float[] rawValues = childFloats(element, "KeyValueFloat");
            if (rawValues == null)
            {
                double[] doubles = childDoubles(element, "KeyValueDouble");
                if (doubles != null)
                {
                    rawValues = new float[doubles.length];
                    for (int i = 0; i < doubles.length; i++) rawValues[i] = (float) doubles[i];
                }
            }
            int count = Math.min(rawTimes == null ? 0 : rawTimes.length, rawValues == null ? 0 : rawValues.length);
            this.times = rawTimes == null ? new long[0] : Arrays.copyOf(rawTimes, count);
            this.values = rawValues == null ? new float[0] : Arrays.copyOf(rawValues, count);
            Element defaultElement = element.child("Default");
            this.defaultValue = defaultElement != null && defaultElement.property(0) instanceof Number number ? number.floatValue() : 0;
        }

        float valueAt(long time, float fallback)
        {
            if (this.times.length == 0) return fallback;
            if (time <= this.times[0]) return this.values[0];
            int last = this.times.length - 1;
            if (time >= this.times[last]) return this.values[last];
            int index = Arrays.binarySearch(this.times, time);
            if (index >= 0) return this.values[index];
            index = -index - 2;
            long span = this.times[index + 1] - this.times[index];
            float factor = span == 0 ? 0 : (float) ((double) (time - this.times[index]) / span);
            return this.values[index] + (this.values[index + 1] - this.values[index]) * factor;
        }
    }

    private static final class AxisCurves
    {
        final CurveData[] curves = new CurveData[3];

        Vector3f valueAt(long time, Vector3f fallback)
        {
            return new Vector3f(
                    this.curves[0] == null ? fallback.x : this.curves[0].valueAt(time, fallback.x),
                    this.curves[1] == null ? fallback.y : this.curves[1].valueAt(time, fallback.y),
                    this.curves[2] == null ? fallback.z : this.curves[2].valueAt(time, fallback.z));
        }
    }

    private static final class ModelCurves
    {
        final AxisCurves translation = new AxisCurves();
        final AxisCurves rotation = new AxisCurves();
        final AxisCurves scale = new AxisCurves();

        AxisCurves forProperty(String property)
        {
            return switch (property)
            {
                case "translation" -> this.translation;
                case "rotation" -> this.rotation;
                default -> this.scale;
            };
        }

        Collection<CurveData> allCurves()
        {
            List<CurveData> result = new ArrayList<>();
            for (AxisCurves axes : List.of(this.translation, this.rotation, this.scale))
            {
                for (CurveData curve : axes.curves) if (curve != null) result.add(curve);
            }
            return result;
        }

        TreeSet<Long> times()
        {
            TreeSet<Long> result = new TreeSet<>();
            for (CurveData curve : allCurves()) for (long time : curve.times) result.add(time);
            return result;
        }
    }

    private static final class Layer
    {
        final String mapping;
        final String reference;
        final double[] doubles;
        final int[] indices;
        final int dimensions;

        Layer(String mapping, String reference, double[] doubles, int[] indices, int dimensions)
        {
            this.mapping = mapping;
            this.reference = reference;
            this.doubles = doubles;
            this.indices = indices;
            this.dimensions = dimensions;
        }

        static Layer normal(Element geometry)
        {
            Element layer = firstChildPrefix(geometry, "LayerElementNormal");
            return layer == null ? null : new Layer(childString(layer, "MappingInformationType"), childString(layer, "ReferenceInformationType"), childDoubles(layer, "Normals"), childInts(layer, "NormalsIndex"), 3);
        }

        static List<Layer> uvs(Element geometry)
        {
            List<Layer> result = new ArrayList<>();
            for (Element layer : geometry.children)
            {
                if (layer.name.startsWith("LayerElementUV"))
                {
                    result.add(new Layer(childString(layer, "MappingInformationType"), childString(layer, "ReferenceInformationType"), childDoubles(layer, "UV"), childInts(layer, "UVIndex"), 2));
                }
            }
            return result;
        }

        static Layer material(Element geometry)
        {
            Element layer = firstChildPrefix(geometry, "LayerElementMaterial");
            if (layer == null) return null;
            int[] values = childInts(layer, "Materials");
            double[] asDouble = values == null ? null : new double[values.length];
            if (values != null) for (int i = 0; i < values.length; i++) asDouble[i] = values[i];
            return new Layer(childString(layer, "MappingInformationType"), childString(layer, "ReferenceInformationType"), asDouble, null, 1);
        }

        int mappedIndex(int controlPoint, int polygon, int polygonVertex, int corner)
        {
            String normalized = this.mapping.toLowerCase(Locale.ROOT);
            if (normalized.equals("byvertice") || normalized.equals("byvertex")) return controlPoint;
            if (normalized.equals("bypolygonvertex")) return polygonVertex;
            if (normalized.equals("bypolygon")) return polygon;
            return 0;
        }

        int directIndex(int controlPoint, int polygon, int polygonVertex, int corner)
        {
            int mapped = mappedIndex(controlPoint, polygon, polygonVertex, corner);
            if ((this.reference.equalsIgnoreCase("IndexToDirect") || this.reference.equalsIgnoreCase("Index")) && this.indices != null && mapped >= 0 && mapped < this.indices.length)
            {
                return this.indices[mapped];
            }
            return mapped;
        }

        int integerValue(int controlPoint, int polygon, int polygonVertex, int corner)
        {
            int index = directIndex(controlPoint, polygon, polygonVertex, corner);
            return this.doubles != null && index >= 0 && index < this.doubles.length ? (int) this.doubles[index] : 0;
        }

        Vector3f vector3(int index)
        {
            if (this.doubles == null || index < 0 || index * 3 + 2 >= this.doubles.length) return new Vector3f();
            return new Vector3f((float) this.doubles[index * 3], (float) this.doubles[index * 3 + 1], (float) this.doubles[index * 3 + 2]).normalize();
        }

        Vector2f vector2(int index)
        {
            if (this.doubles == null || index < 0 || index * 2 + 1 >= this.doubles.length) return new Vector2f();
            return new Vector2f((float) this.doubles[index * 2], (float) this.doubles[index * 2 + 1]);
        }
    }

    private static List<List<Corner>> decodePolygons(int[] encoded, int controlPointCount)
    {
        List<List<Corner>> polygons = new ArrayList<>();
        List<Corner> current = new ArrayList<>();
        int polygonVertex = 0;
        for (int value : encoded)
        {
            boolean last = value < 0;
            int controlPoint = last ? -value - 1 : value;
            if (controlPoint >= 0 && controlPoint < controlPointCount) current.add(new Corner(controlPoint, polygonVertex));
            polygonVertex++;
            if (last)
            {
                if (!current.isEmpty()) polygons.add(current);
                current = new ArrayList<>();
            }
        }
        if (!current.isEmpty()) polygons.add(current);
        return polygons;
    }

    private static void generateNormals(Mesh mesh)
    {
        for (int[] face : mesh.faces)
        {
            Vector3f a = mesh.vertices[face[0]];
            Vector3f b = mesh.vertices[face[1]];
            Vector3f c = mesh.vertices[face[2]];
            Vector3f normal = new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a));
            mesh.normals[face[0]].add(normal);
            mesh.normals[face[1]].add(normal);
            mesh.normals[face[2]].add(normal);
        }
        for (Vector3f normal : mesh.normals)
        {
            if (normal.lengthSquared() > 1e-12f) normal.normalize();
            else normal.set(0, 1, 0);
        }
    }

    private static Matrix4f rotation(Vector3f degrees, int order)
    {
        Quaternionf q = new Quaternionf();
        float x = degrees.x * DEG_TO_RAD;
        float y = degrees.y * DEG_TO_RAD;
        float z = degrees.z * DEG_TO_RAD;
        switch (order)
        {
            /* FBX Euler order describes the order the rotations are applied
             * to a column vector, so the corresponding matrix/quaternion
             * product is the reverse axis order. */
            case 1 -> q.rotateY(y).rotateZ(z).rotateX(x); // XZY
            case 2 -> q.rotateX(x).rotateZ(z).rotateY(y); // YZX
            case 3 -> q.rotateZ(z).rotateX(x).rotateY(y); // YXZ
            case 4 -> q.rotateY(y).rotateX(x).rotateZ(z); // ZXY
            case 5 -> q.rotateX(x).rotateY(y).rotateZ(z); // ZYX
            default -> q.rotateZ(z).rotateY(y).rotateX(x); // XYZ
        }
        return new Matrix4f().rotation(q);
    }

    private static Matrix4f matrix(double[] values)
    {
        if (values == null || values.length < 16) return new Matrix4f();
        return new Matrix4f(
                (float) values[0], (float) values[1], (float) values[2], (float) values[3],
                (float) values[4], (float) values[5], (float) values[6], (float) values[7],
                (float) values[8], (float) values[9], (float) values[10], (float) values[11],
                (float) values[12], (float) values[13], (float) values[14], (float) values[15]);
    }

    private static Map<String, Object[]> properties(Element owner)
    {
        Map<String, Object[]> result = new LinkedHashMap<>();
        if (owner == null) return result;
        Element properties = owner.child("Properties70");
        if (properties == null) properties = owner.child("Properties60");
        if (properties == null) return result;
        for (Element property : properties.children("P"))
        {
            if (property.properties.length < 5) continue;
            result.put(property.string(0), Arrays.copyOfRange(property.properties, 4, property.properties.length));
        }
        return result;
    }

    private static Vector3f vector(Map<String, Object[]> properties, String key, Vector3f fallback)
    {
        Object[] values = properties.get(key);
        if (values == null || values.length < 3) return fallback == null ? null : new Vector3f(fallback);
        int start = values.length - 3;
        if (!(values[start] instanceof Number) || !(values[start + 1] instanceof Number) || !(values[start + 2] instanceof Number)) return fallback == null ? null : new Vector3f(fallback);
        return new Vector3f(((Number) values[start]).floatValue(), ((Number) values[start + 1]).floatValue(), ((Number) values[start + 2]).floatValue());
    }

    private static int scalarInt(Map<String, Object[]> properties, String key, int fallback)
    {
        Object[] values = properties.get(key);
        return values != null && values.length > 0 && values[values.length - 1] instanceof Number number ? number.intValue() : fallback;
    }

    private static double scalarDouble(Map<String, Object[]> properties, String key, double fallback)
    {
        Object[] values = properties.get(key);
        return values != null && values.length > 0 && values[values.length - 1] instanceof Number number ? number.doubleValue() : fallback;
    }

    private static long scalarLong(Map<String, Object[]> properties, String key, long fallback)
    {
        Object[] values = properties.get(key);
        return values != null && values.length > 0 && values[values.length - 1] instanceof Number number ? number.longValue() : fallback;
    }

    private static Element firstChildPrefix(Element owner, String prefix)
    {
        for (Element child : owner.children) if (child.name.startsWith(prefix)) return child;
        return null;
    }

    private static String childString(Element owner, String name)
    {
        Element child = owner == null ? null : owner.child(name);
        return child == null ? "" : child.string(0);
    }

    private static byte[] childBytes(Element owner, String name)
    {
        Element child = owner == null ? null : owner.child(name);
        return child != null && child.property(0) instanceof byte[] bytes ? bytes : null;
    }

    private static double[] childDoubles(Element owner, String name)
    {
        Element child = owner == null ? null : owner.child(name);
        Object value = child == null ? null : child.property(0);
        if (value instanceof double[] doubles) return doubles;
        if (value instanceof float[] floats)
        {
            double[] result = new double[floats.length];
            for (int i = 0; i < floats.length; i++) result[i] = floats[i];
            return result;
        }
        return null;
    }

    private static float[] childFloats(Element owner, String name)
    {
        Element child = owner == null ? null : owner.child(name);
        return child != null && child.property(0) instanceof float[] values ? values : null;
    }

    private static int[] childInts(Element owner, String name)
    {
        Element child = owner == null ? null : owner.child(name);
        return child != null && child.property(0) instanceof int[] values ? values : null;
    }

    private static long[] childLongs(Element owner, String name)
    {
        Element child = owner == null ? null : owner.child(name);
        return child != null && child.property(0) instanceof long[] values ? values : null;
    }

    private static String cleanName(String raw)
    {
        if (raw == null) return "";
        int nul = raw.indexOf('\0');
        if (nul >= 0) raw = raw.substring(0, nul);
        int separator = raw.indexOf("::");
        if (separator >= 0) raw = raw.substring(separator + 2);
        /* FBX names are significant byte strings. In particular, Blender
         * uses a trailing space to disambiguate same-named armature and mesh
         * nodes; trimming it makes both nodes target the same BOBJ bone. */
        return raw;
    }

    private static String baseName(String path)
    {
        if (path == null) return "";
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? normalized : normalized.substring(slash + 1);
    }

    private static String normalizeAnimatedProperty(String property)
    {
        if (property == null) return null;
        return switch (property.toLowerCase(Locale.ROOT))
        {
            case "lcl translation", "t" -> "translation";
            case "lcl rotation", "r" -> "rotation";
            case "lcl scaling", "s" -> "scale";
            default -> null;
        };
    }

    private static int axis(String property)
    {
        if (property == null || property.isEmpty()) return -1;
        char axis = Character.toUpperCase(property.charAt(property.length() - 1));
        return axis == 'X' ? 0 : axis == 'Y' ? 1 : axis == 'Z' ? 2 : -1;
    }

    private static int bits(float value)
    {
        return Float.floatToIntBits(value == 0.0f ? 0.0f : value);
    }

    private static String baseName(Object path)
    {
        return baseName(path == null ? null : String.valueOf(path));
    }
}
