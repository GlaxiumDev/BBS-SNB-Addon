package glaxium.snb.model.fbx.scene;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small format-neutral scene graph used by the Java FBX/glTF readers and the
 * BOBJ converter.  Keeping this model owned by the addon is what lets the
 * runtime stay pure Java instead of leaking native-library structs through
 * every conversion stage.
 */
public final class JavaScene
{
    public Node root = new Node("RootNode");
    public final List<Mesh> meshes = new ArrayList<>();
    public final List<Material> materials = new ArrayList<>();
    public final List<Texture> textures = new ArrayList<>();
    public final List<Animation> animations = new ArrayList<>();
    public final Metadata metadata = new Metadata();

    public static final class Metadata
    {
        public int upAxis = 1;
        public int originalUpAxis = 1;
        public int frontAxis = 2;
        public int coordAxis = 0;
        public double unitScaleFactor = 1.0;
    }

    public static final class Node
    {
        public String name;
        public final Matrix4f transform = new Matrix4f();
        public final List<Integer> meshes = new ArrayList<>();
        public final List<Node> children = new ArrayList<>();

        public Node(String name)
        {
            this.name = name == null ? "" : name;
        }
    }

    public static final class Mesh
    {
        public String name = "";
        public Vector3f[] vertices = new Vector3f[0];
        public Vector3f[] normals = new Vector3f[0];
        public Vector2f[] texCoords = new Vector2f[0];
        public int[][] faces = new int[0][];
        public int materialIndex = -1;
        public final List<Bone> bones = new ArrayList<>();
        public final List<ShapeKey> shapeKeys = new ArrayList<>();
    }

    public static final class Bone
    {
        public String name = "";
        public final Matrix4f offsetMatrix = new Matrix4f();
        public final List<VertexWeight> weights = new ArrayList<>();
    }

    public record VertexWeight(int vertexId, float weight) {}

    public static final class ShapeKey
    {
        public String name = "";
        public Vector3f[] vertices = new Vector3f[0];
        public Vector3f[] normals = new Vector3f[0];
    }

    public static final class Material
    {
        public String name = "";
        public String texturePath;
        public float[] color;
        public Texture texture;
    }

    public static final class Texture
    {
        public String name = "";
        public String fileName = "";
        /** Encoded PNG/JPEG/WebP/etc. bytes. */
        public byte[] data;
        /** Optional raw BGRA8 texels when the source stores uncompressed pixels. */
        public byte[] bgra;
        public int width;
        public int height;
    }

    public static final class Animation
    {
        public String name = "";
        /** Key times are expressed in these units per second. */
        public double ticksPerSecond = 1.0;
        public double duration;
        public final List<NodeAnimation> channels = new ArrayList<>();
    }

    public static final class NodeAnimation
    {
        public String nodeName = "";
        public final List<VectorKey> positions = new ArrayList<>();
        public final List<QuaternionKey> rotations = new ArrayList<>();
        public final List<VectorKey> scales = new ArrayList<>();
    }

    public record VectorKey(double time, Vector3f value) {}
    public record QuaternionKey(double time, Quaternionf value) {}

    /** Useful while importers resolve source IDs and connections. */
    public static <K, V> Map<K, List<V>> listMap()
    {
        return new LinkedHashMap<>();
    }
}
