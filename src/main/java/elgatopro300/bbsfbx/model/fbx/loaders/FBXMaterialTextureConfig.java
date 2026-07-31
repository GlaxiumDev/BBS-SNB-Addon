package elgatopro300.bbsfbx.model.fbx.loaders;

import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.resources.LinkUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persists which texture the user picked per material for a given FBX model,
 * across reloads/sessions. Deliberately NOT stored as a new {@code ModelForm}
 * field: adding a genuinely new persisted property to {@code ModelForm}
 * would mean mixing a field into its constructor and hooking it into BBS's
 * own value/serialization system, which is real engine-internal surface
 * this addon has never touched before and can't verify here. This file-based
 * approach instead reuses the same "small file living next to the model"
 * convention {@code FBXTextureExtractor} already uses for extracted embedded
 * textures - fully addon-owned, no engine serialization involved.
 *
 * <p>Scope note: this makes a material's chosen texture a property of the
 * MODEL FILE, shared by every placed instance of it - not a property of
 * each individual {@code Form} the way BBS FS's {@code form.materialTextures}
 * is. Simpler, and fine for "this FBX has 3 materials, I want to assign
 * textures to each" - but if per-instance overrides (two placed copies of
 * the same model wearing different textures) turn out to matter, this would
 * need to move to a real {@code ModelForm} mixin instead.</p>
 *
 * <p>Format is intentionally plain text, not JSON - one {@code material=source:path}
 * line per override, hand-parsed with no dependency on any of CML's own
 * (unverified from this addon's position) data/JSON classes.</p>
 */
public final class FBXMaterialTextureConfig
{
    private static final String FILE_NAME = "bbs_fbx_materials.txt";

    private FBXMaterialTextureConfig() {}

    /** Material name -> chosen Link, or empty if no file exists yet or it's unreadable. */
    public static Map<String, Link> load(AssetProvider provider, Link model)
    {
        Map<String, Link> result = new LinkedHashMap<>();
        File file = configFile(provider, model);

        if (file == null || !file.exists())
        {
            return result;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(file)))
        {
            String line;

            while ((line = reader.readLine()) != null)
            {
                line = line.trim();

                if (line.isEmpty() || line.startsWith("#"))
                {
                    continue;
                }

                int eq = line.indexOf('=');

                if (eq <= 0)
                {
                    continue;
                }

                String material = line.substring(0, eq);
                String linkStr = line.substring(eq + 1);
                Link link = parseLink(linkStr);

                if (link != null)
                {
                    result.put(material, link);
                }
            }
        }
        catch (IOException ignored)
        {
            // Unreadable or half-written file - treat as "no overrides saved yet".
        }

        return result;
    }

    /** Overwrites the saved overrides for this model with the given map (a null-valued entry clears that material). */
    public static void save(AssetProvider provider, Link model, Map<String, Link> materialTextures)
    {
        File file = configFile(provider, model);

        if (file == null)
        {
            return;
        }

        File parent = file.getParentFile();

        if (parent != null)
        {
            parent.mkdirs();
        }

        try (FileWriter writer = new FileWriter(file))
        {
            for (Map.Entry<String, Link> entry : materialTextures.entrySet())
            {
                if (entry.getValue() == null)
                {
                    continue;
                }

                writer.write(entry.getKey());
                writer.write('=');
                writer.write(entry.getValue().source);
                writer.write(':');
                writer.write(entry.getValue().path);
                writer.write('\n');
            }
        }
        catch (IOException ignored)
        {
            // Best-effort - the in-memory choice still applies for the rest of this session either way.
        }
    }

    private static File configFile(AssetProvider provider, Link model)
    {
        try
        {
            return provider.getFile(model.combine(FILE_NAME));
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    private static Link parseLink(String encoded)
    {
        int colon = encoded.indexOf(':');

        if (colon < 0)
        {
            return LinkUtils.create(encoded);
        }

        String source = encoded.substring(0, colon);
        String path = encoded.substring(colon + 1);

        return new Link(source, path);
    }
}
