package elgatopro300.bbsfbx;

import mchorse.bbs_mod.events.BBSAddonMod;
import mchorse.bbs_mod.events.Subscribe;
import mchorse.bbs_mod.events.register.RegisterL10nEvent;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.resources.Link;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code bbs-addon} entrypoint (see fabric.mod.json).
 *
 * <p>{@code RegisterL10nEvent} is genuinely native, common-bus BBS API --
 * present with an identical shape in BBS Base, BBS FS and BBS CML EDITION
 * (unlike model-loader/importer registration below), so this class needs no
 * fork-specific handling and works unmodified on all three.</p>
 *
 * <p>Model-loader and importer registration deliberately do NOT go through
 * {@code RegisterModelLoadersEvent}/{@code RegisterImportersEvent} here, even
 * though CML ships those natively and BBS Addon Engine can supply them on
 * Base/FS. Reason: {@code ModelManagerMixin} and {@code ImportersMixin}
 * already register the FBX loader/importer unconditionally on every fork by
 * mixing directly into {@code ModelManager}/{@code Importers}, which are
 * identical across Base, FS and CML (verified directly against the Base
 * 1.7.7-1.20.4 and BBS CML EDITION 2.0-beta-1-1.20.4 jars). Also subscribing
 * to those events would register a SECOND copy of the loader/importer
 * whenever the event path is available (native CML, or Base/FS with the
 * engine installed) -- so this addon intentionally has exactly one
 * registration path per hook, not two competing ones. This is why it does
 * not depend on {@code bbs-addon-engine} at all: the mixin path already
 * covers Base and FS without it.</p>
 */
public class BBSFbxAddon implements BBSAddonMod
{
    public static final Logger LOGGER = LoggerFactory.getLogger("BBS Fbx Addon");

    public BBSFbxAddon()
    {
        LOGGER.info("BBS Fbx Addon ready");
    }

    @Subscribe
    public void registerL10n(RegisterL10nEvent event)
    {
        event.l10n.register((lang) -> List.of(
                new Link("bbs_fbx", "lang/" + L10n.DEFAULT_LANGUAGE + ".json"),
                new Link("bbs_fbx", "lang/" + lang + ".json")
        ));

        try
        {
            event.l10n.reload();
        }
        catch (Exception e)
        {
            LOGGER.warn("Failed to reload BBS L10n", e);
        }
    }
}