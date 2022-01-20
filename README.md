# MindustryMods

Automatically compiles a list of Mindustry mods for the mod browser. Refreshes every few hours automatically.

## Criteria

- Must have a valid `mod.json` / `mod.hjson` file in the root or `assets/` directory.
- Must have the `mindustry-mod` topic.
- Must have a `minGameVersion` >= `105` in `mod.json`.

## Opting Out

If you would like to remove your mod from the public browser, add `hideBrowser: true` to your `mod.json`.