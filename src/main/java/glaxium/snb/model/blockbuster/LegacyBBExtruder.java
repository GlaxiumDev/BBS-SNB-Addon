package glaxium.snb.model.blockbuster;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.resources.Pixels;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.util.math.MatrixStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/** Builds Blockbuster's texture-alpha voxel shell for limbs marked is3D. */
final class LegacyBBExtruder
{
    private static final byte TOP = 1;
    private static final byte BOTTOM = 2;
    private static final byte FRONT = 4;
    private static final byte BACK = 8;
    private static final byte LEFT = 16;
    private static final byte RIGHT = 32;

    /* Weak keys: after a reload the old LegacyBBModel instances become
     * unreachable and their extruded meshes (the expensive part) are
     * collected instead of leaking per reload. All access is synchronized
     * because the loader thread now pre-warms entries that the render
     * thread reads. */
    private static final Map<LegacyBBModel, Map<String, Mesh>> CACHE = new WeakHashMap<>();

    private LegacyBBExtruder() {}

    /**
     * Pre-extrudes every is3D limb with the model's default texture. Runs on
     * the model loader thread right after loading, so the first rendered
     * frames don't stall the render thread on PNG decoding and voxel
     * extrusion. Render-time lazy building stays as a fallback for textures
     * that were not warmed (per-form texture selections).
     */
    static void warm(LegacyBBModel model)
    {
        Link texture = model.defaultTexture();

        if (texture == null)
        {
            return;
        }

        for (Map.Entry<String, BlockbusterModelLoader.LegacyLimb> entry : model.data.limbs.entrySet())
        {
            if (entry.getValue().is3D)
            {
                buildCached(model, entry.getKey(), entry.getValue(), texture);
            }
        }
    }

    private static Map<String, Mesh> cacheMeshes(LegacyBBModel model)
    {
        synchronized (CACHE)
        {
            return CACHE.computeIfAbsent(model, ignored -> new HashMap<>());
        }
    }

    /** Build once per (limb, texture); concurrent builds are allowed, the first result wins. */
    private static Mesh buildCached(LegacyBBModel model, String name, BlockbusterModelLoader.LegacyLimb limb, Link texture)
    {
        String key = name + "\n" + texture;
        Map<String, Mesh> meshes = cacheMeshes(model);

        synchronized (meshes)
        {
            if (meshes.containsKey(key))
            {
                return meshes.get(key);
            }
        }

        Mesh mesh = build(model, limb, texture);

        synchronized (meshes)
        {
            meshes.putIfAbsent(key, mesh);
        }

        return mesh;
    }

    static boolean render(
            LegacyBBModel model, String name, BlockbusterModelLoader.LegacyLimb limb, Link selectedTexture,
            MatrixStack matrices, Supplier<ShaderProgram> shader,
            float red, float green, float blue, float alpha, int light, int overlay)
    {
        Link texture = selectedTexture != null ? selectedTexture : model.defaultTexture();
        if (texture == null) return false;

        Mesh mesh = buildCached(model, name, limb, texture);

        if (mesh == null) return false;

        BufferBuilder buffer = LegacyBBRenderer.begin(VertexFormat.DrawMode.QUADS);
        for (Vertex vertex : mesh.vertices)
        {
            LegacyBBRenderer.vertex(buffer, matrices,
                    vertex.x, vertex.y, vertex.z, vertex.u, vertex.v,
                    vertex.nx, vertex.ny, vertex.nz,
                    red, green, blue, alpha, light, overlay);
        }
        LegacyBBRenderer.draw(buffer, shader);
        return true;
    }

    private static Mesh build(LegacyBBModel model, BlockbusterModelLoader.LegacyLimb limb, Link texture)
    {
        try
        {
            Pixels pixels = BBSModClient.getTextures().getPixels(texture);
            if (pixels == null || pixels.width <= 0 || pixels.height <= 0) return null;

            int baseW = Math.max(1, Math.round(limb.size.x));
            int baseH = Math.max(1, Math.round(limb.size.y));
            int baseD = Math.max(1, Math.round(limb.size.z));
            int sourceScaleX = Math.max(1, pixels.width / Math.max(1, Math.round(model.data.texture.x)));
            int sourceScaleY = Math.max(1, pixels.height / Math.max(1, Math.round(model.data.texture.y)));
            int factor = Math.max(1, model.data.extrudeMaxFactor);
            factor = Math.min(factor, Math.min(sourceScaleX, sourceScaleY));
            int sampleStepX = sourceScaleX > 1 ? Math.max(1, sourceScaleX / factor) : 1;
            int sampleStepY = sourceScaleY > 1 ? Math.max(1, sourceScaleY / factor) : 1;
            int inward = Math.max(1, Math.min(model.data.extrudeInwards, factor));
            int w = baseW * factor;
            int h = baseH * factor;
            int d = baseD * factor;
            VoxelGrid grid = new VoxelGrid(w, h, d);
            int ox = Math.round(limb.texture.x) * sourceScaleX;
            int oy = Math.round(limb.texture.y) * sourceScaleY;

            /* Standard legacy cube unwrap: top/bottom, front/back, left/right. */
            fillTopBottom(grid, pixels, ox, oy, baseW, baseD, factor, sourceScaleX, sampleStepX, sampleStepY, inward);
            fillFrontBack(grid, pixels, ox, oy, baseW, baseH, baseD, factor, sourceScaleX, sourceScaleY, sampleStepX, sampleStepY, inward);
            fillLeftRight(grid, pixels, ox, oy, baseW, baseH, baseD, factor, sourceScaleX, sourceScaleY, sampleStepX, sampleStepY, inward);

            return mesh(grid, model, limb, factor);
        }
        catch (Exception e)
        {
            System.err.println("[BBS FBX] Could not extrude a legacy is3D limb: " + e.getMessage());
            return null;
        }
    }

    private static void fillTopBottom(VoxelGrid g, Pixels image, int ox, int oy, int bw, int bd,
                                      int ef, int sourceScaleX, int sx, int sy, int inward)
    {
        int topX = ox + bd * sourceScaleX;
        int bottomX = ox + (bd + bw) * sourceScaleX;
        for (int x = 0; x < g.w; x++) for (int z = 0; z < g.d; z++)
        {
            if (opaque(image, topX + x * sx, oy + z * sy))
                for (int k = 0; k < inward && k < g.h; k++) g.or(x, g.h - 1 - k, z, TOP);
            if (opaque(image, bottomX + x * sx, oy + z * sy))
                for (int k = 0; k < inward && k < g.h; k++) g.or(x, k, z, BOTTOM);
        }
    }

    private static void fillFrontBack(VoxelGrid g, Pixels image, int ox, int oy, int bw, int bh, int bd,
                                      int ef, int sourceScaleX, int sourceScaleY, int sx, int sy, int inward)
    {
        int frontX = ox + bd * sourceScaleX;
        int backX = ox + (bd * 2 + bw) * sourceScaleX;
        int faceY = oy + bd * sourceScaleY;
        for (int x = 0; x < g.w; x++) for (int y = 0; y < g.h; y++)
        {
            if (opaque(image, frontX + x * sx, faceY + y * sy))
                for (int k = 0; k < inward && k < g.d; k++) g.or(x, g.h - y - 1, g.d - 1 - k, FRONT);
            if (opaque(image, backX + x * sx, faceY + y * sy))
                for (int k = 0; k < inward && k < g.d; k++) g.or(g.w - x - 1, g.h - y - 1, k, BACK);
        }
    }

    private static void fillLeftRight(VoxelGrid g, Pixels image, int ox, int oy, int bw, int bh, int bd,
                                      int ef, int sourceScaleX, int sourceScaleY, int sx, int sy, int inward)
    {
        int rightX = ox + (bd + bw) * sourceScaleX;
        int faceY = oy + bd * sourceScaleY;
        for (int z = 0; z < g.d; z++) for (int y = 0; y < g.h; y++)
        {
            if (opaque(image, ox + z * sx, faceY + y * sy))
                for (int k = 0; k < inward && k < g.w; k++) g.or(k, g.h - y - 1, z, LEFT);
            if (opaque(image, rightX + z * sx, faceY + y * sy))
                for (int k = 0; k < inward && k < g.w; k++) g.or(g.w - 1 - k, g.h - y - 1, g.d - z - 1, RIGHT);
        }
    }

    private static boolean opaque(Pixels pixels, int x, int y)
    {
        if (x < 0 || y < 0 || x >= pixels.width || y >= pixels.height) return false;
        return ((pixels.getARGB()[y * pixels.width + x] >>> 24) & 0xff) >= 0x80;
    }

    private static Mesh mesh(VoxelGrid grid, LegacyBBModel model, BlockbusterModelLoader.LegacyLimb limb, int ef)
    {
        Mesh result = new Mesh();
        float f = 1F / 16F / ef;
        float so = limb.sizeOffset * ef;
        float sw = grid.w + so * 2F;
        float sh = grid.h + so * 2F;
        float sd = grid.d + so * 2F;
        float ax = (1F - limb.anchor.x) * sw;
        float ay = (1F - limb.anchor.y) * sh;
        float az = (1F - limb.anchor.z) * sd;
        float tw = model.data.texture.x * ef;
        float th = model.data.texture.y * ef;
        int ox = Math.round(limb.texture.x) * ef;
        int oy = Math.round(limb.texture.y) * ef;

        for (int x = 0; x < grid.w; x++) for (int y = 0; y < grid.h; y++) for (int z = 0; z < grid.d; z++)
        {
            int bx = limb.mirror ? grid.w - x - 1 : x;
            byte bits = grid.get(bx, y, z);
            if (bits == 0) continue;

            float x0 = (x + (limb.mirror ? 1 : 0)) * (sw / grid.w) - ax;
            float x1 = (x + (limb.mirror ? 0 : 1)) * (sw / grid.w) - ax;
            x0 *= f; x1 *= f;
            float y0 = -(y * (sh / grid.h) - ay) * f;
            float y1 = -((y + 1) * (sh / grid.h) - ay) * f;
            float z0 = -(z * (sd / grid.d) - az) * f;
            float z1 = -((z + 1) * (sd / grid.d) - az) * f;

            if (!grid.has(bx, y + 1, z)) quad(result, x0,y1,z0, x0,y1,z1, x1,y1,z1, x1,y1,z0, uv(bits, TOP, ox,oy,grid.w,grid.h,grid.d,bx,y,z,tw,th), 0,-1,0);
            if (!grid.has(bx, y - 1, z)) quad(result, x0,y0,z0, x1,y0,z0, x1,y0,z1, x0,y0,z1, uv(bits, BOTTOM, ox,oy,grid.w,grid.h,grid.d,bx,y,z,tw,th), 0,1,0);
            if (!grid.has(bx, y, z + 1)) quad(result, x0,y1,z1, x0,y0,z1, x1,y0,z1, x1,y1,z1, uv(bits, FRONT, ox,oy,grid.w,grid.h,grid.d,bx,y,z,tw,th), 0,0,-1);
            if (!grid.has(bx, y, z - 1)) quad(result, x0,y1,z0, x1,y1,z0, x1,y0,z0, x0,y0,z0, uv(bits, BACK, ox,oy,grid.w,grid.h,grid.d,bx,y,z,tw,th), 0,0,1);
            if (!grid.has(bx + 1, y, z)) quad(result, x1,y1,z0, x1,y1,z1, x1,y0,z1, x1,y0,z0, uv(bits, RIGHT, ox,oy,grid.w,grid.h,grid.d,bx,y,z,tw,th), 1,0,0);
            if (!grid.has(bx - 1, y, z)) quad(result, x0,y1,z0, x0,y0,z0, x0,y0,z1, x0,y1,z1, uv(bits, LEFT, ox,oy,grid.w,grid.h,grid.d,bx,y,z,tw,th), -1,0,0);
        }
        return result;
    }

    private static float[] uv(byte all, byte preferred, int ox, int oy, int w, int h, int d,
                              int x, int y, int z, float tw, float th)
    {
        byte bit = (all & preferred) != 0 ? preferred : firstBit(all);
        float u;
        float v;
        if (bit == TOP) { u = ox + d + x; v = oy + z; }
        else if (bit == BOTTOM) { u = ox + d + w + x; v = oy + z; }
        else if (bit == FRONT) { u = ox + d + x; v = oy + d + h - y - 1; }
        else if (bit == BACK) { u = ox + d * 2 + w * 2 - x - 1; v = oy + d + h - y - 1; }
        else if (bit == LEFT) { u = ox + z; v = oy + d + h - y - 1; }
        else { u = ox + d + w + d - z - 1; v = oy + d + h - y - 1; }
        return new float[] {u / tw, v / th, (u + 1F) / tw, (v + 1F) / th};
    }

    private static byte firstBit(byte bits)
    {
        for (byte bit : new byte[] {TOP, BOTTOM, FRONT, BACK, LEFT, RIGHT}) if ((bits & bit) != 0) return bit;
        return TOP;
    }

    private static void quad(Mesh mesh,
                             float x0,float y0,float z0, float x1,float y1,float z1,
                             float x2,float y2,float z2, float x3,float y3,float z3,
                             float[] uv, float nx,float ny,float nz)
    {
        mesh.vertices.add(new Vertex(x0,y0,z0,uv[0],uv[1],nx,ny,nz));
        mesh.vertices.add(new Vertex(x1,y1,z1,uv[0],uv[3],nx,ny,nz));
        mesh.vertices.add(new Vertex(x2,y2,z2,uv[2],uv[3],nx,ny,nz));
        mesh.vertices.add(new Vertex(x3,y3,z3,uv[2],uv[1],nx,ny,nz));
    }

    private static final class VoxelGrid
    {
        final int w, h, d;
        final byte[] blocks;
        VoxelGrid(int w, int h, int d) { this.w=w; this.h=h; this.d=d; this.blocks=new byte[w*h*d]; }
        void or(int x,int y,int z,byte bit) { if (inside(x,y,z)) blocks[(y*d+z)*w+x] |= bit; }
        byte get(int x,int y,int z) { return inside(x,y,z) ? blocks[(y*d+z)*w+x] : 0; }
        boolean has(int x,int y,int z) { return get(x,y,z) != 0; }
        boolean inside(int x,int y,int z) { return x>=0 && y>=0 && z>=0 && x<w && y<h && z<d; }
    }

    private static final class Mesh { final List<Vertex> vertices = new ArrayList<>(); }
    private record Vertex(float x,float y,float z,float u,float v,float nx,float ny,float nz) {}
}
