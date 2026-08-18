# Third-party assets

The armor-only BOBJ sidecars under
`src/main/resources/assets/bbs/assets/models/emoticons/` are derived from
`props.bobj` and `props_simple.bobj` in McHorse's Emoticons project:

- Source: https://github.com/mchorse/emoticons
- License declared by the source project: GNU GPL v3.0
- Changes: the `popcorn` prop and all non-armor data were removed; retained
  face indices were rewritten into standalone armor-only files. Normal models
  use `props.bobj`; BBS Bend uses the matching Simple+ `props_simple.bobj`
  shell. Runtime code adds a geometric sharp hinge because the original
  player-skin UV ranges do not identify armor-atlas joint vertices. Alex
  outputs narrow and recenter arm-weighted sleeve vertices to match the slim
  player arm bounds.

The four copies are intentionally kept separate so Steve/Alex and their
bend variants can be replaced independently by resource packs. The original
project used one shell for Steve/Alex and another for Simple/Simple+. This
addon keeps independent Steve/Alex and Bend output files for resource-pack
overrides.
