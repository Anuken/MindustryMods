package modupdater;

import arc.*;
import arc.Net.*;
import arc.files.*;
import arc.func.*;
import arc.graphics.*;
import arc.math.*;
import arc.struct.*;
import arc.util.*;
import arc.util.async.*;
import arc.util.serialization.*;
import arc.util.serialization.Jval.*;

import java.util.*;

import static arc.struct.StringMap.*;

public class ModUpdater{
    static final String api = "https://api.github.com", searchTerm = "mindustry mod";
    static final int perPage = 100;
    static final ObjectSet<String> javaLangs = ObjectSet.with("Java", "Kotlin", "Groovy"); //obviously not a comprehensive list
    static final ObjectSet<String> blacklist = ObjectSet.with("TheSaus/Cumdustry"); //really?

    public static void main(String[] args){
        Core.net = makeNet();
        new ModUpdater();
    }

    {
        //register colors to facilitate their removal
        Colors.put("accent", Color.white);
        Colors.put("unlaunched",  Color.white);
        Colors.put("highlight",  Color.white);
        Colors.put("stat",  Color.white);

        query("/search/repositories", of("q", searchTerm, "per_page", perPage), result -> {
            int total = result.getInt("total_count", 0);
            int pages = Mathf.ceil((float)total / perPage);

            for(int i = 1; i < pages; i++){
                query("/search/repositories", of("q", searchTerm, "per_page", perPage, "page", i + 1), secresult -> {
                    result.get("items").asArray().addAll(secresult.get("items").asArray());
                });
            }

            for(String topic : new String[]{"mindustry-mod", "mindustry-mod-v6"}){
                query("/search/repositories", of("q", "topic:" + topic, "per_page", perPage), topicresult -> {
                    Seq<Jval> dest = result.get("items").asArray();
                    Seq<Jval> added = topicresult.get("items").asArray().select(v -> !dest.contains(o -> o.get("full_name").equals(v.get("full_name"))));
                    dest.addAll(added);

                    Log.info("\n&lcFound @ mods via topic: " + topic, added.size);
                });
            }

            ObjectMap<String, Jval> output = new ObjectMap<>();
            ObjectMap<String, Jval> ghmeta = new ObjectMap<>();
            Seq<String> names = result.get("items").asArray().map(val -> {
                ghmeta.put(val.get("full_name").toString(), val);
                return val.get("full_name").toString();
            });

            names.remove("Anuken/ExampleMod");
            names.remove("Anuken/ExampleJavaMod");

            Log.info("&lcTotal mods found: @\n", names.size);

            int index = 0;
            for(String name : names){
                if(blacklist.contains(name)) continue;

                Log.info("&lc[@%] [@]&y: querying...", (int)((float)index++ / names.size * 100), name);
                try{
                    String branch = ghmeta.get(name).getString("default_branch");
                    Jval modjson = tryList(name + "/" + branch + "/mod.json", name + "/" + branch + "/mod.hjson", name + "/" + branch + "/assets/mod.json", name + "/" + branch + "/assets/mod.hjson");

                    if(modjson == null){
                        Log.info("&lc| &lySkipping, no meta found.");
                        continue;
                    }

                    Log.info("&lc|&lg Found mod meta file!");
                    output.put(name, modjson);
                }catch(Throwable t){
                    Log.info("&lc| &lySkipping. [@]", name, Strings.getSimpleMessage(t));
                }
            }

            Log.info("&lcFound @ valid mods.", output.size);
            Seq<String> outnames = output.keys().toSeq();
            outnames.sort(Structs.comps(Comparator.comparingInt(s -> -ghmeta.get(s).getInt("stargazers_count", 0)), Structs.comparing(s -> ghmeta.get(s).getString("pushed_at"))));

            Log.info("&lcCreating mods.json file...");
            Jval array = Jval.read("[]");
            for(String name : outnames){
                Jval gm = ghmeta.get(name);
                Jval modj = output.get(name);
                Jval obj = Jval.read("{}");
                String displayName = Strings.stripColors(modj.getString("displayName", "")).replace("\\n", "");
                if(displayName.isEmpty()) displayName = gm.getString("name");

                //skip outdated mods
                String version = modj.getString("minGameVersion", "104");
                int minBuild = Strings.parseInt(version.contains(".") ? version.split("\\.")[0] : version, 0);
                if(minBuild < 105){
                    continue;
                }

                String lang = gm.getString("language", "");

                obj.add("repo", name);
                obj.add("name", Strings.stripColors(displayName).replace("\n", ""));
                obj.add("author", Strings.stripColors(modj.getString("author", gm.get("owner").get("login").toString())));
                obj.add("lastUpdated", gm.get("pushed_at"));
                obj.add("stars", gm.get("stargazers_count"));
                obj.add("minGameVersion", version);
                obj.add("hasScripts", Jval.valueOf(lang.equals("JavaScript")));
                obj.add("hasJava", Jval.valueOf(modj.getBool("java", false) || javaLangs.contains(lang)));
                obj.add("description", Strings.stripColors(modj.getString("description", "<none>")));
                array.asArray().add(obj);
            }

            new Fi("mods.json").writeString(array.toString(Jformat.formatted));

            Log.info("&lcDone. Exiting.");
        });
    }

    Jval tryList(String... queries){
        Jval[] result = {null};
        for(String str : queries){
            //try to get mod.json instead
            Core.net.httpGet(str, out -> { //hjson
                if(out.getStatus() == HttpStatus.OK){
                    result[0] = Jval.read(out.getResultAsString());
                }
            }, t -> Log.info("&lc |&lr" + Strings.getSimpleMessage(t)));
        }
        return result[0];
    }

    void query(String url, @Nullable StringMap params, Cons<Jval> cons){
        Core.net.http(new HttpRequest()
            .timeout(10000)
            .method(HttpMethod.GET)
            .url(api + url + (params == null ? "" : "?" + params.keys().toSeq().map(entry -> Strings.encode(entry) + "=" + Strings.encode(params.get(entry))).toString("&"))), response -> {
            Log.info("&lcSending search query. Status: @; Queries remaining: @/@", response.getStatus(), response.getHeader("X-RateLimit-Remaining"), response.getHeader("X-RateLimit-Limit"));
            try{
                cons.get(Jval.read(response.getResultAsString()));
            }catch(Throwable error){
                handleError(error);
            }
        }, this::handleError);
    }

    void handleError(Throwable error){
        error.printStackTrace();
    }

    static Net makeNet(){
        Net net = new Net();
        //use blocking requests
        Reflect.set(NetJavaImpl.class, Reflect.get(net, "impl"), "asyncExecutor", new AsyncExecutor(1){
            public <T> AsyncResult<T> submit(final AsyncTask<T> task){
                try{
                    task.call();
                }catch(Exception e){
                    throw new RuntimeException(e);
                }
                return null;
            }
        });

        return net;
    }
}
