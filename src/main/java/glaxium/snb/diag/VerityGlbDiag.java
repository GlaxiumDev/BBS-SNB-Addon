package glaxium.snb.diag;

import glaxium.snb.model.fbx.FBXConverter;
import glaxium.snb.model.fbx.FBXMetadata;
import glaxium.snb.model.fbx.convert.FBXArmatureBuilder;
import glaxium.snb.model.fbx.convert.FBXMath;
import glaxium.snb.model.fbx.convert.FBXSceneWalker;
import glaxium.snb.model.fbx.loaders.FBXAssimpImporter;
import glaxium.snb.model.fbx.loaders.SceneFormat;

import mchorse.bbs_mod.bobj.BOBJAction;
import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.bobj.BOBJChannel;
import mchorse.bbs_mod.bobj.BOBJGroup;
import mchorse.bbs_mod.bobj.BOBJKeyframe;
import mchorse.bbs_mod.bobj.BOBJLoader.BOBJData;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.assimp.AIBone;
import org.lwjgl.assimp.AIMesh;
import org.lwjgl.assimp.AINode;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.Assimp;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Offline Assimp→BOBJ dump comparing Verity (broken) vs Miku (OK) for spaghetti.
 */
public class VerityGlbDiag {
    public static void main(String[] args) throws Exception {
        for (String path : args) {
            dump(path);
        }
    }

    static void dump(String path) {
        File file = new File(path);
        SceneFormat format = SceneFormat.fromPath(path);
        System.out.println("======== " + path + " format=" + format);
        AIScene scene = FBXAssimpImporter.importScene(file, format);
        if (scene == null) {
            System.out.println("IMPORT FAIL: " + Assimp.aiGetErrorString());
            return;
        }
        try {
            FBXMetadata meta = new FBXMetadata(scene);
            AINode rootNode = scene.mRootNode();
            Map<Integer, String> meshNodeNames = new HashMap<>();
            Map<String, String> nodeParents = new HashMap<>();
            Map<String, Matrix4f> nodeLocals = new HashMap<>();
            Map<String, Matrix4f> nodeWorldTransforms = new HashMap<>();
            Map<Integer, Matrix4f> meshTransforms = FBXSceneWalker.collectMeshTransforms(
                    rootNode, meshNodeNames, nodeParents, nodeLocals, nodeWorldTransforms);
            Map<String, Integer> skinnedBoneMeshIndex = new HashMap<>();
            Map<String, AIBone> skinnedBones = FBXArmatureBuilder.collectSkinnedBones(scene, skinnedBoneMeshIndex);
            boolean ibmInSceneSpace = FBXArmatureBuilder.ibmInSceneSpace(
                    skinnedBones, nodeWorldTransforms, skinnedBoneMeshIndex, meshTransforms);

            float maxExtent = meshExtent(scene);
            float maxMeshScale = 1f;
            for (Matrix4f mw : meshTransforms.values()) {
                Vector3f s = new Vector3f();
                mw.getScale(s);
                maxMeshScale = Math.max(maxMeshScale, Math.max(s.x, Math.max(s.y, s.z)));
            }

                        for (Map.Entry<Integer, Matrix4f> me : meshTransforms.entrySet()) {
                Vector3f mt = new Vector3f(), ms = new Vector3f();
                org.joml.Quaternionf mq = new org.joml.Quaternionf();
                me.getValue().getTranslation(mt);
                me.getValue().getUnnormalizedRotation(mq);
                me.getValue().getScale(ms);
                Vector3f eul = new Vector3f();
                mq.getEulerAnglesZYX(eul);
                System.out.printf("  mesh[%d] node=%s T=%.3f,%.3f,%.3f S=%.3f,%.3f,%.3f eulerZYX(deg)=%.1f,%.1f,%.1f%n",
                        me.getKey(), meshNodeNames.get(me.getKey()),
                        mt.x, mt.y, mt.z, ms.x, ms.y, ms.z,
                        Math.toDegrees(eul.x), Math.toDegrees(eul.y), Math.toDegrees(eul.z));
            }
            // sample a few node local T for hips/spine
            for (String bn : new String[]{"mixamorig:Hips","mixamorig:Spine","pelvis","spine_01","Root","Miku"}) {
                Matrix4f loc = nodeLocals.get(bn);
                if (loc == null) continue;
                Vector3f lt = new Vector3f(); loc.getTranslation(lt);
                Vector3f ls = new Vector3f(); loc.getScale(ls);
                System.out.printf("  nodeLocal %-28s T=%.4f,%.4f,%.4f S=%.4f,%.4f,%.4f%n",
                        bn, lt.x, lt.y, lt.z, ls.x, ls.y, ls.z);
            }

            System.out.println("upAxis=" + meta.upAxis + " originalUpAxis=" + meta.originalUpAxis
                    + " unitScaleMeta=" + meta.unitScaleFactor
                    + " meshes=" + scene.mNumMeshes()
                    + " anims=" + scene.mNumAnimations()
                    + " unitScaleArg=" + format.unitScale()
                    + " skinnedBones=" + skinnedBones.size()
                    + " ibmInSceneSpace=" + ibmInSceneSpace
                    + " meshExtent=" + maxExtent
                    + " maxMeshScale=" + maxMeshScale);

            // IBM vs node-world translation error samples
            int nSample = 0;
            float sumScene = 0, sumMesh = 0;
            for (Map.Entry<String, AIBone> e : skinnedBones.entrySet()) {
                Matrix4f nodeWorld = nodeWorldTransforms.get(e.getKey());
                if (nodeWorld == null) continue;
                Matrix4f ibmInv = FBXMath.toMatrix4f(e.getValue().mOffsetMatrix()).invert();
                float errScene = distT(ibmInv, nodeWorld);
                Integer mi = skinnedBoneMeshIndex.get(e.getKey());
                Matrix4f meshWorld = mi == null ? null : meshTransforms.get(mi);
                float errMesh = Float.POSITIVE_INFINITY;
                if (meshWorld != null) {
                    Matrix4f meshLocal = new Matrix4f(meshWorld).invert().mul(nodeWorld);
                    errMesh = distT(ibmInv, meshLocal);
                }
                sumScene += errScene;
                if (errMesh < Float.POSITIVE_INFINITY) sumMesh += errMesh;
                if (nSample++ < 3 || e.getKey().toLowerCase().contains("spine")
                        || e.getKey().toLowerCase().contains("hip")
                        || e.getKey().toLowerCase().contains("pelvis")) {
                    Vector3f it = ibmInv.getTranslation(new Vector3f());
                    Vector3f nt = nodeWorld.getTranslation(new Vector3f());
                    System.out.printf("  ibmVote %-28s errScene=%.4f errMesh=%.4f ibmT=%.3f nodeT=%.3f%n",
                            e.getKey(), errScene, errMesh, it.length(), nt.length());
                }
                if (nSample > 80) break;
            }
            if (nSample > 0) {
                System.out.printf("  ibmAvg errScene=%.4f errMesh=%.4f over %d bones%n",
                        sumScene / nSample, sumMesh / Math.max(1, nSample), nSample);
            }

            BOBJData data = FBXConverter.convert(scene, format.unitScale());
            BOBJArmature arm = data.armatures.values().iterator().next();
            
            float unit = maxMeshScale > 2f ? 1f/maxMeshScale : (maxMeshScale < 0.5f ? maxMeshScale : 1f);
            // note: maxMeshScale for GLB is wrongly 1 due to init; use meshTransforms
            float ms = 1f;
            for (Matrix4f mw : meshTransforms.values()) {
                Vector3f s = new Vector3f(); mw.getScale(s);
                ms = Math.max(s.x, Math.max(s.y, s.z));
            }
            unit = ms > 2f ? 1f/ms : (ms > 0f && ms < 0.5f ? ms : 1f);
            System.out.println("  unitBridge=" + unit + " rawMeshScale=" + ms);
            int cmp = 0; float sumA=0, maxA=0; String worstCmp="";
            Matrix4f rootCorr = FBXMath.buildRootCorrection(meta);
            for (BOBJBone b : arm.orderedBones) {
                Matrix4f nw = nodeWorldTransforms.get(b.name);
                if (nw == null || b.boneMat == null) continue;
                Matrix4f scaled = new Matrix4f(nw);
                scaled.m30(scaled.m30()*unit); scaled.m31(scaled.m31()*unit); scaled.m32(scaled.m32()*unit);
                scaled.normalize3x3();
                Matrix4f expect = new Matrix4f(rootCorr).mul(scaled);
                // angle between rotations
                org.joml.Quaternionf q1 = new org.joml.Quaternionf(), q2 = new org.joml.Quaternionf();
                b.boneMat.getUnnormalizedRotation(q1); expect.getUnnormalizedRotation(q2);
                q1.normalize(); q2.normalize();
                float ang = (float)Math.toDegrees(q1.difference(q2, new org.joml.Quaternionf()).angle());
                Vector3f t1 = b.boneMat.getTranslation(new Vector3f());
                Vector3f t2 = expect.getTranslation(new Vector3f());
                float td = t1.distance(t2);
                sumA += ang; if (ang > maxA) { maxA = ang; worstCmp = b.name; }
                if (cmp < 5 || b.name.contains("Arm") || b.name.contains("Leg") || b.name.contains("Hand") || b.name.contains("Foot")) {
                    System.out.printf("  cmp %-28s angErr=%.2f tErr=%.4f%n", b.name, ang, td);
                }
                cmp++;
            }
            System.out.printf("  cmpAvgAng=%.2f maxAng=%.2f@%s over %d%n", sumA/Math.max(1,cmp), maxA, worstCmp, cmp);

            System.out.println("bones=" + arm.bones.size() + " actions=" + data.actions.size());

            int n = 0;
            float maxT = 0;
            String maxName = "";
            for (BOBJBone b : arm.orderedBones) {
                if (b.boneMat == null) continue;
                float t = b.boneMat.getTranslation(new Vector3f()).length();
                if (t > maxT) { maxT = t; maxName = b.name; }
                if (n++ < 5 || b.name.toLowerCase().contains("spine") || b.name.toLowerCase().contains("hip")
                        || b.name.toLowerCase().contains("pelvis") || b.name.toLowerCase().contains("upperarm")
                        || b.name.toLowerCase().contains("head")) {
                    Vector3f bt = b.boneMat.getTranslation(new Vector3f());
                    Vector3f bs = b.boneMat.getScale(new Vector3f());
                    System.out.printf("  bone %-28s parent=%-24s T=%.3f S=%.3f,%.3f,%.3f%n",
                            b.name, b.parent == null ? "-" : b.parent, bt.length(), bs.x, bs.y, bs.z);
                }
            }
            
            // limb focus: parent-relative rest from boneMat vs nodeLocal
            String[] focus = {"mixamorig:Hips","mixamorig:Spine","mixamorig:LeftUpLeg","mixamorig:LeftLeg","mixamorig:LeftFoot","mixamorig:LeftArm","mixamorig:LeftForeArm","mixamorig:LeftHand","mixamorig:RightArm","mixamorig:RightHand"};
            java.util.Map<String, BOBJBone> byName = new java.util.HashMap<>();
            for (BOBJBone b : arm.orderedBones) byName.put(b.name, b);
            for (String bn : focus) {
                BOBJBone b = byName.get(bn);
                if (b == null) continue;
                Matrix4f world = new Matrix4f(b.boneMat);
                Matrix4f parentW = new Matrix4f();
                if (b.parent != null && !b.parent.isEmpty() && byName.containsKey(b.parent)) {
                    parentW.set(byName.get(b.parent).boneMat);
                } else parentW.identity();
                Matrix4f rel = new Matrix4f(parentW).invert().mul(world);
                Vector3f rt = new Vector3f(), rs = new Vector3f();
                org.joml.Quaternionf rq = new org.joml.Quaternionf();
                rel.getTranslation(rt); rel.getUnnormalizedRotation(rq); rel.getScale(rs);
                Vector3f re = new Vector3f(); rq.getEulerAnglesZYX(re);
                Matrix4f nl = nodeLocals.get(bn);
                String nls = "-";
                if (nl != null) {
                    Vector3f nt = new Vector3f(), ns = new Vector3f();
                    org.joml.Quaternionf nq = new org.joml.Quaternionf();
                    nl.getTranslation(nt); nl.getUnnormalizedRotation(nq); nl.getScale(ns);
                    Vector3f ne = new Vector3f(); nq.getEulerAnglesZYX(ne);
                    nls = String.format("nT=%.3f,%.3f,%.3f nS=%.3f,%.3f,%.3f nE=%.1f,%.1f,%.1f",
                        nt.x,nt.y,nt.z, ns.x,ns.y,ns.z, Math.toDegrees(ne.x),Math.toDegrees(ne.y),Math.toDegrees(ne.z));
                }
                System.out.printf("  rel %-28s T=%.3f,%.3f,%.3f S=%.3f,%.3f,%.3f E=%.1f,%.1f,%.1f | %s%n",
                    bn, rt.x,rt.y,rt.z, rs.x,rs.y,rs.z, Math.toDegrees(re.x),Math.toDegrees(re.y),Math.toDegrees(re.z), nls);
            }

            
            // key0 vs nodeLocal for first action
            if (scene.mNumAnimations() > 0) {
                org.lwjgl.assimp.AIAnimation an = org.lwjgl.assimp.AIAnimation.create(scene.mAnimations().get(0));
                System.out.println("  firstAnim=" + an.mName().dataString() + " channels=" + an.mNumChannels());
                java.util.Set<String> want = new java.util.HashSet<>(java.util.Arrays.asList(focus));
                for (int ci = 0; ci < an.mNumChannels(); ci++) {
                    org.lwjgl.assimp.AINodeAnim na = org.lwjgl.assimp.AINodeAnim.create(an.mChannels().get(ci));
                    String nn = na.mNodeName().dataString();
                    if (!want.contains(nn)) continue;
                    Vector3f kt = new Vector3f(), ks = new Vector3f(1,1,1);
                    org.joml.Quaternionf kq = new org.joml.Quaternionf();
                    if (na.mNumPositionKeys() > 0) {
                        var v = na.mPositionKeys().get(0).mValue();
                        kt.set(v.x(), v.y(), v.z());
                    }
                    if (na.mNumRotationKeys() > 0) {
                        var q = na.mRotationKeys().get(0).mValue();
                        kq.set(q.x(), q.y(), q.z(), q.w());
                    }
                    if (na.mNumScalingKeys() > 0) {
                        var v = na.mScalingKeys().get(0).mValue();
                        ks.set(v.x(), v.y(), v.z());
                    }
                    Vector3f ke = new Vector3f(); kq.getEulerAnglesZYX(ke);
                    Matrix4f nl = nodeLocals.get(nn);
                    Vector3f nt = new Vector3f(), ne = new Vector3f();
                    org.joml.Quaternionf nq = new org.joml.Quaternionf();
                    if (nl != null) { nl.getTranslation(nt); nl.getUnnormalizedRotation(nq); nq.getEulerAnglesZYX(ne); }
                    float dR = (float)(Math.abs(ke.x-ne.x)+Math.abs(ke.y-ne.y)+Math.abs(ke.z-ne.z));
                    float dT = kt.distance(nt);
                    System.out.printf("  key0 %-28s T=%.3f,%.3f,%.3f E=%.1f,%.1f,%.1f | node dT=%.3f dR=%.3f S=%.3f,%.3f,%.3f%n",
                        nn, kt.x,kt.y,kt.z, Math.toDegrees(ke.x),Math.toDegrees(ke.y),Math.toDegrees(ke.z), dT, Math.toDegrees(dR), ks.x,ks.y,ks.z);
                }
            }

            System.out.println("maxBoneT=" + maxT + " @" + maxName);

            float vmax = 0;
            for (var v : data.vertices) {
                float len = (float) Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z);
                if (len > vmax) vmax = len;
            }
            System.out.println("vertMaxR=" + vmax + " boneOverVert=" + (vmax > 1e-6 ? maxT / vmax : -1));

            int printed = 0;
            for (Map.Entry<String, BOBJAction> e : data.actions.entrySet()) {
                BOBJAction a = e.getValue();
                int bad = 0, checked = 0;
                float maxR = 0, maxLoc = 0;
                String worst = "", worstLoc = "";
                for (BOBJGroup g : a.groups.values()) {
                    float[] r = samplePath(g, 0f, "rotation");
                    float[] loc = samplePath(g, 0f, "location");
                    if (r != null) {
                        checked++;
                        float mag = Math.abs(r[0]) + Math.abs(r[1]) + Math.abs(r[2]);
                        if (mag > 0.2f) bad++;
                        if (mag > maxR) { maxR = mag; worst = g.name; }
                    }
                    if (loc != null) {
                        float mag = Math.abs(loc[0]) + Math.abs(loc[1]) + Math.abs(loc[2]);
                        if (mag > maxLoc) { maxLoc = mag; worstLoc = g.name; }
                    }
                }
                System.out.printf("  action %-28s groups=%d f0BadR=%d/%d max|R|=%.3f@%s max|L|=%.3f@%s%n",
                        e.getKey(), a.groups.size(), bad, checked, maxR, worst, maxLoc, worstLoc);
                if (++printed >= 4) break;
            }
        } finally {
            Assimp.aiReleaseImport(scene);
        }
    }

    static float meshExtent(AIScene scene) {
        float maxExtent = 0f;
        for (int i = 0; i < scene.mNumMeshes(); i++) {
            AIMesh mesh = AIMesh.create(scene.mMeshes().get(i));
            float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
            var verts = mesh.mVertices();
            while (verts.remaining() > 0) {
                var v = verts.get();
                minX = Math.min(minX, v.x()); maxX = Math.max(maxX, v.x());
                minY = Math.min(minY, v.y()); maxY = Math.max(maxY, v.y());
                minZ = Math.min(minZ, v.z()); maxZ = Math.max(maxZ, v.z());
            }
            maxExtent = Math.max(maxExtent, Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ)));
        }
        return maxExtent;
    }

    static float distT(Matrix4f a, Matrix4f b) {
        float dx = a.m30() - b.m30();
        float dy = a.m31() - b.m31();
        float dz = a.m32() - b.m32();
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    static float[] samplePath(BOBJGroup g, float frame, String base) {
        Float x = null, y = null, z = null;
        for (BOBJChannel c : g.channels) {
            if (c.keyframes == null || c.keyframes.isEmpty()) continue;
            float v = c.keyframes.get(0).value;
            for (BOBJKeyframe kf : c.keyframes) {
                if (kf.frame <= frame) v = kf.value;
            }
            if ((base + ".x").equals(c.path)) x = v;
            if ((base + ".y").equals(c.path)) y = v;
            if ((base + ".z").equals(c.path)) z = v;
        }
        if (x == null && y == null && z == null) return null;
        return new float[]{x == null ? 0 : x, y == null ? 0 : y, z == null ? 0 : z};
    }
}
