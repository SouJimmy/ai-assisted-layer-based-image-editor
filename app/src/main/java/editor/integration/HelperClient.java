package editor.integration;
import editor.processing.ImageProcessor;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.zip.*;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;

/** B-side adapter for API v1. It never updates Document, Layer or History. */
public final class HelperClient implements AutoCloseable {
    public record RegisteredImage(String imageId,String sourceRevision,int width,int height,String serviceInstanceId) {}
    public record Point(int x,int y,int label) {}
    public record Candidate(String candidateId,BufferedImage mask,double score) {}
    public record Segmentation(String imageId,String sourceRevision,String requestId,List<Candidate> candidates,String deviceUsed) {
        public Segmentation {candidates=List.copyOf(candidates);}
    }
    public record PsdImport(int canvasWidth,int canvasHeight,List<Map<String,Object>> nodes,
                            Map<String,BufferedImage> assets,List<Map<String,Object>> warnings) {
        public PsdImport {nodes=List.copyOf(nodes);assets=Map.copyOf(assets);warnings=List.copyOf(warnings);}
    }
    private static final Set<String> CODES=Set.of("INVALID_ARGUMENT","RESOURCE_LIMIT","AUTH_FAILED","API_VERSION_UNSUPPORTED",
            "NO_RESULT","INVALID_IMAGE","UNSUPPORTED_FORMAT","IMAGE_NOT_FOUND","REVISION_MISMATCH","BUSY",
            "MODEL_NOT_READY","MODEL_INIT_FAILED","INFERENCE_FAILED","PSD_PARSE_FAILED","TIMEOUT","CANCELLED","INTERNAL_ERROR");
    private final URI base;
    private final String token;
    private final HttpClient http;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),
            r->{Thread t=new Thread(r,"helper-client");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final ConcurrentMap<String,String> latest=new ConcurrentHashMap<>();
    private final ConcurrentMap<String,RegisteredImage> images=new ConcurrentHashMap<>();
    private final Set<CompletableFuture<?>> pending=ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    public HelperClient(URI base,String token) {
        if(!"http".equals(base.getScheme()) || !"127.0.0.1".equals(base.getHost()) || base.getPort()<1 || base.getPort()>65535
                || base.getRawUserInfo()!=null || base.getRawQuery()!=null || base.getRawFragment()!=null
                || !(base.getPath().isEmpty() || base.getPath().equals("/")) || token==null || token.isBlank())
            throw new IllegalArgumentException("Loopback origin and token required");
        this.base=base;this.token=token;
        http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER)
            .proxy(new ProxySelector() {
                public List<Proxy> select(URI uri) {return List.of(Proxy.NO_PROXY);}
                public void connectFailed(URI uri,SocketAddress address,IOException error) {}
            }).build();
    }
    private <T> CompletableFuture<T> task(Callable<T> action) {
        CompletableFuture<T> result=new CompletableFuture<>();pending.add(result);
        result.whenComplete((value,error)->pending.remove(result));
        if(closed) {result.completeExceptionally(new ServiceFailure("SERVICE_UNAVAILABLE","サービス接続を終了しました",null));return result;}
        try {
            Future<?> work=workers.submit(()->{
                try {T value=action.call();if(!result.isCancelled())result.complete(value);}
                catch(Throwable e) {result.completeExceptionally(e);}
            });
            result.whenComplete((value,error)->{if(result.isCancelled())work.cancel(true);});
        } catch(RejectedExecutionException e) {result.completeExceptionally(new ServiceFailure("BUSY","通信キューが満杯です",e));}
        return result;
    }
    public CompletableFuture<Map<String,Object>> health() {
        return task(()->object(exchange("GET","/health",null,null,2,65536,200,UUID.randomUUID().toString())));
    }
    public CompletableFuture<RegisteredImage> registerImage(BufferedImage snapshot,String revision) {
        return task(()->{
            if(snapshot==null || revision==null || revision.isEmpty() || revision.length()>128)throw invalid("登録入力が不正です");
            ImageProcessor.checkDimensions(snapshot.getWidth(),snapshot.getHeight());
            ByteArrayOutputStream png=new ByteArrayOutputStream();ImageIO.write(snapshot,"PNG",png);
            if(png.size()>64*1024*1024)throw invalid("登録画像が上限を超えています");
            Multipart body=multipart("image","image.png",png.toByteArray(),revision);
            Map<String,Object> data=object(exchange("POST","/image",body.bytes,body.type,30,65536,201,UUID.randomUUID().toString()));
            try {
                RegisteredImage image=new RegisteredImage(Json.string(data,"imageId"),Json.string(data,"sourceRevision"),
                        Json.integer(data,"width"),Json.integer(data,"height"),Json.string(data,"serviceInstanceId"));
                UUID.fromString(image.imageId());UUID.fromString(image.serviceInstanceId());
                if(!revision.equals(image.sourceRevision()) || snapshot.getWidth()!=image.width() || snapshot.getHeight()!=image.height())throw new IllegalArgumentException();
                images.put(image.imageId(),image);return image;
            } catch(RuntimeException e) {throw invalidResponse(e);}
        });
    }
    public CompletableFuture<Segmentation> segment(String imageId,String revision,String requestId,List<Point> points) {
        final List<Point> copy;
        try {copy=List.copyOf(points);UUID.fromString(requestId);}
        catch(RuntimeException e) {return CompletableFuture.failedFuture(invalid("推論入力が不正です"));}
        RegisteredImage registered=images.get(imageId);
        if(registered==null)return CompletableFuture.failedFuture(new ServiceFailure("IMAGE_NOT_FOUND","画像を登録してください",requestId,404));
        if(!registered.sourceRevision().equals(revision))return CompletableFuture.failedFuture(invalid("画像世代が一致しません"));
        if(copy.isEmpty() || copy.size()>64 || copy.stream().noneMatch(p->p.label()==1)
                || copy.stream().anyMatch(p->p.x()<0 || p.y()<0 || p.x()>=registered.width() || p.y()>=registered.height() || (p.label()!=0 && p.label()!=1)))
            return CompletableFuture.failedFuture(invalid("クリック座標またはラベルが不正です"));
        latest.put(imageId,requestId);
        return task(()->{
            if(!requestId.equals(latest.get(imageId)))throw new CancellationException();
            List<Object> prompt=new ArrayList<>();for(Point p:copy)prompt.add(Map.of("x",p.x(),"y",p.y(),"label",p.label()));
            byte[] body=Json.write(Map.of("imageId",imageId,"sourceRevision",revision,"requestId",requestId,"points",prompt)).getBytes(StandardCharsets.UTF_8);
            Map<String,Object> data=object(exchange("POST","/segment",body,"application/json",15,48*1024*1024,200,requestId));
            Segmentation value;
            try {
                if(!imageId.equals(Json.string(data,"imageId")) || !revision.equals(Json.string(data,"sourceRevision"))
                        || !requestId.equals(Json.string(data,"requestId")) || Json.integer(data,"width")!=registered.width()
                        || Json.integer(data,"height")!=registered.height())throw new IllegalArgumentException();
                List<?> candidates=Json.list(data,"candidates");if(candidates.isEmpty() || candidates.size()>3)throw new IllegalArgumentException();
                List<Candidate> result=new ArrayList<>();Set<String> ids=new HashSet<>();
                for(Object item:candidates) {
                    Map<String,Object> candidate=Json.object(item);String id=Json.string(candidate,"candidateId");
                    UUID.fromString(id);if(!ids.add(id) || !(candidate.get("score") instanceof Number score) || !Double.isFinite(score.doubleValue()))throw new IllegalArgumentException();
                    BufferedImage mask=readPng(Base64.getDecoder().decode(Json.string(candidate,"maskPngBase64")),registered.width(),registered.height(),true);
                    result.add(new Candidate(id,mask,score.doubleValue()));
                }
                String device=Json.string(data,"deviceUsed");if(!device.equals("cpu") && !device.equals("mps"))throw new IllegalArgumentException();
                value=new Segmentation(imageId,revision,requestId,result,device);
            } catch(RuntimeException|IOException e) {throw invalidResponse(e);}
            if(!requestId.equals(latest.get(imageId)) || !images.containsKey(imageId))throw new CancellationException();
            return value;
        });
    }
    public void cancelSegmentation(String imageId) {latest.remove(imageId);}
    public CompletableFuture<Void> releaseImage(String imageId) {
        images.remove(imageId);latest.remove(imageId);
        try {UUID.fromString(imageId);}catch(RuntimeException e) {return CompletableFuture.failedFuture(invalid("画像IDが不正です"));}
        return task(()->{exchange("DELETE","/image/"+imageId,null,null,15,65536,204,UUID.randomUUID().toString());return null;});
    }
    public CompletableFuture<PsdImport> importPsd(Path file) {
        return task(()->{
            byte[] fileBytes;
            try(var stream=Files.newInputStream(file)) {fileBytes=stream.readNBytes(128*1024*1024+1);}
            if(fileBytes.length>128*1024*1024)throw invalid("PSDが上限を超えています");
            Multipart body=multipart("file","input.psd",fileBytes,null);
            byte[] archive=exchange("POST","/psd/import",body.bytes,body.type,60,256*1024*1024,200,UUID.randomUUID().toString());
            return readPsdArchive(archive);
        });
    }
    public static PsdImport readPsdArchive(byte[] bytes) {
        try {
            Map<String,byte[]> entries=new LinkedHashMap<>();long total=0;
            try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes),StandardCharsets.UTF_8)) {
                ZipEntry entry;
                while((entry=zip.getNextEntry())!=null) {
                    String name=entry.getName();
                    if(entries.size()>=502 || entry.isDirectory() || name.contains("\\") || name.startsWith("/")
                            || Arrays.asList(name.split("/")).contains("..") || entries.containsKey(name)
                            || !(name.equals("manifest.json") || name.matches("assets/[a-zA-Z0-9-]+\\.png")))throw new IOException("Invalid ZIP entry");
                    int max=name.equals("manifest.json")?4*1024*1024:128*1024*1024;
                    byte[] data=zip.readNBytes(max+1);if(data.length>max)throw new IOException("Entry too large");
                    total+=data.length;if(total>256L*1024*1024)throw new IOException("ZIP expansion limit");entries.put(name,data);
                }
            }
            Map<String,Object> manifest=object(Objects.requireNonNull(entries.get("manifest.json")));
            if(Json.integer(manifest,"schemaVersion")!=1)throw new IOException("Unknown schema version");
            int width=Json.integer(manifest,"canvasWidth"),height=Json.integer(manifest,"canvasHeight");ImageProcessor.checkDimensions(width,height);
            Map<String,BufferedImage> assets=new LinkedHashMap<>();Set<String> paths=new HashSet<>();long pixels=0;
            for(Object item:Json.list(manifest,"assets")) {
                Map<String,Object> asset=Json.object(item);String id=Json.string(asset,"assetId"),path=Json.string(asset,"path");
                if(assets.containsKey(id) || !path.equals("assets/"+id+".png") || !paths.add(path))throw new IOException("Duplicate/invalid asset");
                int w=Json.integer(asset,"width"),h=Json.integer(asset,"height");pixels+=(long)w*h*4;if(pixels>256L*1024*1024)throw new IOException("Raster budget");
                BufferedImage decoded=readPng(Objects.requireNonNull(entries.get(path)),w,h,false);
                BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB_PRE);
                var graphics=image.createGraphics();try {graphics.setComposite(java.awt.AlphaComposite.Src);graphics.drawImage(decoded,0,0,null);}finally {graphics.dispose();}
                assets.put(id,image);
            }
            if(entries.size()!=assets.size()+1)throw new IOException("Unreferenced archive entry");
            List<Map<String,Object>> nodes=new ArrayList<>(),warnings=new ArrayList<>();Map<String,Map<String,Object>> byId=new HashMap<>();
            for(Object item:Json.list(manifest,"nodes")) {
                Map<String,Object> node=Json.object(item);String id=Json.string(node,"id");
                if(byId.put(id,node)!=null || byId.size()>501)throw new IOException("Duplicate node");
                Json.string(node,"name");Json.string(node,"kind");Json.integer(node,"x");Json.integer(node,"y");
                if(Json.integer(node,"width")<0 || Json.integer(node,"height")<0 || Json.integer(node,"siblingIndex")<0)throw new IOException("Invalid bounds");
                for(String key:List.of("opacity","effectiveOpacity")) {
                    if(!(node.get(key) instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue()<0 || n.doubleValue()>1)throw new IOException("Opacity");
                }
                if(!(node.get("selfVisible") instanceof Boolean) || !(node.get("effectiveVisible") instanceof Boolean))throw new IOException("Visibility");
                String disposition=Json.string(node,"importDisposition");
                if(!Set.of("RASTER","GROUP_METADATA","REPLACEMENT_RASTER","METADATA_ONLY","EMPTY_METADATA").contains(disposition))throw new IOException("Disposition");
                Object assetId=node.get("assetId");
                if(disposition.equals("RASTER") || disposition.equals("REPLACEMENT_RASTER")) {
                    BufferedImage image=assets.get(assetId);
                    if(image==null || image.getWidth()!=Json.integer(node,"width") || image.getHeight()!=Json.integer(node,"height"))throw new IOException("Node image mismatch");
                } else if(assetId!=null)throw new IOException("Metadata node has drawable asset");
                nodes.add(freezeMap(node));
            }
            Set<String> siblingKeys=new HashSet<>();
            for(var node:byId.values()) {
                Object parent=node.get("parentId");Set<Object> seen=new HashSet<>();seen.add(node.get("id"));
                if(!siblingKeys.add(String.valueOf(parent)+":"+Json.integer(node,"siblingIndex")))throw new IOException("Duplicate order");
                while(parent!=null) {
                    if(!seen.add(parent) || !byId.containsKey(parent))throw new IOException("Invalid parent tree");
                    parent=byId.get(parent).get("parentId");
                }
            }
            for(Object item:Json.list(manifest,"warnings"))warnings.add(freezeMap(Json.object(item)));
            return new PsdImport(width,height,nodes,assets,warnings);
        } catch(Exception e) {throw invalidResponse(e);}
    }
    @SuppressWarnings("unchecked") private static Object freeze(Object value) {
        if(value instanceof Map<?,?> m) {Map<String,Object> out=new LinkedHashMap<>();m.forEach((k,v)->out.put((String)k,freeze(v)));return Collections.unmodifiableMap(out);}
        if(value instanceof List<?> list)return list.stream().map(HelperClient::freeze).toList();return value;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> freezeMap(Map<String,Object> value) {return (Map<String,Object>)freeze(value);}
    private static BufferedImage readPng(byte[] data,int w,int h,boolean binary) throws IOException {
        ImageProcessor.checkDimensions(w,h);
        try(var stream=ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers=ImageIO.getImageReaders(stream);if(!readers.hasNext())throw new IOException("Invalid PNG");
            ImageReader reader=readers.next();
            try {
                reader.setInput(stream);
                if(!reader.getFormatName().equalsIgnoreCase("png") || reader.getWidth(0)!=w || reader.getHeight(0)!=h)throw new IOException("PNG dimensions/format");
                BufferedImage image=reader.read(0);
                if(binary) {
                    if(image.getRaster().getNumBands()!=1 || image.getSampleModel().getSampleSize(0)!=8)throw new IOException("8-bit gray mask required");
                    for(int y=0;y<h;y++)for(int x=0;x<w;x++) {int v=image.getRaster().getSample(x,y,0);if(v!=0 && v!=255)throw new IOException("Non-binary mask");}
                }
                return image;
            } finally {reader.dispose();}
        }
    }
    private byte[] exchange(String method,String path,byte[] body,String type,int seconds,int limit,int expected,String requestId) {
        HttpRequest.Builder builder=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(seconds))
                .header("Authorization","Bearer "+token).header("X-Api-Version","1").header("X-Request-Id",requestId);
        if(type!=null)builder.header("Content-Type",type);
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body));
        CompletableFuture<HttpResponse<byte[]>> call=http.sendAsync(builder.build(),info->new LimitedBody(limit));
        try {
            HttpResponse<byte[]> response=call.get(seconds,TimeUnit.SECONDS);
            if(!response.headers().firstValue("X-Api-Version").orElse("").equals("1"))throw new ServiceFailure("API_VERSION_UNSUPPORTED","APIの版が一致しません",requestId,response.statusCode());
            if(!response.headers().firstValue("X-Request-Id").orElse("").equals(requestId))throw invalidResponse(null);
            if(response.statusCode()!=expected) {
                try {
                    Map<String,Object> error=Json.object(object(response.body()).get("error"));String code=Json.string(error,"code");
                    if(!CODES.contains(code) || !requestId.equals(Json.string(error,"requestId")))throw new IllegalArgumentException();
                    throw new ServiceFailure(code,Json.string(error,"message"),requestId,response.statusCode());
                } catch(ServiceFailure e) {throw e;}catch(RuntimeException e) {throw invalidResponse(e);}
            }
            return response.body();
        } catch(TimeoutException e) {call.cancel(true);throw new ServiceFailure("TIMEOUT","サービスの応答が期限を超えました",e);}
        catch(InterruptedException e) {call.cancel(true);Thread.currentThread().interrupt();throw new CancellationException();}
        catch(ExecutionException e) {
            Throwable cause=e.getCause();
            if(cause instanceof HttpTimeoutException)throw new ServiceFailure("TIMEOUT","サービスの応答が期限を超えました",cause);
            if(cause instanceof ServiceFailure failure)throw failure;
            throw new ServiceFailure("SERVICE_UNAVAILABLE","サービスに接続できません",cause);
        }
    }
    private static Map<String,Object> object(byte[] bytes) {
        try {return Json.object(Json.parse(new String(bytes,StandardCharsets.UTF_8)));}catch(RuntimeException e) {throw invalidResponse(e);}
    }
    private record Multipart(byte[] bytes,String type) {}
    private static Multipart multipart(String field,String filename,byte[] data,String revision) throws IOException {
        String boundary="editor-"+UUID.randomUUID();ByteArrayOutputStream out=new ByteArrayOutputStream();
        if(revision!=null)out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"sourceRevision\"\r\n\r\n"+revision+"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+field+"\"; filename=\""+filename+"\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(data);out.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));return new Multipart(out.toByteArray(),"multipart/form-data; boundary="+boundary);
    }
    private static ServiceFailure invalid(String message) {return new ServiceFailure("INVALID_ARGUMENT",message,(Throwable)null);}
    private static ServiceFailure invalidResponse(Throwable cause) {return new ServiceFailure("INVALID_RESPONSE","サービスの応答が不正です",cause);}
    @Override public void close() {closed=true;latest.clear();images.clear();for(var job:pending)job.cancel(true);workers.shutdownNow();}
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> result=new CompletableFuture<>();final ByteArrayOutputStream out=new ByteArrayOutputStream();final int limit;Flow.Subscription subscription;
        LimitedBody(int limit) {this.limit=limit;}
        public CompletionStage<byte[]> getBody() {return result;}
        public void onSubscribe(Flow.Subscription value) {subscription=value;value.request(1);}
        public void onNext(List<ByteBuffer> buffers) {
            for(ByteBuffer buffer:buffers) {
                if((long)out.size()+buffer.remaining()>limit) {subscription.cancel();result.completeExceptionally(invalidResponse(null));return;}
                byte[] bytes=new byte[buffer.remaining()];buffer.get(bytes);out.writeBytes(bytes);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) {result.completeExceptionally(error);}
        public void onComplete() {result.complete(out.toByteArray());}
    }
}
