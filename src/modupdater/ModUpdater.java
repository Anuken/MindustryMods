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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static arc.struct.StringMap.*;

public class ModUpdater{
    static final String api = "https://api.github.com", searchTerm = "mindustry mod";
    static final int perPage = 100;
    static final int maxLength = 55;
    static final ObjectSet<String> javaLangs = ObjectSet.with("Java", "Kotlin", "Groovy", "Scala"); //obviously not a comprehensive list
    static final ObjectSet<String> blacklist = ObjectSet.with("Snow-of-Spirit-Fox-Mori/old-mod", "fox1va-the-fox/schems", "TheSaus/Cumdustry", "Anuken/ExampleMod", "Anuken/ExampleJavaMod", "Anuken/ExampleKotlinMod", "Mesokrix/Vanilla-Upgraded", "RebornTrack970/Multiplayernt", "RebornTrack970/Multiplayerntnt", "RebornTrack970/Destroyer", "RebornTrack970/Mindustrynt", "NemesisTheory/killer", "TheDogOfChaos/reset-UUID-mindustry");
    static final Seq<String> nameBlacklist = Seq.with("o7", "pixaxeofpixie", "Iron-Miner", "EasyPlaySu", "guiYMOUR", "mishakorzik").map(s -> s.toLowerCase(Locale.ROOT));
    static final String[] topics = {"mindustry-mod"};
    static final int iconSize = 64;

    static final String githubToken = OS.prop("githubtoken");

    public static void main(String[] args){
        new ModUpdater();
    }

    {
        //register colors to facilitate their removal
        Colors.put("accent", Color.white);
        Colors.put("unlaunched",  Color.white);
        Colors.put("highlight",  Color.white);
        Colors.put("stat",  Color.white);

        query("/search/repositories", of("q", searchTerm, "per_page", perPage), result -> {
            int pages = Mathf.ceil(result.getFloat("total_count", 0) / perPage);

            for(int i = 1; i < pages; i++){
                query("/search/repositories", of("q", searchTerm, "per_page", perPage, "page", i + 1), secresult -> {
                    result.get("items").asArray().addAll(secresult.get("items").asArray());
                });
            }

            for(String topic : topics){
                query("/search/repositories", of("q", "topic:" + topic, "per_page", perPage), topicresult -> {
                    int pagesTopic = Mathf.ceil(result.getFloat("total_count", 0) / perPage);

                    for(int i = 1; i < pagesTopic; i++){
                        query("/search/repositories", of("q", "topic:" + topic, "per_page", perPage, "page", i + 1), secresult -> {
                            topicresult.get("items").asArray().addAll(secresult.get("items").asArray());
                        });
                    }

                    Seq<Jval> dest = result.get("items").asArray();
                    Seq<Jval> added = topicresult.get("items").asArray().select(v -> !dest.contains(o -> o.get("full_name").equals(v.get("full_name"))));
                    dest.addAll(added);

                    Log.info("\n&lcFound @ mods via topic: " + topic, added.size);
                });
            }

            result.get("items").asArray().removeAll(v -> v.getBool("is_template", false));

            ObjectMap<String, Jval> output = new ObjectMap<>();
            ObjectMap<String, Jval> ghmeta = new ObjectMap<>();
            Seq<String> names = result.get("items").asArray().map(val -> {
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

                        Jval modjson = tryList(name + "/" + branch + "/mod.json", name + "/" + branch + "/mod.hjson", name + "/" + branch + "/assets/mod.json", name + "/" + branch + "/assets/mod.hjson");

                        if(modjson == null){
                            print(buffer, "&lc| &lySkipping, no meta found.");
                            return;
                        }

                        if(modjson.getBool("hideBrowser", false)){
                            print(buffer, "&lc| &lySkipping, explicitly hidden in browser.");
                            return;
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
                        print(buffer, "&lc| &lySkipping. [@]", name, Strings.getSimpleMessage(t));
                    }finally{
                        Log.info(buffer.substring(0, buffer.length() - 1));
                    }
                });
            }

            Threads.await(exec);

            Log.info("&lcFound @ potential mods.", output.size);
            Seq<String> outnames = output.keys().toSeq();
            outnames.sort(Structs.comps(Comparator.comparingInt(s -> -ghmeta.get(s).getInt("stargazers_count", 0)), Structs.comparing(s -> ghmeta.get(s).getString("pushed_at"))));

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

                    //skip outdated mods
                    String version = modj.getString("minGameVersion", "104");
                    int minBuild = Strings.parseInt(version.contains(".") ? version.split("\\.")[0] : version, 0);
                    if(minBuild < 136){
                        continue;
                    }

                    String lang = gm.getString("language", "");

                    String metaName = Strings.stripColors(displayName).replace("\n", "");
                    if(metaName.length() > maxLength) metaName = metaName.substring(0, maxLength) + "...";

                    //skip templates
                    if(metaName.equals("Java Mod Template") || metaName.equals("Template") || metaName.equals("Mod Template //the displayed mod name") || metaName.equals("Example Java Mod")){
                        continue;
                    }

                    obj.add("repo", name);
                    obj.add("internalName", internalName);
                    obj.add("name", metaName);
                    obj.add("author", Strings.stripColors(modj.getString("author", gm.get("owner").get("login").toString())));
                    obj.add("lastUpdated", gm.get("pushed_at"));
                    obj.add("stars", gm.get("stargazers_count"));
                    obj.add("minGameVersion", version);
                    obj.add("hasScripts", Jval.valueOf(lang.equals("JavaScript")));
                    obj.add("hasJava", Jval.valueOf(modj.getBool("java", false) || javaLangs.contains(lang)));
                    obj.add("description", Strings.stripColors(modj.getString("description", "No description provided.")));
                    array.asArray().add(obj);
                }catch(Exception e){
                    //ignore horribly malformed json
                    Log.err(e);
                }
            }

            new Fi("mods.json").writeString(array.toString(Jformat.formatted));

            Log.info("&lcDone. Found @ valid mods.", array.asArray().size);
        });
    }

    void print(StringBuilder buffer, String text, Object... args){
        buffer.append(Strings.format(text, args)).append("\n");
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
