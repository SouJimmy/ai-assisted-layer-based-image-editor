package editor;

import com.sun.net.httpserver.*;
import editor.integration.*;
import editor.processing.*;
import static editor.processing.Processing.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.zip.*;
import javax.imageio.ImageIO;

/** Boundary/contract regression tests. Loopback only; no external service or model required. */
public final class CoverageTests {
    static final AtomicInteger checks=new AtomicInteger();
    static void check(boolean condition,String message) {checks.incrementAndGet();if(!condition)throw new AssertionError(message);}
    static void invalid(Runnable action) {
        try {action.run();throw new AssertionError("invalid input accepted");}catch(IllegalArgumentException expected){checks.incrementAndGet();}
    }
    static Throwable error(CompletableFuture<?> future) throws Exception {
        try {future.get(8,TimeUnit.SECONDS);throw new AssertionError("expected failure");}
        catch(ExecutionException e) {return e.getCause();}catch(CancellationException e) {return e;}
    }
    static void code(CompletableFuture<?> future,String expected) throws Exception {
        Throwable e=error(future);check(e instanceof ServiceFailure && ((ServiceFailure)e).code().equals(expected),"expected "+expected+", got "+e);
    }
    static BufferedImage image() {return new BufferedImage(2,2,BufferedImage.TYPE_INT_ARGB_PRE);}
    static byte[] png(BufferedImage image) throws IOException {var out=new ByteArrayOutputStream();ImageIO.write(image,"PNG",out);return out.toByteArray();}
    static String mask() throws IOException {
        var image=new BufferedImage(2,2,BufferedImage.TYPE_BYTE_GRAY);image.getRaster().setSample(0,0,0,255);
        return Base64.getEncoder().encodeToString(png(image));
    }
    static String id() {return UUID.randomUUID().toString();}
    static List<HelperClient.Point> points() {return List.of(new HelperClient.Point(0,0,1));}
    static class Reply {
        int status;byte[] body;String api="1",requestId=null;
        Reply(int status,Object body) {this.status=status;this.body=body instanceof byte[] bytes?bytes:Json.write(body).getBytes(StandardCharsets.UTF_8);}
    }
    static class Server implements AutoCloseable {
        final HttpServer server;
        final ExecutorService pool=Executors.newCachedThreadPool();
        final String imageId=id(),instance=id();
        volatile Function<HttpExchange,Reply> handler=this::normal;
        final AtomicReference<Throwable> failure=new AtomicReference<>();
        Server() throws IOException {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(pool);
            server.createContext("/",exchange->{
                try {
                    check("Bearer secret".equals(exchange.getRequestHeaders().getFirst("Authorization")),"auth header");
                    Reply r=handler.apply(exchange);
                    exchange.getResponseHeaders().set("X-Api-Version",r.api);
                    exchange.getResponseHeaders().set("X-Request-Id",r.requestId==null?exchange.getRequestHeaders().getFirst("X-Request-Id"):r.requestId);
                    exchange.sendResponseHeaders(r.status,r.status==204?-1:r.body.length);
                    if(r.status!=204)exchange.getResponseBody().write(r.body);
                } catch(IOException expectedDisconnect) {}
                catch(Throwable e) {failure.set(e);}
                finally {exchange.close();}
            });server.start();
        }
        URI uri() {return URI.create("http://127.0.0.1:"+server.getAddress().getPort());}
        HelperClient client() {return new HelperClient(uri(),"secret");}
        Map<String,Object> registration() {return new LinkedHashMap<>(Map.of("imageId",imageId,"sourceRevision","r","width",2,"height",2,"serviceInstanceId",instance));}
        Map<String,Object> result(String requestId) {
            try {return new LinkedHashMap<>(Map.of("imageId",imageId,"sourceRevision","r","requestId",requestId,"width",2,"height",2,
                    "deviceUsed","cpu","candidates",List.of(new LinkedHashMap<>(Map.of("candidateId",id(),"score",.8,"maskPngBase64",mask())))));}
            catch(IOException e) {throw new UncheckedIOException(e);}
        }
        Reply normal(HttpExchange exchange) {
            String path=exchange.getRequestURI().getPath();
            if(path.equals("/image"))return new Reply(201,registration());
            if(path.equals("/segment"))return new Reply(200,result(exchange.getRequestHeaders().getFirst("X-Request-Id")));
            if(path.startsWith("/image/"))return new Reply(204,new byte[0]);
            return new Reply(200,Map.of("serviceInstanceId",instance,"aiReady",true));
        }
        public void close() {server.stop(0);pool.shutdownNow();if(failure.get()!=null)throw new AssertionError(failure.get());}
    }
    public static void main(String[] args) throws Exception {
        inputBoundaries();jsonBoundaries();psdValidation();httpContracts();responseOrdering();httpCancellation();managerLifecycle();cli();
        System.out.println("Additional Java contract checks passed: "+checks);
    }
    static void inputBoundaries() throws Exception {
        var p=new ImageProcessor();var s=image();
        List<Request> invalid=List.of(new Request(null,Operation.BLUR,new Blur(1)),new Request(s,null,new None()),new Request(s,Operation.BLUR,null),
            new Request(s,Operation.CROP,new Crop(null)),new Request(s,Operation.CROP,new Crop(new Rect(-1,0,1,1))),new Request(s,Operation.CROP,new Crop(new Rect(0,0,0,1))),
            new Request(s,Operation.RESIZE,new Resize(0,2)),new Request(s,Operation.RESIZE,new Resize(2,16385)),
            new Request(s,Operation.BLUR,new Blur(-1)),new Request(s,Operation.BLUR,new Blur(21)),
            new Request(s,Operation.MOSAIC,new Mosaic(0)),new Request(s,Operation.MOSAIC,new Mosaic(129)),
            new Request(s,Operation.COLOR_ADJUST,new ColorAdjust(256,1,1)),new Request(s,Operation.COLOR_ADJUST,new ColorAdjust(-256,1,1)),
            new Request(s,Operation.COLOR_ADJUST,new ColorAdjust(0,-1,1)),new Request(s,Operation.COLOR_ADJUST,new ColorAdjust(0,4,1)),
            new Request(s,Operation.COLOR_ADJUST,new ColorAdjust(0,1,-1)),new Request(s,Operation.COLOR_ADJUST,new ColorAdjust(0,1,4)),
            new Request(s,Operation.COLOR_ADJUST,new ColorAdjust(0,1,Double.POSITIVE_INFINITY)));
        for(Request r:invalid)try {p.process(r);throw new AssertionError("invalid processor accepted");}catch(Failure e){check(e.code()==Code.INVALID_ARGUMENT,"processor error code");}
        try {p.process(new Request(s,Operation.BLUR,new Blur(1)),null);throw new AssertionError();}catch(Failure expected){checks.incrementAndGet();}
        var rgb=new BufferedImage(1,1,BufferedImage.TYPE_INT_RGB);rgb.setRGB(0,0,0x112233);
        check(p.process(new Request(rgb,Operation.NEGATIVE,new None())).image().getRGB(0,0)==0xffeeddcc,"RGB normalization");
        check(Filters.sepia().apply(rgb)!=rgb && Filters.negative().apply(rgb)!=rgb,"filter ownership");
        Thread.currentThread().interrupt();try {p.process(new Request(s,Operation.BLUR,new Blur(1)));throw new AssertionError();}catch(Failure e){check(e.code()==Code.CANCELLED,"interrupt cancellation");}finally {Thread.interrupted();}
        var executor=new ProcessingExecutor();executor.close();
        try {executor.submit(new Request(s,Operation.BLUR,new Blur(1))).join();throw new AssertionError();}
        catch(CompletionException e){check(e.getCause() instanceof Failure,"closed executor");}
    }
    static void jsonBoundaries() {
        Map<String,Object> values=new LinkedHashMap<>();values.put("text","\"\\\n\r\t\b\f\u0001");values.put("null",null);values.put("list",List.of(false,2.5));
        check(Json.parse(Json.write(values)).equals(values),"all JSON escapes round trip");
        check(Json.parse("\"\\u65e5\\/\\b\\f\\r\\t\"").equals("日/\b\f\r\t"),"unicode and escapes");
        check(Json.parse(" -12.5e+2 ").equals(-1250.0),"exponent");
        for(String text:List.of("", "[", "{", "\"", "\"\\", "\"\\x\"", "\"\\uqqqq\"", "\"\\u12\"", "\"\n\"", "-", "1.", "1e", "1e+", "[1", "{1:2}", "{\"x\" 1}", "[".repeat(66)))invalid(()->Json.parse(text));
        invalid(()->Json.write(Double.NaN));invalid(()->Json.write(new Object()));invalid(()->Json.write(Map.of(1,2)));
        invalid(()->Json.object(List.of()));invalid(()->Json.string(Map.of("x",1),"x"));invalid(()->Json.integer(Map.of("x",1.5),"x"));invalid(()->Json.list(Map.of("x",1),"x"));
        Object deep="end";for(int i=0;i<66;i++)deep=List.of(deep);Object value=deep;invalid(()->Json.write(value));
    }
    static Map<String,Object> manifest() {
        Map<String,Object> node=new LinkedHashMap<>(Map.of("id","node","name","層","kind","pixel","x",-1,"y",2,"width",2,"height",2,"siblingIndex",0,"opacity",1.0,"effectiveOpacity",1.0));
        node.put("parentId",null);node.put("selfVisible",true);node.put("effectiveVisible",false);node.put("assetId","asset");node.put("importDisposition","RASTER");node.put("originalMetadata",Map.of("items",List.of("original")));
        return new LinkedHashMap<>(Map.of("schemaVersion",1,"canvasWidth",2,"canvasHeight",2,"nodes",new ArrayList<>(List.of(node)),
            "assets",new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("assetId","asset","path","assets/asset.png","width",2,"height",2)))),
            "warnings",new ArrayList<>(List.of(Map.of("code","TEST","message","test warning")))));
    }
    static byte[] archive(Map<String,Object> manifest,Map<String,byte[]> extra) throws IOException {
        var bytes=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(bytes)) {
            for(var entry:extra.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}
            zip.putNextEntry(new ZipEntry("manifest.json"));zip.write(Json.write(manifest).getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        }return bytes.toByteArray();
    }
    static Map<String,byte[]> assets() throws IOException {return Map.of("assets/asset.png",png(image()));}
    @SuppressWarnings("unchecked") static Map<String,Object> first(Map<String,Object> m,String key) {return Json.object(((List<?>)m.get(key)).get(0));}
    static void badArchive(Consumer<Map<String,Object>> mutation) throws IOException {
        Map<String,Object> m=manifest();mutation.accept(m);try {HelperClient.readPsdArchive(archive(m,assets()));throw new AssertionError("bad manifest accepted");}
        catch(ServiceFailure e) {check(e.code().equals("INVALID_RESPONSE"),"PSD rejection code");}
    }
    static void psdValidation() throws Exception {
        var valid=HelperClient.readPsdArchive(archive(manifest(),assets()));
        check(valid.canvasWidth()==2 && valid.assets().size()==1 && valid.warnings().size()==1,"valid PSD DTO");
        try {valid.nodes().get(0).put("name","mutated");throw new AssertionError();}catch(UnsupportedOperationException expected){checks.incrementAndGet();}
        for(String key:List.of("schemaVersion","canvasWidth","canvasHeight"))badArchive(m->m.put(key,-1));
        for(String key:List.of("width","height","siblingIndex"))badArchive(m->first(m,"nodes").put(key,-1));
        for(String key:List.of("opacity","effectiveOpacity"))for(Object value:List.of(-.1,1.1,"one"))badArchive(m->first(m,"nodes").put(key,value));
        for(String key:List.of("selfVisible","effectiveVisible"))badArchive(m->first(m,"nodes").put(key,1));
        badArchive(m->first(m,"nodes").put("parentId","missing"));badArchive(m->first(m,"nodes").put("parentId","node"));
        badArchive(m->first(m,"nodes").put("importDisposition","unknown"));badArchive(m->first(m,"nodes").put("assetId","missing"));
        badArchive(m->first(m,"nodes").put("width",1));badArchive(m->first(m,"nodes").put("importDisposition","METADATA_ONLY"));
        badArchive(m->first(m,"assets").put("path","../asset.png"));badArchive(m->first(m,"assets").put("width",1));
        badArchive(m->m.put("nodes",List.of(first(m,"nodes"),first(m,"nodes"))));
        badArchive(m->{var second=new LinkedHashMap<>(first(m,"nodes"));second.put("id","second");m.put("nodes",List.of(first(m,"nodes"),second));});
        badArchive(m->m.put("assets",List.of(first(m,"assets"),first(m,"assets"))));
        badArchive(m->m.put("assets",List.of()));
        for(String path:List.of("../escape","/absolute","assets\\bad.png","unreferenced","assets/extra.png")) {
            var extra=new LinkedHashMap<>(assets());extra.put(path,new byte[0]);
            try {HelperClient.readPsdArchive(archive(manifest(),extra));throw new AssertionError("invalid ZIP accepted");}
            catch(ServiceFailure e){check(e.code().equals("INVALID_RESPONSE"),"ZIP path validation");}
        }
        var metadata=manifest();first(metadata,"nodes").put("assetId",null);first(metadata,"nodes").put("importDisposition","EMPTY_METADATA");metadata.put("assets",List.of());
        check(HelperClient.readPsdArchive(archive(metadata,Map.of())).assets().isEmpty(),"metadata-only import");
    }
    static void httpContracts() throws Exception {
        for(String uri:List.of("https://127.0.0.1:1234","http://localhost:1234","http://127.0.0.1","http://127.0.0.1:1234/a","http://u@127.0.0.1:1234","http://127.0.0.1:1234?x","http://127.0.0.1:1234#x"))invalid(()->new HelperClient(URI.create(uri),"s"));
        invalid(()->new HelperClient(URI.create("http://127.0.0.1:1234"),""));
        try(var server=new Server();var client=server.client()) {
            check(Boolean.TRUE.equals(client.health().get().get("aiReady")),"health success");
            var registered=client.registerImage(image(),"r").get();
            var segment=client.segment(registered.imageId(),"r",id(),points()).get();
            check(segment.candidates().get(0).mask().getRaster().getSample(0,0,0)==255,"binary mask");
            code(client.registerImage(null,"r"),"INVALID_ARGUMENT");code(client.registerImage(image(),""),"INVALID_ARGUMENT");code(client.registerImage(image(),null),"INVALID_ARGUMENT");code(client.registerImage(image(),"r".repeat(129)),"INVALID_ARGUMENT");
            code(client.segment(id(),"r",id(),points()),"IMAGE_NOT_FOUND");code(client.segment(registered.imageId(),"wrong",id(),points()),"INVALID_ARGUMENT");
            code(client.segment(registered.imageId(),"r","bad",points()),"INVALID_ARGUMENT");code(client.segment(registered.imageId(),"r",id(),null),"INVALID_ARGUMENT");
            for(List<HelperClient.Point> ps:List.of(List.<HelperClient.Point>of(),List.of(new HelperClient.Point(0,0,0)),List.of(new HelperClient.Point(-1,0,1)),List.of(new HelperClient.Point(0,-1,1)),List.of(new HelperClient.Point(2,0,1)),List.of(new HelperClient.Point(0,2,1)),List.of(new HelperClient.Point(0,0,1),new HelperClient.Point(1,1,3)),Collections.nCopies(65,new HelperClient.Point(0,0,1))))
                code(client.segment(registered.imageId(),"r",id(),ps),"INVALID_ARGUMENT");
            code(client.releaseImage("not-uuid"),"INVALID_ARGUMENT");
            code(client.releaseImage(null),"INVALID_ARGUMENT");
            code(client.segment(null,"r",id(),points()),"INVALID_ARGUMENT");
            for(String field:List.of("imageId","sourceRevision","serviceInstanceId","width","height")) {
                server.handler=exchange->{var data=server.registration();data.put(field,field.equals("width")||field.equals("height")?3:"bad");return new Reply(201,data);};
                code(client.registerImage(image(),"r"),"INVALID_RESPONSE");
            }
            List<Consumer<Map<String,Object>>> changes=new ArrayList<>();
            for(String field:List.of("imageId","sourceRevision","requestId","deviceUsed"))changes.add(data->data.put(field,"bad"));
            changes.add(data->data.put("width",3));changes.add(data->data.put("height",3));changes.add(data->data.put("candidates",List.of()));
            changes.add(data->data.put("candidates",Collections.nCopies(4,Json.list(data,"candidates").get(0))));
            changes.add(data->data.put("candidates",Collections.nCopies(2,Json.list(data,"candidates").get(0))));
            changes.add(data->first(data,"candidates").put("score","bad"));changes.add(data->first(data,"candidates").put("candidateId","bad"));
            changes.add(data->first(data,"candidates").put("maskPngBase64","bad"));
            var gray=new BufferedImage(2,2,BufferedImage.TYPE_BYTE_GRAY);gray.getRaster().setSample(0,0,0,128);
            for(BufferedImage bad:List.of(image(),gray,new BufferedImage(1,1,BufferedImage.TYPE_BYTE_GRAY))) {
                String encoded=Base64.getEncoder().encodeToString(png(bad));changes.add(data->first(data,"candidates").put("maskPngBase64",encoded));
            }
            for(var change:changes) {
                server.handler=exchange->{var data=server.result(exchange.getRequestHeaders().getFirst("X-Request-Id"));change.accept(data);return new Reply(200,data);};
                code(client.segment(registered.imageId(),"r",id(),points()),"INVALID_RESPONSE");
            }
            server.handler=exchange->{var r=new Reply(200,Map.of());r.api="2";return r;};code(client.health(),"API_VERSION_UNSUPPORTED");
            server.handler=exchange->{var r=new Reply(200,Map.of());r.requestId=id();return r;};code(client.health(),"INVALID_RESPONSE");
            server.handler=exchange->new Reply(200,new byte[65537]);code(client.health(),"INVALID_RESPONSE");
            server.handler=exchange->new Reply(200,"not-json".getBytes(StandardCharsets.UTF_8));code(client.health(),"INVALID_RESPONSE");
            for(String statusCode:List.of("BUSY","NO_RESULT","UNKNOWN")) {
                server.handler=exchange->new Reply(429,Map.of("error",Map.of("code",statusCode,"message","try again","requestId",exchange.getRequestHeaders().getFirst("X-Request-Id"))));
                code(client.health(),statusCode.equals("UNKNOWN")?"INVALID_RESPONSE":statusCode);
            }
            server.handler=server::normal;client.releaseImage(registered.imageId()).get();
            code(client.segment(registered.imageId(),"r",id(),points()),"IMAGE_NOT_FOUND");
            Path psd=Files.createTempFile("helper-test-",".psd");Files.write(psd,new byte[]{1,2});
            try {
                byte[] zip=archive(manifest(),assets());server.handler=exchange->new Reply(200,zip);
                check(client.importPsd(psd).get().assets().size()==1,"HTTP PSD round trip");
            } finally {Files.delete(psd);}
            server.handler=exchange->{try {Thread.sleep(3000);}catch(InterruptedException ignored){}return new Reply(200,Map.of());};code(client.health(),"TIMEOUT");
        }
        URI dead;try(var server=new Server()){dead=server.uri();}
        try(var client=new HelperClient(dead,"secret")){code(client.health(),"SERVICE_UNAVAILABLE");client.close();code(client.health(),"SERVICE_UNAVAILABLE");}
    }
    static void responseOrdering() throws Exception {
        try(var server=new Server();var client=server.client()) {
            var registered=client.registerImage(image(),"r").get();var firstEntered=new CountDownLatch(1);var release=new CountDownLatch(1);String firstId=id(),secondId=id();
            server.handler=exchange->{String requestId=exchange.getRequestHeaders().getFirst("X-Request-Id");if(requestId.equals(firstId)) {firstEntered.countDown();try {release.await(4,TimeUnit.SECONDS);}catch(InterruptedException ignored){}}return new Reply(200,server.result(requestId));};
            var first=client.segment(registered.imageId(),"r",firstId,points());check(firstEntered.await(3,TimeUnit.SECONDS),"first request started");
            try {
                var second=client.segment(registered.imageId(),"r",secondId,points()).get(3,TimeUnit.SECONDS);check(second.requestId().equals(secondId),"latest response published");
            } finally {release.countDown();}
            check(error(first) instanceof CancellationException,"stale response discarded");
            var entered=new CountDownLatch(1);var hold=new CountDownLatch(1);
            server.handler=exchange->{entered.countDown();try {hold.await(3,TimeUnit.SECONDS);}catch(InterruptedException ignored){}return new Reply(200,server.result(exchange.getRequestHeaders().getFirst("X-Request-Id")));};
            var cancelled=client.segment(registered.imageId(),"r",id(),points());check(entered.await(3,TimeUnit.SECONDS),"cancel request started");client.cancelSegmentation(registered.imageId());hold.countDown();
            check(error(cancelled) instanceof CancellationException,"cancel suppresses response");
        }
    }
    static void httpCancellation() throws Exception {
        try(var server=new Server();var client=server.client()) {
            CountDownLatch started=new CountDownLatch(2),release=new CountDownLatch(1);
            server.handler=exchange->{started.countDown();try {release.await(4,TimeUnit.SECONDS);}catch(InterruptedException ignored){}return server.normal(exchange);};
            List<CompletableFuture<?>> requests=new ArrayList<>();requests.add(client.health());requests.add(client.health());
            check(started.await(3,TimeUnit.SECONDS),"workers occupied");
            try {for(int i=0;i<16;i++)requests.add(client.health());code(client.health(),"BUSY");client.close();for(var f:requests)check(f.isDone(),"close settles futures");}
            finally {release.countDown();}
        }
    }
    static String childScript(String mode) {
        String script="import json,os,time,signal,threading\nfrom http.server import HTTPServer,BaseHTTPRequestHandler\ninstance='12345678-1234-1234-1234-123456789abc'\nopen('pid.txt','w').write(str(os.getpid()))\nclass Handler(BaseHTTPRequestHandler):\n def log_message(self,*args): pass\n def do_GET(self):\n  body=json.dumps({'serviceInstanceId':instance}).encode()\n  self.send_response(200)\n  self.send_header('X-Api-Version','1')\n  self.send_header('X-Request-Id',self.headers['X-Request-Id'])\n  self.end_headers()\n  self.wfile.write(body)\nserver=HTTPServer(('127.0.0.1',0),Handler)\n"+
            (mode.equals("bad")?"print('invalid-json',flush=True)\n":mode.equals("long")?"print('x'*9000,flush=True)\n":
            "print(json.dumps({'event':'listening','apiVersion':1,'host':'127.0.0.1','port':server.server_port,'pid':os.getpid(),'serviceInstanceId':instance}),flush=True)\n")+
            (mode.equals("ignore")?"signal.signal(signal.SIGTERM,signal.SIG_IGN)\n":"")+"server.serve_forever()\n";
        if(mode.equals("exit"))script=script.replace("  self.wfile.write(body)\n","  self.wfile.write(body)\n  threading.Timer(.2,lambda:os._exit(0)).start()\n");
        return script;
    }
    static void managerLifecycle() throws Exception {
        Path dir=Files.createTempDirectory("helper-child-");Files.createDirectories(dir.resolve("src"));
        Path python=Path.of("python-service/.venv/bin/python").toAbsolutePath();LocalServiceManager manager=new LocalServiceManager();
        try {
            Files.writeString(dir.resolve("src/editor_helper.py"),childScript("normal"));
            var first=manager.start(python,dir,null);check(manager.start(python,dir,null)==first,"single child reuse");check(first.health().get()!=null,"child health");manager.close();manager.close();
            Files.writeString(dir.resolve("src/editor_helper.py"),childScript("exit"));
            var dying=manager.start(python,dir,null);long pid=Long.parseLong(Files.readString(dir.resolve("pid.txt")));
            var handle=ProcessHandle.of(pid);if(handle.isPresent())handle.get().onExit().get(3,TimeUnit.SECONDS);
            Files.writeString(dir.resolve("src/editor_helper.py"),childScript("normal"));
            check(manager.start(python,dir,null)!=dying,"dead child is replaced");
            check(dying.health().isCompletedExceptionally(),"restart closes previous client");manager.close();
            Files.writeString(dir.resolve("src/editor_helper.py"),childScript("ignore"));manager.start(python,dir,null);manager.close();
            for(String mode:List.of("bad","long")) {Files.writeString(dir.resolve("src/editor_helper.py"),childScript(mode));try {manager.start(python,dir,null);throw new AssertionError("bad handshake accepted");}catch(IOException expected){checks.incrementAndGet();}}
            Files.writeString(dir.resolve("src/editor_helper.py"),"raise SystemExit(1)\n");try {manager.start(python,dir,null);throw new AssertionError();}catch(IOException expected){checks.incrementAndGet();}
            manager.dispose();try {manager.start(python,dir,null);throw new AssertionError();}catch(IOException expected){checks.incrementAndGet();}
        } finally {manager.dispose();try(var paths=Files.walk(dir)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
    static void cli() throws Exception {
        Path dir=Files.createTempDirectory("image-cli-");Path input=dir.resolve("in.png"),output=dir.resolve("out.png");ImageIO.write(image(),"PNG",input.toFile());
        try {
            for(String[] operation:new String[][]{{"GRAYSCALE"},{"RESIZE","3","4"},{"CROP","0","0","1","1"},{"COLOR_ADJUST","1","1","1"},{"BLUR","1"},{"MOSAIC","2"}}) {
                List<String> args=new ArrayList<>(List.of(input.toString(),output.toString()));args.addAll(List.of(operation));editor.cli.Main.main(args.toArray(String[]::new));check(ImageIO.read(output.toFile())!=null,"CLI output PNG");Files.delete(output);
            }
            Files.write(output,new byte[]{1,2});try {editor.cli.Main.main(new String[]{input.toString(),output.toString(),"GRAYSCALE"});throw new AssertionError();}catch(IllegalArgumentException expected){check(Arrays.equals(Files.readAllBytes(output),new byte[]{1,2}),"CLI preserves existing output");}
        } finally {try(var paths=Files.walk(dir)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
}
