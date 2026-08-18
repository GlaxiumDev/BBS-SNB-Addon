(function() {
    var exportAction, importAction;
    var lastOptions = {
        model: true,
        animations: true,
        smoothShading: false
    };
    var sides = {
        north: "front",
        south: "back",
        west: "left",
        east: "right",
        up: "top",
        down: "bottom"
    };
    var sidesInverse = {
        front: "north",
        back: "south",
        left: "west",
        right: "east",
        top: "up",
        bottom: "down"
    };

    /* Model exporter code */

    function areThereObjects(objects)
    {
        for (let i = 0; i < objects.length; i++) 
        {
            if (objects[i].type === "group")
            {
                return true;
            }
        }

        return false;
    }

    function createHierarchy(objects)
    {
        var groups = {};
        var createAnchor = !areThereObjects(objects);

        if (createAnchor)
        {
            var group = new Group();

            group.children = objects;
            groups.anchor = createModelGroup(group, groups);
        }
        else
        {
            for (let i = 0; i < objects.length; i++)
            {
                var object = objects[i];
    
                if (object.type === "group")
                {
                    groups[object.name] = createModelGroup(object, groups);
                }
            }
        }

        return groups;
    }

    function createModelGroup(o, groups)
    {
        var group = {};
        var cubes = [];
        var meshes = [];

        group.origin = o.origin.slice();

        if (!o.rotation.allEqual(0))
        {
            group.rotate = o.rotation.slice();
        }

        if (typeof o.parent.name === "string")
        {
            group.parent = o.parent.name;
        }

        for (let i = 0; i < o.children.length; i++)
        {
            var object = o.children[i];

            if (object.type === "group")
            {
                groups[object.name] = createModelGroup(object, groups);
            }
            else if (object.type === "cube")
            {
                cubes.push(createCube(object));
            }
            else if (object.type === "mesh")
            {
                var created = createMesh(object);

                if (Array.isArray(created))
                {
                    for (var j = 0; j < created.length; j++)
                    {
                        meshes.push(created[j]);
                    }
                }
                else
                {
                    meshes.push(created);
                }
            }
        }

        if (cubes.length > 0)
        {
            group.cubes = cubes;
        }

        if (meshes.length > 0)
        {
            group.meshes = meshes;
        }

        return group;
    }

    /**
     * Position of a face's texture in Texture.all (the same order the
     * embedded model.textures array is written out). The primary texture
     * (index 0) needs no reference in the legacy format; unknown textures
     * also fall back to 0 so export never fails on a dangling uuid.
     */
    function textureIndex(face)
    {
        if (!face || typeof face.texture !== "string")
        {
            return 0;
        }

        var all = typeof Texture !== "undefined" ? Texture.all : [];

        for (var i = 0; i < all.length; i++)
        {
            if (all[i] && all[i].uuid === face.texture)
            {
                return i;
            }
        }

        return 0;
    }

    function createCube(c)
    {
        var cube = {};
        var uvs = {};
        var textures = {};

        cube.origin = c.origin.slice();
        cube.from = c.from.slice();
        cube.size = c.size();

        if (c.inflate !== 0)
        {
            cube.offset = c.inflate;
        }

        if (!c.rotation.allEqual(0))
        {
            cube.rotate = c.rotation.slice();
        }

        Object.keys(CubeFace.opposite).forEach(key =>
        {
            var face = c.faces[key];

            if (face && face.texture !== null)
            {
                var uv = face.uv.slice();

                if (face.rotation !== 0)
                {
                    uv.push(face.rotation);
                }

                uvs[sides[key]] = uv;
            }

            var index = textureIndex(face);

            if (index > 0)
            {
                textures[sides[key]] = index;
            }
        });

        if (Object.keys(uvs).length > 0)
        {
            cube.uvs = uvs;
        }

        /* Only present when the cube actually uses a non-primary texture;
         * single-texture output stays byte-identical to the legacy exporter. */
        if (Object.keys(textures).length > 0)
        {
            cube.textures = textures;
        }

        return cube;
    }

    /**
     * Material names for the legacy export: each texture that a cube or mesh
     * actually uses maps to a unique folder name (textures/<name>/default.png
     * in the game). Names are sanitized and deduplicated with _1, _2 suffixes
     * so textures that share a name still get separate folders.
     */
    var legacyMaterialMap = null;

    function legacyMaterials()
    {
        if (legacyMaterialMap)
        {
            return legacyMaterialMap;
        }

        legacyMaterialMap = {};
        var used = {};
        var all = typeof Texture !== "undefined" ? Texture.all : [];

        /* Every texture in Texture.all order, so the embedded names and the
         * mesh materials always agree. Duplicate names get the same
         * treatment ("default", "default_1", "default_2", ...); generic
         * names fall back to the texture's source folder. */
        all.forEach(texture =>
        {
            if (!texture || typeof texture.uuid !== "string" || legacyMaterialMap[texture.uuid])
            {
                return;
            }

            var name = texture.name ? texture.name.replace(/[\\/]/g, "_").replace(/\.png$/i, "") : "";

            if ((!name || /^default(?:_\d+)?$/i.test(name)))
            {
                var rel = String(texture.path || texture.relative_path || "").replace(/\\/g, "/");
                var parts = rel.split("/");
                parts.pop();
                var dir = parts[parts.length - 1] || "";

                if (/^[a-z0-9._ -]+$/i.test(dir))
                {
                    name = dir.replace(/\.[a-z0-9]+$/i, "");
                }
            }

            if (!name)
            {
                return;
            }

            var base = name;
            var candidate = name;
            var i = 1;

            while (used[candidate])
            {
                candidate = base + "_" + i;
                i++;
            }

            used[candidate] = true;
            legacyMaterialMap[texture.uuid] = candidate;
        });

        return legacyMaterialMap;
    }

    function legacyMaterialName(uuid)
    {
        return legacyMaterials()[uuid] || null;
    }

    function createMesh(c)
    {
        /* Smooth shading comes from the element's own setting, or from the
         * export dialog's Smooth Shading toggle which forces it for every
         * mesh in the model (the game applies per-vertex normals when the
         * file carries them). */
        var smooth = lastOptions.smoothShading || c.shading === "smooth";

        /* Group faces by texture. Blockbench stores mesh UVs in atlas
         * space (texture i's tile sits at y in [-32*i, 32-32*i]); each
         * texture becomes its own sub-mesh with UVs remapped into that
         * texture's own 0..32 tile space, matching how the game's OBJ
         * loader splits objects per material. */
        var faceKeys = {};
        var order = [];
        var untextured = [];

        for (var key in c.faces)
        {
            var face = c.faces[key];
            var t = face.texture && typeof face.texture === "string" ? face.texture : null;

            if (t === null)
            {
                untextured.push(key);
                continue;
            }

            if (!faceKeys[t])
            {
                faceKeys[t] = [];
                order.push(t);
            }

            faceKeys[t].push(key);
        }

        if (order.length === 0)
        {
            return buildMesh(c, untextured, null);
        }

        if (untextured.length > 0)
        {
            var target = order[0];

            for (var i = 0; i < untextured.length; i++)
            {
                faceKeys[target].push(untextured[i]);
            }
        }

        var sub = [];

        for (var i = 0; i < order.length; i++)
        {
            sub.push(buildMesh(c, faceKeys[order[i]], order[i]));
        }

        if (sub.length === 1)
        {
            return sub[0];
        }

        var names = order.map(uuid =>
        {
            var t = typeof Texture !== "undefined" ? Texture.all.find(t => t && t.uuid === uuid) : null;

            return t && t.name ? t.name : "?";
        });

        console.log("[BBS S&B] Mesh '" + c.name + "' mixes " + sub.length + " textures; split into per-texture meshes: " + names.join(", ") + ".");

        return sub;
    }

    function buildMesh(c, keys, textureUuid)
    {
        var mesh = {};
        var vertices = [];
        var uvs = [];
        var textureIndexValue = textureUuid ? textureIndex({texture: textureUuid}) : 0;
        var smooth = lastOptions.smoothShading || c.shading === "smooth";
        var pushedKeys = [];
        var smoothAccum = {};

        var pushVertexKey = (k, f) =>
        {
            var v = c.vertices[k];
            var u = f.uv[k];

            vertices.push(v[0]);
            vertices.push(v[1]);
            vertices.push(v[2]);
            uvs.push(u[0]);
            uvs.push(u[1] + 32 * textureIndexValue);

            pushedKeys.push(k);
        };

        var faceNormal = (keys) =>
        {
            var v0 = c.vertices[keys[0]];
            var a = c.vertices[keys[1]];
            var b = c.vertices[keys[2]];
            var ax = a[0] - v0[0], ay = a[1] - v0[1], az = a[2] - v0[2];
            var bx = b[0] - v0[0], by = b[1] - v0[1], bz = b[2] - v0[2];
            var nx = ay * bz - az * by;
            var ny = az * bx - ax * bz;
            var nz = ax * by - ay * bx;
            var length = Math.sqrt(nx * nx + ny * ny + nz * nz) || 1;

            return [nx / length, ny / length, nz / length];
        };

        var pushTriangles = (keys, face) =>
        {
            var normal = smooth ? faceNormal(keys) : null;

            for (var i = 0; i < keys.length; i++)
            {
                pushVertexKey(keys[i], face);

                if (smooth)
                {
                    var acc = smoothAccum[keys[i]] || (smoothAccum[keys[i]] = [0, 0, 0]);

                    acc[0] += normal[0];
                    acc[1] += normal[1];
                    acc[2] += normal[2];
                }
            }
        };

        mesh.origin = c.origin.slice();

        if (!c.rotation.allEqual(0))
        {
            mesh.rotate = c.rotation.slice();
        }

        for (var i = 0; i < keys.length; i++)
        {
            var face = c.faces[keys[i]];
            var vertexKeys = face.vertices;

            if (vertexKeys.length == 3)
            {
                pushTriangles(vertexKeys, face);
            }
            else if (vertexKeys.length == 4)
            {
                /* Triangulate a quad */
                var sorted = face.getSortedVertices();

                pushTriangles([sorted[0], sorted[1], sorted[2]], face);
                pushTriangles([sorted[0], sorted[2], sorted[3]], face);
            }
        }

        mesh.vertices = vertices;
        mesh.uvs = uvs;

        /* Materials drive per-part textures in BBS, mirroring the FBX/glTF
         * convention (textures/<material>/default.png). Every mesh whose
         * faces share a single texture gets its material - texture index 0
         * included, so models textured on the first texture slot don't fall
         * back to model.png. */
        if (textureUuid)
        {
            var material = legacyMaterialName(textureUuid);

            if (material)
            {
                mesh.material = material;

                if (textureIndexValue > 0)
                {
                    mesh.texture = textureIndexValue;
                }
            }
        }

        /* Smooth-shaded meshes carry per-vertex normals (averaged across
         * the mesh's triangles); Blockbench cubes are never smoothed. */
        if (smooth)
        {
            var normals = [];

            for (var i = 0; i < pushedKeys.length; i++)
            {
                var acc = smoothAccum[pushedKeys[i]];
                var length = Math.sqrt(acc[0] * acc[0] + acc[1] * acc[1] + acc[2] * acc[2]) || 1;

                normals.push(acc[0] / length);
                normals.push(acc[1] / length);
                normals.push(acc[2] / length);
            }

            mesh.normals = normals;
        }

        return mesh;
    }

    /* Animation exporting code */

    function createAnimation(a)
    {
        var animation = {
            groups: {}
        };

        animation.duration = a.length;

        for (let key in a.animators)
        {
            var animator = a.animators[key];
            var group = createAnimationGroup(animator);

            if (Object.keys(group).length > 0)
            {
                animation.groups[animator.name] = group;
            }
        }

        return animation;
    }

    function createAnimationGroup(a)
    {
        var group = {};
        var translate = createAnimationKeyframes(a.position, "p");
        var scale = createAnimationKeyframes(a.scale, "s");
        var rotate = createAnimationKeyframes(a.rotation, "r");

        if (translate.length > 0) group.translate = translate;
        if (scale.length > 0) group.scale = scale;
        if (rotate.length > 0) group.rotate = rotate;

        return group;
    }

    function createAnimationKeyframes(g, typeGroup)
    {
        if (!g)
        {
            return [];
        }

        var keyframes = [];

        for (let i = 0; i < g.length; i++)
        {
            var keyframe = g[i];
            var data = keyframe.data_points[0];
            var out = [
                keyframe.time,
                keyframe.interpolation,
                getExpression(data, "x", typeGroup === "r" || typeGroup === "p"), getExpression(data, "y", typeGroup === "r"), getExpression(data, "z", false)
            ];

            keyframes.push(out);
        }

        return keyframes;
    }

    function invertMolang(value)
    {
        if (typeof value === "number")
        {
            return -value;
        }

        if (typeof value === "string")
        {
            value = value.trim();

            // Toggle double-negation: -(expr) -> expr
            if (value.startsWith("-(") && value.endsWith(")"))
            {
                return value.slice(2, -1);
            }

            // Toggle simple leading minus on a plain token: -query.x -> query.x
            if (value.startsWith("-") && !/\s/.test(value.slice(1)) && !value.slice(1).startsWith("("))
            {
                return value.slice(1);
            }

            // Wrap everything else in a negation: query.x -> -(query.x)
            return "-(" + value + ")";
        }

        return value;
    }

    function getExpression(data, component, invert)
    {
        var value = data[component] || 0;
        var parsed = parseFloat(value);
        var inverter = invert && Blockbench.isNewerThan('4.99') ? invertMolang : (v) => v;

        if (!isNaN(value) && !isNaN(parsed))
        {
            return inverter(parsed);
        }

        if (typeof value === "string")
        {
            value = value.trim();

            if (!value)
            {
                return 0;
            }
        }

        return inverter(value);
    }

    /**
     * Whether the current project actually contains a real Blockbench 5
     * Armature (i.e. the user built a rig on purpose). Plain Group/Cube/Mesh
     * hierarchies -- the traditional Java/Bedrock-style models -- have none,
     * and should be exported as their own group/mesh nodes, not force-baked
     * into a single skinned mesh.
     */
    function hasArmatureElements()
    {
        if (typeof Armature !== "undefined" && Array.isArray(Armature.all))
        {
            return Armature.all.length > 0;
        }

        function scan(nodes)
        {
            for (let i = 0; i < (nodes || []).length; i++)
            {
                var node = nodes[i];

                if (node && node.type === "armature")
                {
                    return true;
                }

                if (node && Array.isArray(node.children) && scan(node.children))
                {
                    return true;
                }
            }

            return false;
        }

        return scan(typeof Outliner !== "undefined" ? Outliner.root : []);
    }

    /**
     * Non-armature projects export through the legacy BBS exporter format
     * (the original BBS Ex/importer plugin, "0.7.2"): one group per outliner
     * group with cubes/meshes, a single texture size and plain keyframe
     * animations.  Importing such a file restores the exact group hierarchy,
     * so exporting and importing a non-armature model round-trips cleanly
     * (no meshes dumped at the outliner root, no duplicate "_1" names).
     *
     * Multi-texture models keep per-face UVs from each cube's own texture,
     * plus a per-cube/per-mesh texture index extension (cube.textures /
     * mesh.texture) pointing into the textures stored alongside the file;
     * single-texture output stays byte-identical to the legacy exporter.
     */
    function compileLegacy()
    {
        legacyMaterialMap = null;

        function findTextureSize()
        {
            var c = Cube.all;
            var keys = Object.keys(sides);

            for (var i = 0; i < c.length; i++)
            {
                var cube = c[i];

                for (var j = 0; j < keys.length; j++)
                {
                    var face = cube.faces[keys[j]];

                    if (face)
                    {
                        var textureUuid = face.texture;

                        for (var k = 0; k < Texture.all.length; k++)
                        {
                            var texture = Texture.all[k];

                            if (texture && texture.uuid == textureUuid)
                            {
                                return [texture.uv_width, texture.uv_height];
                            }
                        }
                    }
                }
            }

            /* Mesh-only projects have no cubes to sample; fall back to the
             * first mesh face's texture size. */
            if (typeof Mesh !== "undefined" && Array.isArray(Mesh.all))
            {
                for (var i = 0; i < Mesh.all.length; i++)
                {
                    var mesh = Mesh.all[i];

                    for (var key in mesh.faces)
                    {
                        var face = mesh.faces[key];
                        var textureUuid = face && face.texture;

                        for (var k = 0; k < Texture.all.length; k++)
                        {
                            var texture = Texture.all[k];

                            if (texture && texture.uuid == textureUuid)
                            {
                                return [texture.uv_width, texture.uv_height];
                            }
                        }
                    }
                }
            }

            return null;
        }

        var output = {
            version: "0.7.2",
            animations: {}
        };

        /* Embedded textures, mirroring the armature (S&B) path: the game
         * extracts them into the model folder on load (model.png for the
         * primary, textures/<material>/default.png for the rest), so a
         * single .bbs.json carries the whole model. Kept in Texture.all
         * order so the texture indices used by cube.textures / mesh.texture
         * stay aligned on import. */
        var embeddedTextures = [];

        if (typeof Texture !== "undefined")
        {
            Texture.all.forEach((t) =>
            {
                if (t.error || !t.name)
                {
                    return;
                }

                /* Same mechanism as Blockbench's built-in glTF exporter
                 * (the armature path): the texture's canvas holds the
                 * loaded image no matter how the texture was linked
                 * (external file, blob URL, ...), where the source string
                 * may be empty or unusable. */
                var source = null;

                if (typeof t.source === "string" && t.source.startsWith("data:"))
                {
                    source = t.source;
                }
                else if (t.canvas && typeof t.canvas.toDataURL === "function"
                        && (t.canvas.width > 16 || !(t.width > 16)))
                {
                    source = t.canvas.toDataURL("image/png");
                }
                else if (t.source && typeof t.source.toDataURL === "function")
                {
                    source = t.source.toDataURL("image/png");
                }

                if (!source)
                {
                    return;
                }

                var name = legacyMaterialName(t.uuid)
                        || (t.name.endsWith(".png") ? t.name : t.name + ".png").replace(/[\\/]/g, "_");

                embeddedTextures.push({name: name, source: source});
            });

            if (embeddedTextures.length > 0)
            {
                output.textures = embeddedTextures;
                console.log("[BBS S&B] Embedded " + embeddedTextures.length + " texture(s): " + embeddedTextures.map(t => t.name).join(", "));
            }
            else
            {
                console.warn("[BBS S&B] No textures embedded - no data: URLs available in Texture.all");
            }
        }

        if (lastOptions.model)
        {
            var texture = [Project.texture_width, Project.texture_height];
            var textureSize = findTextureSize();

            if (textureSize)
            {
                texture = textureSize;
            }

            output.model = {
                groups: createHierarchy(Outliner.root),
                texture: texture
            };
        }

        if (lastOptions.animations)
        {
            Animation.all.forEach(animation =>
            {
                output.animations[animation.name] = createAnimation(animation);
            });
        }

        return output;
    }

    /**
     * Compile through Blockbench's own glTF 2.0 exporter.  This is important:
     * the built-in exporter is the canonical implementation of Blockbench 5's
     * Armature, ArmatureBone, vertex-weight, bind-matrix and sampled-animation
     * APIs.  Keeping its scene intact means BBS S&B gets every one of those
     * features without maintaining a second, subtly different skinning path.
     *
     * The `armature` flag is only turned on when the project actually has a
     * real Armature rig. Projects without one are handled by
     * {@link compileLegacy} above, which reproduces the original BBS
     * exporter's format so non-armature models round-trip with their group
     * hierarchy intact.
     */
    async function compile()
    {
        if (!hasArmatureElements())
        {
            return compileLegacy();
        }

        var codecs = typeof Codecs !== "undefined" ? Codecs : window.Codecs;
        var gltfCodec = codecs && codecs.gltf;

        if (!gltfCodec || typeof gltfCodec.compile !== "function")
        {
            throw new Error("BBS S&B requires Blockbench 5.0 or newer with the built-in glTF exporter.");
        }

        var exportScale = Number(Settings.get("model_export_scale")) || 16;
        var previousTime = typeof Timeline !== "undefined" ? Timeline.time : 0;
        var compiled;

        try
        {
            compiled = await gltfCodec.compile({
                encoding: "ascii",
                scale: exportScale,
                embed_textures: true,
                armature: true,
                animations: !!lastOptions.animations
            });
        }
        finally
        {
            if (typeof Timeline !== "undefined")
            {
                Timeline.time = previousTime;
            }
        }

        var scene = typeof compiled === "string" ? JSON.parse(compiled) : compiled;

        if (!scene || !scene.asset || scene.asset.version !== "2.0")
        {
            throw new Error("Blockbench did not produce a valid glTF 2.0 scene.");
        }

        /* Animation-only exports keep the node hierarchy but omit renderable
         * geometry.  Accessors/buffers are intentionally retained because
         * animation tracks can share the same embedded binary buffer. */
        if (!lastOptions.model)
        {
            if (Array.isArray(scene.nodes))
            {
                scene.nodes.forEach(node => delete node.mesh);
            }

            delete scene.meshes;
            delete scene.materials;
            delete scene.textures;
            delete scene.images;
            delete scene.samplers;
        }

        return {
            version: "1.0.0",
            format: "bbs_snb",
            exporter: {
                name: "BBS S&B",
                author: "glaxium"
            },
            settings: {
                smooth_shading: !!lastOptions.smoothShading,
                export_scale: exportScale,
                armature: true
            },
            /* Editor-only hints. Blockbench's glTF exporter writes UVs in the
             * glTF convention (origin top-left, V down), which is exactly what
             * Blockbench meshes use, so imports must NOT flip V. Runtime BBS
             * ignores this object. */
            editor_import: {
                flip_uv_v: true
            },
            scene: scene
        };
    }

    function compileFirstCubes()
    {
        var group = Group.selected;
        var cubes = [];

        if (group)
        {
            for (var child of group.children)
            {
                if (child.type === "cube")
                {
                    cubes.push(createCube(child));
                }
            }
        }

        return cubes;
    }

    /* S&B runtime package importing
     *
     * S&B embeds the complete glTF scene produced by Blockbench.  Blockbench's
     * regular glTF importer deliberately ignores armatures, so importing the
     * wrapper through that codec would lose the most important data.  The
     * converter below reads the glTF accessors directly and builds a normal
     * free-format .bbmodel blueprint.  Passing that blueprint to Blockbench's
     * project codec gives us editable meshes, armatures, vertex weights,
     * textures and animation keyframes using Blockbench's native objects.
     */

    function decodeDataUri(uri)
    {
        if (typeof uri !== "string" || !uri.startsWith("data:"))
        {
            throw new Error("S&B imports require embedded buffers and textures.");
        }

        var comma = uri.indexOf(",");
        var header = uri.slice(5, comma);
        var body = uri.slice(comma + 1);

        if (header.includes(";base64"))
        {
            if (typeof Buffer !== "undefined")
            {
                return new Uint8Array(Buffer.from(body, "base64"));
            }

            var binary = atob(body);
            var bytes = new Uint8Array(binary.length);

            for (var i = 0; i < binary.length; i++)
            {
                bytes[i] = binary.charCodeAt(i);
            }

            return bytes;
        }

        var decoded = decodeURIComponent(body);
        var output = new Uint8Array(decoded.length);

        for (var i = 0; i < decoded.length; i++)
        {
            output[i] = decoded.charCodeAt(i) & 255;
        }

        return output;
    }

    function encodeBase64(bytes)
    {
        if (typeof Buffer !== "undefined")
        {
            return Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength).toString("base64");
        }

        var chunks = [];
        var chunkSize = 0x8000;

        for (var i = 0; i < bytes.length; i += chunkSize)
        {
            chunks.push(String.fromCharCode.apply(null, bytes.subarray(i, i + chunkSize)));
        }

        return btoa(chunks.join(""));
    }

    function componentInfo(componentType)
    {
        var types = {
            5120: {size: 1, getter: "getInt8", signed: true, max: 127},
            5121: {size: 1, getter: "getUint8", signed: false, max: 255},
            5122: {size: 2, getter: "getInt16", signed: true, max: 32767},
            5123: {size: 2, getter: "getUint16", signed: false, max: 65535},
            5125: {size: 4, getter: "getUint32", signed: false, max: 4294967295},
            5126: {size: 4, getter: "getFloat32", float: true}
        };

        if (!types[componentType])
        {
            throw new Error("Unsupported glTF accessor component type: " + componentType);
        }

        return types[componentType];
    }

    function accessorWidth(type)
    {
        return {
            SCALAR: 1,
            VEC2: 2,
            VEC3: 3,
            VEC4: 4,
            MAT2: 4,
            MAT3: 9,
            MAT4: 16
        }[type];
    }

    function createSceneReader(scene)
    {
        var buffers = (scene.buffers || []).map(buffer => decodeDataUri(buffer.uri));
        var accessorCache = {};

        function readComponent(view, offset, info, normalized)
        {
            var value = view[info.getter](offset, true);

            if (normalized && !info.float)
            {
                if (info.signed)
                {
                    value = Math.max(value / info.max, -1);
                }
                else
                {
                    value /= info.max;
                }
            }

            return value;
        }

        function readAccessor(index)
        {
            if (accessorCache[index])
            {
                return accessorCache[index];
            }

            var accessor = scene.accessors && scene.accessors[index];

            if (!accessor)
            {
                throw new Error("Missing glTF accessor " + index + ".");
            }

            var width = accessorWidth(accessor.type);
            var info = componentInfo(accessor.componentType);
            var result = new Array(accessor.count);

            for (var i = 0; i < accessor.count; i++)
            {
                result[i] = new Array(width).fill(0);
            }

            if (accessor.bufferView !== undefined)
            {
                var bufferView = scene.bufferViews[accessor.bufferView];
                var bytes = buffers[bufferView.buffer];
                var data = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
                var start = (bufferView.byteOffset || 0) + (accessor.byteOffset || 0);
                var stride = bufferView.byteStride || width * info.size;

                for (var i = 0; i < accessor.count; i++)
                {
                    for (var c = 0; c < width; c++)
                    {
                        result[i][c] = readComponent(data, start + i * stride + c * info.size, info, accessor.normalized);
                    }
                }
            }

            if (accessor.sparse)
            {
                var sparse = accessor.sparse;
                var indexInfo = componentInfo(sparse.indices.componentType);
                var indexView = scene.bufferViews[sparse.indices.bufferView];
                var indexBytes = buffers[indexView.buffer];
                var indexData = new DataView(indexBytes.buffer, indexBytes.byteOffset, indexBytes.byteLength);
                var indexStart = (indexView.byteOffset || 0) + (sparse.indices.byteOffset || 0);
                var valueView = scene.bufferViews[sparse.values.bufferView];
                var valueBytes = buffers[valueView.buffer];
                var valueData = new DataView(valueBytes.buffer, valueBytes.byteOffset, valueBytes.byteLength);
                var valueStart = (valueView.byteOffset || 0) + (sparse.values.byteOffset || 0);

                for (var i = 0; i < sparse.count; i++)
                {
                    var target = readComponent(indexData, indexStart + i * indexInfo.size, indexInfo, false);

                    for (var c = 0; c < width; c++)
                    {
                        result[target][c] = readComponent(valueData, valueStart + (i * width + c) * info.size, info, accessor.normalized);
                    }
                }
            }

            accessorCache[index] = result;
            return result;
        }

        function readBufferView(index)
        {
            var view = scene.bufferViews[index];
            var bytes = buffers[view.buffer];
            var start = view.byteOffset || 0;

            return bytes.subarray(start, start + view.byteLength);
        }

        return {
            accessor: readAccessor,
            bufferView: readBufferView
        };
    }

    function imageDimensions(bytes, mimeType)
    {
        if (mimeType === "image/png" && bytes.length >= 24)
        {
            return [
                (bytes[16] << 24 | bytes[17] << 16 | bytes[18] << 8 | bytes[19]) >>> 0,
                (bytes[20] << 24 | bytes[21] << 16 | bytes[22] << 8 | bytes[23]) >>> 0
            ];
        }

        if ((mimeType === "image/jpeg" || mimeType === "image/jpg") && bytes.length >= 4)
        {
            var offset = 2;

            while (offset + 9 < bytes.length)
            {
                if (bytes[offset] !== 255)
                {
                    offset++;
                    continue;
                }

                var marker = bytes[offset + 1];
                var length = bytes[offset + 2] << 8 | bytes[offset + 3];

                if ([192, 193, 194, 195, 197, 198, 199, 201, 202, 203, 205, 206, 207].includes(marker))
                {
                    return [bytes[offset + 7] << 8 | bytes[offset + 8], bytes[offset + 5] << 8 | bytes[offset + 6]];
                }

                offset += Math.max(length + 2, 2);
            }
        }

        return [64, 64];
    }

    function cleanName(name, fallback)
    {
        name = typeof name === "string" ? name.trim() : "";
        return name || fallback;
    }

    function quaternionToDegrees(quaternion)
    {
        var q = new THREE.Quaternion().fromArray(quaternion || [0, 0, 0, 1]).normalize();
        var euler = new THREE.Euler().setFromQuaternion(q, Formats.free.euler_order || "ZYX");

        return [euler.x, euler.y, euler.z].map(value => Math.round(value * 180 / Math.PI * 1000000) / 1000000);
    }

    function nodeTransform(node)
    {
        var translation = new THREE.Vector3();
        var rotation = new THREE.Quaternion();
        var scale = new THREE.Vector3(1, 1, 1);

        if (Array.isArray(node.matrix))
        {
            new THREE.Matrix4().fromArray(node.matrix).decompose(translation, rotation, scale);
        }
        else
        {
            translation.fromArray(node.translation || [0, 0, 0]);
            rotation.fromArray(node.rotation || [0, 0, 0, 1]).normalize();
            scale.fromArray(node.scale || [1, 1, 1]);
        }

        return {translation: translation, rotation: rotation, scale: scale};
    }

    function primitiveTriangles(primitive, reader, vertexCount)
    {
        var indices;

        if (primitive.indices !== undefined)
        {
            indices = reader.accessor(primitive.indices).map(value => value[0]);
        }
        else
        {
            indices = Array.from({length: vertexCount}, (_, index) => index);
        }

        var mode = primitive.mode === undefined ? 4 : primitive.mode;
        var triangles = [];

        if (mode === 4)
        {
            for (var i = 0; i + 2 < indices.length; i += 3)
            {
                triangles.push([indices[i], indices[i + 1], indices[i + 2]]);
            }
        }
        else if (mode === 5)
        {
            for (var i = 2; i < indices.length; i++)
            {
                triangles.push(i % 2 ? [indices[i - 1], indices[i - 2], indices[i]] : [indices[i - 2], indices[i - 1], indices[i]]);
            }
        }
        else if (mode === 6)
        {
            for (var i = 2; i < indices.length; i++)
            {
                triangles.push([indices[0], indices[i - 1], indices[i]]);
            }
        }

        return triangles.filter(triangle => triangle[0] !== triangle[1] && triangle[1] !== triangle[2] && triangle[2] !== triangle[0]);
    }

    function materialImageIndex(scene, materialIndex)
    {
        var material = scene.materials && scene.materials[materialIndex];
        var textureIndex = material && material.pbrMetallicRoughness && material.pbrMetallicRoughness.baseColorTexture && material.pbrMetallicRoughness.baseColorTexture.index;
        var texture = textureIndex !== undefined && scene.textures && scene.textures[textureIndex];

        return texture && texture.source !== undefined ? texture.source : -1;
    }

    function convertSnbTextures(scene, reader)
    {
        var dimensions = [];
        var textures = (scene.images || []).map((image, index) =>
        {
            var mimeType = image.mimeType || (typeof image.uri === "string" && image.uri.match(/^data:([^;,]+)/) || [])[1] || "image/png";
            var source;
            var bytes;

            if (typeof image.uri === "string" && image.uri.startsWith("data:"))
            {
                source = image.uri;
                bytes = decodeDataUri(image.uri);
            }
            else if (image.bufferView !== undefined)
            {
                bytes = reader.bufferView(image.bufferView);
                source = "data:" + mimeType + ";base64," + encodeBase64(bytes);
            }
            else
            {
                throw new Error("Texture " + (image.name || index) + " is not embedded in this S&B file.");
            }

            var size = imageDimensions(bytes, mimeType);
            dimensions[index] = size;
            var extension = mimeType.split("/")[1].replace("jpeg", "jpg");

            return {
                uuid: guid(),
                name: cleanName(image.name, "texture_" + index + "." + extension),
                source: source,
                internal: true,
                saved: false,
                uv_width: size[0],
                uv_height: size[1]
            };
        });

        return {textures: textures, dimensions: dimensions};
    }

    function convertSnbPackage(json, fileName)
    {
        if (!json || json.format !== "bbs_snb" || !json.scene)
        {
            throw new Error("This is not a BBS S&B model package.");
        }

        var scene = json.scene;

        if (!scene.asset || scene.asset.version !== "2.0")
        {
            throw new Error("The S&B package does not contain a valid glTF 2.0 scene.");
        }

        var reader = createSceneReader(scene);
        var exportScale = Number(json.settings && json.settings.export_scale) || 16;
        /* Packages converted directly from Blender glTF keep glTF metre-sized
         * coordinates and used export_scale=1 for the BBS runtime loader.  A
         * Blockbench project uses 16 units per model unit, so restore those
         * converter packages at 16x.  Files exported by this plugin already
         * record the Blockbench export scale and continue to round-trip with
         * that exact value. */
        var directGltfImport = json.exporter && json.exporter.source === "glb-converter";
        var importScale = directGltfImport ? 16 : exportScale;
        /* Editor-only fixes are opt-in package metadata. Runtime BBS ignores
         * this object, and packages without it keep the original importer
         * behavior. This prevents one Blender model's axis/UV quirks from
         * changing every OBJ/BOBJ/FBX/glTF/S&B model. */
        var editorImport = json.editor_import || {};
        /* Blockbench's glTF exporter (used for every BBS S&B export) writes
         * UVs in the glTF convention -- origin top-left, V down -- which is
         * exactly what Blockbench meshes use, so V must NOT be flipped on
         * import. The flip below applies only to packages that were not
         * exported by this plugin (Blender/OBJ converted glTF, whose V axis
         * is inverted), or when flip_uv_v is explicitly set to false. */
        var ownExport = json.exporter && json.exporter.name === "BBS S&B";
        var flipUvV = editorImport.flip_uv_v === true || (ownExport && editorImport.flip_uv_v !== false);
        var boneAxisCorrections = editorImport.bone_axis_corrections || {};
        var hasBoneAxisCorrections = Object.keys(boneAxisCorrections).length > 0;
        var shading = json.settings && json.settings.smooth_shading ? "smooth" : "flat";
        var textureData = convertSnbTextures(scene, reader);
        var elements = [];
        var outliner = [];
        var nodeParents = {};
        var nodeToBones = {};
        var bonesBySkin = [];
        var boneTransformsBySkin = [];
        var boneImportData = {};
        var armaturesBySkin = [];
        var meshRecords = [];

        (scene.nodes || []).forEach((node, nodeIndex) =>
        {
            (node.children || []).forEach(child => nodeParents[child] = nodeIndex);
        });

        function prepareBoneTransforms(joints, jointSet, roots)
        {
            var transforms = {};

            joints.forEach(joint =>
            {
                var raw = nodeTransform(scene.nodes[joint] || {});
                transforms[joint] = {
                    translation: raw.translation.clone(),
                    rotation: raw.rotation.clone(),
                    scale: raw.scale.clone(),
                    parentCorrection: new THREE.Quaternion(),
                    correction: new THREE.Quaternion()
                };
            });

            joints.forEach(joint =>
            {
                var child = ((scene.nodes[joint] || {}).children || []).find(index => jointSet.has(index));
                transforms[joint].length = child === undefined ? 4 / importScale : transforms[child].translation.length();
            });

            if (!hasBoneAxisCorrections)
            {
                return transforms;
            }

            function visit(joint, parentCorrection)
            {
                var transform = transforms[joint];
                var childJoints = ((scene.nodes[joint] || {}).children || []).filter(index => jointSet.has(index));
                var boneName = cleanName((scene.nodes[joint] || {}).name, "bone_" + joint);
                var settings = boneAxisCorrections[boneName] || boneAxisCorrections[String(joint)] || {};
                var rotation = Array.isArray(settings.rotation) ? settings.rotation : [0, 0, 0];
                var axisCorrection = new THREE.Quaternion().setFromEuler(new THREE.Euler(
                    (Number(rotation[0]) || 0) * Math.PI / 180,
                    (Number(rotation[1]) || 0) * Math.PI / 180,
                    (Number(rotation[2]) || 0) * Math.PI / 180,
                    "XYZ"
                ));
                var inverseCorrection = axisCorrection.clone().invert();

                transform.parentCorrection.copy(parentCorrection || new THREE.Quaternion());
                transform.correction.copy(axisCorrection);
                transform.editorSettings = settings;
                transform.rotation.multiply(axisCorrection).normalize();

                if (Number.isFinite(Number(settings.length)) && Number(settings.length) > 0)
                {
                    transform.length = Number(settings.length) / importScale;
                }

                childJoints.forEach(child =>
                {
                    transforms[child].translation.applyQuaternion(inverseCorrection);
                    transforms[child].rotation.premultiply(inverseCorrection).normalize();
                });

                childJoints.forEach(child => visit(child, axisCorrection));
            }

            roots.forEach(root => visit(root, new THREE.Quaternion()));
            return transforms;
        }

        function makeBone(skinIndex, jointNodeIndex, jointSet)
        {
            if (bonesBySkin[skinIndex][jointNodeIndex])
            {
                return bonesBySkin[skinIndex][jointNodeIndex];
            }

            var node = scene.nodes[jointNodeIndex] || {};
            var transform = boneTransformsBySkin[skinIndex][jointNodeIndex];
            var length = transform.length * importScale;
            var editorSettings = transform.editorSettings || {};
            var width = Number.isFinite(Number(editorSettings.width)) && Number(editorSettings.width) > 0
                ? Number(editorSettings.width)
                : Math.max(Math.min(length * 0.25, 4), 0.25);
            var connected = typeof editorSettings.connected === "boolean" ? editorSettings.connected : true;
            var bone = {
                uuid: guid(),
                type: "armature_bone",
                name: cleanName(node.name, "bone_" + jointNodeIndex),
                origin: transform.translation.toArray().map(value => value * importScale),
                rotation: quaternionToDegrees(transform.rotation.toArray()),
                length: Math.max(length || 4, 0.01),
                width: width,
                connected: connected,
                color: jointNodeIndex % 8,
                vertex_weights: {},
                children: [],
                export: true,
                visibility: true,
                locked: false
            };

            bonesBySkin[skinIndex][jointNodeIndex] = bone;
            boneImportData[bone.uuid] = transform;
            if (!nodeToBones[jointNodeIndex]) nodeToBones[jointNodeIndex] = [];
            nodeToBones[jointNodeIndex].push(bone);
            elements.push(bone);
            return bone;
        }

        function boneOutliner(skinIndex, jointNodeIndex, jointSet)
        {
            var bone = makeBone(skinIndex, jointNodeIndex, jointSet);
            var children = (scene.nodes[jointNodeIndex].children || [])
                .filter(child => jointSet.has(child))
                .map(child => boneOutliner(skinIndex, child, jointSet));

            bone.children = children.map(child => child.uuid);

            return {
                uuid: bone.uuid,
                isOpen: true,
                children: children
            };
        }

        (scene.skins || []).forEach((skin, skinIndex) =>
        {
            var joints = skin.joints || [];
            var jointSet = new Set(joints);
            var roots = joints.filter(joint => !jointSet.has(nodeParents[joint]));
            bonesBySkin[skinIndex] = {};
            boneTransformsBySkin[skinIndex] = prepareBoneTransforms(joints, jointSet, roots);
            joints.forEach(joint => makeBone(skinIndex, joint, jointSet));
            var armature = {
                uuid: guid(),
                type: "armature",
                name: cleanName(skin.name, "Armature_" + skinIndex),
                origin: [0, 0, 0],
                children: [],
                export: true,
                visibility: true,
                locked: false
            };
            var rootTrees = roots.map(root => boneOutliner(skinIndex, root, jointSet));

            armaturesBySkin[skinIndex] = {element: armature, roots: rootTrees};
            elements.push(armature);
        });

        function makeMesh(nodeIndex, meshIndex, skinIndex)
        {
            var gltfMesh = scene.meshes[meshIndex];
            var node = scene.nodes[nodeIndex] || {};
            var transform = nodeTransform(node);
            var mesh = {
                uuid: guid(),
                type: "mesh",
                name: cleanName(gltfMesh.name || node.name, "mesh_" + meshIndex),
                origin: [0, 0, 0],
                rotation: [0, 0, 0],
                color: meshIndex % 8,
                shading: shading,
                export: true,
                visibility: true,
                locked: false,
                render_order: "default",
                vertices: {},
                faces: {}
            };
            var vertexNumber = 0;
            var faceNumber = 0;

            (gltfMesh.primitives || []).forEach((primitive, primitiveIndex) =>
            {
                if (!primitive.attributes || primitive.attributes.POSITION === undefined)
                {
                    return;
                }

                var positions = reader.accessor(primitive.attributes.POSITION);
                var uvs = primitive.attributes.TEXCOORD_0 !== undefined ? reader.accessor(primitive.attributes.TEXCOORD_0) : [];
                var joints0 = primitive.attributes.JOINTS_0 !== undefined ? reader.accessor(primitive.attributes.JOINTS_0) : [];
                var weights0 = primitive.attributes.WEIGHTS_0 !== undefined ? reader.accessor(primitive.attributes.WEIGHTS_0) : [];
                var joints1 = primitive.attributes.JOINTS_1 !== undefined ? reader.accessor(primitive.attributes.JOINTS_1) : [];
                var weights1 = primitive.attributes.WEIGHTS_1 !== undefined ? reader.accessor(primitive.attributes.WEIGHTS_1) : [];
                var vertexKeys = [];
                var imageIndex = materialImageIndex(scene, primitive.material);
                var textureSize = textureData.dimensions[imageIndex] || [64, 64];

                positions.forEach((position, localIndex) =>
                {
                    var key = "v" + vertexNumber++;
                    var vector = new THREE.Vector3(position[0], position[1], position[2]);

                    vector.multiply(transform.scale).applyQuaternion(transform.rotation).add(transform.translation).multiplyScalar(importScale);
                    mesh.vertices[key] = vector.toArray();
                    vertexKeys[localIndex] = key;

                    if (skinIndex !== undefined && bonesBySkin[skinIndex])
                    {
                        var skin = scene.skins[skinIndex];
                        var allJoints = (joints0[localIndex] || []).concat(joints1[localIndex] || []);
                        var allWeights = (weights0[localIndex] || []).concat(weights1[localIndex] || []);

                        allJoints.forEach((jointSlot, influenceIndex) =>
                        {
                            var weight = Number(allWeights[influenceIndex]) || 0;
                            var jointNode = skin.joints[jointSlot];
                            var bone = bonesBySkin[skinIndex][jointNode];

                            if (bone && weight > 0.000001)
                            {
                                bone.vertex_weights[mesh.uuid.substring(0, 6) + ":" + key] = weight;
                            }
                        });
                    }
                });

                primitiveTriangles(primitive, reader, positions.length).forEach(triangle =>
                {
                    var keys = triangle.map(index => vertexKeys[index]);
                    var faceUvs = {};

                    triangle.forEach((index, corner) =>
                    {
                        var uv = uvs[index] || [0, 0];
                        var v = flipUvV ? uv[1] : 1 - uv[1];
                        faceUvs[keys[corner]] = [uv[0] * textureSize[0], v * textureSize[1]];
                    });

                    mesh.faces["f" + faceNumber++] = {
                        vertices: keys,
                        uv: faceUvs,
                        /* false means an editable, visible untextured face in
                         * Blockbench. null intentionally hides a face. */
                        texture: imageIndex >= 0 ? imageIndex : false
                    };
                });
            });

            if (Object.keys(mesh.vertices).length)
            {
                elements.unshift(mesh);
                meshRecords.push({mesh: mesh, skin: skinIndex});
            }
        }

        (scene.nodes || []).forEach((node, nodeIndex) =>
        {
            if (node.mesh !== undefined && scene.meshes && scene.meshes[node.mesh])
            {
                makeMesh(nodeIndex, node.mesh, node.skin);
            }
        });

        meshRecords.forEach(record =>
        {
            if (record.skin !== undefined && armaturesBySkin[record.skin])
            {
                armaturesBySkin[record.skin].element.children.push(record.mesh.uuid);
            }
            else
            {
                outliner.push(record.mesh.uuid);
            }
        });

        armaturesBySkin.forEach(armatureRecord =>
        {
            if (!armatureRecord) return;
            armatureRecord.element.children.push(...armatureRecord.roots.map(root => root.uuid));
            outliner.push({
                uuid: armatureRecord.element.uuid,
                isOpen: true,
                children: armatureRecord.element.children
                    .filter(uuid => meshRecords.some(record => record.mesh.uuid === uuid))
                    .concat(armatureRecord.roots)
            });
        });

        function deltaRotation(restRotation, absoluteRotation)
        {
            var rest = new THREE.Quaternion().fromArray(restRotation || [0, 0, 0, 1]).normalize();
            var absolute = new THREE.Quaternion().fromArray(absoluteRotation || [0, 0, 0, 1]).normalize();
            var delta = rest.invert().multiply(absolute).normalize();
            return quaternionToDegrees(delta.toArray());
        }

        var animations = (scene.animations || []).map((gltfAnimation, animationIndex) =>
        {
            var animation = {
                uuid: guid(),
                name: cleanName(gltfAnimation.name, "animation_" + animationIndex),
                loop: "once",
                override: false,
                length: 0,
                snapping: 20,
                animators: {}
            };

            (gltfAnimation.channels || []).forEach(channel =>
            {
                var target = channel.target || {};
                var targetBones = nodeToBones[target.node] || [];
                var sampler = gltfAnimation.samplers && gltfAnimation.samplers[channel.sampler];

                if (!sampler || !targetBones.length || !["translation", "rotation", "scale"].includes(target.path))
                {
                    return;
                }

                var times = reader.accessor(sampler.input).map(value => value[0]);
                var rawValues = reader.accessor(sampler.output);
                var cubic = sampler.interpolation === "CUBICSPLINE";
                var values = cubic ? times.map((_, index) => rawValues[index * 3 + 1]) : rawValues;
                var interpolation = sampler.interpolation === "STEP" ? "step" : "linear";
                var rest = nodeTransform(scene.nodes[target.node] || {});

                animation.length = Math.max(animation.length, ...times);

                targetBones.forEach(bone =>
                {
                    var importedRest = boneImportData[bone.uuid];

                    if (!animation.animators[bone.uuid])
                    {
                        animation.animators[bone.uuid] = {
                            name: bone.name,
                            type: "armature_bone",
                            quaternion_interpolation: true,
                            keyframes: []
                        };
                    }

                    times.forEach((time, valueIndex) =>
                    {
                        var value = values[valueIndex];
                        var converted;
                        var bbChannel;

                        if (target.path === "translation")
                        {
                            var translated = new THREE.Vector3().fromArray(value);
                            translated.applyQuaternion(importedRest.parentCorrection.clone().invert());
                            translated.sub(importedRest.translation).multiplyScalar(importScale);
                            converted = translated.toArray();
                            bbChannel = "position";
                        }
                        else if (target.path === "rotation")
                        {
                            var rotated = new THREE.Quaternion().fromArray(value).normalize();
                            rotated.premultiply(importedRest.parentCorrection.clone().invert());
                            rotated.multiply(importedRest.correction).normalize();
                            converted = deltaRotation(importedRest.rotation.toArray(), rotated.toArray());
                            bbChannel = "rotation";
                        }
                        else
                        {
                            converted = [
                                value[0] / (rest.scale.x || 1),
                                value[1] / (rest.scale.y || 1),
                                value[2] / (rest.scale.z || 1)
                            ];
                            bbChannel = "scale";
                        }

                        animation.animators[bone.uuid].keyframes.push({
                            channel: bbChannel,
                            time: time,
                            interpolation: interpolation,
                            data_points: [{x: converted[0], y: converted[1], z: converted[2]}]
                        });
                    });
                });
            });

            return animation;
        }).filter(animation => Object.keys(animation.animators).length > 0);

        var resolution = textureData.dimensions[0] || [64, 64];
        var projectName = cleanName(fileName, "S&B model").replace(/\.bbs\.json$/i, "").replace(/\.json$/i, "");

        return {
            meta: {
                format_version: "5.0",
                model_format: "free",
                box_uv: false
            },
            name: projectName,
            resolution: {width: resolution[0], height: resolution[1]},
            elements: elements,
            outliner: outliner,
            textures: textureData.textures,
            animations: animations
        };
    }

    function importSnb(json, file)
    {
        var fileName = file && (file.name || file.path && file.path.split(/[\\/]/).pop());
        var model = convertSnbPackage(json, fileName);

        setupProject(Formats.free);
        Codecs.project.parse(model);
        Project.name = model.name;
        Project.saved = false;
        Canvas.updateAll();

        Blockbench.showQuickMessage(
            "Imported S&B: " + model.elements.filter(element => element.type === "mesh").length +
            " mesh(es), " + model.elements.filter(element => element.type === "armature_bone").length +
            " bone(s), " + model.animations.length + " animation(s)",
            4000
        );
    }

    /* Legacy BBS importing */

    function importBBS(json)
    {
        Undo.initEdit({
            outliner: true,
            animations: []
        });

        try
        {
            if (Array.isArray(json.textures) && typeof Texture !== "undefined")
            {
                json.textures.forEach(t =>
                {
                    if (!t || typeof t.source !== "string" || !t.source.startsWith("data:"))
                    {
                        return;
                    }

                    var bytes = decodeDataUri(t.source);
                    var size = imageDimensions(bytes, "image/png");

                    new Texture({
                        name: t.name,
                        source: t.source,
                        internal: true,
                        saved: false,
                        uv_width: size[0],
                        uv_height: size[1]
                    });
                });
            }

            if (json.model) importModel(json.model);
            if (json.animations) importAnimations(json.animations);
        }
        catch (e)
        {
            console.log(e);
        }

        Undo.finishEdit("Finished importing a BBS model");
        Canvas.updateAll();
    }

    function importAnimations(animations)
    {
        for (var key in animations)
        {
            var animationObject = animations[key];
            var data = {
                name: key,
                length: animationObject.duration
            };

            var animation = new Animation(data).add();
            var groupKeys = Object.keys(animationObject.groups);

            groupKeys.forEach(k => importGroup(k, animationObject.groups[k], animation));
        }
    }

    function importGroup(key, groupObject, animation)
    {
        var group = Group.all.find(o => o.name === key);

        if (!group)
        {
            return;
        }

        var animator = new BoneAnimator(group.uuid, animation, key);

        animation.animators[group.uuid] = animator;

        if (groupObject.translate) importChannel(animator, "position", groupObject.translate);
        if (groupObject.rotate) importChannel(animator, "rotation", groupObject.rotate);
        if (groupObject.scale) importChannel(animator, "scale", groupObject.scale);
    }

    function importChannel(animator, name, channel)
    {
        var invertX = name === "rotation" || name === "position";
        var invertY = name === "rotation";

        channel.forEach(kf =>
        {
            var x = invertX ? invertMolang(kf[2]) : kf[2];
            var y = invertY ? invertMolang(kf[3]) : kf[3];
            var z = kf[4];

            animator.addKeyframe({
                channel: name,
                time: kf[0],
                interpolation: kf[1],
                data_points: [{x: x, y: y, z: z}]
            });
        });
    }

    function importModel(model)
    {
        var texture = model.texture;
        var relations = {};
        var groups = {};

        Project.texture_width = texture[0];
        Project.texture_height = texture[1];

        for (var key in model.groups)
        {
            var groupObject = model.groups[key];
            var data = {
                name: key
            };

            if (groupObject.rotate) data.rotation = groupObject.rotate;
            if (groupObject.origin) data.origin = groupObject.origin;

            var group = new Group(data);

            group.init();

            if (groupObject.parent) relations[key] = groupObject.parent;
            if (groupObject.cubes) groupObject.cubes.forEach(v => importCube(v, group));
            if (groupObject.meshes) groupObject.meshes.forEach(v => importMesh(v, group));

            groups[key] = group;
        }

        for (var key in relations)
        {
            groups[key].addTo(groups[relations[key]]);
        }
    }

    function importCube(cubeObject, group)
    {
        var cube = new Cube({
            origin: cubeObject.origin || [0, 0, 0],
            from: cubeObject.from,
            to: [
                cubeObject.from[0] + cubeObject.size[0],
                cubeObject.from[1] + cubeObject.size[1],
                cubeObject.from[2] + cubeObject.size[2]
            ],
            rotation: cubeObject.rotate || [0, 0, 0],
            inflate: cubeObject.offset || 0
        });

        Object.keys(cubeObject.uvs).forEach(key =>
        {
            var uv = cubeObject.uvs[key];
            var face = cube.faces[sidesInverse[key]];

            face.uv = uv.slice(0, 4);

            if (uv.length >= 5)
            {
                face.rotation = uv[4];
            }
        });

        if (cubeObject.textures)
        {
            Object.keys(cubeObject.textures).forEach(key =>
            {
                var face = cube.faces[sidesInverse[key]];
                var texture = typeof Texture !== "undefined" ? Texture.all[cubeObject.textures[key]] : null;

                if (face && texture)
                {
                    face.texture = texture.uuid;
                }
            });
        }

        cube.init();
        cube.addTo(group);
    }

    function importMesh(meshObject, group)
    {
        var vertices = {};
        var faces = {};

        /* Material names come first: they survive importing into a project
         * whose Texture.all already holds the same textures (embedded
         * textures appended later would shift the raw indices). The index
         * extension is the fallback for files without materials. */
        var textureIndexValue = -1;

        if (typeof meshObject.material === "string")
        {
            var byName = typeof Texture !== "undefined" ? Texture.all.find(t => t && t.name === meshObject.material) : null;

            if (byName)
            {
                for (var i = 0; i < Texture.all.length; i++)
                {
                    if (Texture.all[i] === byName)
                    {
                        textureIndexValue = i;
                        break;
                    }
                }
            }
        }
        else if (typeof meshObject.texture === "number")
        {
            textureIndexValue = meshObject.texture;
        }

        for (var i = 0, c = meshObject.vertices.length / 9; i < c; i++)
        {
            var a1 = [
                meshObject.vertices[i * 9],
                meshObject.vertices[i * 9 + 1],
                meshObject.vertices[i * 9 + 2]
            ];
            var a2 = [
                meshObject.vertices[i * 9 + 3],
                meshObject.vertices[i * 9 + 4],
                meshObject.vertices[i * 9 + 5]
            ];
            var a3 = [
                meshObject.vertices[i * 9 + 6],
                meshObject.vertices[i * 9 + 7],
                meshObject.vertices[i * 9 + 8]
            ];
            var key1 = bbuid(6);
            var key2 = bbuid(6);
            var key3 = bbuid(6);

            vertices[key1] = a1;
            vertices[key2] = a2;
            vertices[key3] = a3;

            var face = {
                uv: {},
                vertices: [key1, key2, key3]
            };

            /* The file stores UVs in the texture's own 0..32 tile space;
             * Blockbench's mesh UV atlas stacks texture i at y in
             * [-32*i, 32-32*i], so shift the imported UVs back into atlas
             * space or the faces would sit on the wrong tile. */
            var uvOffset = textureIndexValue > 0 ? 32 * textureIndexValue : 0;

            face.uv[key1] = [meshObject.uvs[i * 6], meshObject.uvs[i * 6 + 1] + uvOffset];
            face.uv[key2] = [meshObject.uvs[i * 6 + 2], meshObject.uvs[i * 6 + 3] + uvOffset];
            face.uv[key3] = [meshObject.uvs[i * 6 + 4], meshObject.uvs[i * 6 + 5] + uvOffset];

            faces[bbuid(6)] = face;
        }

        var mesh = new Mesh({
            origin: meshObject.origin || [0, 0, 0],
            rotation: meshObject.rotate || [0, 0, 0],
            vertices: vertices,
            faces: faces,
            shading: meshObject.normals ? "smooth" : "flat"
        });

        if (textureIndexValue >= 0)
        {
            var texture = typeof Texture !== "undefined" ? Texture.all[textureIndexValue] : null;

            if (texture)
            {
                Object.keys(faces).forEach(faceKey => faces[faceKey].texture = texture.uuid);
            }
        }

        mesh.init();
        mesh.addTo(group);
    }

    /* Bootstrap */

    function exportFileName()
    {
        var name = Project.name || "model";

        return name.endsWith(".bbs") ? name : name + ".bbs";
    }

    async function compileText()
    {
        return autoStringify(await compile());
    }

    async function downloadExport()
    {
        var content = await compileText();

        Blockbench.export({
            resource_id: "bbs_snb",
            type: "BBS S&B model",
            extensions: ["json"],
            name: exportFileName(),
            content: content,
            savetype: "text"
        });
    }

    var bbsCodec = new Codec("bbs_snb_model", {
        name: "BBS S&B model",
        extension: "json",
        remember: false,
        load_filter: {
            type: "json",
            extensions: ["json"],
            condition: (file) => {
                return file && (file.format === "bbs_snb" || file.model && file.model.groups && file.model.texture);
            }
        },
        load(content, file) {
            if (content && content.format === "bbs_snb")
            {
                importSnb(content, file);
                return;
            }

            if (!Project) setupProject(Formats.free);
            importBBS(content);
        },
        async compile(options) {
            return await compileText();
        },
        fileName() {
            return exportFileName();
        }
    });

    var exportDialog = new Dialog({
        id: "bbs_snb_export_options",
        title: "BBS S&B exporter",
        form: {
            exportModel: {
                label: "Export model data",
                type: "checkbox",
                value: true
            },
            exportAnimations: {
                label: "Export animations",
                type: "checkbox",
                value: true
            },
            smoothShading: {
                label: "Smooth Shading",
                description: "Average normals across connected geometry when BBS loads this model.",
                type: "checkbox",
                value: false
            },
            copyToBuffer: {
                label: "Copy to buffer",
                type: "checkbox",
                value: false
            },
            copyOnlyFirst: {
                label: "Copy first selected group",
                description: "When enabled, copies to the buffer only cubes from the first found group. This option is ignored when Copy to buffer option is disabled!",
                type: "checkbox",
                value: false
            },
            exportAsFolder: {
                label: "Export to folder",
                description: "Write model.bbs.json to a chosen folder. Textures are stored in the folder; BBS extracts them from there if present.",
                type: "checkbox",
                value: false
            }
        },
        onConfirm: async function(formData) {
            this.hide();

            lastOptions.model = formData.exportModel;
            lastOptions.animations = formData.exportAnimations;
            lastOptions.smoothShading = formData.smoothShading;

            try
            {
                if (formData.exportAsFolder)
                {
                    var folder = Blockbench.pickDirectory({
                        title: "Export destination..."
                    });

                    if (folder)
                    {
                        var content = await compileText();
                        var parsed = typeof content === "string" ? JSON.parse(content) : content;

                        Blockbench.writeFile(PathModule.join(folder, "model.bbs.json"), {
                            content: content,
                            savetype: "text"
                        });

                        if (parsed && parsed.format === "bbs_snb")
                        {
                            /* Armature models: store the textures in the
                             * folder; BBS uses them from textures/<name>/
                             * default.png when present. */
                            Texture.all.forEach((t) =>
                            {
                                if (t.error || !t.name)
                                {
                                    return;
                                }

                                var name = t.name.replace(/[\\/]/g, "_");
                                var source = t.source;

                                if (typeof source === "string" && source.startsWith("data:"))
                                {
                                    Blockbench.writeFile(PathModule.join(folder, "textures", name, "default.png"), {
                                        content: source,
                                        savetype: "image"
                                    });
                                }
                                else if (source && typeof source.toDataURL === "function")
                                {
                                    Blockbench.writeFile(PathModule.join(folder, "textures", name, "default.png"), {
                                        content: source.toDataURL("image/png"),
                                        savetype: "image"
                                    });
                                }
                            });
                        }
                        else
                        {
                            /* Legacy models: store the primary texture as
                             * model.png (the model's default) and every other
                             * used texture in its material folder so the game
                             * can render each mesh with its own texture. */
                            var materialUuids = {};

                            if (parsed && parsed.model)
                            {
                                Object.keys(parsed.model.groups).forEach(groupKey =>
                                {
                                    var group = parsed.model.groups[groupKey];

                                    (group.meshes || []).forEach(mesh =>
                                    {
                                        if (typeof mesh.material === "string")
                                        {
                                            Object.keys(legacyMaterials()).forEach(uuid =>
                                            {
                                                if (legacyMaterials()[uuid] === mesh.material)
                                                {
                                                    materialUuids[uuid] = mesh.material;
                                                }
                                            });
                                        }
                                    });
                                });
                            }

                            var sourceOf = (t) =>
                            {
                                if (typeof t.source === "string" && t.source.startsWith("data:"))
                                {
                                    return t.source;
                                }

                                /* Texture canvases hold the loaded image for
                                 * every linking mode (external files, blob
                                 * URLs, ...); the glTF exporter reads from
                                 * them the same way. */
                                if (t.canvas && typeof t.canvas.toDataURL === "function"
                                        && (t.canvas.width > 16 || !(t.width > 16)))
                                {
                                    return t.canvas.toDataURL("image/png");
                                }

                                if (t.source && typeof t.source.toDataURL === "function")
                                {
                                    return t.source.toDataURL("image/png");
                                }

                                return null;
                            };

                            Texture.all.forEach((t, index) =>
                            {
                                if (t.error)
                                {
                                    return;
                                }

                                var source = sourceOf(t);

                                if (!source)
                                {
                                    return;
                                }

                                if (index === 0)
                                {
                                    Blockbench.writeFile(PathModule.join(folder, "model.png"), {
                                        content: source,
                                        savetype: 'image'
                                    });

                                    return;
                                }

                                var material = materialUuids[t.uuid];

                                if (material)
                                {
                                    Blockbench.writeFile(PathModule.join(folder, "textures", material, "default.png"), {
                                        content: source,
                                        savetype: 'image'
                                    });
                                }
                            });
                        }
                    }
                }
                else if (formData.copyToBuffer)
                {
                    var data = formData.copyOnlyFirst ? compileFirstCubes() : await compile();

                    Clipbench.setText(autoStringify(data));
                }
                else
                {
                    await downloadExport();
                }
            }
            catch (error)
            {
                console.error("BBS S&B export failed", error);
                Blockbench.showMessageBox({
                    title: "BBS S&B export failed",
                    message: error && error.message ? error.message : String(error),
                    icon: "error"
                });
            }
        }
    });

    Plugin.register("BBS S&B", {
        title: "BBS S&B",
        author: "glaxium",
        description: "Import and export BBS S&B models. Armature projects use the S&B glTF format; non-armature projects export like the original BBS exporter, restoring the group hierarchy on import. Multi-texture (per-mesh materials), smooth shading and folder export supported.",
        icon: "fa-cubes",
        version: "2.4.1",
        min_version: "5.0.0",
        variant: "both",
        has_changelog: false,
        onload() {
            exportAction = new Action("bbs_snb_export", {
                name: "Export BBS S&B model",
                category: "file",
                description: "Export an armature-ready BBS S&B (.bbs.json) model",
                icon: "fa-file-export",
                click() {
                    exportDialog.show();
                }
            });

            importAction = new Action("bbs_snb_import", {
                name: "Import BBS S&B model",
                category: "file",
                description: "Import an editable BBS S&B or legacy BBS (.bbs.json) model",
                icon: "fa-file-import",
                click() {
                    Blockbench.import({
                        extensions: ['bbs.json'],
                        type: 'BBS S&B model',
                        readtype: 'text',
                    }, (files) => {
                        try
                        {
                            var content = JSON.parse(files[0].content);

                            if (content && content.format === "bbs_snb")
                            {
                                importSnb(content, files[0]);
                            }
                            else
                            {
                                if (!Project) setupProject(Formats.free);
                                importBBS(content);
                            }
                        }
                        catch (error)
                        {
                            console.error("BBS S&B import failed", error);
                            Blockbench.showMessageBox({
                                title: "BBS S&B import failed",
                                message: error && error.message ? error.message : String(error),
                                icon: "error"
                            });
                        }
                    });
                }
            });

            MenuBar.addAction(exportAction, "file.export");
            MenuBar.addAction(importAction, "file.import");
        },
        onunload() {
            exportAction.delete();
            importAction.delete();
            bbsCodec.delete();
        }
    });
})();
