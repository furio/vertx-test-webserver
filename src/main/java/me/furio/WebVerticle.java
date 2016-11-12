package me.furio;

import com.google.common.net.InetAddresses;
import io.vertx.core.*;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.CookieHandler;
import io.vertx.ext.web.handler.CorsHandler;
import me.furio.utils.AsyncResultGenerator;
import me.furio.utils.LanguagePair;
import org.apache.commons.codec.binary.StringUtils;
import org.apache.commons.validator.routines.EmailValidator;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.index.query.QueryBuilders;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/*
        Il sistema è simile a node.js c'e' un event loop mono-thread che riceve le connessioni ed esegue qualcosa
        Con la differenza che qua è multi-thread, quindi l'event-loop distribuisce
        Questo significa anche che non puoi fare operazioni onerose sugli handler di risposta... ma 8(vedi dopo)
 */

// Ogni cosa che vuoi che usa vertx deve estendere AbstractVerticle
public class WebVerticle extends AbstractVerticle {
    private TransportClient esClient;

    // c'e' un inzio
    @Override
    public void start(Future<Void> futStart) {
        // Se tutto va in porto il future và segnalato come ok
        // Questa classe viene lanciata da un launcher di vertx in modalità standalone o clustered [hazelcast] quindi volendo puoi fare impicci clustered a-la mmt
        // ah si usa il nuovo java quindi lambda a gogo xD

        startEsClient((es) -> {
            if ( es.failed() ) {
                futStart.fail(es.cause());
            } else {
                startWebApp((http) -> completeStartup(http, futStart));
            }
        });
    }

    // ed una fine
    @Override
    public void stop() throws Exception {
        this.esClient.close();
    }

    private void startEsClient(Handler<AsyncResult<Void>> next) {
        // Vabbè inzializza es in modalità client node
        // Questo puo' essere fatto meglio, facendo un altro verticle in modalità worker, e usando l'eventbus per scambiare dati tra questo verticle e quello di es

        Throwable t = null;

        try {
            // config() è di vert.x greppa una config (json)
            // nel pom capisci da dove la prende xD
            this.esClient = TransportClient.builder().build()
                    .addTransportAddress(new InetSocketTransportAddress(InetAddress.getByName(config().getString("es.host")), config().getInteger("es.port")));
        } catch (Exception e) {
            t = e;
        }

        // asyncresult generaotr è una mia per generare gli handle di risposta
        next.handle(AsyncResultGenerator.Generate(t, t != null, null));
    }

    private void startWebApp(Handler<AsyncResult<HttpServer>> next) {
        // ce serve un router
        Router router = Router.router(vertx);
        // leggiamo i cookie, non serve un cazzo ma nun se sà mai
        router.route().handler(CookieHandler.create());
        // daje con le cors, sono api
        router.route().handler(CorsHandler.create("vertx\\.io").allowedMethod(HttpMethod.GET));


        // La root, cosi' per, nota il :: per dire che per la root la lmbda è quella funzione
        router.route("/").handler(this::getRoot);
        // health check per haproxy/nginx, ed è un blovkinghandler... ovvero la magia, se un handler è marcato cosi'
        // sposta il lavoro su un thread pool che non gestisce gli eventi, quindi non rallenta il sistema
        router.route("/health").blockingHandler(this::getHealth);

        // per lo stesso pth puoi dare diversi handler, come se fossero vari filtri,
        // verranno eseguiti in catena a meno che non blocchi

        // Parsa la query string
        router.get("/get").handler(this::getTranslationQueryParse);
        // Pre processing
        router.get("/get").handler(this::getTranslationPreProcess);
        // Chiedi a es
        router.get("/get").blockingHandler(this::getTranslationElastic);
        // Post processing + output
        router.get("/get").handler(this::getTranslationPostProcess);

        // fai partire il webserver
        vertx
                .createHttpServer()
                .requestHandler(router::accept)
                .listen(
                        config().getInteger("http.port", 8080),
                        next::handle
                );
    }

    private void completeStartup(AsyncResult<HttpServer> http, Future<Void> fut) {
        if (http.succeeded()) {
            fut.complete();
        } else {
            fut.fail(http.cause());
        }
    }

    private void getRoot(RoutingContext routingContext) {
        HttpServerResponse response = routingContext.response();
        response
                .putHeader("content-type", "text/html")
                .end("<h1>vert.x proto</h1>");
    }

    private void getHealth(RoutingContext routingContext) {
        HttpServerResponse response = routingContext.response();

        if (!this.esClient.connectedNodes().isEmpty()) {
            response.putHeader("content-type", "text/plain").end("ok");
        } else {
            response.putHeader("content-type", "text/plain").setStatusCode(500).end("fail");
        }
    }

    private void getTranslationQueryParse(RoutingContext routingContext) {
        // Abbastanza ovvio, damme i parametri
        final String q = routingContext.request().getParam("q");
        final String langpair = routingContext.request().getParam("langpair");
        final String of = routingContext.request().getParam("of");
        final String mt = routingContext.request().getParam("mt");
        final String key = routingContext.request().getParam("key");
        final String onlyprivate = routingContext.request().getParam("onlyprivate");
        final String ip = routingContext.request().getParam("ip");
        final String de = routingContext.request().getParam("de");
        final String user = routingContext.request().getParam("user");

        // per ogni parametro parsa/valida

        // Validate query
        if (org.apache.commons.lang.StringUtils.isEmpty(q) || StringUtils.getBytesUtf8(q).length > config().getInteger("query.string.maxlength")) {
            sendError(routingContext,"Query is malformed");
            return;
        }
        routingContext.put("q", q);

        // Validate language pair
        if (org.apache.commons.lang.StringUtils.isEmpty(langpair)) {
            sendError(routingContext, "Specify a language pair");
            return;
        }

        LanguagePair lpData = null;
        try {
            lpData = LanguagePair.fromQueryString(langpair, config().getString("query.languagepair.separator"));
        } catch(Exception e) {
            sendError(routingContext,e.getMessage());
        }

        if (lpData == null) {
            return;
        }
        routingContext.put("langpair", lpData);

        // Validate of
        if (org.apache.commons.lang.StringUtils.isEmpty(of)) {
            routingContext.put("of", config().getString("query.of.default"));
        } else {
            if (config().getJsonArray("query.of.values").getList().contains(of)) {
                routingContext.put("of", of);
            } else {
                sendError(routingContext,"Invalid of " + of);
                return;
            }
        }

        // Machine translation
        if (org.apache.commons.lang.StringUtils.isEmpty(mt)) {
            routingContext.put("mt", true);
        } else {
            routingContext.put("mt", mt.equals("1"));
        }

        // Key
        if (!org.apache.commons.lang.StringUtils.isEmpty(key)) {
            routingContext.put("key", key);
        }

        // Only private
        if (org.apache.commons.lang.StringUtils.isEmpty(onlyprivate)) {
            routingContext.put("onlyprivate", false);
        } else {
            routingContext.put("onlyprivate", onlyprivate.equals("1"));
        }

        // Email
        if (!org.apache.commons.lang.StringUtils.isEmpty(de)) {
            if ( EmailValidator.getInstance().isValid(de) ) {
                routingContext.put("email", de);
            } else {
                sendError(routingContext, "Email is not valid " + de);
                return;
            }
        }

        // Ip
        routingContext.put("ip", incomingIp(routingContext, ip));

        // Prossimo handler
        routingContext.next();
    }

    private void getTranslationPreProcess(RoutingContext routingContext) {
        // Vedi te xD
        routingContext.next();
    }

    private void getTranslationElastic(RoutingContext routingContext) {
        // Pija la robba parsata prima e messa nel contesto
        // E chiama es

        LanguagePair langCtx = (LanguagePair)routingContext.get("langpair");

        SearchResponse response = this.esClient.prepareSearch(langCtx.sourceLanguage + "_" + langCtx.targetLanguage)
                .setTypes("tiponub")
                .setSearchType(SearchType.DFS_QUERY_THEN_FETCH)
                .setQuery(QueryBuilders.termQuery("data", routingContext.get("q")))
                .setPostFilter(QueryBuilders.rangeQuery("points").from(12).to(18))
                .setFrom(0).setSize(100)
                .addFields("data","points")
                .execute()
                .actionGet();

        // Vabbè viva le lambda xD
        List<JsonObject> results = Arrays.stream(response.getHits().hits()).map((hit) -> new JsonObject(){{
            this.put("data", (String)hit.field("data").getValue());
            this.put("points", (int)hit.field("points").getValue());
        }}).collect(Collectors.toList());

        // Metti i risultati nel contestp
        routingContext.put("results", results);

        routingContext.next();
    }

    private void getTranslationPostProcess(RoutingContext routingContext) {
        // rispondi

        JsonObject response = new JsonObject();
        response.put("query", (String)routingContext.get("q"));
        response.put("results", (List<JsonObject>)routingContext.get("results"));

        routingContext.response()
                .putHeader("content-type", "application/json")
                .end(response.encodePrettily());
    }

    private String incomingIp(RoutingContext routingContext, String incomingIp) {
        // Qualcosa per prendere gli ip tra i tuoi argomenti e gli header

        String currentIp = routingContext.request().remoteAddress().host();

        String forwardedFor = routingContext.request().headers().entries().stream()
                .filter(x -> x.getKey().equalsIgnoreCase("x-forwarded-for"))
                .map(x -> x.getValue())
                .collect(Collectors.joining())
                .split(",")[0];

        if (!org.apache.commons.lang.StringUtils.isEmpty(incomingIp) && InetAddresses.isInetAddress(incomingIp)) {
            currentIp = incomingIp;
        }

        if (InetAddresses.isInetAddress(forwardedFor) ) {
            currentIp = forwardedFor;
        }

        return currentIp;
    }

    private void sendError(RoutingContext routingContext, String message) {
        JsonObject errorMessage = new JsonObject();
        errorMessage.put("err", message);

        routingContext.response()
                .putHeader("content-type", "application/json")
                .end(errorMessage.encodePrettily());
    }
}
