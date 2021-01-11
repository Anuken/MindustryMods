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
    static final String api = "https://api.github.com";
    static final String searchTerm = "mindustry mod";
    static final int perPage = 100;
    static final String[] modFiles = new String[]{"/6.0/mod.json", "/6.0/mod.hjson", "/master/mod.json", "/master/mod.hjson"};

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

            Cons<Throwable> logger = t -> Log.info("&lc |&lr" + Strings.getSimpleMessage(t));
            int index = 0;
            for(String name : names){
                Jval[] modjson = {null};
                Log.info("&lc[@%] [@]&y: querying...", (int)((float)index++ / names.size * 100), name);
                try{
                    modjson[0] = tryFetch(name, logger, 0);
                }catch(Throwable t){
                    Log.info("&lc| &lySkipping. [@]", name, Strings.getSimpleMessage(t));
                }

                if(modjson[0] == null){
                    Log.info("&lc| &lySkipping, no meta found.");
                    continue;
                }

                Log.info("&lc|&lg Found mod meta file!");
                output.put(name, modjson[0]);
            }

            Log.info("&lcFound @ valid mods.", output.size);
            Seq<String> outnames = output.keys().toSeq();
            outnames.sort(Structs.comps(Comparator.comparingInt(s -> -ghmeta.get(s).getInt("stargazers_count", 0)), Structs.comparing(s -> ghmeta.get(s).getString("pushed_at"))));

            Log.info("&lcCreating mods.json file...");
            Jval array = Jval.read("[]");
            for(String name : outnames){
                Jval gmeta = ghmeta.get(name);
                Jval modj = output.get(name);
                Jval obj = Jval.read("{}");
                String displayName = Strings.stripColors(modj.getString("displayName", "")).replace("\\n", "");
                if(displayName.isEmpty()) displayName = gmeta.getString("name");

                //skip outdated mods
                String version = modj.getString("minGameVersion", "104");
                int minBuild = Strings.parseInt(version.contains(".") ? version.split("\\.")[0] : version, 0);
                if(minBuild < 105){
                    continue;
                }

                String lang = gmeta.has("language") && !gmeta.get("language").isNull() ? gmeta.getString("language") : "";

                obj.add("repo", name);
                obj.add("name", Strings.stripColors(displayName));
                obj.add("author", Strings.stripColors(modj.getString("author", gmeta.get("owner").get("login").toString())));
                obj.add("lastUpdated", gmeta.get("pushed_at"));
                obj.add("stars", gmeta.get("stargazers_count"));
                obj.add("minGameVersion", version);
                obj.add("hasScripts", Jval.valueOf(lang.equals("JavaScript")));
                obj.add("hasJava", Jval.valueOf(lang.equals("Java")));
                obj.add("description", Strings.stripColors(modj.getString("description", "<none>")));
                array.asArray().add(obj);
            }

            new Fi("mods.json").writeString(array.toString(Jformat.formatted));

            Log.info("&lcDone. Exiting.");
        });
    }
    
    Jval tryFetch(String name, Cons<Throwable> logger, int tries){
        if(tries >= modFiles.length) return null;
        Core.net.httpGet("https://raw.githubusercontent.com/" + name + modFiles[tries], out -> {
            if(out.getStatus() == HttpStatus.OK){
                //got mod.(h)json
                return Jval.read(out.getResultAsString());
            }else if(out.getStatus() == HttpStatus.NOT_FOUND){
                //try to get the next mod.(h)json instead
                return tryFetch(name, logger, tries + 1);
            }
        }, logger);
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
