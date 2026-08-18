package glaxium.snb.render;

import mchorse.bbs_mod.resources.Link;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/** The whole-model texture selected on the innermost rendered ModelForm. */
public final class CurrentModelTexture
{
    private static final Deque<Optional<Link>> STACK = new ArrayDeque<>();

    private CurrentModelTexture() {}

    public static void push(Link texture)
    {
        STACK.push(Optional.ofNullable(texture));
    }

    public static void pop()
    {
        if (!STACK.isEmpty()) STACK.pop();
    }

    public static Link current()
    {
        return STACK.isEmpty() ? null : STACK.peek().orElse(null);
    }
}
