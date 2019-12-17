package modupdater;

import io.anuke.arc.*;
import io.anuke.arc.Net.*;
import io.anuke.arc.collection.*;
import io.anuke.arc.func.*;
import io.anuke.arc.math.*;
import io.anuke.arc.util.ArcAnnotate.*;
import io.anuke.arc.util.*;
import io.anuke.arc.util.async.*;
import io.anuke.arc.util.serialization.*;

import static io.anuke.arc.collection.StringMap.of;

public class ModUpdater{
    static final String api = "https://api.github.com";
    static final String searchTerm = "mindustry mod";
    static final int perPage = 40;

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
                Array<Jval> dest = result.get("items").asArray();
                Array<Jval> added = topicresult.get("items").asArray().select(v -> !dest.contains(o -> o.get("full_name").equals(v.get("full_name"))));
                dest.addAll(added);

                Log.info("\n&lcFound {0} mods via topic.", added.size);
            });

            Array<String> names = result.get("items").asArray().map(val -> val.get("full_name").toString());

            Log.info("&lcTotal mods found: {0}\n", names.size);

            ObjectMap<String, Jval> output = new ObjectMap<>();
            Cons<Throwable> logger = t -> Log.info("&lc |&lr" + Strings.getSimpleMessage(t));
            int index = 0;
            for(String name : names){
                Jval[] modjson = {null};
                Log.info("&lc[{0}%] [{1}]&y: querying...", (int)((float)index++ / names.size * 100), name);
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
                    Log.info("&lc| &lySkipping. [{1}]", name, Strings.getSimpleMessage(t));
                }

                if(modjson[0] == null){
                    Log.info("&lc| &lySkipping, no mod.{h}json found.");
                    continue;
                }

                Log.info("&lc|&lg Found mod meta file!");
                output.put(name, modjson[0]);
            }

            Log.info("&lcFound {0} valid mods.", output.size);
        });
    }

    void query(String url, @Nullable StringMap params, Cons<Jval> cons){
        Core.net.httpGet(api + url + (params == null ? "" : "?" + params.keys().toArray().map(entry -> Strings.encode(entry) + "=" + Strings.encode(params.get(entry))).toString("&")), response -> {
            Log.info("&lcQuery. Status: {0}; Queries remaining: {1}/{2}", response.getStatus(), response.getHeader("X-RateLimit-Remaining"), response.getHeader("X-RateLimit-Limit"));
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
