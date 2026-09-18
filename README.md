# MindustryMods

Automatically compiles a list of Mindustry mods for the mod browser. Refreshes every few hours automatically.

**Do not make PRs adding your mod to this list** - instead, make sure it fits the criteria below.

## Criteria

- Must have a valid `mod.json` / `mod.hjson` file in the root or `assets/` directory.
- Must have the `mindustry-mod` topic. Do **NOT** use the `mindustry-mod-v6` or `mindustry-mod-v7` topics, they are ignored!
- Must have a `minGameVersion` >= `136` in `mod.json`.

## Opting Out

If you would like to remove your mod from the public browser, add `hideBrowser: true` to your `mod.json`.

## Releases

Mindustry downloads mods as follows:

- If the mod is Java-based, a release with a valid JAR file is required.
- The most recent Github release matching the game's version is prioritized.
  - Setting the target game version for a release can be done by putting it in the release name in square brackets.
  - For example, putting `Frog Mod [v160]` will make the release target build 160, and any revision, such as 160.3.
  - Writing `[v160.3]` (specifying a revision) will make it **only** target that revision; it will not be targeted to 160.1 or 160.4.
  - If there are no releases targeting the game version, the newest release is downloaded.
- For JS and JSON mods, the latest commit on the main branch is downloaded.
  - This makes it simpler to download and manage these mods for beginners.
  - It is recommended to have a separate branch for active development.
- For non-Java mods, releases without a matching target game version tag in their title are ignored.
  - For example, if you have a JSON/JS mod, and the user is using Mindustry `Build 160`, it will only download a release if it has `[v160]` at the end of its title. Otherwise, it will download the latest commit on the main branch.

Practical example: Let's say I want to have a mod that has both v8 and v9 versions. Here's how I would do it:
- Create a v8 branch with the code you want to be v8-specific.
- Make sure `minGameVersion: 160` is in that branch's `mod.hjson`.
- Create a release with the name `My Spectacular Mod [v160]`, and upload the JAR built from the v8 source, targeting the v8 branch.
  - Everyone running Mindustry v8 (`build 160`) will download this release instead of the latest one. 
- For the v9 release, simply create a release as you would normally, with no version tags in the title, and include `minGameVersion: 161` (or whichever build you are targeting) in your `mod.hjson`
  - Anyone who is *not* on `build 160` will automatically download this version instead upon installation.

For updates:

- If you have a release that matches the Mindustry version the game is running, it will check for updates based on the `version` in your `mod.hjson` in the repository at the release tag.
- Otherwise, if there are no exact matching releases, it checks based on the `version` in the `mod.hjson` *at the latest commit of your repository.*
- Version strings should be valid [semver](https://semver.org/). Essentially: three numbers separated by periods, e.g. `1.3.5`. If you write versions in other formats, the update checker might get confused. Whatever you do, do **not** write versions in wacky formats like `build-123-1.5.6 beta-3`.
- If you don't update your `mod.hjson`'s version string when you make a release, the game won't know that an update is an available.
