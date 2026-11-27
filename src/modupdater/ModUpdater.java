package modupdater;

import arc.files.*;
import arc.func.*;
import arc.graphics.*;
import arc.math.*;
import arc.struct.*;
import arc.util.*;
import arc.util.Http.*;
import arc.util.serialization.*;
import arc.util.serialization.Jval.*;

import javax.imageio.*;
import java.awt.image.*;
import java.text.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;

import static arc.struct.StringMap.*;

public class ModUpdater{
    static final String api = "https://api.github.com";
    static final int perPage = 100;
    static final int maxLength = 55;
    static final ObjectSet<String> javaLangs = ObjectSet.with("Java", "Kotlin", "Groovy", "Scala"); //obviously not a comprehensive list

    static final Seq<String> nameBlacklist = Seq.with("TheEE145", "o7", "pixaxeofpixie", "Iron-Miner", "EasyPlaySu", "guiYMOUR", "mishakorzik", "N3M1X10", "EvilMan12317", "guiYMOU", "TheSaus", "Raviravirego76466", "ravirego994-dotcom").map(s -> s.toLowerCase(Locale.ROOT));
    static final ObjectSet<String> blacklist = ObjectSet.with(
        "Snow-of-Spirit-Fox-Mori/old-mod",
        "fox1va-the-fox/schems",
        "Anuken/ExampleMod",
        "Anuken/ExampleJavaMod",
        "Anuken/ExampleKotlinMod",
        "Mesokrix/Vanilla-Upgraded",
        "RebornTrack970/Multiplayernt",
        "RebornTrack970/Multiplayerntnt",
        "EsqueletoBrOficial/meu-mod",
        "RebornTrack970/Destroyer",
        "RebornTrack970/Mindustrynt",
        "NemesisTheory/killer",
        "TheDogOfChaos/reset-UUID-mindustry",
        "OBSIDAN55/UUID_replacer",
        "timofeyfilkin/uuid-changer",
        "Zaxerf1234/mindustry-id-manager",
        "AnthropicCom/Lithium4Mindustry",
        "chuvaak410-cmd/GriefersToolsMod",
        "raviraviravi98989-byte/Frostnova",
        "raviraviravi98989-byte/Project",
        "MINDTECH-Industries/MINDTECH" //obviously botted stars + very suspicious behavior via stolen sprites
    );
    static final Pattern modVersionPattern = Pattern.compile("\\[[vb](\\d+)(?:\\.(\\d+))?\\]");
    static final String topic = "mindustry-mod";
    static final String lastPushDate = "2022-05-01";
    static final int iconSize = 64;

    static final String githubToken = OS.prop("githubtoken");


    /** Characters that are commonly used in place of a letter; leetspeak and Cyrillic/Greek lookalikes. */
    static final ObjectMap<Character, String> lookalikes = ObjectMap.of(
    'a', "4@аα", 'b', "8в", 'c', "(с¢", 'e', "3€еε", 'g', "9", 'h', "#нһ",
    'i', "1!|іı", 'k', "кκ", 'l', "1|", 'm', "м", 'n', "пη", 'o', "0оο", 'p', "рρ",
    's', "5$ѕ", 't', "7+т", 'u', "υ", 'x', "х×", 'y', "у"
    );

    /** Words that may not appear in any public-facing field. Base64, comma-separated. Look at your own risk. This is not comprehensive. */
    static final Seq<Pattern> wordBlacklist = loadWordBlacklist(new Fi("blacklist.txt"));

    public static void main(String[] args){
        new ModUpdater();
    }

    {
        //register colors to facilitate their removal
        Colors.put("accent", Color.white);
        Colors.put("unlaunched",  Color.white);
        Colors.put("highlight",  Color.white);
        Colors.put("stat",  Color.white);

        Seq<Jval> dest = new Seq<>();

        query("/search/repositories", of("q", topic + " in:topics archived:false template:false pushed:>=" + lastPushDate, "per_page", perPage, "sort", "updated"), topicresult -> {
            int pagesTopic = Mathf.ceil(topicresult.getFloat("total_count", 0) / perPage);

            for(int i = 1; i < pagesTopic; i++){
                query("/search/repositories", of("q", "topic:" + topic + " archived:false template:false pushed:>=" + lastPushDate, "per_page", perPage, "page", i + 1, "sort", "updated"), secresult -> {
                    dest.addAll(secresult.get("items").asArray());
                });
            }

            dest.addAll(topicresult.get("items").asArray());

            Log.info("\n&lcFound @ mods via topic: " + topic, dest.size);
        });

        dest.removeAll(v -> v.getBool("is_template", false));

        ObjectMap<String, Jval> output = new ObjectMap<>();
        ObjectMap<String, Jval> ghmeta = new ObjectMap<>();
        ObjectMap<String, Jval> releasesOutput = new ObjectMap<>();
        ObjectMap<String, String> latestReleaseVersions = new ObjectMap<>();
        Seq<String> names = dest.map(val -> {
            ghmeta.put(val.get("full_name").toString().toLowerCase(Locale.ROOT), val);
            return val.get("full_name").toString().toLowerCase(Locale.ROOT);
        });

        int prevSize = names.size;
        //add old list of mods
        Jval prevList = Jval.read(new Fi("mods.json").readString());
        for(var value : prevList.asArray()){
            names.add(value.getString("repo"));
        }
        names.replace(s -> s.toLowerCase(Locale.ROOT));
        //there may be duplicates
        names.distinct();

        names.removeAll(n -> nameBlacklist.contains(n::startsWith));

        for(String name : blacklist){
            names.remove(name.toLowerCase(Locale.ROOT));
        }

        if(prevSize == 0){
            Log.err("Github is probably down, exiting.");
            System.exit(1);
        }

        Log.info("&lyOld repos not found by the API: &lr@", names.size - prevSize);

        Fi icons = Fi.get("icons");

        icons.deleteDirectory();
        icons.mkdirs();

        Log.info("&lcTotal mods found: @\n", names.size);

        //awful.
        ExecutorService exec = Threads.executor("mods", 100);
        ObjectSet<String> usedNames = new ObjectSet<>();

        AtomicInteger index = new AtomicInteger();
        for(String bname : names){
            String name = bname.toLowerCase(Locale.ROOT);
            exec.submit(() -> {
                StringBuilder buffer = new StringBuilder();

                print(buffer, "&lc[@%] [@]&y: querying...", (int)((float)index.getAndIncrement() / names.size * 100), name);

                try{
                    if(!ghmeta.containsKey(name)){
                        print(buffer, "&lr! Manually querying repo info. !");
                        query("/repos/" + name, null, res -> {
                            ghmeta.put(name, res);
                        });
                    }

                    Jval meta = ghmeta.get(name);
                    String branch = meta.getString("default_branch");

                    //is archived, skip
                    if(meta.getBool("archived", false)){
                        print(buffer, "&lc| &lySkipping, repo is archived.");
                        return;
                    }

                    if(!usedNames.add(meta.getString("full_name"))){
                        print(buffer, "&lc| &lySkipping, it's a duplicate.");
                        return;
                    }

                    Jval modjson = fetchModJson(name, branch);

                    if(modjson == null){
                        print(buffer, "&lc| &lySkipping, no meta found.");
                        return;
                    }

                    if(modjson.getBool("hideBrowser", false)){
                        print(buffer, "&lc| &lySkipping, explicitly hidden in browser.");
                        return;
                    }

                    //check for version-specific releases regardless of whether it's a java mod or not (possibly bad idea...?)
                    try{
                        Jval releases = queryReleases(name);
                        Jval releaseMap = buildReleaseMap(name, releases);
                        if(releaseMap != null && releaseMap.asObject().size > 0){
                            releasesOutput.put(name, releaseMap);
                            print(buffer, "&lc| &lmFound @ release(s) with revision tags.", releaseMap.asObject().size);
                        }

                        //java mods are downloaded from the latest release, so use the version at its tag (reuses the release list above)
                        if(modjson.getBool("java", false) || javaLangs.contains(meta.getString("language", ""))){
                            Jval latest = findLatestRelease(releases);
                            if(latest != null){
                                Jval releaseModj = fetchModJson(name, latest.getString("tag_name"));
                                if(releaseModj != null && releaseModj.isObject() && releaseModj.has("version")){
                                    latestReleaseVersions.put(name, releaseModj.getString("version"));
                                    print(buffer, "&lc| &lmUsing version from latest release: @", latest.getString("tag_name"));
                                }
                            }
                        }
                    }catch(Throwable t){
                        print(buffer, "&lc| &lyFailed to fetch releases. [@]", Strings.getSimpleMessage(t));
                    }

                    //filter icons based on stars to prevent potential abuse
                    if(meta.getInt("stargazers_count", 0) >= 2){
                        var icon = tryImage(name + "/" + branch + "/icon.png", name + "/" + branch + "/assets/icon.png");
                        if(icon != null){
                            var scaled = new BufferedImage(iconSize, iconSize, BufferedImage.TYPE_INT_ARGB);
                            scaled.createGraphics().drawImage(icon.getScaledInstance(iconSize, iconSize, java.awt.Image.SCALE_AREA_AVERAGING), 0, 0, iconSize, iconSize, null);
                            print(buffer, "&lc| &lmFound icon file: @x@", icon.getWidth(), icon.getHeight());
                            ImageIO.write(scaled, "png", icons.child(name.replace("/", "_")).file());
                        }
                    }

                    print(buffer, "&lc|&lg Found mod meta file!");
                    output.put(name, modjson);
                }catch(Throwable t){
                    print(buffer, "&lc| &lySkipping. [@]", Strings.getSimpleMessage(t));
                    Log.err(t);
                }finally{
                    Log.info(buffer.substring(0, buffer.length() - 1));
                }
            });
        }

        Threads.await(exec);

        Log.info("&lcFound @ potential mods.", output.size);
        Seq<String> outnames = output.keys().toSeq();
        outnames.sort(); //sort by name only, the client sorts by stars/update date on its end anyway

        Log.info("&lcCreating mods.json file...");
        Jval array = Jval.read("[]");
        for(String name : outnames){
            try{
                Jval gm = ghmeta.get(name);
                Jval modj = output.get(name);
                Jval obj = Jval.read("{}");
                //how
                if(!modj.isObject()) continue;
                String displayName = Strings.stripColors(modj.getString("displayName", "")).replace("\\n", "");
                if(displayName.isEmpty()) displayName = gm.getString("name");

                String internalName = Strings.stripColors(modj.getString("name").toLowerCase());
                String lang = gm.getString("language", "");
                boolean isJava = modj.getBool("java", false) || javaLangs.contains(lang);

                Jval releases = releasesOutput.get(name);

                //skip outdated mods
                String minVersion = modj.getString("minGameVersion", "104");
                int minBuild = Strings.parseInt(minVersion.contains(".") ? minVersion.split("\\.")[0] : minVersion, 0);
                if(minBuild < 136 || (isJava && !modj.getBool("legacyCompatible", false) && minBuild < 154)){
                    continue;
                }

                //there may be version-specific releases that have a lower minGameVersion requirement, so choose the lowest possible minGameVersion
                if(releases != null){
                    for(var entry : releases.asObject()){
                        if(Strings.checkNewerSemver(minVersion, entry.key)){
                            minVersion = entry.key;
                        }
                    }
                }

                String metaName = Strings.stripColors(displayName).replace("\n", "");
                if(metaName.length() > maxLength) metaName = metaName.substring(0, maxLength) + "...";

                //skip templates
                if(metaName.equals("Java Mod Template") || metaName.equals("Template") || metaName.equals("Mod Template //the displayed mod name") || metaName.equals("Example Java Mod")){
                    continue;
                }

                Fi iconFile = icons.child(name.toLowerCase(Locale.ROOT).replace("/", "_"));

                obj.add("repo", name);
                obj.add("internalName", internalName);
                obj.add("name", metaName);
                obj.add("author", Strings.stripColors(modj.getString("author", gm.get("owner").get("login").toString())));
                obj.add("lastUpdated", gm.get("pushed_at"));
                obj.add("stars", gm.get("stargazers_count"));
                //java mods use the version at the latest release, falling back to the latest commit
                obj.add("version", isJava && latestReleaseVersions.containsKey(name) ? latestReleaseVersions.get(name) : modj.getString("version", "1.0.0"));
                obj.add("minGameVersion", minVersion);
                obj.add("hasIcon", Jval.valueOf(iconFile.exists()));
                obj.add("hasScripts", Jval.valueOf(lang.equals("JavaScript")));
                obj.add("hasJava", Jval.valueOf(isJava));
                obj.add("description", Strings.stripColors(modj.getString("description", "No description provided.")));
                if(modj.getBool("iosCompatible", false)) obj.put("iosCompatible", true);
                if(modj.getBool("legacyCompatible", false)) obj.put("legacyCompatible", true);
                if(iconFile.exists()) obj.put("iconHash", Strings.bytesToHex(iconFile.sha256()));
                if(releases != null && releases.isObject() && releases.asObject().size > 0){
                    obj.add("releases", releases);
                }

                //check every public-facing string value (name, author, description, version, releases...) against the word blacklist
                if(containsBlacklisted(obj)){
                    Log.info("&lc| &ly[@] Skipping, contains a blacklisted word.", name);
                    continue;
                }

                array.asArray().add(obj);
            }catch(Exception e){
                //ignore horribly malformed json
                Log.err(e);
            }
        }

        new Fi("mods.json").writeString(array.toString(Jformat.formatted));

        Log.info("&lcDone. Found @ valid mods.", array.asArray().size);
    }

    /**
     * Loads the word blacklist. The file is base64; decoded, it's a comma-separated list of words.
     * A word prefixed with '=' only matches as a whole word (plus common suffixes), others match anywhere, even inside other words.
     */
    static Seq<Pattern> loadWordBlacklist(Fi file){
        if(!file.exists()){
            Log.warn("@ not found, word blacklist is disabled.", file.name());
            return new Seq<>();
        }

        Seq<Pattern> patterns = new Seq<>();
        for(String entry : Base64Coder.decodeString(file.readString().replaceAll("\\s", "")).split(",")){
            String word = entry.trim().toLowerCase(Locale.ROOT);
            boolean wholeWord = word.startsWith("=");
            if(wholeWord) word = word.substring(1).trim();
            word = normalizeText(word).replaceAll("[^\\p{L}\\p{N}]", "");
            if(!word.isEmpty()) patterns.add(buildWordPattern(word, wholeWord));
        }

        Log.info("&lcLoaded @ blacklisted words.", patterns.size);
        return patterns;
    }

    /**
     * Builds a pattern that matches a word with lookalike characters, stretched letters ("woooord")
     * and a consistent separator between letters ("w o r d", "w.o.r.d", "w_o_r_d").
     */
    static Pattern buildWordPattern(String word, boolean wholeWord){
        StringBuilder out = new StringBuilder();
        if(wholeWord) out.append("(?<![\\p{L}\\p{N}])");

        for(int i = 0; i < word.length(); i++){
            char c = word.charAt(i);
            if(i == 1) out.append("([^\\p{L}\\p{N}]{0,3}?)");
            else if(i > 1) out.append("\\1");

            out.append("[").append(c);
            for(char alt : lookalikes.get(c, "").toCharArray()){
                if(!Character.isLetterOrDigit(alt)) out.append('\\');
                out.append(alt);
            }
            out.append("]+");
        }

        if(wholeWord) out.append("(?:e?s|ed|er|ers|ing|y)?(?![\\p{L}\\p{N}])");
        return Pattern.compile(out.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** Folds fullwidth/stylized/accented characters to their base form and removes invisible characters. */
    static String normalizeText(String text){
        return Normalizer.normalize(text, Normalizer.Form.NFKD).replaceAll("[\\p{M}\\p{Cf}]", "").toLowerCase(Locale.ROOT);
    }

    /** @return whether any string value in this Jval (recursively) contains a blacklisted word. */
    static boolean containsBlacklisted(Jval val){
        if(val == null) return false;

        if(val.isString()){
            String text = normalizeText(val.asString());
            return wordBlacklist.contains(p -> p.matcher(text).find());
        }
        if(val.isObject()){
            for(var entry : val.asObject()){
                //generated values that are not authored by the mod creator; hashes could produce false positives
                if(entry.key.equals("iconHash") || entry.key.equals("lastUpdated")) continue;
                if(containsBlacklisted(entry.value)) return true;
            }
        }else if(val.isArray()){
            for(Jval child : val.asArray()){
                if(containsBlacklisted(child)) return true;
            }
        }
        return false;
    }

    void print(StringBuilder buffer, String text, Object... args){
        buffer.append(Strings.format(text, args)).append("\n");
    }

    /** Fetches mod.json/mod.hjson (root or assets/) for a given repo at a given ref (branch, tag, or commit). */
    Jval fetchModJson(String name, String ref){
        return tryList(name + "/" + ref + "/mod.json", name + "/" + ref + "/mod.hjson", name + "/" + ref + "/assets/mod.json", name + "/" + ref + "/assets/mod.hjson");
    }

    /** Fetches all releases (all pages) for a repo. */
    Jval queryReleases(String name){
        Seq<Jval> all = new Seq<>();
        int page = 1;
        while(true){
            Jval[] result = {null};
            query("/repos/" + name + "/releases", of("per_page", perPage, "page", page), res -> result[0] = res);
            Jval res = result[0];
            if(res == null || !res.isArray() || res.asArray().isEmpty()) break;
            all.addAll(res.asArray());
            if(res.asArray().size < perPage) break;
            page++;
        }
        Jval arr = Jval.read("[]");
        for(Jval r : all) arr.asArray().add(r);
        return arr;
    }

    /** Builds a map of revision key ("BUILD" or "BUILD.REVISION") -> {id, version} for a repo's releases. */
    Jval buildReleaseMap(String name, Jval releases){
        if(releases == null || !releases.isArray() || releases.asArray().size == 0) return null;

        ObjectMap<String, Jval> latest = new ObjectMap<>();
        for(Jval release : releases.asArray()){
            if(release.getBool("draft", false)) continue;

            String key = parseVersionKey(release.getString("name", ""));
            if(key == null) key = parseVersionKey(release.getString("tag_name", ""));
            if(key == null) continue;

            Jval existing = latest.get(key);
            if(existing == null || isReleaseNewer(release, existing)){
                latest.put(key, release);
            }
        }

        Jval out = Jval.read("{}");
        for(var entry : latest){
            Jval release = entry.value;
            String tag = release.getString("tag_name");

            Jval modj = fetchModJson(name, tag);
            String version = (modj != null && modj.isObject()) ? modj.getString("version", "1.0.0") : "1.0.0";

            Jval releaseObj = Jval.read("{}");
            releaseObj.add("id", release.get("id"));
            releaseObj.add("version", Jval.valueOf(version));
            out.add(entry.key, releaseObj);
        }
        return out;
    }

    /** @return the most recently published non-draft release, or null if there are none. */
    @Nullable Jval findLatestRelease(Jval releases){
        if(releases == null || !releases.isArray()) return null;

        Jval latest = null;
        for(Jval release : releases.asArray()){
            if(release.getBool("draft", false) || release.getString("tag_name", "").isEmpty()) continue;
            if(latest == null || isReleaseNewer(release, latest)) latest = release;
        }
        return latest;
    }

    /** @return true if release {@code a} is more recently published than release {@code b}. */
    boolean isReleaseNewer(Jval a, Jval b){
        String da = a.getString("published_at", a.getString("created_at", ""));
        String db = b.getString("published_at", b.getString("created_at", ""));
        return da.compareTo(db) > 0;
    }

    /** Like parseVersionTag in Mindustry, but returns the raw key string ("BUILD" or "BUILD.REVISION") instead of parsed ints. */
    static @Nullable String parseVersionKey(String str){
        if(str == null) return null;

        Matcher m = modVersionPattern.matcher(str);
        while(m.find()){
            if(m.start() == 0 || m.end() == str.length()){
                String major = m.group(1);
                String minor = m.group(2);
                if(major != null && Strings.parseInt(major) < 15) return null;
                if(minor != null && Strings.parseInt(minor) == Integer.MIN_VALUE) return null;
                return minor != null ? major + "." + minor : major;
            }
        }
        return null;
    }

    Jval tryList(String... queries){
        Jval[] result = {null};
        for(String str : queries){
            //try to get mod.json instead
            Http.get("https://raw.githubusercontent.com/" + str)
            .timeout(10000)
            .error(this::simpleError)
            .block(out -> result[0] = Jval.read(out.getResultAsString()));
        }
        return result[0];
    }

    BufferedImage tryImage(String... queries){
        BufferedImage[] result = {null};
        for(String str : queries){
            //try to get mod.json instead
            Http.get("https://raw.githubusercontent.com/" + str)
            .timeout(10000)
            .error(this::simpleError)
            .block(out -> result[0] = ImageIO.read(out.getResultAsStream()));
        }
        return result[0];
    }

    void query(String url, @Nullable StringMap params, Cons<Jval> cons){
        Http.get(api + url + (params == null ? "" : "?" + params.keys().toSeq().map(entry -> Strings.encode(entry) + "=" + Strings.encode(params.get(entry))).toString("&")))
        .timeout(10000)
        .method(HttpMethod.GET)
        .header("authorization", githubToken)
        .header("accept", "application/vnd.github.baptiste-preview+json")
        .error(this::handleError)
        .block(response -> {
            Log.info("&lcSending search query. Status: @; Queries remaining: @/@", response.getStatus(), response.getHeader("X-RateLimit-Remaining"), response.getHeader("X-RateLimit-Limit"));
            cons.get(Jval.read(response.getResultAsString()));
        });
    }

    void simpleError(Throwable error){
        if(!(error instanceof HttpStatusException)){
            Log.info("&lc |&lr" + Strings.getSimpleMessage(error));
        }
    }

    void handleError(Throwable error){
        Log.err(error);
    }

}
