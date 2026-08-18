package glaxium.snb.render;

import glaxium.snb.model.bobj.EmoticonArmorSidecar;
import glaxium.snb.model.bobj.MinecraftTextureSourcePack;

import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.resources.Link;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.DyedColorComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/** Runtime equipment state for the sidecar armor meshes of one nested render. */
public final class CurrentEmoticonArmor
{
    private static final Deque<State> STACK = new ArrayDeque<>();
    private static final Map<String, EquipmentSlot> SLOTS = Map.of(
            EmoticonArmorSidecar.HELMET, EquipmentSlot.HEAD,
            EmoticonArmorSidecar.CHEST, EquipmentSlot.CHEST,
            EmoticonArmorSidecar.LEGGINGS, EquipmentSlot.LEGS,
            EmoticonArmorSidecar.FEET, EquipmentSlot.FEET
    );

    private CurrentEmoticonArmor() {}

    public static void push(IEntity target, ModelInstance model, boolean renderEquipment)
    {
        if (model == null || !EmoticonArmorSidecar.supportsModel(model.id))
        {
            STACK.push(State.INACTIVE);
            return;
        }

        Map<String, Part> parts = new HashMap<>();

        if (target != null && renderEquipment)
        {
            for (Map.Entry<String, EquipmentSlot> entry : SLOTS.entrySet())
            {
                Part part = createPart(target.getEquipmentStack(entry.getValue()), entry.getValue());

                if (part != null)
                {
                    parts.put(entry.getKey(), part);
                }
            }
        }

        STACK.push(new State(true, parts));
    }

    public static void pop()
    {
        if (!STACK.isEmpty())
        {
            STACK.pop();
        }
    }

    public static boolean shouldHide(String mesh)
    {
        State state = current();

        return state.active && EmoticonArmorSidecar.isArmorMesh(mesh) && !state.parts.containsKey(mesh);
    }

    public static Link texture(String mesh)
    {
        Part part = current().parts.get(mesh);

        return part == null ? null : part.texture;
    }

    public static float tint(String mesh, int channel, float original)
    {
        Part part = current().parts.get(mesh);

        if (part == null)
        {
            return original;
        }

        return original * switch (channel)
        {
            case 0 -> part.red;
            case 1 -> part.green;
            case 2 -> part.blue;
            default -> 1F;
        };
    }

    private static State current()
    {
        return STACK.isEmpty() ? State.INACTIVE : STACK.peek();
    }

    private static Part createPart(ItemStack stack, EquipmentSlot slot)
    {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof ArmorItem armor)
                || armor.getSlotType() != slot)
        {
            return null;
        }

        RegistryEntry<?> materialEntry = armor.getMaterial();
        String material = materialEntry.getKey().map(key -> key.getValue().toString()).orElse("unknown");
        String namespace = Identifier.DEFAULT_NAMESPACE;
        int colon = material.indexOf(':');

        if (colon >= 0)
        {
            namespace = material.substring(0, colon);
            material = material.substring(colon + 1);
        }

        int layer = slot == EquipmentSlot.LEGS ? 2 : 1;
        Identifier textureId;

        try
        {
            textureId = Identifier.of(namespace, "textures/models/armor/" + material + "_layer_" + layer + ".png");
        }
        catch (RuntimeException error)
        {
            return null;
        }

        float red = 1F;
        float green = 1F;
        float blue = 1F;

        DyedColorComponent dyedColor = stack.get(DataComponentTypes.DYED_COLOR);
        if (dyedColor != null)
        {
            int color = dyedColor.rgb();

            red = ((color >> 16) & 0xff) / 255F;
            green = ((color >> 8) & 0xff) / 255F;
            blue = (color & 0xff) / 255F;
        }

        return new Part(MinecraftTextureSourcePack.link(textureId), red, green, blue);
    }

    private record Part(Link texture, float red, float green, float blue) {}

    private record State(boolean active, Map<String, Part> parts)
    {
        private static final State INACTIVE = new State(false, Collections.emptyMap());
    }
}
