package modupdater;

import arc.*;
import arc.Net.*;
import arc.files.*;
import arc.func.*;
import arc.math.*;
import arc.struct.*;
import arc.util.ArcAnnotate.*;
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

    public static void main(String[] args){
        Core.net = makeNet();
        new ModUpdater();
    }

    {
        query("/search/repositories", of("q", searchTerm, "per_page", perPage), result -> {
            int total = result.getInt("total_count", 0);
            int pages = Mathf.ceil((float)total / perPage);

            for(int i = 1; i < pages; i++){
                query("/search/repositories", of("q", searchTerm, "per_page", perPage, "page", i + 1), secresult -> {
                    result.get("items").asArray().addAll(secresult.get("items").asArray());
                });
            }

            query("/search/repositories", of("q", "topic:mindustry-mod", "per_page", perPage), topicresult -> {
                Seq<Jval> dest = result.get("items").asArray();
                Seq<Jval> added = topicresult.get("items").asArray().select(v -> !dest.contains(o -> o.get("full_name").equals(v.get("full_name"))));
                dest.addAll(added);

                Log.info("\n&lcFound @ mods via topic.", added.size);
            });

            ObjectMap<String, Jval> output = new ObjectMap<>();
            ObjectMap<String, Jval> ghmeta = new ObjectMap<>();
            Seq<String> names = result.get("items").asArray().map(val -> {
                ghmeta.put(val.get("full_name").toString(), val);
                return val.get("full_name").toString();
            });

            names.remove("Anuken/ExampleMod");

            Log.info("&lcTotal mods found: @\n", names.size);

            Cons<Throwable> logger = t -> Log.info("&lc |&lr" + Strings.getSimpleMessage(t));
            int index = 0;
            for(String name : names){
                Jval[] modjson = {null};
                Log.info("&lc[@%] [@]&y: querying...", (int)((float)index++ / names.size * 100), name);
                try{
                    Core.net.httpGet("https://raw.githubusercontent.com/" + name + "/master/mod.json", out -> {
                        if(out.getStatus() == HttpStatus.OK){
                            //got mod.hjson
                            modjson[0] = Jval.read(out.getResultAsString());
                        }else if(out.getStatus() == HttpStatus.NOT_FOUND){
                            //try to get mod.json instead
                            Core.net.httpGet("https://raw.githubusercontent.com/" + name + "/master/mod.hjson", out2 -> {
                                if(out2.getStatus() == HttpStatus.OK){
                                    //got mod.json
                                    modjson[0] = Jval.read(out2.getResultAsString());
                                }
                            }, logger);
                        }
                    }, logger);
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

                obj.add("repo", name);
                obj.add("name", gmeta.get("name"));
                obj.add("author", modj.getString("author", gmeta.get("owner").get("login").toString()));
                obj.add("lastUpdated", gmeta.get("pushed_at"));
                obj.add("stars", gmeta.get("stargazers_count"));
                obj.add("description", modj.getString("description", modj.getString("description", "<none>")));
                array.asArray().add(obj);
            }

            new Fi("mods.json").writeString(array.toString(Jformat.formatted));

            Log.info("&lcCommitting files...");

            pexec("git", "add", "mods.json");
            pexec("git", "commit", "-m", "[auto-update]");
            pexec("git", "push");

            Log.info("&lcDone. Exiting.");
        });
    }

    void pexec(String... command){
        String res = OS.exec(command);
        if(!res.trim().replace("\n", "").isEmpty()){
            String p = "| &y " + res.replace("\n", "\n&lg| &y").replace("| &y\n", "");
            if(p.endsWith("| &y")) p = p.substring(0, p.length() - "| &y".length() - 4);
            Log.info(p);
        }
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
