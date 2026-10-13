package editor.processing;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import static editor.processing.Processing.*;

/** Pure, deterministic image processing. Every result owns its pixels. */
public final class ImageProcessor {
    public static final long MAX_PIXELS = 33_554_432;
    public static final int MAX_SIDE = 16_384;
    public static void checkDimensions(int w, int h) {
        if (w < 1 || h < 1 || w > MAX_SIDE || h > MAX_SIDE || (long) w * h > MAX_PIXELS)
            throw new Failure(Code.INVALID_ARGUMENT, "画像サイズが範囲外です");
    }
    public Result process(Request request) { return process(request, Context.defaults()); }
    public Result process(Request request, Context context) {
        if (request == null || request.source() == null || request.operation() == null
                || request.parameters() == null || context == null) invalid("入力が不足しています");
        BufferedImage source = request.source();
        checkDimensions(source.getWidth(), source.getHeight());
        validate(request);
        context.check();
        BufferedImage image = normalize(source);
        Progress progress = new Progress(context);
        BufferedImage out = switch (request.operation()) {
            case ROTATE_RIGHT, ROTATE_LEFT, FLIP_H, FLIP_V -> geometry(image, request.operation(), context, progress);
            case CROP -> crop(image, ((Crop) request.parameters()).rect(), context, progress);
            case RESIZE -> resize(image, (Resize) request.parameters(), context, progress);
            case BLUR -> blur(image, ((Blur) request.parameters()).radius(), context, progress);
            case MOSAIC -> mosaic(image, ((Mosaic) request.parameters()).blockSize(), context, progress);
            default -> colors(image, request.operation(), request.parameters(), context, progress);
        };
        context.check();
        context.progress().accept(1);
        return new Result(out, new Rect(0,0,source.getWidth(),source.getHeight()),
                new Rect(0,0,out.getWidth(),out.getHeight()),
                source.getWidth()!=out.getWidth() || source.getHeight()!=out.getHeight(), true, "v1",
                request.operation(), request.parameters());
    }
    private void validate(Request r) {
        boolean correct = switch (r.operation()) {
            case CROP -> r.parameters() instanceof Crop;
            case RESIZE -> r.parameters() instanceof Resize;
            case COLOR_ADJUST -> r.parameters() instanceof ColorAdjust;
            case BLUR -> r.parameters() instanceof Blur;
            case MOSAIC -> r.parameters() instanceof Mosaic;
            default -> r.parameters() instanceof None;
        };
        if (!correct) invalid("処理とパラメータの型が一致しません");
        if (r.parameters() instanceof Crop p) {
            Rect a=p.rect();
            if (a==null || a.x()<0 || a.y()<0 || a.width()<1 || a.height()<1
                    || (long)a.x()+a.width()>r.source().getWidth()
                    || (long)a.y()+a.height()>r.source().getHeight()) invalid("切り抜き範囲が不正です");
        } else if (r.parameters() instanceof Resize p) checkDimensions(p.width(),p.height());
        else if (r.parameters() instanceof ColorAdjust p) {
            if (p.brightness() < -255 || p.brightness()>255 || !Double.isFinite(p.contrast())
                    || !Double.isFinite(p.saturation()) || p.contrast()<0 || p.contrast()>3
                    || p.saturation()<0 || p.saturation()>3) invalid("色調整値が不正です");
        } else if (r.parameters() instanceof Blur p) {
            if(p.radius()<0 || p.radius()>20) invalid("ぼかし半径が不正です");
        } else if (r.parameters() instanceof Mosaic p) {
            if(p.blockSize()<1 || p.blockSize()>128) invalid("モザイクサイズが不正です");
        }
    }
    private static void invalid(String s) { throw new Failure(Code.INVALID_ARGUMENT,s); }
    private static BufferedImage blank(int w,int h) { return new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB_PRE); }
    private static BufferedImage normalize(BufferedImage source) {
        BufferedImage out=blank(source.getWidth(),source.getHeight());
        if(source.getType()==BufferedImage.TYPE_INT_ARGB_PRE) out.getRaster().setRect(source.getRaster());
        else {
            Graphics2D g=out.createGraphics();
            try { g.setComposite(AlphaComposite.Src); g.drawImage(source,0,0,null); } finally { g.dispose(); }
        }
        return out;
    }
    private static int raw(BufferedImage image,int x,int y) {
        return ((java.awt.image.DataBufferInt)image.getRaster().getDataBuffer()).getData()[y*image.getWidth()+x];
    }
    private static void raw(BufferedImage image,int x,int y,int pixel) {
        ((java.awt.image.DataBufferInt)image.getRaster().getDataBuffer()).getData()[y*image.getWidth()+x]=pixel;
    }
    private BufferedImage geometry(BufferedImage s,Operation op,Context ctx,Progress progress) {
        int w=s.getWidth(),h=s.getHeight();
        boolean rotate=op==Operation.ROTATE_LEFT || op==Operation.ROTATE_RIGHT;
        BufferedImage out=blank(rotate?h:w,rotate?w:h);
        for(int y=0;y<h;y++) {
            ctx.check();
            for(int x=0;x<w;x++) {
                int dx=x,dy=y;
                switch(op) {
                    case ROTATE_RIGHT -> { dx=h-1-y;dy=x; }
                    case ROTATE_LEFT -> { dx=y;dy=w-1-x; }
                    case FLIP_H -> dx=w-1-x;
                    case FLIP_V -> dy=h-1-y;
                    default -> throw new AssertionError();
                }
                raw(out,dx,dy,raw(s,x,y));
            }
            progress.send((y+1.0)/h);
        }
        return out;
    }
    private BufferedImage crop(BufferedImage s,Rect r,Context ctx,Progress progress) {
        BufferedImage out=blank(r.width(),r.height());
        for(int y=0;y<r.height();y++) {
            ctx.check();
            Object row=s.getRaster().getDataElements(r.x(),r.y()+y,r.width(),1,null);
            out.getRaster().setDataElements(0,y,r.width(),1,row);
            progress.send((y+1.0)/r.height());
        }
        return out;
    }
    private BufferedImage resize(BufferedImage s,Resize p,Context ctx,Progress progress) {
        if(s.getWidth()==p.width() && s.getHeight()==p.height()) return s;
        BufferedImage out=blank(p.width(),p.height());
        for(int y=0;y<p.height();y++) {
            ctx.check();
            double sy=Math.max(0,Math.min(s.getHeight()-1,(y+.5)*s.getHeight()/p.height()-.5));
            int y0=(int)Math.floor(sy),y1=Math.min(y0+1,s.getHeight()-1);double fy=sy-y0;
            for(int x=0;x<p.width();x++) {
                double sx=Math.max(0,Math.min(s.getWidth()-1,(x+.5)*s.getWidth()/p.width()-.5));
                int x0=(int)Math.floor(sx),x1=Math.min(x0+1,s.getWidth()-1);double fx=sx-x0;
                int p00=raw(s,x0,y0),p10=raw(s,x1,y0),p01=raw(s,x0,y1),p11=raw(s,x1,y1);
                double w00=(1-fx)*(1-fy),w10=fx*(1-fy),w01=(1-fx)*fy,w11=fx*fy;
                int pixel=0,alpha=255;
                for(int shift=24;shift>=0;shift-=8) {
                    double v=((p00>>>shift)&255)*w00+((p10>>>shift)&255)*w10+((p01>>>shift)&255)*w01+((p11>>>shift)&255)*w11;
                    int c=Math.min(alpha,round(v));if(shift==24) alpha=c;
                    pixel |= c<<shift;
                }
                raw(out,x,y,pixel);
            }
            progress.send((y+1.0)/p.height());
        }
        return out;
    }
    private BufferedImage colors(BufferedImage s,Operation op,Parameters parameters,Context ctx,Progress progress) {
        if(parameters instanceof ColorAdjust c && c.brightness()==0 && c.contrast()==1 && c.saturation()==1) return s;
        int w=s.getWidth(),h=s.getHeight();BufferedImage out=blank(w,h);
        for(int y=0;y<h;y++) {
            ctx.check();int[] row=s.getRGB(0,y,w,1,null,0,w);
            for(int x=0;x<w;x++) {
                int pixel=row[x],a=pixel>>>24;
                if(a==0) { row[x]=0;continue; }
                int r=(pixel>>>16)&255,g=(pixel>>>8)&255,b=pixel&255;
                int nr=r,ng=g,nb=b;
                switch(op) {
                    case GRAYSCALE -> nr=ng=nb=round(.299*r+.587*g+.114*b);
                    case NEGATIVE -> { nr=255-r;ng=255-g;nb=255-b; }
                    case SEPIA -> { nr=round(.393*r+.769*g+.189*b);ng=round(.349*r+.686*g+.168*b);nb=round(.272*r+.534*g+.131*b); }
                    case COLOR_ADJUST -> {
                        ColorAdjust c=(ColorAdjust)parameters;
                        r=round((round(r+c.brightness())-128)*c.contrast()+128);
                        g=round((round(g+c.brightness())-128)*c.contrast()+128);
                        b=round((round(b+c.brightness())-128)*c.contrast()+128);
                        double l=.299*r+.587*g+.114*b;
                        nr=round(l+c.saturation()*(r-l));ng=round(l+c.saturation()*(g-l));nb=round(l+c.saturation()*(b-l));
                    }
                    default -> throw new AssertionError();
                }
                row[x]=(a<<24)|(nr<<16)|(ng<<8)|nb;
            }
            out.setRGB(0,y,w,1,row,0,w);progress.send((y+1.0)/h);
        }
        return out;
    }
    private BufferedImage mosaic(BufferedImage s,int size,Context ctx,Progress progress) {
        if(size==1) return s;
        int w=s.getWidth(),h=s.getHeight();BufferedImage out=blank(w,h);
        for(int by=0;by<h;by+=size) {
            ctx.check();int bottom=Math.min(h,by+size);
            for(int bx=0;bx<w;bx+=size) {
                int right=Math.min(w,bx+size);long sa=0,sr=0,sg=0,sb=0;
                int[] pixels=s.getRGB(bx,by,right-bx,bottom-by,null,0,right-bx);
                for(int v:pixels) { int a=v>>>24;sa+=a;sr+=((v>>>16)&255)*(long)a;sg+=((v>>>8)&255)*(long)a;sb+=(v&255)*(long)a; }
                int a=round((double)sa/pixels.length);
                int v=sa==0?0:(a<<24)|(round((double)sr/sa)<<16)|(round((double)sg/sa)<<8)|round((double)sb/sa);
                Arrays.fill(pixels,v);out.setRGB(bx,by,right-bx,bottom-by,pixels,0,right-bx);
            }
            progress.send((double)bottom/h);
        }
        return out;
    }
    private BufferedImage blur(BufferedImage s,int radius,Context ctx,Progress progress) {
        if(radius==0) return s;
        int w=s.getWidth(),h=s.getHeight();double sigma=Math.max(radius/3.0,.5),sum=0;
        double[] kernel=new double[2*radius+1];
        for(int i=-radius;i<=radius;i++) { kernel[i+radius]=Math.exp(-i*i/(2*sigma*sigma));sum+=kernel[i+radius]; }
        for(int i=0;i<kernel.length;i++) kernel[i]/=sum;
        // Ring of horizontally filtered rows; memory is O(width * radius), not O(width * height).
        double[][] rows=new double[kernel.length][];int[] keys=new int[kernel.length];Arrays.fill(keys,-1);
        BufferedImage out=blank(w,h);
        for(int y=0;y<h;y++) {
            ctx.check();
            for(int ky=-radius;ky<=radius;ky++) {
                int iy=Math.max(0,Math.min(h-1,y+ky)),slot=iy%rows.length;
                if(keys[slot]!=iy) { rows[slot]=horizontal(s,iy,kernel,radius);keys[slot]=iy; }
            }
            int[] line=new int[w];
            for(int x=0;x<w;x++) {
                int pixel=0,alpha=255;
                for(int c=0;c<4;c++) {
                    double v=0;
                    for(int ky=-radius;ky<=radius;ky++) {
                        int iy=Math.max(0,Math.min(h-1,y+ky));v+=rows[iy%rows.length][x*4+c]*kernel[ky+radius];
                    }
                    int channel=Math.min(alpha,round(v));if(c==0)alpha=channel;
                    pixel |= channel<<(24-c*8);
                }
                line[x]=pixel;
            }
            out.getRaster().setDataElements(0,y,w,1,line);progress.send((y+1.0)/h);
        }
        return out;
    }
    private double[] horizontal(BufferedImage s,int y,double[] kernel,int radius) {
        int w=s.getWidth();int[] pixels=(int[])s.getRaster().getDataElements(0,y,w,1,null);
        double[] out=new double[w*4];
        for(int x=0;x<w;x++) for(int k=-radius;k<=radius;k++) {
            int pixel=pixels[Math.max(0,Math.min(w-1,x+k))];
            for(int c=0;c<4;c++) out[x*4+c]+=((pixel>>>(24-c*8))&255)*kernel[k+radius];
        }
        return out;
    }
    private static int round(double v) { return (int)Math.max(0,Math.min(255,Math.floor(v+.5))); }
    private static final class Progress {
        private final Context context;private long last;
        Progress(Context context) { this.context=context; }
        void send(double fraction) { long now=System.nanoTime();if(now-last>=100_000_000L) { last=now;context.progress().accept(fraction); } }
    }
}
