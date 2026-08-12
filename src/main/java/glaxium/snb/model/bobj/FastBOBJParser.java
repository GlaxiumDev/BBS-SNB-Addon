package glaxium.snb.model.bobj;

import mchorse.bbs_mod.bobj.BOBJAction;
import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.bobj.BOBJChannel;
import mchorse.bbs_mod.bobj.BOBJGroup;
import mchorse.bbs_mod.bobj.BOBJKeyframe;
import mchorse.bbs_mod.bobj.BOBJLoader;

import org.joml.Matrix4f;
import org.joml.Vector2d;
import org.joml.Vector3f;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Streaming parser for BBS's text BOBJ format.
 *
 * <p>The native parser first retains every source line and then performs a
 * regular-expression split that allocates a String array and a String for
 * every token. Large animated BOBJ files can contain hundreds of thousands
 * of lines. Parsing one line at a time with reusable token boundaries cuts
 * both first-load time and peak memory while producing the same BOBJ data
 * structures consumed by the rest of BBS.</p>
 */
public final class FastBOBJParser
{
    private FastBOBJParser()
    {}

    public static BOBJLoader.BOBJData read(InputStream stream) throws Exception
    {
        List<BOBJLoader.Vertex> vertices = new ArrayList<>();
        List<Vector2d> textures = new ArrayList<>();
        List<Vector3f> normals = new ArrayList<>();
        List<BOBJLoader.BOBJMesh> meshes = new ArrayList<>();
        Map<String, BOBJAction> actions = new HashMap<>();
        Map<String, BOBJArmature> armatures = new HashMap<>();

        BOBJLoader.BOBJMesh mesh = null;
        BOBJAction action = null;
        BOBJGroup group = null;
        BOBJChannel channel = null;
        BOBJArmature armature = null;
        BOBJLoader.Vertex vertex = null;
        int boneIndex = 0;

        TokenLine tokens = new TokenLine();
        float[] matrixValues = new float[16];

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8), 128 * 1024))
        {
            String line;

            while ((line = reader.readLine()) != null)
            {
                tokens.reset(line);

                if (tokens.count == 0)
                {
                    continue;
                }

                if (tokens.is(0, "o"))
                {
                    mesh = new BOBJLoader.BOBJMesh(tokens.text(1));
                    meshes.add(mesh);
                    armature = null;
                    vertex = null;
                }
                else if (tokens.is(0, "o_arm"))
                {
                    mesh.armatureName = tokens.text(1);
                }
                else if (tokens.is(0, "v"))
                {
                    if (vertex != null)
                    {
                        vertex.eliminateTinyWeights();
                    }

                    vertex = new BOBJLoader.Vertex(tokens.floatAt(1), tokens.floatAt(2), tokens.floatAt(3));
                    vertices.add(vertex);
                }
                else if (tokens.is(0, "vw"))
                {
                    float weight = tokens.floatAt(2);

                    if (weight != 0F)
                    {
                        vertex.weights.add(new BOBJLoader.Weight(tokens.text(1), weight));
                    }
                }
                else if (tokens.is(0, "vt"))
                {
                    textures.add(new Vector2d(tokens.doubleAt(1), tokens.doubleAt(2)));
                }
                else if (tokens.is(0, "vn"))
                {
                    normals.add(new Vector3f(tokens.floatAt(1), tokens.floatAt(2), tokens.floatAt(3)));
                }
                else if (tokens.is(0, "f"))
                {
                    BOBJLoader.Face face = new BOBJLoader.Face();

                    face.idxGroups[0] = tokens.faceAt(1);
                    face.idxGroups[1] = tokens.faceAt(2);
                    face.idxGroups[2] = tokens.faceAt(3);
                    mesh.faces.add(face);
                }
                else if (tokens.is(0, "arm_name"))
                {
                    boneIndex = 0;
                    armature = new BOBJArmature(tokens.text(1));
                    armatures.put(armature.name, armature);
                }
                else if (tokens.is(0, "arm_bone"))
                {
                    /* Parse the three legacy head-position values as the
                     * stock parser does, even though BBS does not retain
                     * that temporary vector. */
                    tokens.floatAt(3);
                    tokens.floatAt(4);
                    tokens.floatAt(5);

                    for (int i = 0; i < 16; i++)
                    {
                        matrixValues[i] = tokens.floatAt(i + 6);
                    }

                    Matrix4f matrix = new Matrix4f().set(matrixValues).transpose();
                    BOBJBone bone = new BOBJBone(boneIndex++, tokens.text(1), tokens.text(2), matrix);

                    armature.addBone(bone);
                }
                else if (tokens.is(0, "an"))
                {
                    String name = tokens.text(1);

                    action = new BOBJAction(name);
                    actions.put(name, action);
                }
                else if (tokens.is(0, "ao"))
                {
                    String name = tokens.text(1);

                    group = new BOBJGroup(name);
                    action.groups.put(name, group);
                }
                else if (tokens.is(0, "ag"))
                {
                    channel = new BOBJChannel(tokens.text(1), tokens.intAt(2));
                    group.channels.add(channel);
                }
                else if (tokens.is(0, "kf"))
                {
                    channel.keyframes.add(tokens.keyframe());
                }
            }
        }

        if (vertex != null)
        {
            vertex.eliminateTinyWeights();
        }

        return new BOBJLoader.BOBJData(vertices, textures, normals, meshes, actions, armatures);
    }

    /** Reusable String.split("\\s") equivalent represented as ranges. */
    private static final class TokenLine
    {
        private String line;
        private int[] starts = new int[24];
        private int[] ends = new int[24];
        private int count;

        private void reset(String line)
        {
            this.line = line;
            this.count = 0;
            int start = 0;

            for (int i = 0; i < line.length(); i++)
            {
                if (isSplitWhitespace(line.charAt(i)))
                {
                    this.add(start, i);
                    start = i + 1;
                }
            }

            this.add(start, line.length());

            /* String.split(regex) discards trailing empty fields but keeps
             * interior ones. The latter is important: root arm_bone lines
             * encode an empty parent using two consecutive spaces. */
            while (this.count > 0 && this.starts[this.count - 1] == this.ends[this.count - 1]
                    && line.length() > 0)
            {
                this.count--;
            }

            if (line.isEmpty())
            {
                this.count = 1;
            }
        }

        private void add(int start, int end)
        {
            if (this.count == this.starts.length)
            {
                int size = this.count * 2;
                int[] newStarts = new int[size];
                int[] newEnds = new int[size];

                System.arraycopy(this.starts, 0, newStarts, 0, this.count);
                System.arraycopy(this.ends, 0, newEnds, 0, this.count);
                this.starts = newStarts;
                this.ends = newEnds;
            }

            this.starts[this.count] = start;
            this.ends[this.count] = end;
            this.count++;
        }

        private boolean is(int index, String expected)
        {
            int start = this.starts[index];
            int length = this.ends[index] - start;

            return length == expected.length() && this.line.regionMatches(start, expected, 0, length);
        }

        private String text(int index)
        {
            return this.line.substring(this.starts[index], this.ends[index]);
        }

        private float floatAt(int index)
        {
            return Float.parseFloat(this.text(index));
        }

        private double doubleAt(int index)
        {
            return Double.parseDouble(this.text(index));
        }

        private int intAt(int index)
        {
            return parseInt(this.line, this.starts[index], this.ends[index]);
        }

        private BOBJLoader.IndexGroup faceAt(int index)
        {
            int start = this.starts[index];
            int end = this.ends[index];
            int firstSlash = -1;
            int secondSlash = -1;

            for (int i = start; i < end; i++)
            {
                if (this.line.charAt(i) == '/')
                {
                    if (firstSlash < 0)
                    {
                        firstSlash = i;
                    }
                    else
                    {
                        secondSlash = i;
                        break;
                    }
                }
            }

            if (firstSlash < 0)
            {
                return new BOBJLoader.IndexGroup(parseInt(this.line, start, end) - 1, -1, -1);
            }

            int position = parseInt(this.line, start, firstSlash) - 1;
            int textureEnd = secondSlash < 0 ? end : secondSlash;
            int texture = firstSlash + 1 < textureEnd
                    ? parseInt(this.line, firstSlash + 1, textureEnd) - 1
                    : -1;
            int normal = secondSlash >= 0
                    ? parseInt(this.line, secondSlash + 1, end) - 1
                    : -1;

            return new BOBJLoader.IndexGroup(position, texture, normal);
        }

        private BOBJKeyframe keyframe()
        {
            if (this.count != 3 && this.count != 4 && this.count != 8)
            {
                return null;
            }

            float frame = this.floatAt(1);
            float value = this.floatAt(2);
            BOBJKeyframe keyframe = new BOBJKeyframe(frame, value);

            if (this.count >= 4)
            {
                keyframe.interpolation = this.is(3, "CONSTANT")
                        ? BOBJKeyframe.Interpolation.CONSTANT
                        : this.is(3, "BEZIER")
                                ? BOBJKeyframe.Interpolation.BEZIER
                                : BOBJKeyframe.Interpolation.LINEAR;
            }

            if (this.count == 8)
            {
                keyframe.leftX = this.floatAt(4);
                keyframe.leftY = this.floatAt(5);
                keyframe.rightX = this.floatAt(6);
                keyframe.rightY = this.floatAt(7);
            }

            return keyframe;
        }

        private static boolean isSplitWhitespace(char c)
        {
            return c == ' ' || c == '\t' || c == '\f' || c == '\u000B';
        }

        private static int parseInt(String text, int start, int end)
        {
            boolean negative = start < end && text.charAt(start) == '-';
            int value = 0;

            if (negative || start < end && text.charAt(start) == '+')
            {
                start++;
            }

            for (int i = start; i < end; i++)
            {
                value = value * 10 + text.charAt(i) - '0';
            }

            return negative ? -value : value;
        }
    }
}
