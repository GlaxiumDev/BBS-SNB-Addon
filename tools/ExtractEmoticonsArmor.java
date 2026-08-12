import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Reproducibly strips the non-armor prop mesh and reindexes BOBJ faces. */
public final class ExtractEmoticonsArmor
{
    private static final Set<String> ARMOR = Set.of(
            "armor_helmet", "armor_chest", "armor_leggings", "armor_feet"
    );

    public static void main(String[] args) throws Exception
    {
        if (args.length != 3)
        {
            throw new IllegalArgumentException(
                    "Usage: <props.bobj> <props_simple.bobj> <resources root>");
        }

        String normal = extract(Path.of(args[0]), "props.bobj");
        /* BBS's visible Bend model is the old Emoticons Simple+ model (its
         * internal resource ID is still *_simple). Use its matching low-poly
         * shell; the addon supplies the missing Simple+ hinge at runtime. */
        String bend = extract(Path.of(args[1]),
                "props_simple.bobj (Simple+ / BBS Bend sharp-hinge armor)");
        Path root = Path.of(args[2]).resolve("assets/bbs/assets/models/emoticons");

        write(root.resolve("steve/armor.bobj"), normal);
        write(root.resolve("alex/armor.bobj"), slimArms(normal));
        write(root.resolve("steve_bend/armor.bobj"), bend);
        write(root.resolve("alex_bend/armor.bobj"), slimArms(bend));
    }

    private static String extract(Path input, String sourceName) throws IOException
    {
        List<String> output = new ArrayList<>();
        Map<Integer, Integer> vertices = new HashMap<>();
        Map<Integer, Integer> textures = new HashMap<>();
        Map<Integer, Integer> normals = new HashMap<>();
        int oldVertex = 0;
        int oldTexture = 0;
        int oldNormal = 0;
        int newVertex = 0;
        int newTexture = 0;
        int newNormal = 0;
        boolean selected = false;

        output.add("# Armor-only BOBJ sidecar extracted from mchorse/emoticons " + sourceName);
        output.add("# Original Emoticons repository: https://github.com/mchorse/emoticons (GPL-3.0)");

        for (String line : Files.readAllLines(input, StandardCharsets.UTF_8))
        {
            String trimmed = line.trim();

            if (trimmed.isEmpty() || trimmed.startsWith("#"))
            {
                continue;
            }

            String[] tokens = trimmed.split("\\s+");

            switch (tokens[0])
            {
                case "o" ->
                {
                    selected = tokens.length > 1 && ARMOR.contains(tokens[1]);

                    if (selected)
                    {
                        output.add("");
                        output.add(trimmed);
                    }
                }
                case "v" ->
                {
                    oldVertex++;

                    if (selected)
                    {
                        vertices.put(oldVertex, ++newVertex);
                        output.add(trimmed);
                    }
                }
                case "vt" ->
                {
                    oldTexture++;

                    if (selected)
                    {
                        textures.put(oldTexture, ++newTexture);
                        output.add(trimmed);
                    }
                }
                case "vn" ->
                {
                    oldNormal++;

                    if (selected)
                    {
                        normals.put(oldNormal, ++newNormal);
                        output.add(trimmed);
                    }
                }
                case "f" ->
                {
                    if (selected)
                    {
                        StringBuilder face = new StringBuilder("f");

                        for (int i = 1; i < tokens.length; i++)
                        {
                            face.append(' ').append(remapFace(tokens[i], vertices, textures, normals));
                        }

                        output.add(face.toString());
                    }
                }
                default ->
                {
                    if (selected && (tokens[0].equals("o_arm") || tokens[0].equals("vw")))
                    {
                        output.add(trimmed);
                    }
                }
            }
        }

        return String.join("\n", output) + "\n";
    }

    /**
     * Converts Steve-width armor sleeves to the arm bounds used by Alex.
     *
     * <p>Both source prop files contain one shared Steve-sized armor shell.
     * Alex's arm centers are shifted 1/32 toward the torso and the arms are
     * one pixel narrower. Scaling around the two arm centers maps the source
     * sleeve bounds exactly:</p>
     *
     * <pre>
     * Steve: [-0.5625, -0.1875] / [0.1875, 0.5625]
     * Alex:  [-0.5000, -0.1875] / [0.1875, 0.5000]
     * </pre>
     */
    private static String slimArms(String input)
    {
        List<String> lines = new ArrayList<>(List.of(input.split("\n", -1)));

        for (int i = 0; i < lines.size(); i++)
        {
            String line = lines.get(i);

            if (!line.startsWith("v "))
            {
                continue;
            }

            boolean left = false;
            boolean right = false;

            for (int j = i + 1; j < lines.size() && lines.get(j).startsWith("vw "); j++)
            {
                String[] weight = lines.get(j).trim().split("\\s+");

                if (weight.length < 3 || Float.parseFloat(weight[2]) <= 0.01F)
                {
                    continue;
                }

                left |= weight[1].contains("left_arm");
                right |= weight[1].contains("right_arm");
            }

            if (!left && !right)
            {
                continue;
            }

            String[] vertex = line.trim().split("\\s+");
            float x = Float.parseFloat(vertex[1]);
            boolean useLeft = left && (!right || x >= 0F);
            float steveCenter = useLeft ? 0.375F : -0.375F;
            float alexCenter = useLeft ? 0.34375F : -0.34375F;
            float slimX = alexCenter + (x - steveCenter) * (5F / 6F);

            lines.set(i, String.format(
                    Locale.ROOT,
                    "v %.6f %s %s",
                    slimX,
                    vertex[2],
                    vertex[3]
            ));
        }

        return String.join("\n", lines);
    }

    private static String remapFace(
            String token, Map<Integer, Integer> vertices, Map<Integer, Integer> textures,
            Map<Integer, Integer> normals)
    {
        String[] indices = token.split("/", -1);
        StringBuilder result = new StringBuilder();

        result.append(required(vertices, indices[0], "vertex"));

        if (indices.length > 1)
        {
            result.append('/');

            if (!indices[1].isEmpty())
            {
                result.append(required(textures, indices[1], "texture"));
            }
        }

        if (indices.length > 2)
        {
            result.append('/');

            if (!indices[2].isEmpty())
            {
                result.append(required(normals, indices[2], "normal"));
            }
        }

        return result.toString();
    }

    private static int required(Map<Integer, Integer> map, String raw, String kind)
    {
        int old = Integer.parseInt(raw);
        Integer replacement = map.get(old);

        if (replacement == null)
        {
            throw new IllegalStateException("Selected armor face references an excluded " + kind + " #" + old);
        }

        return replacement;
    }

    private static void write(Path output, String contents) throws IOException
    {
        Files.createDirectories(output.getParent());
        Files.writeString(output, contents, StandardCharsets.UTF_8);
    }
}
