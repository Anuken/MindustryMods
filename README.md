# MindustryMods

Automatically compiles a list of Mindustry mods for the mod browser. Refreshes every few hours automatically.

## Criteria

- Must have a valid `mod.json` / `mod.hjson` file in the root or `assets/` directory.
- Must have the `mindustry-mod` topic. Do **NOT** use the `mindustry-mod-v6` or `mindustry-mod-v7` topics, they are ignored!
- Must have a `minGameVersion` >= `136` in `mod.json`.

## Opting Out

If you would like to remove your mod from the public browser, add `hideBrowser: true` to your `mod.json`.
