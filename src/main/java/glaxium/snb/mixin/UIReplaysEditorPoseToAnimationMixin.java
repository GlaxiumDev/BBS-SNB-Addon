package glaxium.snb.mixin;

import glaxium.snb.anim.PoseToAnimation;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.model.ModelManager;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditor;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIPromptOverlayPanel;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import mchorse.bbs_mod.utils.pose.Pose;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.util.List;

/**
 * Adds a "Pose to animation" context menu entry on pose keyframe sheets in the
 * film replay editor. Selecting pose keyframes, right-clicking, and choosing
 * this entry opens a name prompt; on confirm, the selected poses are converted
 * to a standalone animation-only {@code .bobj} and saved under
 * {@code config/bbs/assets/<modelId>/animations/<name>.bobj}. The model is
 * then reloaded so the new animation appears in the animation dropdown.
 *
 * <p>Fork-agnostic: {@code updateChannelsList()V}, the {@code keyframeEditor},
 * {@code replay}, and {@code filmPanel} fields all have identical shapes on
 * BBS Base, FS, and CML EDITION. The additive {@code context(Consumer)} API
 * on {@code UIElement} means this mixin's menu entry coexists with the
 * vanilla entries without conflicting.
 */
@Mixin(value = UIReplaysEditor.class, remap = false)
public abstract class UIReplaysEditorPoseToAnimationMixin
{
    @Shadow
    private mchorse.bbs_mod.film.replays.Replay replay;

    @Inject(method = "updateChannelsList()V", at = @At("TAIL"), remap = false)
    private void bbsFbx$addPoseToAnimationContext(CallbackInfo info)
    {
        UIReplaysEditor self = (UIReplaysEditor) (Object) this;

        if (self.keyframeEditor == null || self.keyframeEditor.view == null)
        {
            return;
        }

        self.keyframeEditor.view.context(menu ->
        {
            if (this.replay == null || !(this.replay.form.get() instanceof ModelForm modelForm))
            {
                return;
            }

            int mouseY = self.getContext().mouseY;
            UIKeyframeSheet sheet = self.keyframeEditor.view.getGraph().getSheet(mouseY);

            if (sheet == null || sheet.channel.getFactory() != KeyframeFactories.POSE)
            {
                return;
            }

            if (!sheet.selection.hasAny())
            {
                return;
            }

            menu.action(
                    mchorse.bbs_mod.ui.utils.icons.Icons.POSE,
                    L10n.lang("bbs_fbx.pose_to_animation.context_menu"),
                    () -> this.bbsFbx$openPoseToAnimationPrompt(self, modelForm, sheet)
            );
        });
    }

    private void bbsFbx$openPoseToAnimationPrompt(UIReplaysEditor self, ModelForm modelForm, UIKeyframeSheet sheet)
    {
        String modelId = modelForm.model.get();

        if (modelId == null || modelId.isEmpty())
        {
            return;
        }

        UIPromptOverlayPanel panel = new UIPromptOverlayPanel(
                L10n.lang("bbs_fbx.pose_to_animation.title"),
                L10n.lang("bbs_fbx.pose_to_animation.message"),
                (name) -> this.bbsFbx$savePoseAnimation(self, modelId, name, sheet)
        );

        panel.text.filename();
        panel.text.placeholder(L10n.lang("bbs_fbx.pose_to_animation.placeholder"));

        UIOverlay.addOverlay(self.getContext(), panel, 220, 120);
    }

    @SuppressWarnings("unchecked")
    private void bbsFbx$savePoseAnimation(UIReplaysEditor self, String modelId, String name, UIKeyframeSheet sheet)
    {
        if (name == null || name.trim().isEmpty())
        {
            return;
        }

        List<Keyframe<Pose>> selected = (List<Keyframe<Pose>>) (List<?>) sheet.selection.getSelected();

        if (selected.isEmpty())
        {
            return;
        }

        File saved = PoseToAnimation.save(selected, modelId, name);

        if (saved == null)
        {
            self.getContext().notifyError(L10n.lang("bbs_fbx.pose_to_animation.error"));

            return;
        }

        /* Merge straight into the live cached instance so the animation shows
         * up in the dropdown immediately; the saved file also makes it survive
         * any future full model reload (picked up by the loadModel mixin). */
        ModelManager manager = BBSModClient.getModels();
        ModelInstance live = manager.models.get(modelId);

        if (live != null)
        {
            PoseToAnimation.merge(live, manager);
        }
        else
        {
            manager.getModel(modelId);
        }

        self.getContext().notifySuccess(
                L10n.lang("bbs_fbx.pose_to_animation.saved").format(PoseToAnimation.sanitizeFileName(name))
        );
    }
}
